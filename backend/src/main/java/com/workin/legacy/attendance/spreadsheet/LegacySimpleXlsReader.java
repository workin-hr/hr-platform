package com.workin.legacy.attendance.spreadsheet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.OldFileFormatException;
import org.apache.poi.hssf.record.BOFRecord;
import org.apache.poi.hssf.record.DimensionsRecord;
import org.apache.poi.hssf.record.FormatRecord;
import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;

import com.workin.legacy.LegacyValues;

/**
 * {@code Shuchkin\SimpleXLS::rows()} for the first worksheet of a BIFF8
 * workbook, with Apache POI HSSF doing the binary reading (D-097).
 *
 * <h2>What POI is and is not used for</h2>
 * <p>POI is the <b>structural</b> parser: OLE2 container, BIFF records, the
 * shared string table, the XF/FORMAT tables, the date window. Every value that
 * leaves this class is rendered by the rules in the vendored
 * {@code SimpleXLS.php}, not by POI's own idea of what a cell means --
 * {@code DataFormatter} is never called. That distinction is the whole design:
 * the two libraries agree about what a cell <em>contains</em> and disagree
 * about how it should <em>look</em>, and the legacy contract is the second
 * thing.
 *
 * <p>The gap is not cosmetic. A numeric employee code in a {@code 0.00}-format
 * cell is {@code "555004.00"} to {@code SimpleXLS} and {@code 555004} to POI,
 * and the punch-log code guard is {@code preg_match('/^\d+$/')}: rendering it
 * POI's way would silently import a sheet legacy rejects.
 *
 * <h2>Only the first sheet, and only as a dense grid</h2>
 * <p>{@code rows()} walks {@code 0..numRows-1} by {@code 0..numCols-1} taking
 * the bounds from the {@code DIMENSION} record, and yields {@code ''} for every
 * position no cell record covered. So the result is rectangular, missing cells
 * are empty strings rather than absent keys, and sheets beyond the first are
 * never read even though POI has them open.
 *
 * <h2>Everything here is measured</h2>
 * <p>Each rule below was taken from PHP 8.3 running this repository's own
 * {@code SimpleXLS.php} over the committed fixtures in
 * {@code src/test/resources/legacy/spreadsheet/xls}. See
 * {@code LegacySimpleXlsReaderDifferentialTest}, which pins the whole grid for
 * every one of them.
 *
 * @see LegacyXlsFixtures
 */
public final class LegacySimpleXlsReader {

	/** {@code SimpleXLS::$datetimeFormat} -- unconditional, never date-only. */
	private static final DateTimeFormatter DATE_TIME =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/** {@code SimpleXLS::DEF_NUM_FORMAT}, which is what {@code $defaultFormat} holds. */
	private static final String DEFAULT_FORMAT = "%s";

	/** The 1900 and 1904 epoch offsets {@code createDate()} subtracts. */
	private static final int EPOCH_1900 = 25_569;

	private static final int EPOCH_1904 = 24_107;

	/**
	 * {@code SimpleXLS::$dateFormats}. Only the keys matter: a cell whose
	 * format index is in this set is a date, and {@code createDate()} then
	 * formats it with {@code $datetimeFormat} regardless of the pattern here.
	 */
	private static final Map<Integer, String> DATE_FORMATS = Map.ofEntries(
			Map.entry(0x0e, "d/m/Y"),
			Map.entry(0x0f, "d-M-Y"),
			Map.entry(0x10, "d-M"),
			Map.entry(0x11, "M-Y"),
			Map.entry(0x12, "h:i a"),
			Map.entry(0x13, "h:i:s a"),
			Map.entry(0x14, "H:i"),
			Map.entry(0x15, "H:i:s"),
			Map.entry(0x16, "d/m/Y H:i"),
			Map.entry(0x2d, "i:s"),
			Map.entry(0x2e, "H:i:s"),
			Map.entry(0x2f, "i:s.S"));

	/**
	 * {@code SimpleXLS::$numberFormats} -- the {@code sprintf()} pattern per
	 * built-in format index, verbatim including the entries whose comment in
	 * the PHP says one thing and whose pattern says another.
	 */
	private static final Map<Integer, String> NUMBER_FORMATS = Map.ofEntries(
			Map.entry(0x01, "%1.0f"),
			Map.entry(0x02, "%1.2f"),
			Map.entry(0x03, "%1.0f"),
			Map.entry(0x04, "%1.2f"),
			Map.entry(0x05, "%1.0f"),
			Map.entry(0x06, "$%1.0f"),
			Map.entry(0x07, "$%1.2f"),
			Map.entry(0x08, "$%1.2f"),
			Map.entry(0x09, "%1.0f%%"),
			Map.entry(0x0a, "%1.2f%%"),
			Map.entry(0x0b, "%1.2f"),
			Map.entry(0x25, "%1.0f"),
			Map.entry(0x26, "%1.0f"),
			Map.entry(0x27, "%1.2f"),
			Map.entry(0x28, "%1.2f"),
			Map.entry(0x29, "%1.0f"),
			Map.entry(0x2a, "$%1.0f"),
			Map.entry(0x2b, "%1.2f"),
			Map.entry(0x2c, "$%1.2f"),
			Map.entry(0x30, "%1.0f"));

	/**
	 * {@code preg_match('/^[hmsday\/\-:\., ]+$/i', $fs)} -- the test that
	 * promotes a custom {@code FORMAT} string to a date format.
	 *
	 * <p>It is looser than it looks: any string built only from those letters
	 * and separators qualifies, so {@code "dd/mm/yyyy"} is a date and so is the
	 * bare word {@code "may"}.
	 */
	private static final Pattern DATE_LIKE =
			Pattern.compile("^[hmsday/\\-:., ]+$", Pattern.CASE_INSENSITIVE);

	/** Every entry of {@link #NUMBER_FORMATS}: an optional {@code $}, {@code %1.Nf}, an optional {@code %%}. */
	private static final Pattern NUMBER_PATTERN = Pattern.compile("^(\\$?)%1\\.(\\d)f(%%)?$");

	/**
	 * A password no workbook can have been encrypted with, installed for the
	 * duration of the parse so POI cannot decrypt anything.
	 *
	 * <p>Left to itself POI tries Excel's well-known default password and will
	 * happily open a workbook that was "protected" with it. {@code SimpleXLS}
	 * has no crypto at all -- it sees {@code FILEPASS} and abandons the
	 * globals substream -- so a workbook POI could open is one legacy cannot
	 * read, and opening it would be a divergence in the direction that matters
	 * least defensibly. This makes the refusal deliberate rather than
	 * incidental. Never make it a real password.
	 */
	private static final String REFUSE_TO_DECRYPT = "legacy parity -- never decrypt an .xls";

	private LegacySimpleXlsReader() {
	}

	/**
	 * The first worksheet as {@code SimpleXLS::rows()} exposes it.
	 *
	 * <p>An empty list means the workbook parsed but held nothing readable --
	 * which is also what legacy produces for an encrypted file, because
	 * {@code parseEntries()} returns {@code false} on {@code FILEPASS} without
	 * setting an error, so {@code parseFile()} still reports success and
	 * {@code rows()} finds no sheet to walk. Measured, not assumed.
	 *
	 * @throws LegacyAttendanceImportException with the message legacy raises
	 *         when {@code SimpleXLS} cannot read a workbook at all
	 */
	public static List<List<String>> readFirstSheet(byte[] content) {
		HSSFWorkbook workbook = open(content);
		if (workbook == null) {
			return List.of();
		}
		try (workbook) {
			requireBiff8(workbook);
			return rows(workbook);
		} catch (IOException ex) {
			// close() only. The stream is a byte array, so this cannot happen
			// -- and if it somehow did it is not a bad workbook.
			throw new IllegalStateException("closing an in-memory workbook", ex);
		}
	}

	/**
	 * POI's parse, and <b>only</b> POI's parse.
	 *
	 * <p>The wide catch below belongs to reading a hostile file: a corrupt
	 * record, a bad container, an unsupported version. It deliberately does
	 * <em>not</em> cover the rendering that follows, because a defect in this
	 * class' own {@code sprintf} or date arithmetic is an unexpected Java
	 * failure and must reach D-084's 500 -- not be disguised as a 400 telling
	 * the caller their file was invalid.
	 *
	 * @return the workbook, or {@code null} for an encrypted one, which legacy
	 *         reports as an empty sheet rather than as an error
	 */
	private static HSSFWorkbook open(byte[] content) {
		String previousPassword = Biff8EncryptionKey.getCurrentUserPassword();
		Biff8EncryptionKey.setCurrentUserPassword(REFUSE_TO_DECRYPT);
		try {
			return new HSSFWorkbook(new ByteArrayInputStream(content));
		} catch (EncryptedDocumentException ex) {
			return null;
		} catch (OldFileFormatException ex) {
			// D-097: BIFF5/BIFF7 is outside the documented "Excel 97-2003"
			// surface. Legacy's vendored reader has an incidental 0x500 branch
			// and can parse some of these; Java refuses them deterministically.
			throw unreadable();
		} catch (IOException | RuntimeException ex) {
			// Corrupt, truncated, or not a workbook at all. Legacy has no
			// answer here worth reproducing -- see this class' companion note
			// in the wave discovery -- so the bounded, legacy-shaped refusal
			// stands in for behaviour that does not terminate.
			throw unreadable();
		} finally {
			Biff8EncryptionKey.setCurrentUserPassword(previousPassword);
		}
	}

	/**
	 * {@code parse_legacy_xls_spreadsheet($path, true)}'s message for a
	 * workbook {@code SimpleXLS} refuses: the prefix plus
	 * {@code parseError() ?: 'Invalid or corrupted file'}.
	 */
	private static LegacyAttendanceImportException unreadable() {
		return new LegacyAttendanceImportException("Cannot read XLS file. Invalid or corrupted file");
	}

	/**
	 * D-097's format gate: the workbook globals must open with a BIFF8
	 * {@code BOF}.
	 *
	 * <p>POI has its own old-format detection, but it keys on the OLE2 stream
	 * being named {@code Book} rather than {@code Workbook} -- which is how
	 * Excel 5/95 wrote it, and which {@code SimpleXLS} accepts as an alias
	 * without looking further. A workbook carrying the older <em>version</em>
	 * in a {@code Workbook} stream slips past that check, and legacy will then
	 * parse it through its {@code BIFF7 = 0x500} branch. The version field is
	 * the thing both readers actually dispatch on, so it is what is tested
	 * here; POI's stream-name check still stands behind it, in {@link #open}.
	 */
	private static void requireBiff8(HSSFWorkbook workbook) {
		BOFRecord bof = (BOFRecord) workbook.getInternalWorkbook().findFirstRecordBySid(BOFRecord.sid);
		if (bof == null || bof.getVersion() != BOFRecord.VERSION) {
			throw unreadable();
		}
	}

	private static List<List<String>> rows(HSSFWorkbook workbook) {
		if (workbook.getNumberOfSheets() == 0) {
			return List.of();
		}
		HSSFSheet sheet = workbook.getSheetAt(0);
		int numRows = rowCount(sheet);
		int numCols = columnCount(sheet);
		Map<Integer, String> formats = formatRecords(workbook);
		boolean nineteenFour = workbook.getInternalWorkbook().isUsing1904DateWindowing();

		List<List<String>> grid = new ArrayList<>(numRows);
		for (int rowIndex = 0; rowIndex < numRows; rowIndex++) {
			HSSFRow row = sheet.getRow(rowIndex);
			List<String> cells = new ArrayList<>(numCols);
			for (int column = 0; column < numCols; column++) {
				HSSFCell cell = row == null ? null : row.getCell(column);
				cells.add(cell == null ? "" : value(cell, formats, nineteenFour));
			}
			grid.add(List.copyOf(cells));
		}
		return List.copyOf(grid);
	}

	/**
	 * {@code $this->sheets[$sn]['numRows']}: the {@code DIMENSION} record's
	 * "last row + 1" field, falling back to the highest row index that carries
	 * a recorded cell.
	 *
	 * <p>That fallback is legacy's, off-by-one included -- {@code maxrow} is an
	 * index and it is assigned to a <em>count</em>, so a workbook with no
	 * {@code DIMENSION} loses its last row. Reproduced rather than corrected;
	 * no writer this endpoint sees omits {@code DIMENSION}.
	 */
	private static int rowCount(HSSFSheet sheet) {
		DimensionsRecord dimensions = dimensions(sheet);
		int fromRecord = dimensions == null ? 0 : dimensions.getLastRow();
		if (fromRecord != 0) {
			return fromRecord;
		}
		int highest = 0;
		for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
			HSSFRow row = sheet.getRow(rowIndex);
			if (row != null && hasRecordedCell(row)) {
				highest = rowIndex;
			}
		}
		return highest;
	}

	/** {@code numCols}, on the same terms as {@link #rowCount}. */
	private static int columnCount(HSSFSheet sheet) {
		DimensionsRecord dimensions = dimensions(sheet);
		int fromRecord = dimensions == null ? 0 : dimensions.getLastCol() & 0xFFFF;
		if (fromRecord != 0) {
			return fromRecord;
		}
		int highest = 0;
		for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
			HSSFRow row = sheet.getRow(rowIndex);
			if (row == null) {
				continue;
			}
			for (int column = 0; column < row.getLastCellNum(); column++) {
				HSSFCell cell = row.getCell(column);
				if (cell != null && records(cell)) {
					highest = Math.max(highest, column);
				}
			}
		}
		return highest;
	}

	private static DimensionsRecord dimensions(HSSFSheet sheet) {
		return (DimensionsRecord) sheet.getSheet().findFirstRecordBySid(DimensionsRecord.sid);
	}

	private static boolean hasRecordedCell(HSSFRow row) {
		for (int column = 0; column < row.getLastCellNum(); column++) {
			HSSFCell cell = row.getCell(column);
			if (cell != null && records(cell)) {
				return true;
			}
		}
		return false;
	}

	/** Whether {@code parseSheet()} would have reached {@code addCell()} for this cell. */
	private static boolean records(HSSFCell cell) {
		CellType type = cell.getCellType();
		if (type == CellType.FORMULA) {
			return cell.getCachedFormulaResultType() == CellType.NUMERIC;
		}
		return type != CellType.BLANK && type != CellType._NONE;
	}

	/**
	 * One cell as {@code addCell()} stored it.
	 *
	 * <p>The formula rule is the surprising one. {@code parseSheet()} only
	 * records a formula cell when the cached result is numeric: a cached
	 * string, boolean, error or empty-string result fails its
	 * {@code byte6 < 4 && byte12 == 255 && byte13 == 255} guard and the cell is
	 * never added, so it comes back as {@code ''}. PHP never evaluates a
	 * formula, and neither does this.
	 *
	 * <p>{@code BOOLERR} is stored as the raw byte, so {@code TRUE} is 1,
	 * {@code FALSE} is 0 and {@code #DIV/0!} is 7 -- legacy keeps those as PHP
	 * integers rather than strings, which is invisible downstream because every
	 * consumer either casts to string or compares against {@code null}/{@code ''}
	 * identically for both.
	 */
	private static String value(HSSFCell cell, Map<Integer, String> formats, boolean nineteenFour) {
		CellType type = cell.getCellType();
		if (type == CellType.FORMULA) {
			return cell.getCachedFormulaResultType() == CellType.NUMERIC
					? numeric(cell, formats, nineteenFour)
					: "";
		}
		return switch (type) {
			case STRING -> cell.getStringCellValue();
			case NUMERIC -> numeric(cell, formats, nineteenFour);
			case BOOLEAN -> cell.getBooleanCellValue() ? "1" : "0";
			case ERROR -> Integer.toString(cell.getErrorCellValue() & 0xFF);
			default -> "";
		};
	}

	/**
	 * {@code isDate()} followed by either {@code createDate()} or
	 * {@code sprintf($this->curFormat, $value * $this->multiplier)}.
	 *
	 * <p>The three-way classification is by format index: the built-in date
	 * set, the built-in number set, then a custom {@code FORMAT} string that
	 * looks like a date. Anything left over -- {@code General}, the text format
	 * {@code @}, a custom pattern with digits or currency in it -- falls to
	 * {@code $defaultFormat}, which is {@code '%s'}, so the value is rendered
	 * as PHP renders a float in string context.
	 */
	private static String numeric(HSSFCell cell, Map<Integer, String> formats, boolean nineteenFour) {
		double raw = cell.getNumericCellValue();
		int formatIndex = cell.getCellStyle().getDataFormat() & 0xFFFF;

		if (DATE_FORMATS.containsKey(formatIndex)) {
			return createDate(raw, nineteenFour);
		}
		String numberFormat = NUMBER_FORMATS.get(formatIndex);
		if (numberFormat != null) {
			// `if (strpos($this->curFormat, '%%') !== false) $this->multiplier = 100;`
			double multiplier = numberFormat.contains("%%") ? 100d : 1d;
			return sprintf(numberFormat, raw * multiplier);
		}
		if (formatIndex > 0) {
			String custom = formats.get(formatIndex);
			// `$fs = str_replace('\\', '', $formatstr); if ($fs && preg_match(...))`.
			// PHP's truthiness, so an empty string is not a date and neither is
			// the single character "0".
			String stripped = custom == null ? "" : custom.replace("\\", "");
			if (!stripped.isEmpty() && !"0".equals(stripped) && DATE_LIKE.matcher(stripped).matches()) {
				return createDate(raw, nineteenFour);
			}
		}
		return sprintf(DEFAULT_FORMAT, raw);
	}

	/**
	 * {@code createDate()}: shift by the workbook's epoch, round to whole
	 * seconds, and render as {@code Y-m-d H:i:s} in UTC.
	 *
	 * <p>Two details are legacy's and both are observable. The epoch is
	 * subtracted only when the serial exceeds 1, so a time-only cell
	 * ({@code 0.5}) is reported as noon on <b>1 January 1970</b> rather than as
	 * a time of day. And the format is unconditional, so a date-only serial
	 * still carries {@code 00:00:00} -- which is where this parts company with
	 * {@code excel_serial_to_datetime_string()}, the XLSX-side helper, that
	 * drops the time half when it is zero.
	 */
	static String createDate(double serial, boolean nineteenFour) {
		double shifted = serial > 1 ? serial - (nineteenFour ? EPOCH_1904 : EPOCH_1900) : serial;
		long timestamp = phpRound(shifted * 24d * 3600d);
		return LocalDateTime.ofEpochSecond(timestamp, 0, ZoneOffset.UTC).format(DATE_TIME);
	}

	/** PHP's {@code round()}: halves go away from zero, unlike its {@code sprintf()}. */
	private static long phpRound(double value) {
		return value < 0 ? -(long) Math.floor(-value + 0.5d) : (long) Math.floor(value + 0.5d);
	}

	/**
	 * {@code sprintf()} for the bounded set of patterns the format tables hold.
	 *
	 * <p>{@code '%s'} is PHP's float-to-string conversion -- 14 significant
	 * digits with trailing zeros trimmed, which is why an integral cell value
	 * is {@code "555004"} and not {@code "555004.0"}.
	 *
	 * <p>The {@code %1.Nf} patterns round <b>half to even</b>, because PHP's
	 * {@code sprintf} formats through {@code zend_dtoa} and rounds the exact
	 * binary value correctly. {@code 2.5} with {@code %1.0f} is therefore
	 * {@code "2"}, not {@code "3"} -- measured, and the opposite of what
	 * {@link #phpRound} does two methods up.
	 */
	private static String sprintf(String format, double value) {
		if (DEFAULT_FORMAT.equals(format)) {
			return LegacyValues.toPhpString(value);
		}
		Matcher pattern = NUMBER_PATTERN.matcher(format);
		if (!pattern.matches()) {
			throw new IllegalStateException("Unsupported SimpleXLS number format: " + format);
		}
		String rendered = pattern.group(1)
				+ fixed(value, Integer.parseInt(pattern.group(2)));
		return pattern.group(3) == null ? rendered : rendered + "%";
	}

	/** One {@code %f} conversion, sign taken from the sign bit as PHP's is. */
	private static String fixed(double value, int precision) {
		if (Double.isNaN(value)) {
			return "nan";
		}
		if (Double.isInfinite(value)) {
			return value > 0 ? "inf" : "-inf";
		}
		boolean negative = (Double.doubleToRawLongBits(value) & Long.MIN_VALUE) != 0;
		String digits = new BigDecimal(Math.abs(value))
				.setScale(precision, RoundingMode.HALF_EVEN)
				.toPlainString();
		return negative ? "-" + digits : digits;
	}

	/**
	 * {@code $this->formatRecords[$indexCode] = $formatString} -- the custom
	 * {@code FORMAT} records only.
	 *
	 * <p>POI would also answer with a built-in pattern for an index that has no
	 * {@code FORMAT} record of its own; legacy would see nothing there, so the
	 * records are read directly rather than through
	 * {@code getDataFormatString()}.
	 */
	private static Map<Integer, String> formatRecords(HSSFWorkbook workbook) {
		Map<Integer, String> formats = new HashMap<>();
		for (FormatRecord record : workbook.getInternalWorkbook().getFormats()) {
			formats.put(record.getIndexCode(), record.getFormatString());
		}
		return formats;
	}

}
