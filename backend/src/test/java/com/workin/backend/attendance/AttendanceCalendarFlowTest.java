package com.workin.backend.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.LoginRequest;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * Attendance-calendar engine flow, against the legacy rules it ports
 * (hr-legacy attendance_calendar_helper.php / attendance_session_helper.php
 * @ d113204).
 *
 * <p>Dates are fixed in March 2026 so the results do not move: every
 * such day is comfortably past its open-session deadline, which makes
 * auto-close deterministic. The one test that needs a punch to still be
 * <em>live</em> uses the real clock instead, and stays deterministic
 * for the opposite reason — a punch made now can never be past a
 * deadline that falls on a later day.
 *
 * <p>March 2026 starts on a Sunday, so 2026-03-02 is a Monday and
 * 2026-03-06 a Friday.
 */
class AttendanceCalendarFlowTest extends AbstractIntegrationTest {

	private static final AtomicLong PHONE = new AtomicLong(7_400_000_000L);

	private static final String MONDAY = "2026-03-02";
	private static final String TUESDAY = "2026-03-03";
	private static final String FRIDAY = "2026-03-06";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(flywayDataSource);
	}

	private static String uniquePhone() {
		return "+2" + PHONE.incrementAndGet();
	}

	private AuthResponse registerCompanyAdmin() {
		return restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Calendar Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private Long createEmployee(Long companyId) {
		return jdbc().queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'Cal', 'Emp') RETURNING id",
				Long.class, companyId);
	}

	private Long createShift(Long companyId, String start, String end, String daysOff) {
		return jdbc().queryForObject(
				"INSERT INTO shifts (company_id, name, start_time, end_time, days_off) "
						+ "VALUES (?, 'Day', ?::time, ?::time, ?) RETURNING id",
				Long.class, companyId, start, end, daysOff);
	}

	private void assignShift(Long companyId, Long employeeId, Long shiftId, String effectiveFrom) {
		jdbc().update(
				"INSERT INTO employee_shift_assignments (company_id, employee_id, shift_id, effective_from) "
						+ "VALUES (?, ?, ?, ?::date)",
				companyId, employeeId, shiftId, effectiveFrom);
	}

	private void insertPunch(Long companyId, Long employeeId, String checkIn, String checkOut) {
		jdbc().update(
				"INSERT INTO attendance (company_id, employee_id, check_in, check_out, method) "
						+ "VALUES (?, ?, ?::timestamptz, ?::timestamptz, 'app')",
				companyId, employeeId, checkIn, checkOut);
	}

	private Long insertExceptionType(Long companyId, String name) {
		return jdbc().queryForObject(
				"INSERT INTO exception_types (company_id, name) VALUES (?, ?) RETURNING id",
				Long.class, companyId, name);
	}

	private void insertExceptionDay(Long companyId, Long employeeId, String date, Long exceptionTypeId) {
		jdbc().update(
				"INSERT INTO attendance (company_id, employee_id, check_in, exception_type_id) "
						+ "VALUES (?, ?, ?::timestamptz, ?)",
				companyId, employeeId, date + "T00:00:00Z", exceptionTypeId);
	}

	private void insertApprovedTimedRequest(
			Long companyId, Long employeeId, String date, String fromTime, String toTime) {
		Long requestTypeId = jdbc().queryForObject(
				"INSERT INTO request_types (company_id, name) VALUES (?, 'Mission') RETURNING id",
				Long.class, companyId);
		jdbc().update(
				"INSERT INTO requests (company_id, employee_id, request_type_id, from_date, to_date, "
						+ "from_time, to_time, status) "
						+ "VALUES (?, ?, ?, ?::date, ?::date, ?::time, ?::time, 'APPROVED')",
				companyId, employeeId, requestTypeId, date, date, fromTime, toTime);
	}

	private HttpHeaders bearer(String accessToken) {
		HttpHeaders headers = new HttpHeaders();
		if (accessToken != null) {
			headers.setBearerAuth(accessToken);
		}
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	private ResponseEntity<List<CalendarDayView>> calendar(
			String token, Long employeeId, String from, String to) {
		return restTemplate.exchange(
				"/api/tenant/attendance/" + employeeId + "/calendar?from=" + from + "&to=" + to,
				HttpMethod.GET, new HttpEntity<>(bearer(token)),
				new ParameterizedTypeReference<List<CalendarDayView>>() {
				});
	}

	private CalendarDayView singleDay(String token, Long employeeId, String date) {
		ResponseEntity<List<CalendarDayView>> response = calendar(token, employeeId, date, date);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).hasSize(1);
		return response.getBody().get(0);
	}

	/** An employee on a plain 09:00-17:00 shift with no days off. */
	private Long employeeOnDayShift(Long companyId) {
		Long employeeId = createEmployee(companyId);
		assignShift(companyId, employeeId, createShift(companyId, "09:00", "17:00", null), "2026-01-01");
		return employeeId;
	}

	@Test
	void completePunchesAreCreditedWithTheirRawDuration() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnDayShift(admin.companyId());
		insertPunch(admin.companyId(), employeeId, MONDAY + "T09:00:00Z", MONDAY + "T17:00:00Z");

		CalendarDayView day = singleDay(admin.accessToken(), employeeId, MONDAY);

		assertThat(day.durationMinutes()).isEqualTo(480);
		assertThat(day.expectedDurationMinutes()).isEqualTo(480);
		assertThat(day.isMissing()).isFalse();
		assertThat(day.attendanceId()).isNotNull();
		assertThat(day.checkIn()).isNotNull();
	}

	@Test
	void aStalePunchWithNoCheckOutIsAutoClosedAtExpectedMinusTwoHours() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnDayShift(admin.companyId());
		insertPunch(admin.companyId(), employeeId, MONDAY + "T09:00:00Z", null);

		CalendarDayView day = singleDay(admin.accessToken(), employeeId, MONDAY);

		// expected 480 - the 120-minute incomplete-punch deduction.
		assertThat(day.durationMinutes()).isEqualTo(360);
		// The read is what closed it: the synthetic check-out is persisted.
		Instant persisted = jdbc().queryForObject(
				"SELECT check_out FROM attendance WHERE employee_id = ?", Instant.class, employeeId);
		assertThat(persisted).isEqualTo(Instant.parse(MONDAY + "T15:00:00Z"));
	}

	@Test
	void aPunchStillInsideItsWindowScoresNothingRatherThanPhantomHours() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnDayShift(admin.companyId());
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		// Checked in a minute ago: the deadline is the next working day's
		// shift start, so this punch cannot yet be stale.
		jdbc().update(
				"INSERT INTO attendance (company_id, employee_id, check_in, method) VALUES (?, ?, ?, 'app')",
				admin.companyId(), employeeId, java.sql.Timestamp.from(Instant.now().minusSeconds(60)));

		CalendarDayView day = singleDay(admin.accessToken(), employeeId, today.toString());

		assertThat(day.durationMinutes()).isZero();
		assertThat(jdbc().queryForObject(
				"SELECT check_out FROM attendance WHERE employee_id = ?", Instant.class, employeeId)).isNull();
	}

	@Test
	void aWorkingDayWithNoRowIsAbsentAndCarriesAStableSyntheticId() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnDayShift(admin.companyId());

		CalendarDayView day = singleDay(admin.accessToken(), employeeId, MONDAY);

		assertThat(day.isMissing()).isTrue();
		assertThat(day.durationMinutes()).isZero();
		assertThat(day.attendanceId()).isNull();
		assertThat(day.id()).isEqualTo(-1L * ((employeeId * 100_000_000L) + 20_260_302L));
		// Stable across calls -- clients key on it.
		assertThat(singleDay(admin.accessToken(), employeeId, MONDAY).id()).isEqualTo(day.id());
	}

	@Test
	void anExceptionOnlyDayHidesItsMidnightPunchAndScoresZero() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnDayShift(admin.companyId());
		Long exceptionTypeId = insertExceptionType(admin.companyId(), "Sick");
		insertExceptionDay(admin.companyId(), employeeId, MONDAY, exceptionTypeId);

		CalendarDayView day = singleDay(admin.accessToken(), employeeId, MONDAY);

		assertThat(day.checkIn()).isNull();
		assertThat(day.checkOut()).isNull();
		assertThat(day.durationMinutes()).isZero();
		assertThat(day.expectedDurationMinutes()).isZero();
		assertThat(day.exceptionTypeName()).isEqualTo("Sick");
		assertThat(day.isMissing()).isFalse();
		assertThat(day.attendanceId()).isNotNull();
	}

	@Test
	void aWeeklyRestDayIsSynthesizedWithItsLabelAndNeverCountsAsAbsent() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		assignShift(admin.companyId(), employeeId,
				createShift(admin.companyId(), "09:00", "17:00", "Friday"), "2026-01-01");

		CalendarDayView day = singleDay(admin.accessToken(), employeeId, FRIDAY);

		assertThat(day.isWeeklyRest()).isTrue();
		assertThat(day.isMissing()).isFalse();
		assertThat(day.durationMinutes()).isZero();
		assertThat(day.expectedDurationMinutes()).isZero();
		assertThat(day.exceptionTypeName()).isEqualTo("Weekly rest");
		assertThat(day.attendanceId()).isNull();
	}

	@Test
	void anApprovedTimedRequestCreditsItsWindowEvenWithNoPunchAtAll() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnDayShift(admin.companyId());
		insertApprovedTimedRequest(admin.companyId(), employeeId, MONDAY, "10:00", "14:00");

		CalendarDayView day = singleDay(admin.accessToken(), employeeId, MONDAY);

		// Legacy manufactures attendance from the request alone.
		assertThat(day.durationMinutes()).isEqualTo(240);
		assertThat(day.isMissing()).isFalse();
		assertThat(day.attendanceId()).isNull();
	}

	@Test
	void aTimedRequestWithCompletePunchesIsCreditedFromTheScheduledStart() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnDayShift(admin.companyId());
		insertApprovedTimedRequest(admin.companyId(), employeeId, MONDAY, "10:00", "14:00");
		// Arrived late at 11:00, left at 15:00.
		insertPunch(admin.companyId(), employeeId, MONDAY + "T11:00:00Z", MONDAY + "T15:00:00Z");

		CalendarDayView day = singleDay(admin.accessToken(), employeeId, MONDAY);

		// Shift start 09:00 -> checkout 15:00, not the 4 worked hours and
		// not the 4-hour mission window.
		assertThat(day.durationMinutes()).isEqualTo(360);
	}

	@Test
	void everyDayInTheRangeIsReportedAscending() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnDayShift(admin.companyId());
		insertPunch(admin.companyId(), employeeId, MONDAY + "T09:00:00Z", MONDAY + "T17:00:00Z");

		ResponseEntity<List<CalendarDayView>> response =
				calendar(admin.accessToken(), employeeId, MONDAY, FRIDAY);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).hasSize(5);
		assertThat(response.getBody()).extracting(CalendarDayView::date)
				.containsExactly(
						LocalDate.parse(MONDAY), LocalDate.parse(TUESDAY), LocalDate.parse("2026-03-04"),
						LocalDate.parse("2026-03-05"), LocalDate.parse(FRIDAY));
	}

	@Test
	void aReversedRangeIsEmptyRatherThanAnError() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnDayShift(admin.companyId());

		ResponseEntity<List<CalendarDayView>> response =
				calendar(admin.accessToken(), employeeId, FRIDAY, MONDAY);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEmpty();
	}

	@Test
	void anotherCompanysEmployeeIsNotFound() {
		AuthResponse admin = registerCompanyAdmin();
		AuthResponse otherAdmin = registerCompanyAdmin();
		Long foreignEmployeeId = employeeOnDayShift(otherAdmin.companyId());

		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/attendance/" + foreignEmployeeId + "/calendar?from=" + MONDAY + "&to=" + MONDAY,
				HttpMethod.GET, new HttpEntity<>(bearer(admin.accessToken())), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void readingWithoutAttendanceReadIsForbidden() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnDayShift(admin.companyId());
		String hrToken = loginHrWithout(admin.companyId());

		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/attendance/" + employeeId + "/calendar?from=" + MONDAY + "&to=" + MONDAY,
				HttpMethod.GET, new HttpEntity<>(bearer(hrToken)), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		// The interceptor's generic body -- it never names the missing
		// permission, so this is all a caller can learn.
		assertThat(response.getBody()).contains("error.forbidden");
	}

	@Test
	void unauthenticatedAccessNeverSucceeds() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnDayShift(admin.companyId());

		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/attendance/" + employeeId + "/calendar?from=" + MONDAY + "&to=" + MONDAY,
				HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);

		assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
	}

	/** An HR member with a real membership but no attendance permission. */
	private String loginHrWithout(Long companyId) {
		JdbcTemplate jdbc = jdbc();
		String phone = uniquePhone();
		String password = "correct horse battery staple";
		Long identityId = jdbc.queryForObject(
				"INSERT INTO identities (phone, password_hash) VALUES (?, ?) RETURNING id",
				Long.class, phone, passwordEncoder.encode(password));
		Long membershipId = jdbc.queryForObject(
				"INSERT INTO tenant_memberships (identity_id, company_id, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
				Long.class, identityId, companyId);
		jdbc.update(
				"INSERT INTO membership_roles (membership_id, company_id, role) VALUES (?, ?, 'HR')",
				membershipId, companyId);
		return restTemplate.postForEntity(
				"/api/auth/login", new LoginRequest(phone, password), AuthResponse.class).getBody().accessToken();
	}

}
