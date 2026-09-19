package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/** How {@code employees/detail.php} prints what {@link EmployeeDetail} carries. */
class EmployeeDetailTest {

	@Test
	void theHoursCardPrintsTheSumAsPhpEchoesAFloat() {
		assertThat(detail(day("8.0"), day("5.5")).hoursWorkedLabel()).isEqualTo("13.5");
		assertThat(detail(day("8.0")).hoursWorkedLabel()).as("a whole float echoes without its decimal").isEqualTo("8");
		assertThat(detail().hoursWorkedLabel()).as("array_sum of nothing").isEqualTo("0");
		assertThat(detail(day("8.0"), day(null)).hoursWorkedLabel()).as("an open shift adds nothing").isEqualTo("8");
	}

	@Test
	void anAttendanceRowPrintsItsTimesToTheMinuteAndADashForOpenHours() {
		EmployeeDetail.AttendanceDay closed = new EmployeeDetail.AttendanceDay(
				"2026-03-02", "2026-03-02 09:05:00", "2026-03-02 17:30:00", "mobile", new BigDecimal("8.4"));
		assertThat(closed.checkInTime()).isEqualTo("09:05");
		assertThat(closed.checkOutTime()).isEqualTo("17:30");
		assertThat(closed.open()).isFalse();
		assertThat(closed.hoursLabel()).isEqualTo("8.4");

		EmployeeDetail.AttendanceDay open = new EmployeeDetail.AttendanceDay(
				"2026-03-02", "2026-03-02 09:05:00", null, "mobile", null);
		assertThat(open.open()).isTrue();
		assertThat(open.hoursLabel()).isEqualTo("—");
		assertThat(new EmployeeDetail.AttendanceDay("d", "2026-03-02", "", "app", null).checkInTime())
				.as("substr past the end is empty").isEmpty();
	}

	@Test
	void anAdvancePrintsWholePoundsAndIsRedWhileAnyIsOwed() {
		EmployeeDetail.Advance owed = new EmployeeDetail.Advance(
				new BigDecimal("1500.00"), new BigDecimal("250.50"), "approved");
		assertThat(owed.amountDisplay()).isEqualTo("1,500");
		assertThat(owed.remainingDisplay()).isEqualTo("251");
		assertThat(owed.stillOwed()).isTrue();
		assertThat(new EmployeeDetail.Advance(new BigDecimal("100.00"), new BigDecimal("0.00"), "approved").stillOwed())
				.isFalse();
	}

	@Test
	void aDocumentLinksOnlyToAnHttpOrSchemelessUrl() {
		assertThat(doc("https://files.example.com/docs/a.pdf")).isEqualTo("https://files.example.com/docs/a.pdf");
		assertThat(doc("HTTP://files.example.com/a.pdf")).isEqualTo("HTTP://files.example.com/a.pdf");
		assertThat(doc("/uploads/docs/a.pdf")).isEqualTo("/uploads/docs/a.pdf");
		assertThat(doc("uploads/docs/a.pdf")).isEqualTo("uploads/docs/a.pdf");
		assertThat(doc("uploads/a:b.pdf")).as("a colon after the path starts is not a scheme").isEqualTo("uploads/a:b.pdf");
		assertThat(doc("javascript:alert(1)")).isNull();
		assertThat(doc(" JavaScript:alert(1)")).isNull();
		assertThat(doc("java\tscript:alert(1)")).as("a browser drops the tab").isNull();
		assertThat(doc("data:text/html,<script>alert(1)</script>")).isNull();
		assertThat(doc("")).isNull();
		assertThat(doc(null)).isNull();
	}

	@Test
	void initialsAreLegacysUpperCasedLettersAndAQuestionMarkForNoName() {
		assertThat(EmployeeDisplay.initials("aya alpha")).isEqualTo("A A");
		assertThat(EmployeeDisplay.initials("  آية   أحمد محمد ")).isEqualTo("آ أ");
		assertThat(EmployeeDisplay.initials("")).isEqualTo("?");
		assertThat(EmployeeDisplay.initials(null)).isEqualTo("?");
	}

	@Test
	void aBlankNameIsTheFallbackEachLegacyPagePassesBeforeDrawingInitials() {
		assertThat(EmployeeDisplay.displayName("aya alpha", "—")).isEqualTo("aya alpha");
		assertThat(EmployeeDisplay.displayName(" ", "—")).isEqualTo("—");
		assertThat(EmployeeDisplay.displayName(null, "E")).isEqualTo("E");
		assertThat(EmployeeDisplay.initials(EmployeeDisplay.displayName("", "—")))
				.as("detail.php draws the em dash itself").isEqualTo("—");
	}

	private static String doc(String url) {
		return new EmployeeDetail.Document("id_card", url, "2026-03-01 10:00:00").href();
	}

	private static EmployeeDetail.AttendanceDay day(String hours) {
		return new EmployeeDetail.AttendanceDay("2026-03-02", "2026-03-02 09:00:00",
				hours == null ? null : "2026-03-02 17:00:00", "mobile", hours == null ? null : new BigDecimal(hours));
	}

	private static EmployeeDetail detail(EmployeeDetail.AttendanceDay... days) {
		return new EmployeeDetail(null, 3, 2026, null, null, List.of(days), List.of(), List.of(), List.of(), null,
				List.of());
	}

}
