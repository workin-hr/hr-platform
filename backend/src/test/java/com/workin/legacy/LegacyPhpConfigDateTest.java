package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link LegacyPhpConfigDate} against measured PHP 8.3 output.
 *
 * <p>Every expectation is the result of running the literal body of
 * {@code parse_ymd_config_date()} under {@code php:8.3-cli}. The rows that
 * matter most are the ones a reasonable implementation would get wrong:
 * {@code 2026-02-30} and {@code 2026-13-01} are <em>not</em> null, because the
 * helper never inspects {@code getLastErrors()}.
 */
class LegacyPhpConfigDateTest {

	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"2026-01-15    | 2026-01-15",
		"2026-1-5      | 2026-01-05",
		"2026-1-15     | 2026-01-15",
		"2026-01-5     | 2026-01-05",
		// The rolls. Neither of these is null in PHP.
		"2026-02-30    | 2026-03-02",
		"2026-13-01    | 2027-01-01",
		"2026-00-00    | 2025-11-30",
		"0000-00-00    | -0001-11-30",
		// Y takes one to four digits, so a short year is that year.
		"26-01-15      | 0026-01-15",
		"999-01-15     | 0999-01-15",
		// Trailing data is an error, so these are null rather than truncated.
		"garbage       | NULL",
		"2026-01-15abc | NULL",
		"2026-1-15x    | NULL",
		"2026-01-15.5  | NULL",
		"2026/01/15    | NULL",
		"2026-01       | NULL",
		"12026-01-15   | NULL",
		"-2026-01-15   | NULL",
	})
	void matchesPhp(String raw, String expected) {
		LocalDate actual = LegacyPhpConfigDate.parse(raw);
		if ("NULL".equals(expected)) {
			assertThat(actual).isNull();
		} else {
			assertThat(actual).isEqualTo(LocalDate.parse(expected));
		}
	}

	/**
	 * A datetime is null, not a date. Worth its own row because it is the one
	 * shape an administrator is most likely to paste into the config, and
	 * because "prefix parses, remainder ignored" is the intuition PHP does not
	 * follow here.
	 */
	@Test
	void aDateWithATimeIsNull() {
		assertThat(LegacyPhpConfigDate.parse("2026-01-15 10:00")).isNull();
		assertThat(LegacyPhpConfigDate.parse("2026-01-15T10:00:00Z")).isNull();
	}

	/** {@code trim($raw)} runs first, on PHP's charlist. */
	@Test
	void surroundingWhitespaceIsTrimmedFirst() {
		assertThat(LegacyPhpConfigDate.parse("  2026-01-15  ")).isEqualTo(LocalDate.of(2026, 1, 15));
		assertThat(LegacyPhpConfigDate.parse("2026-01-15\t")).isEqualTo(LocalDate.of(2026, 1, 15));
		assertThat(LegacyPhpConfigDate.parse("")).isNull();
		assertThat(LegacyPhpConfigDate.parse("   ")).isNull();
		assertThat(LegacyPhpConfigDate.parse(null)).isNull();
	}

	/**
	 * {@code format('j/n/Y')} for the {@code {date}} placeholder: no leading
	 * zeros on the day or month, four digits on the year -- and PHP writes a
	 * negative year as {@code -0001}, not {@code -001}.
	 */
	@ParameterizedTest(name = "[{index}] {0} displays as {1}")
	@CsvSource(delimiter = '|', value = {
		"2026-01-15  | 15/1/2026",
		"2026-11-30  | 30/11/2026",
		"0026-01-15  | 15/1/0026",
		"-0001-11-30 | 30/11/-0001",
	})
	void formatsTheDisplayDate(String iso, String expected) {
		assertThat(LegacyPhpConfigDate.formatDayMonthYear(LocalDate.parse(iso))).isEqualTo(expected);
	}

}
