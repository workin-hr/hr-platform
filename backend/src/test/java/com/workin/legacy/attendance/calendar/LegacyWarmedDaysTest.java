package com.workin.legacy.attendance.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * What {@link LegacyWarmedDays} counts against its budget when one employee is
 * warmed more than once in a request (D-292): overlapping and adjacent windows
 * merge, and a window that neither overlaps nor touches the held one replaces
 * it rather than allocating the gap -- so the count is what is actually held.
 */
class LegacyWarmedDaysTest {

	private static final Object NOT_WARMED = new Object();
	private static final LocalDate JAN_1 = LocalDate.parse("2020-01-01");

	@Test
	void aDisjointSecondWindowReplacesTheFirst() {
		LegacyWarmedDays<Object> days = new LegacyWarmedDays<>(NOT_WARMED);
		days.put(1L, JAN_1, answers(10, "old"));
		days.put(1L, LocalDate.parse("2025-01-01"), answers(5, "new"));

		assertThat(days.slotCount()).as("the new window's length, not the span between them").isEqualTo(5);
		assertThat(days.get(1L, "2025-01-03")).isEqualTo("new");
		assertThat(days.get(1L, "2020-01-03")).as("the replaced window is no longer answered").isSameAs(NOT_WARMED);
	}

	@Test
	void anOverlappingSecondWindowIsMerged() {
		LegacyWarmedDays<Object> days = new LegacyWarmedDays<>(NOT_WARMED);
		days.put(1L, JAN_1, answers(10, "first"));
		days.put(1L, JAN_1.plusDays(5), answers(10, "second"));

		assertThat(days.slotCount()).as("2020-01-01 .. 2020-01-15").isEqualTo(15);
		assertThat(days.get(1L, "2020-01-01")).isEqualTo("first");
		assertThat(days.get(1L, "2020-01-15")).isEqualTo("second");
	}

	@Test
	void anAdjacentSecondWindowIsMerged() {
		LegacyWarmedDays<Object> days = new LegacyWarmedDays<>(NOT_WARMED);
		days.put(1L, JAN_1, answers(10, "first"));
		days.put(1L, JAN_1.plusDays(10), answers(10, "second"));

		assertThat(days.slotCount()).isEqualTo(20);
		assertThat(days.get(1L, "2020-01-10")).isEqualTo("first");
		assertThat(days.get(1L, "2020-01-11")).isEqualTo("second");
	}

	@Test
	void theBudgetCountsEveryEmployeeAndEveryWarm() {
		LegacyWarmedDays<Object> days = new LegacyWarmedDays<>(NOT_WARMED);
		assertThat(days.admits(200, 740_000)).as("a span under the budget, times 200 employees, is over it").isFalse();
		assertThat(days.admits(1, 2_000_000)).isTrue();
		days.put(1L, JAN_1, new Object[2_000_000]);

		assertThat(days.slotCount()).isEqualTo(2_000_000);
		assertThat(days.admits(1, 2_000_000)).as("a second 2,000,000 on top of the first").isFalse();
		assertThat(days.admits(1, 1_000_000)).as("exactly the rest of the budget").isTrue();
	}

	private static Object[] answers(int length, Object answer) {
		Object[] answers = new Object[length];
		java.util.Arrays.fill(answers, answer);
		return answers;
	}
}
