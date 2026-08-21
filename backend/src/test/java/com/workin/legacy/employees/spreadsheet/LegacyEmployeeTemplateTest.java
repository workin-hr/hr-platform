package com.workin.legacy.employees.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.ObjectMapper;

import com.workin.legacy.spreadsheet.LegacyCsvReader;
import com.workin.legacy.spreadsheet.LegacyXlsxReader;

/**
 * The two files {@code employees/template_excel.php} generates, against the two
 * files the real PHP generates.
 *
 * <p>Both fixtures were produced by running legacy itself under a PHP 8.3 CLI
 * with empty lookups: {@code template_expected.csv} is its CSV branch, and
 * {@code template_expected.xlsx} is {@code stream_employee_template_xlsx()}.
 * The comparison is what D-085's amended wording promises and no more. The CSV
 * is compared byte for byte, because PHP's CSV output is fully determined by
 * the data. The XLSX is compared on everything a reader can observe -- the
 * cells, the sheet name, the merges, the column widths, the frozen pane -- but
 * not as a byte image: ZIP timestamps, compression metadata and CRC
 * representation are archive incidentals, and no binary invariant is promised.
 */
class LegacyEmployeeTemplateTest {

	/** The date the fixtures were generated on, so {@code hire_date}'s example lines up. */
	private static final String GENERATED_ON = (String) goldenRoot().get("generated_on");

	private static final List<String> HEADERS =
			LegacyEmployeeSpreadsheetColumns.templateHeaders(null, null, null, null, GENERATED_ON);

	@Test
	void theCsvIsByteForByteTheOnePhpWrites() {
		assertThat(LegacyEmployeeTemplate.csv(HEADERS)).isEqualTo(fixture("template_expected.csv"));
	}

	@Test
	void theCsvCarriesOneBomTheGroupRowAndThe28HeadersInThatOrder() {
		byte[] csv = LegacyEmployeeTemplate.csv(HEADERS);

		assertThat(csv[0]).isEqualTo((byte) 0xEF);
		assertThat(csv[1]).isEqualTo((byte) 0xBB);
		assertThat(csv[2]).isEqualTo((byte) 0xBF);
		// Exactly one: the fourth byte is already the first record.
		assertThat(csv[3]).isNotEqualTo((byte) 0xEF);

		List<List<String>> records = LegacyCsvReader.read(csv);
		assertThat(records).hasSize(2);
		assertThat(records.get(0)).hasSize(28);
		assertThat(records.get(1)).hasSize(28);

		// Record 1 is the group row, with the label repeated across the block.
		assertThat(records.get(0).subList(18, 23)).containsOnly("استحقاقات");
		assertThat(records.get(0).subList(23, 28)).containsOnly("استقطاعات");
		assertThat(records.get(0).subList(0, 18)).containsOnly("");
		// Record 2 is the headers, newlines and all.
		assertThat(records.get(1)).containsExactlyElementsOf(HEADERS);
		assertThat(records.get(1).get(0)).contains("\n").contains("مثال: 1001");
	}

	@Test
	void theCsvQuotesOnPhpsRuleAndSeparatesOnCommas() {
		// The three BOM bytes decode to a single char, so one char is what is skipped.
		String text = new String(LegacyEmployeeTemplate.csv(HEADERS), StandardCharsets.UTF_8);
		String groupLine = text.substring(1, text.indexOf('\n'));

		// fputcsv() encloses on a space, so a one-word group label is bare...
		assertThat(groupLine).contains(",استحقاقات,").doesNotContain("\"استحقاقات\"");
		// ...and the comma is the delimiter, with 27 of them in a 28-field record.
		assertThat(groupLine.chars().filter(character -> character == ',').count()).isEqualTo(27);
		// A header containing a newline is enclosed, so the record survives it.
		assertThat(text).contains("\"" + HEADERS.get(0) + "\"");
		assertThat(LegacyCsvReader.detectDelimiter(text.substring(1))).isEqualTo(',');
	}

	@Test
	void theXlsxHasTheSameCellsAsPhps() {
		List<List<String>> ours = LegacyXlsxReader.readFirstSheet(LegacyEmployeeTemplate.xlsx(HEADERS));
		List<List<String>> theirs = LegacyXlsxReader.readFirstSheet(fixture("template_expected.xlsx"));

		assertThat(ours).isEqualTo(theirs);
		// Two rows and no data: the group titles, then the headers.
		assertThat(ours).hasSize(2);
		assertThat(ours.get(0).get(18)).isEqualTo("استحقاقات");
		assertThat(ours.get(0).get(23)).isEqualTo("استقطاعات");
		assertThat(ours.get(0).get(0)).isEmpty();
		assertThat(ours.get(1)).containsExactlyElementsOf(HEADERS);
	}

	@Test
	void theXlsxCarriesPhpsSheetNameMergesWidthsAndFrozenPane() {
		String ourSheet = part(LegacyEmployeeTemplate.xlsx(HEADERS), "xl/worksheets/sheet1.xml");
		String theirSheet = part(fixture("template_expected.xlsx"), "xl/worksheets/sheet1.xml");

		// The group row is merged across each salary block, exactly as PHP does it.
		assertThat(merges(ourSheet)).isEqualTo(merges(theirSheet)).containsExactly("S1:W1", "X1:AB1");
		// Both leading rows are frozen.
		assertThat(pane(ourSheet)).isEqualTo(pane(theirSheet))
				.contains("ySplit=\"2\"").contains("state=\"frozen\"");
		// Every column width, computed from the longest line of each header.
		assertThat(columns(ourSheet)).isEqualTo(columns(theirSheet)).hasSize(28);
		assertThat(columns(ourSheet).get(0)).isEqualTo("25.75");

		assertThat(part(LegacyEmployeeTemplate.xlsx(HEADERS), "xl/workbook.xml"))
				.contains("name=\"Employees\"");
	}

	@Test
	void theSalaryBlocksAreStyledYellowAndRedOnBothLeadingRows() {
		String sheet = part(LegacyEmployeeTemplate.xlsx(HEADERS), "xl/worksheets/sheet1.xml");
		String theirs = part(fixture("template_expected.xlsx"), "xl/worksheets/sheet1.xml");

		for (String reference : List.of("S1", "W1", "S2", "W2")) {
			assertThat(styleOf(sheet, reference)).as("entitlements at %s", reference).isEqualTo("4");
		}
		for (String reference : List.of("X1", "AB1", "X2", "AB2")) {
			assertThat(styleOf(sheet, reference)).as("deductions at %s", reference).isEqualTo("5");
		}
		// And the same cells carry the same styles in PHP's own file.
		for (String reference : List.of("S1", "W1", "S2", "W2", "X1", "AB1", "X2", "AB2")) {
			assertThat(styleOf(sheet, reference)).as("style parity at %s", reference)
					.isEqualTo(styleOf(theirs, reference));
		}
	}

	@Test
	void theArchiveHoldsTheSamePartsAsPhps() {
		// Not a byte comparison -- the part list, which is what a reader needs.
		assertThat(parts(LegacyEmployeeTemplate.xlsx(HEADERS)))
				.containsExactlyInAnyOrderElementsOf(parts(fixture("template_expected.xlsx")));
	}

	@Test
	void theFilenameAndContentTypesAreLegacys() {
		assertThat(LegacyEmployeeTemplate.filename("2026-08-21", true))
				.isEqualTo("employees_template_2026-08-21.csv");
		assertThat(LegacyEmployeeTemplate.filename("2026-08-21", false))
				.isEqualTo("employees_template_2026-08-21.xlsx");
		assertThat(LegacyEmployeeTemplate.contentDisposition("employees_template_2026-08-21.csv"))
				.isEqualTo("attachment; filename=\"employees_template_2026-08-21.csv\"");
		assertThat(LegacyEmployeeTemplate.CSV_CONTENT_TYPE).isEqualTo("text/csv; charset=utf-8");
		assertThat(LegacyEmployeeTemplate.XLSX_CONTENT_TYPE)
				.isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
	}

	// ------------------------------------------------------------------

	private static final Pattern MERGE = Pattern.compile("<mergeCell ref=\"([^\"]+)\"/>");

	private static final Pattern PANE = Pattern.compile("<pane[^>]*/>");

	private static final Pattern COLUMN = Pattern.compile("<col min=\"\\d+\" max=\"\\d+\" width=\"([^\"]+)\"");

	private static List<String> merges(String sheet) {
		return all(MERGE, sheet);
	}

	private static List<String> columns(String sheet) {
		return all(COLUMN, sheet);
	}

	private static String pane(String sheet) {
		Matcher matcher = PANE.matcher(sheet);
		return matcher.find() ? matcher.group() : "";
	}

	/** The {@code s} attribute of one cell, or {@code ""} when it carries none. */
	private static String styleOf(String sheet, String reference) {
		Matcher matcher = Pattern.compile("<c r=\"" + reference + "\"([^>]*)>").matcher(sheet);
		if (!matcher.find()) {
			return "";
		}
		Matcher style = Pattern.compile("s=\"(\\d+)\"").matcher(matcher.group(1));
		return style.find() ? style.group(1) : "";
	}

	private static List<String> all(Pattern pattern, String text) {
		List<String> found = new ArrayList<>();
		Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			found.add(matcher.group(1));
		}
		return found;
	}

	private static List<String> parts(byte[] workbook) {
		List<String> names = new ArrayList<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				names.add(entry.getName());
			}
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return names;
	}

	private static String part(byte[] workbook, String name) {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().equals(name)) {
					return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
				}
			}
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		throw new IllegalStateException("missing part " + name);
	}

	private static byte[] fixture(String name) {
		try (InputStream stream = new ClassPathResource("legacy/spreadsheet/" + name).getInputStream()) {
			return stream.readAllBytes();
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> goldenRoot() {
		try (InputStream stream = new ClassPathResource("legacy/spreadsheet/normalize_golden.json")
				.getInputStream()) {
			return new ObjectMapper().readValue(stream, Map.class);
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
