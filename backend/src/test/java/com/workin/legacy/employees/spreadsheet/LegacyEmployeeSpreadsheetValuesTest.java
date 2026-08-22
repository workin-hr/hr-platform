package com.workin.legacy.employees.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.ObjectMapper;

/**
 * The cell-level helpers and the error vocabulary, against PHP's own output.
 *
 * <p>{@code analyze_golden.json} is what a real PHP 8.3 CLI produced for every
 * input below -- 43 date values, both boolean defaults, the gender and phone
 * vocabularies, the row-shape predicates, the display rewriting, and all 27
 * error codes with and without row context. So these are not assertions about
 * what the port should do; they are the measured behaviour of the code being
 * ported, including the parts that are surprising.
 *
 * <p>The date cases are why the fixture records its own generation date: PHP's
 * {@code strtotime()} resolves {@code today}, a bare year and a bare time
 * against the current date, so the expectations only line up when the same date
 * is handed to the port.
 */
class LegacyEmployeeSpreadsheetValuesTest {

	private static final Map<String, Object> GOLDEN = golden();

	private static final LocalDate TODAY = LocalDate.parse((String) GOLDEN.get("generated_on"));

	@Test
	void everyProbedDateValueNormalizesAsPhpNormalizesIt() {
		for (Map<String, Object> probe : section("dates")) {
			Object input = probe.get("input");
			assertThat(LegacyEmployeeSpreadsheetValues.normalizeDateValue(input, TODAY))
					.as("date %s", input)
					.isEqualTo(probe.get("result"));
		}
	}

	@Test
	void theInterestingDateBranchesAreTheOnesWorthNaming() {
		// An ISO prefix is truncated, never re-parsed -- so an impossible date
		// survives intact rather than rolling over.
		assertThat(LegacyEmployeeSpreadsheetValues.normalizeDateValue("1990-02-30", TODAY))
				.isEqualTo("1990-02-30");
		assertThat(LegacyEmployeeSpreadsheetValues.normalizeDateValue("0000-00-00", TODAY))
				.isEqualTo("0000-00-00");
		// A serial inside [1, 100000) is an Excel day count...
		assertThat(LegacyEmployeeSpreadsheetValues.normalizeDateValue("34973", TODAY))
				.isEqualTo("1995-10-01");
		// ...and outside it is not: 0 fails strtotime and comes back raw, while
		// 100000 is read as the time 10:00:00 and lands on today.
		assertThat(LegacyEmployeeSpreadsheetValues.normalizeDateValue("0", TODAY)).isEqualTo("0");
		assertThat(LegacyEmployeeSpreadsheetValues.normalizeDateValue("100000", TODAY))
				.isEqualTo(TODAY.toString());
		// Dashes are day-first, and a day that overflows its month rolls.
		assertThat(LegacyEmployeeSpreadsheetValues.normalizeDateValue("15/01/1990", TODAY))
				.isEqualTo("1990-01-15");
		assertThat(LegacyEmployeeSpreadsheetValues.normalizeDateValue("30-02-2024", TODAY))
				.isEqualTo("2024-03-01");
		// Unparseable input is returned exactly as it arrived.
		assertThat(LegacyEmployeeSpreadsheetValues.normalizeDateValue("abc", TODAY)).isEqualTo("abc");
		assertThat(LegacyEmployeeSpreadsheetValues.normalizeDateValue("", TODAY)).isNull();
		assertThat(LegacyEmployeeSpreadsheetValues.normalizeDateValue(null, TODAY)).isNull();
	}

	@Test
	void theBooleanVocabularyIsClosedAndTheDefaultDistinguishesMissingFromInvalid() {
		for (Map<String, Object> probe : section("bools")) {
			Object input = probe.get("input");
			assertThat(LegacyEmployeeSpreadsheetValues.parseBool(input, -1))
					.as("bool(%s, -1)", input).isEqualTo(number(probe.get("default_minus1")));
			assertThat(LegacyEmployeeSpreadsheetValues.parseBool(input, 0))
					.as("bool(%s, 0)", input).isEqualTo(number(probe.get("default_0")));
		}
		// The reason row_to_payload passes -1: with 0 it could not tell "no"
		// from "not a value at all".
		assertThat(LegacyEmployeeSpreadsheetValues.parseBool("maybe", -1)).isEqualTo(-1);
		assertThat(LegacyEmployeeSpreadsheetValues.parseBool("no", -1)).isZero();
	}

	@Test
	void theGenderVocabularyIsClosedToo() {
		for (Map<String, Object> probe : section("genders")) {
			Object input = probe.get("input");
			assertThat(LegacyEmployeeSpreadsheetValues.parseGender(input))
					.as("gender %s", input).isEqualTo(probe.get("result"));
		}
	}

	@Test
	void aShortRowIsPaddedAndALongRowIsTruncated() {
		List<String> header = List.of("a", "b", "c");
		for (Map<String, Object> probe : section("assoc_rows")) {
			@SuppressWarnings("unchecked")
			List<String> row = (List<String>) probe.get("row");
			List<String> headerForCase = "no header".equals(probe.get("label")) ? List.of() : header;
			assertThat(LegacyEmployeeSpreadsheetValues.assocRow(headerForCase, row))
					.as("assoc %s", probe.get("label"))
					.isEqualTo(probe.get("result"));
		}
	}

	@Test
	void theRowShapePredicatesMatchPhp() {
		for (Map<String, Object> probe : section("row_flags")) {
			@SuppressWarnings("unchecked")
			Map<String, Object> row = (Map<String, Object>) probe.get("row");
			assertThat(LegacyEmployeeSpreadsheetValues.isRowEmpty(row))
					.as("empty: %s", probe.get("label")).isEqualTo(probe.get("empty"));
			assertThat(LegacyEmployeeSpreadsheetValues.isHintRow(row))
					.as("hint: %s", probe.get("label")).isEqualTo(probe.get("hint"));
			assertThat(LegacyEmployeeSpreadsheetValues.isExampleRow(row))
					.as("example: %s", probe.get("label")).isEqualTo(probe.get("example"));
		}
		// A cell holding '0' is not empty -- the predicate trims and compares to
		// the empty string, it does not use empty().
		assertThat(LegacyEmployeeSpreadsheetValues.isRowEmpty(Map.of("first_name", "0"))).isFalse();
	}

	@Test
	void theDisplayRowRewritesFourCellsAndOnlyWhenTheyArePresent() {
		for (Map<String, Object> probe : section("display_rows")) {
			@SuppressWarnings("unchecked")
			Map<String, Object> row = (Map<String, Object>) probe.get("row");
			assertThat(LegacyEmployeeSpreadsheetValues.normalizeDisplayRow(row, TODAY))
					.as("display %s", row).isEqualTo(probe.get("result"));
		}
		// A value outside the vocabulary is left exactly as it arrived.
		assertThat(LegacyEmployeeSpreadsheetValues.normalizeDisplayRow(
				Map.of("gender", "other"), TODAY).get("gender")).isEqualTo("other");
	}

	@Test
	void everyErrorCodeRendersTheMessagePhpRenders() {
		@SuppressWarnings("unchecked")
		List<String> codes = (List<String>) GOLDEN.get("codes");
		for (Map<String, Object> probe : section("error_messages")) {
			Map<String, Object> row = rowOf(probe.get("row"));
			assertThat(LegacyEmployeeSpreadsheetErrors.messages(codes, row))
					.as("messages, %s", probe.get("label"))
					.isEqualTo(probe.get("messages"));
			assertThat(LegacyEmployeeSpreadsheetErrors.fieldErrors(codes, row))
					.as("field errors, %s", probe.get("label"))
					.isEqualTo(probe.get("field_errors"));
		}
	}

	@Test
	void twoErrorsOnOneFieldAreJoinedByANewline() {
		assertThat(LegacyEmployeeSpreadsheetErrors.fieldErrors(
				List.of("employee_code_required", "employee_code_invalid"),
				Map.of("employee_code", "7001")))
				.isEqualTo(GOLDEN.get("field_error_join"));
	}

	@Test
	void anUnmappedCodeIsItsOwnMessageAndBelongsToNoField() {
		assertThat(LegacyEmployeeSpreadsheetErrors.message("something_unmapped",
				new LegacyEmployeeSpreadsheetErrors.Context("", "", ""))).isEqualTo("something_unmapped");
		assertThat(LegacyEmployeeSpreadsheetErrors.fieldKey("something_unmapped")).isNull();
		assertThat(LegacyEmployeeSpreadsheetErrors.fieldErrors(
				List.of("something_unmapped"), Map.of())).isEmpty();
	}

	@Test
	void theEmployeeCodeIsCollapsedBeforeItIsQuotedBack() {
		// normalize_employee_code() trims and collapses whitespace runs, and the
		// message quotes that form rather than the raw cell.
		assertThat(LegacyEmployeeSpreadsheetErrors.normalizeEmployeeCode("  70   01  ")).isEqualTo("70 01");
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("employee_code", "  70   01  ");
		assertThat(LegacyEmployeeSpreadsheetErrors.messages(List.of("employee_code_invalid"), row).get(0))
				.contains("(70 01)");
	}

	// ------------------------------------------------------------------

	/**
	 * PHP's empty array encodes as {@code []}, not {@code {}}, so an empty row
	 * arrives from the fixture as a list rather than a map.
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> rowOf(Object raw) {
		return raw instanceof Map ? (Map<String, Object>) raw : new LinkedHashMap<>();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> section(String name) {
		return new ArrayList<>((List<Map<String, Object>>) GOLDEN.get(name));
	}

	/** Jackson reads a JSON integer as an Integer here; the port returns an int. */
	private static int number(Object value) {
		return ((Number) value).intValue();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> golden() {
		try (InputStream stream = new ClassPathResource("legacy/spreadsheet/analyze_golden.json")
				.getInputStream()) {
			return new ObjectMapper().readValue(stream, Map.class);
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
