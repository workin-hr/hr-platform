package com.workin.backend.platformadmin.home;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The home page's derived display (D-290): which chart takes a whole row, the
 * colours a status chart draws in, and today's attendance as a share.
 */
class HomeLayoutTest {

	private static final Set<String> WIDE = Set.of("new", "salary");

	@Test
	void aChartLeftAloneInItsRowTakesTheRow() {
		assertThat(HomeDisplay.fullRow(List.of("new", "daily", "gender", "age", "salary", "pen"), WIDE))
				.as("daily and gender pair, age sits alone before a wide card, pen is last and alone")
				.containsExactlyInAnyOrder("new", "age", "salary", "pen");
	}

	@Test
	void pairsStayHalfWidth() {
		assertThat(HomeDisplay.fullRow(List.of("a", "b", "c", "d"), WIDE)).isEmpty();
		assertThat(HomeDisplay.fullRow(List.of("new", "a", "b", "salary"), WIDE))
				.containsExactlyInAnyOrder("new", "salary");
	}

	@Test
	void aDroppedChartMovesTheOrphan() {
		// The same page with "daily" dropped for having no punches this week.
		assertThat(HomeDisplay.fullRow(List.of("new", "gender", "age", "salary", "pen"), WIDE))
				.containsExactlyInAnyOrder("new", "salary", "pen");
	}

	@Test
	void statusSlicesTakeTheirBadgesColoursAndAnUnknownLabelFallsBack() {
		Map<String, String> tokens = Map.of("في الانتظار", "--ui-warning", "مرفوض", "--ui-danger");

		assertThat(HomeDisplay.tokensJson(List.of("في الانتظار", "غير محدد", "مرفوض"), tokens))
				.isEqualTo("[\"--ui-warning\",null,\"--ui-danger\"]");
		assertThat(HomeDisplay.tokensJson(List.of(), tokens)).isEqualTo("[]");
	}

	@Test
	void attendanceRateIsOneDecimalCappedAndAbsentWithNobodyToCount() {
		assertThat(summary(3, 1).attendanceRate()).isEqualTo("33.3");
		assertThat(summary(4, 4).attendanceRate()).isEqualTo("100.0");
		assertThat(summary(2, 3).attendanceRate())
				.as("someone deactivated after punching is in the count, not the headcount")
				.isEqualTo("100.0");
		assertThat(summary(0, 0).attendanceRate()).isNull();
	}

	private static HomeSummary summary(long employees, long checkedIn) {
		return new HomeSummary(0, 0, 0, employees, 0, checkedIn, 0, 0, 0, 0, 0,
				BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0);
	}
}
