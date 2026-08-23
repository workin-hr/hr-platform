package com.workin.legacy.attendance.spreadsheet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.poi.hssf.record.DateWindow1904Record;
import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFFormulaEvaluator;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.FormulaError;

/**
 * The BIFF8 workbooks {@link LegacySimpleXlsReaderDifferentialTest} runs
 * against, and the tool that produced the committed copies of them.
 *
 * <h2>Why the bytes are committed rather than built by the test</h2>
 * <p>Every expectation in the differential test is the measured output of the
 * real vendored {@code Shuchkin\SimpleXLS} under PHP 8.3. That oracle was taken
 * by feeding it <em>these exact files</em>. If the test rebuilt the workbooks
 * at run time, a future POI release could quietly change a byte and the
 * assertions would no longer be pinned to anything PHP was ever shown. So the
 * files under {@code src/test/resources/legacy/spreadsheet/xls/} are the
 * fixtures; this class is how they were made and how they can be remade.
 *
 * <p>Regenerate with the compiled test classes on the classpath:
 * <pre>
 * java -cp &lt;test-classes&gt;:&lt;poi jars&gt; \
 *     com.workin.legacy.attendance.spreadsheet.LegacyXlsFixtures \
 *     backend/src/test/resources/legacy/spreadsheet/xls
 * </pre>
 * Regenerating means re-taking the PHP oracle: the committed expectations
 * belong to the committed bytes, not to this generator.
 *
 * <h2>What POI cannot write, and how that is covered anyway</h2>
 * <p>HSSF writes {@code NUMBER} and {@code BLANK} records and offers no way to
 * ask it for the packed {@code RK}, {@code MULRK} or {@code MULBLANK} forms. A
 * renderer test over {@code NUMBER} cells therefore proves the formatting rules
 * and says <b>nothing</b> about structural decoding -- and those records are
 * not a formatting variant: {@code SimpleXLS::parseSheet()} decodes an
 * {@code RK} through its own {@code getIEEE754()}, with its own XF lookup, date
 * check, multiplier and {@code sprintf}, and walks a {@code MULRK} run with
 * hand-written offset arithmetic. So {@link #rk()}, {@link #mulRk()} and
 * {@link #mulBlank()} rewrite the cell records of a POI-written workbook at the
 * byte level, and the result is measured against PHP like every other fixture.
 *
 * <h2>One fixture is not byte-reproducible</h2>
 * <p>{@link #encrypted()} carries a random salt and verifier, so regenerating
 * it produces different bytes. The committed copy is the one PHP was measured
 * against; leave it alone unless the oracle is re-taken with it.
 */
public final class LegacyXlsFixtures {

	private LegacyXlsFixtures() {
	}

	/** Every fixture by file name, in the order the differential matrix reports them. */
	public static Map<String, byte[]> all() {
		Map<String, byte[]> fixtures = new LinkedHashMap<>();
		fixtures.put("punch_ordinary.xls", punchOrdinary());
		fixtures.put("punch_reversed.xls", punchReversed());
		fixtures.put("sst_strings.xls", sstStrings());
		fixtures.put("sparse.xls", sparse());
		fixtures.put("unicode.xls", unicode());
		fixtures.put("numbers.xls", numbers());
		fixtures.put("dates.xls", dates(false));
		fixtures.put("dates_1904.xls", dates(true));
		fixtures.put("formulas.xls", formulas());
		fixtures.put("bool_error.xls", boolError());
		fixtures.put("multi_sheet.xls", multiSheet());
		fixtures.put("empty_sheet.xls", emptySheet());
		fixtures.put("truncated.xls", truncated());
		fixtures.put("encrypted.xls", encrypted());
		fixtures.put("not_a_workbook.xls", notAWorkbook());
		fixtures.put("biff5.xls", biff5());
		fixtures.put("biff5_book_stream.xls", biff5BookStream());
		fixtures.put("rk.xls", rk());
		fixtures.put("mulrk.xls", mulRk());
		fixtures.put("mulblank.xls", mulBlank());
		return fixtures;
	}

	/** The shape a fingerprint device actually exports: two string columns. */
	public static byte[] punchOrdinary() {
		return workbook(workbook -> {
			HSSFSheet sheet = workbook.createSheet("Punch");
			strings(sheet, 0, "Emp Code", "DateTime");
			strings(sheet, 1, "555004", "26/04/2026 08:03");
			strings(sheet, 2, "555004", "26/04/2026 17:10");
			strings(sheet, 3, "555005", "26/04/2026 09:00");
		});
	}

	/** The same export with the columns the other way round. */
	public static byte[] punchReversed() {
		return workbook(workbook -> {
			HSSFSheet sheet = workbook.createSheet("Punch");
			strings(sheet, 0, "Time", "No.");
			strings(sheet, 1, "26/04/2026 08:03", "555004");
			strings(sheet, 2, "26/04/2026 17:10", "555004");
			strings(sheet, 3, "26/04/2026 09:00", "555005");
		});
	}

	/**
	 * Enough shared-string bytes to push the SST past one record.
	 *
	 * <p>A BIFF8 {@code SST} tops out at 8224 bytes and continues into
	 * {@code CONTINUE} records, which {@code SimpleXLS} handles with its own
	 * hand-rolled bookkeeping. Forty distinct 300-character strings guarantee
	 * that path is taken, and the repeated header values pin string reuse.
	 */
	public static byte[] sstStrings() {
		return workbook(workbook -> {
			HSSFSheet sheet = workbook.createSheet("Strings");
			strings(sheet, 0, "Label", "Value");
			for (int index = 1; index <= 40; index++) {
				strings(sheet, index, "repeated label", ("row " + index + " ").repeat(30));
			}
			strings(sheet, 41, "repeated label", "repeated label");
		});
	}

	/** Holes: a missing cell, an explicitly blank cell, and a one-cell row. */
	public static byte[] sparse() {
		return workbook(workbook -> {
			HSSFSheet sheet = workbook.createSheet("Sparse");
			strings(sheet, 0, "A", "B", "C");
			HSSFRow gap = sheet.createRow(1);
			gap.createCell(0).setCellValue("a1");
			// no cell 1 at all
			gap.createCell(2).setCellValue("c1");
			HSSFRow blank = sheet.createRow(2);
			blank.createCell(0).setCellValue("a2");
			blank.createCell(1);
			blank.createCell(2).setCellValue("c2");
			HSSFRow lonely = sheet.createRow(3);
			lonely.createCell(0).setCellValue("a3");
			sheet.createRow(4);
			strings(sheet, 5, "a5", "b5", "c5");
		});
	}

	/** Arabic headers and values, which BIFF8 stores as UTF-16LE. */
	public static byte[] unicode() {
		return workbook(workbook -> {
			HSSFSheet sheet = workbook.createSheet("عربي");
			strings(sheet, 0, "كود الموظف", "التاريخ");
			strings(sheet, 1, "555004", "26/04/2026 08:03");
			strings(sheet, 2, "٥٥٥٠٠٤", "مساءً");
		});
	}

	/**
	 * The numeric rendering table, one row per format.
	 *
	 * <p>Column A labels the case so every row survives the wrapper's
	 * "at least two non-blank cells" filter; column B is the cell under test.
	 */
	public static byte[] numbers() {
		return workbook(workbook -> {
			HSSFSheet sheet = workbook.createSheet("Numbers");
			strings(sheet, 0, "Case", "Value");
			numeric(workbook, sheet, 1, "general integer", 555004d, null);
			numeric(workbook, sheet, 2, "general fraction", 1.5d, null);
			numeric(workbook, sheet, 3, "general repeating", 1d / 3d, null);
			numeric(workbook, sheet, 4, "general negative", -12.25d, null);
			numeric(workbook, sheet, 5, "two decimals", 555004d, "0.00");
			numeric(workbook, sheet, 6, "zero decimals", 1234.56d, "0");
			numeric(workbook, sheet, 7, "thousands", 1234.56d, "#,##0.00");
			numeric(workbook, sheet, 8, "currency", 99.5d, "$#,##0.00");
			numeric(workbook, sheet, 9, "percent", 0.075d, "0%");
			numeric(workbook, sheet, 10, "percent decimals", 0.075d, "0.00%");
			numeric(workbook, sheet, 11, "custom non date", 1234.5678d, "#,##0.000");
			numeric(workbook, sheet, 12, "text format", 555004d, "@");
			numeric(workbook, sheet, 13, "half up", 2.5d, "0");
			numeric(workbook, sheet, 14, "big", 1.0e20d, null);
		});
	}

	/**
	 * The date rendering table, written twice -- once per date window.
	 *
	 * <p>The serials are identical in both workbooks, so the 1904 file differs
	 * from the 1900 one by exactly the epoch {@code createDate()} subtracts.
	 */
	public static byte[] dates(boolean nineteenFour) {
		return workbook(workbook -> {
			if (nineteenFour) {
				DateWindow1904Record record = (DateWindow1904Record) workbook.getInternalWorkbook()
						.findFirstRecordBySid(DateWindow1904Record.sid);
				record.setWindowing((short) 1);
			}
			HSSFSheet sheet = workbook.createSheet("Dates");
			strings(sheet, 0, "Case", "Value");
			numeric(workbook, sheet, 1, "date only", 46138d, "m/d/yy");
			numeric(workbook, sheet, 2, "date and time", 46138.335416666669d, "m/d/yy h:mm");
			numeric(workbook, sheet, 3, "time only format", 46138.335416666669d, "h:mm");
			numeric(workbook, sheet, 4, "sub one serial", 0.5d, "m/d/yy h:mm");
			numeric(workbook, sheet, 5, "serial one", 1d, "m/d/yy h:mm");
			numeric(workbook, sheet, 6, "serial two", 2d, "m/d/yy h:mm");
			numeric(workbook, sheet, 7, "excel phantom leap", 60d, "m/d/yy");
			numeric(workbook, sheet, 8, "custom escaped", 46138.5d, "yyyy\\-mm\\-dd");
			numeric(workbook, sheet, 9, "custom slashes", 46138.5d, "dd/mm/yyyy hh:mm:ss");
		});
	}

	/**
	 * Cached formula results, which is the whole point: PHP never evaluates.
	 *
	 * <p>{@code SimpleXLS} adds a cell for a formula only when the cached result
	 * is numeric -- the string, boolean and error cases fall through its
	 * {@code TYPE_FORMULA} guard and never reach {@code addCell()} at all.
	 */
	public static byte[] formulas() {
		return workbook(workbook -> {
			HSSFSheet sheet = workbook.createSheet("Formulas");
			strings(sheet, 0, "Case", "Value");
			formula(sheet, 1, "cached numeric", "2*3", null);
			formula(sheet, 2, "cached numeric date", "1+46137", "m/d/yy");
			formula(sheet, 3, "cached string", "\"HELLO\"", null);
			formula(sheet, 4, "cached concat", "\"5550\"&\"04\"", null);
			formula(sheet, 5, "cached boolean", "1=1", null);
			formula(sheet, 6, "cached error", "1/0", null);
			HSSFFormulaEvaluator.evaluateAllFormulaCells(workbook);
		});
	}

	/** {@code BOOLERR} written directly rather than through a formula. */
	public static byte[] boolError() {
		return workbook(workbook -> {
			HSSFSheet sheet = workbook.createSheet("BoolErr");
			strings(sheet, 0, "Case", "Value");
			HSSFRow trueRow = sheet.createRow(1);
			trueRow.createCell(0).setCellValue("true");
			trueRow.createCell(1).setCellValue(true);
			HSSFRow falseRow = sheet.createRow(2);
			falseRow.createCell(0).setCellValue("false");
			falseRow.createCell(1).setCellValue(false);
			HSSFRow errorRow = sheet.createRow(3);
			errorRow.createCell(0).setCellValue("div by zero");
			errorRow.createCell(1).setCellErrorValue(FormulaError.DIV0.getCode());
		});
	}

	/** Three sheets; only the first may ever be read. */
	public static byte[] multiSheet() {
		return workbook(workbook -> {
			HSSFSheet first = workbook.createSheet("First");
			strings(first, 0, "Emp Code", "DateTime");
			strings(first, 1, "555004", "26/04/2026 08:03");
			HSSFSheet second = workbook.createSheet("Second");
			strings(second, 0, "Emp Code", "DateTime");
			strings(second, 1, "999999", "01/01/2020 01:01");
			HSSFSheet third = workbook.createSheet("Third");
			strings(third, 0, "Emp Code", "DateTime");
			strings(third, 1, "888888", "02/02/2020 02:02");
		});
	}

	/** A workbook whose first sheet holds no cells at all. */
	public static byte[] emptySheet() {
		return workbook(workbook -> workbook.createSheet("Empty"));
	}

	/** {@link #punchOrdinary()} cut off mid-stream, OLE2 header intact. */
	public static byte[] truncated() {
		byte[] whole = punchOrdinary();
		return Arrays.copyOf(whole, whole.length * 3 / 4);
	}

	/** A password-protected workbook, so the globals stream opens with {@code FILEPASS}. */
	public static byte[] encrypted() {
		try {
			Biff8EncryptionKey.setCurrentUserPassword("secret");
			return punchOrdinary();
		} finally {
			Biff8EncryptionKey.setCurrentUserPassword(null);
		}
	}

	/** A valid OLE2 container with no {@code Workbook} or {@code Book} stream. */
	public static byte[] notAWorkbook() {
		try (POIFSFileSystem filesystem = new POIFSFileSystem();
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] payload = "not a workbook at all".repeat(200).getBytes(StandardCharsets.US_ASCII);
			filesystem.createDocument(new ByteArrayInputStream(payload), "NotTheWorkbook");
			filesystem.writeFilesystem(out);
			return out.toByteArray();
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * A pre-97 workbook, synthesised by rewriting the globals {@code BOF}
	 * version from {@code 0x0600} to {@code 0x0500}.
	 *
	 * <p>Genuine Excel 5/95 files cannot be authored here -- nothing in this
	 * repository writes BIFF5 -- and D-097 does not claim parity for them, so
	 * what this fixture has to pin is only that <b>Java</b> refuses the old
	 * version deterministically. Rewriting the version field is exactly the
	 * condition both readers dispatch on, so it reaches that decision without
	 * pretending to be a real Excel 95 export.
	 */
	public static byte[] biff5() {
		byte[] whole = punchOrdinary();
		byte[] marker = {0x09, 0x08, 0x10, 0x00, 0x00, 0x06, 0x05, 0x00};
		int at = indexOf(whole, marker);
		if (at < 0) {
			throw new IllegalStateException("globals BOF not found in the generated workbook");
		}
		byte[] patched = whole.clone();
		patched[at + 5] = 0x05;
		return patched;
	}

	/**
	 * The same pre-97 workbook filed under the Excel 5/95 stream name.
	 *
	 * <p>Two things can mark a workbook as old, and the two readers check
	 * different ones. {@code SimpleXLS} takes either {@code Workbook} or
	 * {@code Book} and then dispatches on the {@code BOF} version; POI keys its
	 * {@code OldExcelFormatException} on the stream being named {@code Book}
	 * and does not mind the version. So {@link #biff5()} reaches the adapter's
	 * own gate and this one reaches POI's, and D-097 needs both to end the same
	 * way.
	 */
	public static byte[] biff5BookStream() {
		byte[] patched = biff5();
		try (POIFSFileSystem source = new POIFSFileSystem(new ByteArrayInputStream(patched));
				POIFSFileSystem renamed = new POIFSFileSystem();
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			try (var stream = source.createDocumentInputStream("Workbook")) {
				renamed.createDocument(stream, "Book");
			}
			renamed.writeFilesystem(out);
			return out.toByteArray();
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// ------------------------------------------------------------------
	// RK, MULRK and MULBLANK
	//
	// HSSF writes NUMBER and BLANK records and has no way to ask it for the
	// packed forms, so these three are built by rewriting the cell records of
	// a POI-written workbook at the byte level. Nothing about the rest of the
	// file changes: the globals substream, the fonts, the XF table, the
	// BOUNDSHEET offset and DIMENSION are all still POI's, and only records
	// inside the sheet substream are touched, so no offset outside it moves.
	// ------------------------------------------------------------------

	/**
	 * Singleton {@code RK} cells across every encoding the format allows.
	 *
	 * <p>An {@code RK} packs a number four ways, chosen by its low two bits:
	 * bit 1 picks a 30-bit signed integer over the top 30 bits of an IEEE-754
	 * double, and bit 0 asks for a further division by 100. All four appear
	 * here, in both signs, and with the format indices that send the value
	 * down the date branch and the {@code 0.00} branch rather than the default
	 * {@code '%s'} one.
	 */
	public static byte[] rk() {
		List<RkCell> cells = List.of(
				new RkCell("rk integer", rkInteger(555_004), null),
				new RkCell("rk integer two decimals", rkInteger(555_004), "0.00"),
				new RkCell("rk hundredths", rkHundredths(1234), null),
				new RkCell("rk hundredths two decimals", rkHundredths(1234), "0.00"),
				new RkCell("rk negative integer", rkInteger(-1234), null),
				new RkCell("rk negative hundredths", rkHundredths(-1234), null),
				new RkCell("rk integer maximum", rkInteger(536_870_911), null),
				new RkCell("rk integer minimum", rkInteger(-536_870_912), null),
				new RkCell("rk double", rkDouble(1.5d), null),
				new RkCell("rk double negative", rkDouble(-12.25d), null),
				new RkCell("rk double hundredths", rkDoubleHundredths(150d), null),
				new RkCell("rk date", rkInteger(46_138), "m/d/yy"),
				new RkCell("rk date and time", rkDouble(46_138.335416666669d), "m/d/yy h:mm"),
				new RkCell("rk date hundredths", rkHundredths(4_613_800), "m/d/yy"));

		byte[] base = workbook(workbook -> {
			HSSFSheet sheet = workbook.createSheet("RK");
			strings(sheet, 0, "Case", "Value");
			for (int index = 0; index < cells.size(); index++) {
				RkCell cell = cells.get(index);
				// The value written here is discarded -- only the row, column
				// and XF index survive the rewrite below.
				numeric(workbook, sheet, index + 1, cell.label(), 0d, cell.format());
			}
		});

		return rewrite(base, records -> {
			List<Biff> out = new ArrayList<>();
			int seen = 0;
			for (Biff record : records) {
				if (record.sid() != NUMBER) {
					out.add(record);
					continue;
				}
				out.add(new Biff(RK, packRk(record.data(), cells.get(seen++).rk())));
			}
			if (seen != cells.size()) {
				throw new IllegalStateException("expected " + cells.size() + " NUMBER records, saw " + seen);
			}
			return out;
		});
	}

	/**
	 * {@code MULRK}: one record carrying a run of adjacent {@code RK} cells.
	 *
	 * <p>Worth its own fixture because {@code SimpleXLS} walks the run with its
	 * own offset arithmetic -- the value at {@code tmppos + 2}, the XF index
	 * the date check reads at {@code tmppos}, a six-byte stride, and the last
	 * column taken from the final two bytes of the record rather than from a
	 * count. Each row below has three adjacent numerics with <b>different</b>
	 * format indices, so a run that shared one XF by mistake would show up.
	 */
	public static byte[] mulRk() {
		List<List<RkCell>> rows = List.of(
				List.of(
						new RkCell("mulrk mixed", rkInteger(555_004), null),
						new RkCell("", rkHundredths(1234), "0.00"),
						new RkCell("", rkInteger(46_138), "m/d/yy")),
				List.of(
						new RkCell("mulrk doubles", rkDouble(1.5d), null),
						new RkCell("", rkDouble(-12.25d), "0.00"),
						new RkCell("", rkDoubleHundredths(150d), null)));

		byte[] base = workbook(workbook -> {
			HSSFSheet sheet = workbook.createSheet("MULRK");
			strings(sheet, 0, "Case", "A", "B", "C");
			for (int index = 0; index < rows.size(); index++) {
				List<RkCell> row = rows.get(index);
				HSSFRow sheetRow = sheet.createRow(index + 1);
				sheetRow.createCell(0).setCellValue(row.get(0).label());
				for (int column = 0; column < row.size(); column++) {
					HSSFCell cell = sheetRow.createCell(column + 1);
					cell.setCellValue(0d);
					String format = row.get(column).format();
					if (format != null) {
						HSSFCellStyle style = workbook.createCellStyle();
						style.setDataFormat(workbook.createDataFormat().getFormat(format));
						cell.setCellStyle(style);
					}
				}
			}
		});

		return rewrite(base, records -> merge(records, NUMBER, MULRK, 14, rows));
	}

	/**
	 * {@code MULBLANK}: a run of blank cells in one record.
	 *
	 * <p>{@code SimpleXLS} has an explicit case for it that does nothing, so
	 * the covered positions stay empty. Pinned because "the parser ignores it"
	 * and "the parser does not know it" are indistinguishable from the grid
	 * unless the surrounding cells prove the run was in the middle of a row.
	 */
	public static byte[] mulBlank() {
		byte[] base = workbook(workbook -> {
			HSSFSheet sheet = workbook.createSheet("MULBLANK");
			strings(sheet, 0, "A", "B", "C", "D");
			HSSFRow gap = sheet.createRow(1);
			gap.createCell(0).setCellValue("a1");
			gap.createCell(1);
			gap.createCell(2);
			gap.createCell(3).setCellValue("d1");
			strings(sheet, 2, "a2", "b2", "c2", "d2");
		});

		return rewrite(base, records -> merge(records, BLANK, MULBLANK, 6, null));
	}

	/** One packed cell: its row label, its {@code RK} payload and its format. */
	private record RkCell(String label, int rk, String format) {
	}

	private record Biff(int sid, byte[] data) {
	}

	/**
	 * Every record type in a fixture's workbook stream, so a test can prove the
	 * bytes really carry the records it claims to be about.
	 *
	 * <p>Without this the packed-record fixtures would be indistinguishable
	 * from ordinary ones if the rewrite below ever silently stopped matching:
	 * the assertions would still pass, on {@code NUMBER} cells, proving
	 * nothing.
	 */
	public static List<Integer> recordSids(byte[] xls) {
		List<Integer> sids = new ArrayList<>();
		for (Biff record : parse(workbookStream(xls))) {
			sids.add(record.sid());
		}
		return sids;
	}

	private static final int NUMBER = 0x0203;

	private static final int BLANK = 0x0201;

	/** BIFF8's {@code RK}. {@code SimpleXLS} also accepts the pre-BIFF8 {@code 0x007E} spelling. */
	private static final int RK = 0x027E;

	private static final int MULRK = 0x00BD;

	private static final int MULBLANK = 0x00BE;

	/** Offset accelerators. Dropped rather than left pointing at moved records. */
	private static final int INDEX = 0x020B;

	private static final int DBCELL = 0x00D7;

	private static int rkInteger(int value) {
		return (value << 2) | 0x02;
	}

	private static int rkHundredths(int value) {
		return (value << 2) | 0x03;
	}

	private static int rkDouble(double value) {
		return (int) (Double.doubleToLongBits(value) >>> 32) & 0xFFFFFFFC;
	}

	private static int rkDoubleHundredths(double value) {
		return rkDouble(value) | 0x01;
	}

	/** A {@code NUMBER} record's row/column/XF prefix, with the double replaced by the packed value. */
	private static byte[] packRk(byte[] number, int rk) {
		byte[] data = new byte[10];
		System.arraycopy(number, 0, data, 0, 6);
		writeInt(data, 6, rk);
		return data;
	}

	/**
	 * Fold every run of adjacent {@code source} records in one row into a
	 * single multi-cell record.
	 *
	 * @param payload the per-cell size of the source record's data
	 * @param packed  the {@code RK} payloads to substitute, row by row, or
	 *                {@code null} to carry only the XF indices across
	 */
	private static List<Biff> merge(
			List<Biff> records, int source, int target, int payload, List<List<RkCell>> packed) {
		List<Biff> out = new ArrayList<>();
		List<Biff> run = new ArrayList<>();
		int mergedRows = 0;
		for (Biff record : records) {
			boolean continues = record.sid() == source
					&& (run.isEmpty() || u16(run.get(0).data(), 0) == u16(record.data(), 0));
			if (continues) {
				run.add(record);
				continue;
			}
			if (run.size() > 1) {
				out.add(multi(run, target, payload, packed == null ? null : packed.get(mergedRows++)));
			} else {
				out.addAll(run);
			}
			run.clear();
			out.add(record);
		}
		if (run.size() > 1) {
			out.add(multi(run, target, payload, packed == null ? null : packed.get(mergedRows++)));
		} else {
			out.addAll(run);
		}
		if (packed != null && mergedRows != packed.size()) {
			throw new IllegalStateException("merged " + mergedRows + " runs, expected " + packed.size());
		}
		return out;
	}

	private static Biff multi(List<Biff> run, int target, int payload, List<RkCell> packed) {
		int perCell = target == MULRK ? 6 : 2;
		byte[] data = new byte[4 + run.size() * perCell + 2];
		System.arraycopy(run.get(0).data(), 0, data, 0, 4);
		int at = 4;
		for (int index = 0; index < run.size(); index++) {
			byte[] cell = run.get(index).data();
			// The XF index, which every one of these records carries per cell.
			data[at] = cell[4];
			data[at + 1] = cell[5];
			if (target == MULRK) {
				writeInt(data, at + 2, packed.get(index).rk());
			}
			at += perCell;
		}
		byte[] last = run.get(run.size() - 1).data();
		data[at] = last[2];
		data[at + 1] = last[3];
		if (payload != (target == MULRK ? 14 : 6)) {
			throw new IllegalStateException("unexpected source record size " + payload);
		}
		return new Biff(target, data);
	}

	private static byte[] rewrite(byte[] xls, java.util.function.UnaryOperator<List<Biff>> transform) {
		List<Biff> records = new ArrayList<>();
		for (Biff record : parse(workbookStream(xls))) {
			if (record.sid() != INDEX && record.sid() != DBCELL) {
				records.add(record);
			}
		}
		return container(serialize(transform.apply(records)));
	}

	private static List<Biff> parse(byte[] stream) {
		List<Biff> records = new ArrayList<>();
		int position = 0;
		while (position + 4 <= stream.length) {
			int sid = u16(stream, position);
			int length = u16(stream, position + 2);
			if (position + 4 + length > stream.length) {
				break;
			}
			records.add(new Biff(sid, Arrays.copyOfRange(stream, position + 4, position + 4 + length)));
			position += 4 + length;
		}
		return records;
	}

	private static byte[] serialize(List<Biff> records) {
		int size = 0;
		for (Biff record : records) {
			size += 4 + record.data().length;
		}
		byte[] stream = new byte[size];
		int at = 0;
		for (Biff record : records) {
			writeShort(stream, at, record.sid());
			writeShort(stream, at + 2, record.data().length);
			System.arraycopy(record.data(), 0, stream, at + 4, record.data().length);
			at += 4 + record.data().length;
		}
		return stream;
	}

	private static byte[] workbookStream(byte[] xls) {
		try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(xls));
				var stream = filesystem.createDocumentInputStream("Workbook")) {
			return stream.readAllBytes();
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static byte[] container(byte[] workbookStream) {
		try (POIFSFileSystem filesystem = new POIFSFileSystem();
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			filesystem.createDocument(new ByteArrayInputStream(workbookStream), "Workbook");
			filesystem.writeFilesystem(out);
			return out.toByteArray();
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static int u16(byte[] data, int at) {
		return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
	}

	private static void writeShort(byte[] data, int at, int value) {
		data[at] = (byte) (value & 0xFF);
		data[at + 1] = (byte) ((value >>> 8) & 0xFF);
	}

	private static void writeInt(byte[] data, int at, int value) {
		writeShort(data, at, value & 0xFFFF);
		writeShort(data, at + 2, (value >>> 16) & 0xFFFF);
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 1) {
			throw new IllegalArgumentException("usage: LegacyXlsFixtures <output-directory>");
		}
		Path directory = Path.of(args[0]);
		Files.createDirectories(directory);
		for (Map.Entry<String, byte[]> fixture : all().entrySet()) {
			Path file = directory.resolve(fixture.getKey());
			Files.write(file, fixture.getValue());
			System.out.println(file + " " + fixture.getValue().length + " bytes");
		}
	}

	private static byte[] workbook(Consumer<HSSFWorkbook> build) {
		try (HSSFWorkbook workbook = new HSSFWorkbook();
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			build.accept(workbook);
			workbook.write(out);
			return out.toByteArray();
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void strings(HSSFSheet sheet, int rowIndex, String... values) {
		HSSFRow row = sheet.createRow(rowIndex);
		for (int column = 0; column < values.length; column++) {
			row.createCell(column).setCellValue(values[column]);
		}
	}

	private static void numeric(
			HSSFWorkbook workbook, HSSFSheet sheet, int rowIndex, String label, double value, String format) {
		HSSFRow row = sheet.createRow(rowIndex);
		row.createCell(0).setCellValue(label);
		HSSFCell cell = row.createCell(1);
		cell.setCellValue(value);
		if (format != null) {
			HSSFCellStyle style = workbook.createCellStyle();
			style.setDataFormat(workbook.createDataFormat().getFormat(format));
			cell.setCellStyle(style);
		}
	}

	private static void formula(HSSFSheet sheet, int rowIndex, String label, String formula, String format) {
		HSSFRow row = sheet.createRow(rowIndex);
		row.createCell(0).setCellValue(label);
		HSSFCell cell = row.createCell(1);
		cell.setCellFormula(formula);
		if (format != null) {
			HSSFWorkbook workbook = sheet.getWorkbook();
			HSSFCellStyle style = workbook.createCellStyle();
			style.setDataFormat(workbook.createDataFormat().getFormat(format));
			cell.setCellStyle(style);
		}
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int start = 0; start + needle.length <= haystack.length; start++) {
			for (int offset = 0; offset < needle.length; offset++) {
				if (haystack[start + offset] != needle[offset]) {
					continue outer;
				}
			}
			return start;
		}
		return -1;
	}

}
