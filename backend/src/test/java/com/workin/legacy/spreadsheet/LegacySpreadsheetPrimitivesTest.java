package com.workin.legacy.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The JDK-only spreadsheet primitives: the CSV reader with D-085's BOM
 * correction, the XLSX reader, and the writer they have to round-trip with.
 *
 * <p>No dependency is involved on either side -- {@code java.util.zip} and the
 * JDK's XML parser are the whole toolkit, which is the point: a spreadsheet
 * library would bring its own date, blank-cell and coercion behaviour, and this
 * port exists to keep legacy's.
 */
class LegacySpreadsheetPrimitivesTest {

	@Test
	void theFormatComesFromTheBytes() {
		assertThat(LegacySpreadsheetFormat.detect("PKrest".getBytes(StandardCharsets.UTF_8)))
				.isEqualTo(LegacySpreadsheetFormat.XLSX);
		assertThat(LegacySpreadsheetFormat.detect(new byte[] {
				(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}))
				.isEqualTo(LegacySpreadsheetFormat.XLS);
		assertThat(LegacySpreadsheetFormat.detect("a,b,c".getBytes(StandardCharsets.UTF_8)))
				.isEqualTo(LegacySpreadsheetFormat.CSV);
		assertThat(LegacySpreadsheetFormat.detect(new byte[0])).isEqualTo(LegacySpreadsheetFormat.EMPTY);
		assertThat(LegacySpreadsheetFormat.detect(null)).isEqualTo(LegacySpreadsheetFormat.UNKNOWN);
	}

	@Test
	void theBomIsConsumedOnceAndOnlyWhenPresent() {
		// D-085: legacy skips three bytes when there is NO BOM and keeps the BOM
		// when there is one. Both halves of that are corrected here.
		byte[] withBom = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
				"code,name\n7001,Nour\n".getBytes(StandardCharsets.UTF_8));
		byte[] withoutBom = "code,name\n7001,Nour\n".getBytes(StandardCharsets.UTF_8);

		for (byte[] content : List.of(withBom, withoutBom)) {
			List<List<String>> records = LegacyCsvReader.read(content);
			assertThat(records).hasSize(2);
			assertThat(records.get(0)).containsExactly("code", "name");
			assertThat(records.get(1)).containsExactly("7001", "Nour");
		}
	}

	@Test
	void theDelimiterComesFromTheFirstLineWithCommasWinningTies() {
		assertThat(LegacyCsvReader.detectDelimiter("a;b;c\n1;2;3\n")).isEqualTo(';');
		assertThat(LegacyCsvReader.detectDelimiter("a,b,c\n1,2,3\n")).isEqualTo(',');
		// Equal counts: PHP's >= comparison picks the comma.
		assertThat(LegacyCsvReader.detectDelimiter("a,b;c\n")).isEqualTo(',');
		// Only the first line is consulted.
		assertThat(LegacyCsvReader.detectDelimiter("a;b\n1,2,3,4\n")).isEqualTo(';');

		List<List<String>> semicolons = LegacyCsvReader.read("a;b\n1;2\n".getBytes(StandardCharsets.UTF_8));
		assertThat(semicolons.get(1)).containsExactly("1", "2");
	}

	@Test
	void quotedFieldsKeepTheirNewlinesAndSeparators() {
		// The template's headers are multi-line, so this is load-bearing.
		String csv = "\"first\nsecond\",\"has,comma\",\"say \"\"hi\"\"\"\nplain,value,here\n";
		List<List<String>> records = LegacyCsvReader.read(csv.getBytes(StandardCharsets.UTF_8));
		assertThat(records.get(0)).containsExactly("first\nsecond", "has,comma", "say \"hi\"");
		assertThat(records.get(1)).containsExactly("plain", "value", "here");
	}

	@Test
	void theWriterAndReaderRoundTripAWorkbook() {
		List<String> headers = List.of("code", "name\nsecond line", "amount");
		List<List<String>> rows = List.of(List.of("7001", "Nour", "5000"), List.of("7002", "Adel", "6000"));
		byte[] workbook = LegacyXlsxWriter.build(
				headers, rows, "Employees", List.of(List.of("", "", "group")), List.of("A1:C1"), 2,
				Map.of(0, Map.of(2, LegacyXlsxWriter.STYLE_HEADER_YELLOW)));

		assertThat(LegacySpreadsheetFormat.detect(workbook)).isEqualTo(LegacySpreadsheetFormat.XLSX);
		List<List<String>> matrix = LegacyXlsxReader.readFirstSheet(workbook);
		assertThat(matrix).hasSize(4);
		assertThat(matrix.get(0)).containsExactly("", "", "group");
		assertThat(matrix.get(1)).containsExactly("code", "name\nsecond line", "amount");
		assertThat(matrix.get(2)).containsExactly("7001", "Nour", "5000");
		assertThat(matrix.get(3)).containsExactly("7002", "Adel", "6000");
	}

	@Test
	void aWorkbookWithNoDataRowsReadsAsItsTwoLeadingRows() {
		// The case D-085 turns on: an empty result is an empty result, never a
		// reason to re-read the ZIP as CSV.
		byte[] workbook = LegacyXlsxWriter.build(
				List.of("code", "name"), List.of(), "Employees",
				List.of(List.of("", "group")), List.of(), 2, Map.of());
		List<List<String>> matrix = LegacyXlsxReader.readFirstSheet(workbook);
		assertThat(matrix).hasSize(2);
		assertThat(matrix.get(1)).containsExactly("code", "name");
	}

	@Test
	void theWorkbookCarriesItsSheetNameMergesWidthsAndFreeze() {
		byte[] workbook = LegacyXlsxWriter.build(
				List.of("a", "b"), List.of(), "Employees", List.of(List.of("x", "y")),
				List.of("A1:B1"), 2, Map.of());
		String sheet = partOf(workbook, "xl/worksheets/sheet1.xml");
		String book = partOf(workbook, "xl/workbook.xml");

		assertThat(book).contains("name=\"Employees\"");
		assertThat(sheet).contains("<mergeCell ref=\"A1:B1\"/>");
		assertThat(sheet).contains("<pane ySplit=\"2\" topLeftCell=\"A3\"");
		assertThat(sheet).contains("<col min=\"1\" max=\"1\" width=\"10\" customWidth=\"1\"/>");
		// Every cell is an inline string, as the PHP writer emits them.
		assertThat(sheet).contains("t=\"inlineStr\"");
	}

	@Test
	void aBrokenContainerStaysAFailureRatherThanBecomingCsv() {
		// D-085: ZIP bytes are never fed to the CSV reader.
		assertThatThrownBy(() -> LegacyXlsxReader.readFirstSheet("PKnot really a zip"
				.getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(LegacyXlsxReader.LegacyXlsxException.class);
	}

	@Test
	void sharedStringsNumbersBooleansAndDatesAreAllRead() {
		byte[] workbook = handWrittenWorkbook();
		List<List<String>> matrix = LegacyXlsxReader.readFirstSheet(workbook);
		assertThat(matrix).hasSize(1);
		// shared string, inline string, number, boolean, date-formatted serial,
		// and a sparse gap that becomes null.
		assertThat(matrix.get(0)).containsExactly(
				"shared value", "inline value", "42", "TRUE", "2024-03-01 00:00:00", null, "tail");
	}

	@Test
	void excelSerialsUseThePhpEpochAndLeapYearOffset() {
		assertThat(LegacyXlsxReader.excelSerialToDateTime(45352d)).startsWith("2024-03-01");
		// Serial 60 is Excel's phantom 29 February 1900; PHP's conversion
		// subtracts a day for everything from there on.
		assertThat(LegacyXlsxReader.excelSerialToDateTime(59d)).startsWith("1900-02-28");
		assertThat(LegacyXlsxReader.columnIndex("A")).isZero();
		assertThat(LegacyXlsxReader.columnIndex("Z")).isEqualTo(25);
		assertThat(LegacyXlsxReader.columnIndex("AA")).isEqualTo(26);
		assertThat(LegacyXlsxWriter.columnLetter(0)).isEqualTo("A");
		assertThat(LegacyXlsxWriter.columnLetter(26)).isEqualTo("AA");
	}

	/** A workbook written by hand, so the reader meets cell types our own writer never emits. */
	private static byte[] handWrittenWorkbook() {
		String sheet = "<?xml version=\"1.0\"?><worksheet "
				+ "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
				+ "<row r=\"1\">"
				+ "<c r=\"A1\" t=\"s\"><v>0</v></c>"
				+ "<c r=\"B1\" t=\"inlineStr\"><is><t>inline value</t></is></c>"
				+ "<c r=\"C1\"><v>42</v></c>"
				+ "<c r=\"D1\" t=\"b\"><v>1</v></c>"
				+ "<c r=\"E1\" s=\"1\"><v>45352</v></c>"
				+ "<c r=\"G1\" t=\"inlineStr\"><is><t>tail</t></is></c>"
				+ "</row></sheetData></worksheet>";
		String sharedStrings = "<?xml version=\"1.0\"?><sst "
				+ "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
				+ "<si><t>shared value</t></si></sst>";
		String styles = "<?xml version=\"1.0\"?><styleSheet "
				+ "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
				+ "<cellXfs count=\"2\"><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/></cellXfs></styleSheet>";
		String rels = "<?xml version=\"1.0\"?><Relationships "
				+ "xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
				+ "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
				+ "relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>";

		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
			for (Map.Entry<String, String> part : new java.util.LinkedHashMap<>(Map.of(
					"xl/_rels/workbook.xml.rels", rels,
					"xl/sharedStrings.xml", sharedStrings,
					"xl/styles.xml", styles,
					"xl/worksheets/sheet1.xml", sheet)).entrySet()) {
				zip.putNextEntry(new java.util.zip.ZipEntry(part.getKey()));
				zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		} catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
		return bytes.toByteArray();
	}

	private static String partOf(byte[] workbook, String name) {
		try (java.util.zip.ZipInputStream zip =
				new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(workbook))) {
			java.util.zip.ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().equals(name)) {
					return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
				}
			}
		} catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
		throw new IllegalStateException("missing part " + name);
	}

	private static byte[] concat(byte[] first, byte[] second) {
		byte[] joined = new byte[first.length + second.length];
		System.arraycopy(first, 0, joined, 0, first.length);
		System.arraycopy(second, 0, joined, first.length, second.length);
		return joined;
	}

}
