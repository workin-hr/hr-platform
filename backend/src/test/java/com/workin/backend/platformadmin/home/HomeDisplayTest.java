package com.workin.backend.platformadmin.home;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@code home_format_datetime()} ({@code pages/home/home_service.php:1043-1072}),
 * which three cells print: the home page's activity feed and complaints panel,
 * and the join requests page's request date.
 */
class HomeDisplayTest {

	@Test
	void theDateReadsAsPhpsMonthDayYearAndTwelveHourClock() {
		assertThat(HomeDisplay.dateTime("2026-03-05 14:07:00", false)).isEqualTo("Mar 5, 2026 2:07 PM");
		assertThat(HomeDisplay.dateTime("2026-03-05 14:07:00", true)).isEqualTo("5 مارس 2026 2:07 م");
	}

	@Test
	void noonAndMidnightAreTwelveRatherThanZero() {
		// PHP's `g` with `$h12 = $h % 12; if ($h12 === 0) $h12 = 12;`.
		assertThat(HomeDisplay.dateTime("2026-12-31 00:30:00", false)).isEqualTo("Dec 31, 2026 12:30 AM");
		assertThat(HomeDisplay.dateTime("2026-12-31 00:30:00", true)).isEqualTo("31 ديسمبر 2026 12:30 ص");
		assertThat(HomeDisplay.dateTime("2026-07-01 12:05:00", false)).isEqualTo("Jul 1, 2026 12:05 PM");
		assertThat(HomeDisplay.dateTime("2026-07-01 12:05:00", true)).isEqualTo("1 يوليو 2026 12:05 م");
	}

	@Test
	void theMinutesKeepTheirLeadingZeroAndTheHourDoesNot() {
		// PHP's `i` is padded and `g` is not, so 09:05 is "9:05", never "09:05".
		assertThat(HomeDisplay.dateTime("2026-01-09 09:05:00", false)).isEqualTo("Jan 9, 2026 9:05 AM");
	}

	@Test
	void aTimestampWithNoSecondsIsRead() {
		// What LocalDateTime.toString() hands over for a whole minute, which is
		// how both templates pass a stored value in.
		assertThat(HomeDisplay.dateTime("2026-03-05T14:07", false)).isEqualTo("Mar 5, 2026 2:07 PM");
	}

	@Test
	void nothingStoredIsTheEmDashAndAnUnreadableValueComesBackAsItIs() {
		assertThat(HomeDisplay.dateTime(null, false)).isEqualTo("—");
		assertThat(HomeDisplay.dateTime("", true)).isEqualTo("—");
		assertThat(HomeDisplay.dateTime("   ", false)).as("trim() leaves nothing").isEqualTo("—");
		assertThat(HomeDisplay.dateTime("not a date", false))
				.as("legacy returns $dt when strtotime() refuses it").isEqualTo("not a date");
	}

}
