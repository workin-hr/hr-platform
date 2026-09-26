package com.workin.legacy.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

/**
 * What one uploaded workbook may cost to open (D-289). Every archive here is
 * built in the test, so what it inflates to is known exactly.
 */
class LegacyXlsxReaderBoundsTest {

	private static final String SHEET_PATH = "xl/worksheets/sheet1.xml";

	private static final String SHEET = "<worksheet><sheetData><row r=\"1\">"
			+ "<c r=\"A1\" t=\"inlineStr\"><is><t>Emp Code</t></is></c>"
			+ "<c r=\"B1\" t=\"inlineStr\"><is><t>DateTime</t></is></c>"
			+ "</row></sheetData></worksheet>";

	@Test
	void anOrdinaryWorkbookStillReads() throws IOException {
		assertThat(LegacyXlsxReader.readFirstSheet(zip(SHEET, 0, 0)))
				.containsExactly(List.of("Emp Code", "DateTime"));
	}

	@Test
	void aPartThatInflatesPastItsLimitIsRefusedAsUnreadable() throws IOException {
		// About 33 MB of blanks inside the sheet deflates to a few dozen KB.
		byte[] bomb = zip(SHEET, 1, LegacyXlsxReader.MAX_PART_BYTES + 1024);
		assertThat(bomb.length).isLessThan(1024 * 1024);

		assertThatThrownBy(() -> LegacyXlsxReader.readFirstSheet(bomb))
				.isInstanceOf(LegacyXlsxReader.LegacyXlsxException.class)
				.hasMessageContaining("size limit");
	}

	@Test
	void partsThatInflatePastTheTotalTogetherAreRefused() throws IOException {
		// Three parts each under the per-part limit, together over the total.
		byte[] bomb = zip(SHEET, 3, 30 * 1024 * 1024);

		assertThatThrownBy(() -> LegacyXlsxReader.readFirstSheet(bomb))
				.isInstanceOf(LegacyXlsxReader.LegacyXlsxException.class)
				.hasMessageContaining("size limit");
	}

	@Test
	void tooManyEntriesAreRefusedEvenBesideAValidSheet() throws IOException {
		byte[] crowded = zip(SHEET, LegacyXlsxReader.MAX_ENTRIES, 0);

		assertThatThrownBy(() -> LegacyXlsxReader.readFirstSheet(crowded))
				.isInstanceOf(LegacyXlsxReader.LegacyXlsxException.class)
				.hasMessageContaining("too many parts");
	}

	@Test
	void aCellReferencePastExcelsLastColumnIsRefused() throws IOException {
		// ZZZZZ is column 12,356,629: the row would be filled out to it with nulls.
		String sheet = "<worksheet><sheetData><row r=\"1\">"
				+ "<c r=\"ZZZZZ1\" t=\"inlineStr\"><is><t>x</t></is></c>"
				+ "</row></sheetData></worksheet>";

		assertThatThrownBy(() -> LegacyXlsxReader.readFirstSheet(zip(sheet, 0, 0)))
				.isInstanceOf(LegacyXlsxReader.LegacyXlsxException.class)
				.hasMessageContaining("out of range");
	}

	@Test
	void manyRowsEachNamingTheLastColumnAreRefusedBeforeTheirPaddingExists() throws IOException {
		// The review's first probe: 190 KB compressed, 1.5 MB inflated, and
		// 655 million list slots once every row is padded out to XFD.
		byte[] wide = sheetZip(zip -> {
			for (int row = 1; row <= 40_000; row++) {
				zip.write(("<row r=\"" + row + "\"><c r=\"XFD" + row + "\"/></row>").getBytes(StandardCharsets.US_ASCII));
			}
		});
		assertThat(wide.length).isLessThan(1024 * 1024);

		assertRefusedCheaply(wide, "too many cells");
	}

	@Test
	void oneRowRepeatingTheSameCellMillionsOfTimesIsRefused() throws IOException {
		// The review's second probe: 2.9 million <c r="A1"/> in one row, under
		// the part limit, which a DOM held as a heap-sized tree.
		byte[] repeated = sheetZip(zip -> {
			byte[] cell = "<c r=\"A1\"/>".getBytes(StandardCharsets.US_ASCII);
			byte[] chunk = new byte[cell.length * 1000];
			for (int copy = 0; copy < 1000; copy++) {
				System.arraycopy(cell, 0, chunk, copy * cell.length, cell.length);
			}
			zip.write("<row r=\"1\">".getBytes(StandardCharsets.US_ASCII));
			for (int written = 0; written < 2_900; written++) {
				zip.write(chunk);
			}
			zip.write("</row>".getBytes(StandardCharsets.US_ASCII));
		});
		assertThat(repeated.length).isLessThan(1024 * 1024);

		assertRefusedCheaply(repeated, "too many cells");
	}

	@Test
	void cellsAcrossRowsAreCountedAgainstTheSheetsTotal() throws IOException {
		// Each row within its own column count, the sheet past the total.
		byte[] crowded = sheetZip(zip -> {
			byte[] cells = "<c/>".repeat(LegacyXlsxReader.MAX_COLUMN_INDEX + 1).getBytes(StandardCharsets.US_ASCII);
			int rows = LegacyXlsxReader.MAX_TOTAL_CELLS / (LegacyXlsxReader.MAX_COLUMN_INDEX + 1) + 1;
			for (int row = 1; row <= rows; row++) {
				zip.write(("<row r=\"" + row + "\">").getBytes(StandardCharsets.US_ASCII));
				zip.write(cells);
				zip.write("</row>".getBytes(StandardCharsets.US_ASCII));
			}
		});

		assertRefusedCheaply(crowded, "too many cells");
	}

	@Test
	void moreRowsThanTheSheetMayHoldAreRefused() throws IOException {
		byte[] tall = sheetZip(zip -> {
			for (int row = 1; row <= LegacyXlsxReader.MAX_ROWS + 1; row++) {
				zip.write(("<row r=\"" + row + "\"/>").getBytes(StandardCharsets.US_ASCII));
			}
		});

		assertRefusedCheaply(tall, "too many rows");
	}

	@Test
	void aSheetJustInsideEveryBoundStillReads() throws IOException {
		// 122 rows padded to XFD: 1,998,848 slots, under the total.
		int rows = LegacyXlsxReader.MAX_TOTAL_CELLS / (LegacyXlsxReader.MAX_COLUMN_INDEX + 1);
		byte[] wide = sheetZip(zip -> {
			for (int row = 1; row <= rows; row++) {
				zip.write(("<row r=\"" + row + "\"><c r=\"XFD" + row + "\"><v>" + row + "</v></c></row>")
						.getBytes(StandardCharsets.US_ASCII));
			}
		});

		List<List<String>> sheet = LegacyXlsxReader.readFirstSheet(wide);
		assertThat(sheet).hasSize(rows);
		assertThat(sheet.get(rows - 1)).hasSize(LegacyXlsxReader.MAX_COLUMN_INDEX + 1)
				.endsWith(String.valueOf(rows));
	}

	@Test
	void theStreamingReadGivesWhatTheDomReadGave() throws IOException {
		// Shared strings with rich runs, a date style, booleans, an inline
		// string, a sparse row, rows out of order and a prefixed element the
		// non-namespace-aware DOM never matched -- each as the DOM answered.
		String sharedStrings = "<sst><si><t>Emp Code</t></si><si><r><t>Date</t></r><r><t xml:space=\"preserve\">"
				+ "Time </t></r></si><si><t>a &amp; <![CDATA[b]]></t></si></sst>";
		String styles = "<styleSheet><cellXfs count=\"2\"><xf numFmtId=\"0\"/><xf numFmtId=\"22\"/></cellXfs>"
				+ "</styleSheet>";
		String sheet = "<worksheet><sheetData>"
				+ "<row r=\"3\"><c r=\"A3\" t=\"b\"><v>1</v></c><c r=\"C3\" t=\"s\"><v>2</v></c></row>"
				+ "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
				+ "<row r=\"2\"><c r=\"A2\"><v>555004</v></c><c r=\"B2\" s=\"1\"><v>46138.5</v></c>"
				+ "<c r=\"C2\" t=\"inlineStr\"><is><t>inline</t></is></c><c r=\"D2\"/></row>"
				+ "<x:row r=\"4\"><c r=\"A4\"><v>9</v></c></x:row>"
				+ "</sheetData></worksheet>";
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (String[] part : new String[][] {
					{SHEET_PATH, sheet}, {"xl/sharedStrings.xml", sharedStrings}, {"xl/styles.xml", styles}}) {
				zip.putNextEntry(new ZipEntry(part[0]));
				zip.write(part[1].getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}

		List<List<String>> read = LegacyXlsxReader.readFirstSheet(out.toByteArray());
		assertThat(read).hasSize(3);
		assertThat(read.get(0)).containsExactly("Emp Code", "DateTime ");
		assertThat(read.get(1)).containsExactly("555004", "2026-04-26 12:00:00", "inline", null);
		assertThat(read.get(2)).containsExactly("TRUE", null, "a & b");
	}

	@Test
	void aDoctypeIsStillRefused() throws IOException {
		String sheet = "<!DOCTYPE worksheet [<!ENTITY x \"y\">]><worksheet><sheetData/></worksheet>";

		assertThatThrownBy(() -> LegacyXlsxReader.readFirstSheet(zip(sheet, 0, 0)))
				.isInstanceOf(LegacyXlsxReader.LegacyXlsxException.class)
				.hasMessageContaining("Cannot read XLSX part");
	}

	/**
	 * Refused as an unreadable workbook, and before the thing refused was
	 * built: the reading thread allocates a bounded amount whatever the file
	 * claims. Measured at 34-102 MB for these files -- the inflated part,
	 * copied while it grows, plus the stream's own garbage -- where the DOM
	 * read of the same probes ran a 768 MB heap out of memory.
	 */
	private static void assertRefusedCheaply(byte[] workbook, String message) {
		com.sun.management.ThreadMXBean threads =
				(com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		long before = threads.getCurrentThreadAllocatedBytes();
		assertThatThrownBy(() -> LegacyXlsxReader.readFirstSheet(workbook))
				.isInstanceOf(LegacyXlsxReader.LegacyXlsxException.class)
				.hasMessageContaining(message);
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertThat(allocated).as("bytes allocated before the refusal").isLessThan(ALLOCATION_BOUND);
	}

	private static final long ALLOCATION_BOUND = 256L * 1024 * 1024;

	private interface SheetRows {
		void write(ZipOutputStream zip) throws IOException;
	}

	/** A workbook whose only part is a sheet with {@code rows} written straight into it. */
	private static byte[] sheetZip(SheetRows rows) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(SHEET_PATH));
			zip.write("<worksheet><sheetData>".getBytes(StandardCharsets.US_ASCII));
			rows.write(zip);
			zip.write("</sheetData></worksheet>".getBytes(StandardCharsets.US_ASCII));
			zip.closeEntry();
		}
		return out.toByteArray();
	}

	/**
	 * The sheet, plus {@code padding} further parts of {@code paddingBytes}
	 * blanks each -- or, when {@code paddingBytes} is past the per-part limit,
	 * the blanks go inside the sheet itself.
	 */
	private static byte[] zip(String sheet, int padding, int paddingBytes) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(SHEET_PATH));
			if (padding == 1 && paddingBytes > LegacyXlsxReader.MAX_PART_BYTES) {
				String open = "<worksheet><sheetData>";
				zip.write(open.getBytes(StandardCharsets.UTF_8));
				blanks(zip, paddingBytes);
				zip.write("</sheetData></worksheet>".getBytes(StandardCharsets.UTF_8));
				padding = 0;
			} else {
				zip.write(sheet.getBytes(StandardCharsets.UTF_8));
			}
			zip.closeEntry();
			for (int part = 0; part < padding; part++) {
				zip.putNextEntry(new ZipEntry("xl/padding" + part + ".xml"));
				blanks(zip, paddingBytes);
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}

	private static void blanks(ZipOutputStream zip, int count) throws IOException {
		byte[] chunk = new byte[64 * 1024];
		java.util.Arrays.fill(chunk, (byte) ' ');
		for (int written = 0; written < count; written += chunk.length) {
			zip.write(chunk, 0, Math.min(chunk.length, count - written));
		}
	}
}
