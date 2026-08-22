package com.workin.legacy.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link LegacyOfficialHolidayDates} against
 * {@code official_holidays_normalize_dates()}.
 *
 * <p>The interesting cases are all the ones it accepts or drops <em>without
 * saying so</em>, plus the trim charlist -- the same distinction that was a
 * live defect in the employee search needle.
 */
class LegacyOfficialHolidayDatesTest {

	private static final String FORM_FEED = String.valueOf((char) 0x0C);
	private static final String VERTICAL_TAB = String.valueOf((char) 0x0B);
	private static final String NUL = String.valueOf((char) 0x00);

	@Test
	void keepsValidDatesInFirstSeenOrder() {
		assertThat(LegacyOfficialHolidayDates.normalize(
				List.of("2026-03-01", "2026-01-01", "2026-02-01")))
				.containsExactly("2026-03-01", "2026-01-01", "2026-02-01");
	}

	@Test
	void collapsesDuplicatesKeepingTheFirstPosition() {
		assertThat(LegacyOfficialHolidayDates.normalize(
				List.of("2026-01-01", "2026-02-01", "2026-01-01")))
				.containsExactly("2026-01-01", "2026-02-01");
	}

	@Test
	void dropsInvalidEntriesSilentlyAndKeepsTheRest() {
		// A mixed list is not an error: the good dates are created and nothing
		// reports the bad ones.
		assertThat(LegacyOfficialHolidayDates.normalize(
				Arrays.asList("2026-01-01", "oops", "", null, "2026-02-01")))
				.containsExactly("2026-01-01", "2026-02-01");
	}

	@Test
	void anEntirelyUnusableListNormalizesToNothing() {
		assertThat(LegacyOfficialHolidayDates.normalize(Arrays.asList("oops", "", null))).isEmpty();
	}

	/**
	 * {@code createFromFormat} is lenient on its own; the round-trip comparison
	 * is what rejects these. A port that only parsed would accept every one.
	 */
	@ParameterizedTest(name = "{0} is rejected by the round trip")
	@ValueSource(strings = {
		"2026-1-1",          // parses, renders as 2026-01-01, so it differs
		"2026-02-30",        // rolls into March in PHP
		"2026-13-01",
		"2026-00-10",
		"2026/01/01",
		"01-01-2026",
		"2026-01-01 00:00:00",
		"20260101",
	})
	void rejectsAnythingThatDoesNotRenderBackToItself(String value) {
		assertThat(LegacyOfficialHolidayDates.normalize(List.of(value))).isEmpty();
	}

	@Test
	void leapDaysFollowTheRealCalendar() {
		assertThat(LegacyOfficialHolidayDates.normalize(List.of("2028-02-29")))
				.containsExactly("2028-02-29");
		assertThat(LegacyOfficialHolidayDates.normalize(List.of("2026-02-29"))).isEmpty();
	}

	@Test
	void trimsExactlyThePhpCharlistAndNotJavas() {
		// The characters PHP trims: space, tab, newline, carriage return, NUL,
		// vertical tab. All of these leave a usable date.
		assertThat(LegacyOfficialHolidayDates.normalize(List.of(" 2026-01-01 ")))
				.containsExactly("2026-01-01");
		assertThat(LegacyOfficialHolidayDates.normalize(List.of("\t2026-01-01\t")))
				.containsExactly("2026-01-01");
		assertThat(LegacyOfficialHolidayDates.normalize(List.of("\n2026-01-01\r")))
				.containsExactly("2026-01-01");
		assertThat(LegacyOfficialHolidayDates.normalize(List.of(VERTICAL_TAB + "2026-01-01")))
				.containsExactly("2026-01-01");
		assertThat(LegacyOfficialHolidayDates.normalize(List.of(NUL + "2026-01-01")))
				.containsExactly("2026-01-01");

		// Form feed is NOT in PHP's charlist, so it survives the trim and then
		// fails the round trip. Java String.trim() would have removed it and
		// accepted the date -- the same divergence fixed for the search needle.
		assertThat(LegacyOfficialHolidayDates.normalize(List.of(FORM_FEED + "2026-01-01"))).isEmpty();
		assertThat(LegacyOfficialHolidayDates.normalize(List.of("2026-01-01" + FORM_FEED))).isEmpty();

		// stated as the divergence, not only as the outcome
		assertThat((FORM_FEED + "2026-01-01").trim()).isEqualTo("2026-01-01");
	}

	@Test
	void nonStringValuesGoThroughPhpsStringCast() {
		// (string) of a number is not a date, so it is skipped -- but it is
		// skipped by the round trip, not by a type check.
		assertThat(LegacyOfficialHolidayDates.normalize(List.of(20260101))).isEmpty();
		assertThat(LegacyOfficialHolidayDates.normalize(List.of(true))).isEmpty();
	}

	@Test
	void anAssociativeShapeIteratesItsValuesLikePhp() {
		// A JSON object decodes to a PHP array, and foreach walks its values.
		Map<String, Object> object = new LinkedHashMap<>();
		object.put("a", "2026-01-01");
		object.put("b", "2026-02-01");
		assertThat(LegacyOfficialHolidayDates.normalize(object))
				.containsExactly("2026-01-01", "2026-02-01");
	}

	@Test
	void aNonArrayYieldsNoDates() {
		assertThat(LegacyOfficialHolidayDates.normalize((Object) "2026-01-01")).isEmpty();
		assertThat(LegacyOfficialHolidayDates.normalize((Object) null)).isEmpty();
	}

}
