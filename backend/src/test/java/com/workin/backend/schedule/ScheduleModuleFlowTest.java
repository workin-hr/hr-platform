package com.workin.backend.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.LoginRequest;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * Schedule foundation flow: assignment-history resolution, weekly-rest
 * union (shift days_off + company weekly_off_days), manual-row
 * precedence, destructive generate, and the F-18 negatives. Fixed
 * historical months keep summaryDate deterministic (LocalDate.now()
 * only matters when the requested month is the current one).
 */
class ScheduleModuleFlowTest extends AbstractIntegrationTest {

	private static final AtomicLong PHONE = new AtomicLong(7_000_000_000L);

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
				new RegisterCompanyRequest("Schedule Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private Long createEmployee(Long companyId) {
		return jdbc().queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'Sched', 'Emp') RETURNING id",
				Long.class, companyId);
	}

	private Long createShift(Long companyId, String name, String start, String end, String daysOff) {
		return jdbc().queryForObject(
				"INSERT INTO shifts (company_id, name, start_time, end_time, days_off) "
						+ "VALUES (?, ?, ?::time, ?::time, ?) RETURNING id",
				Long.class, companyId, name, start, end, daysOff);
	}

	private void insertAssignment(Long companyId, Long employeeId, Long shiftId, String effectiveFrom) {
		jdbc().update(
				"INSERT INTO employee_shift_assignments (company_id, employee_id, shift_id, effective_from) "
						+ "VALUES (?, ?, ?, ?::date)",
				companyId, employeeId, shiftId, effectiveFrom);
	}

	private void setCompanyWeeklyOffDays(Long companyId, String value) {
		jdbc().update(
				"INSERT INTO company_settings (company_id, weekly_off_days) VALUES (?, ?)",
				companyId, value);
	}

	private record HrFixture(String accessToken, Long membershipId, Long companyId) {
	}

	private HrFixture loginHrMember(Long companyId) {
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
		AuthResponse login = restTemplate.postForEntity(
				"/api/auth/login", new LoginRequest(phone, password), AuthResponse.class).getBody();
		return new HrFixture(login.accessToken(), membershipId, companyId);
	}

	private void grantPermission(HrFixture hr, String permissionKey) {
		jdbc().update(
				"INSERT INTO membership_permission_overrides (membership_id, company_id, permission_id, effect) "
						+ "SELECT ?, ?, p.id, 'ALLOW' FROM permissions p WHERE p.permission_key = ?",
				hr.membershipId(), hr.companyId(), permissionKey);
	}

	private HttpHeaders bearer(String accessToken) {
		HttpHeaders headers = new HttpHeaders();
		if (accessToken != null) {
			headers.setBearerAuth(accessToken);
		}
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	private ResponseEntity<MonthlyOverviewView> monthly(String token, Long employeeId, int year, int month) {
		return restTemplate.exchange(
				"/api/tenant/schedules/" + employeeId + "/monthly?year=" + year + "&month=" + month,
				HttpMethod.GET, new HttpEntity<>(bearer(token)), MonthlyOverviewView.class);
	}

	private ResponseEntity<Void> assign(String token, Long employeeId, Long shiftId, List<String> dates) {
		String body = "{\"shiftId\": " + shiftId + ", \"dates\": [\""
				+ String.join("\", \"", dates) + "\"]}";
		return restTemplate.exchange(
				"/api/tenant/schedules/" + employeeId + "/assign",
				HttpMethod.POST, new HttpEntity<>(body, bearer(token)), Void.class);
	}

	private ResponseEntity<GenerateResultView> generate(String token, Long employeeId, String from, String to) {
		String body = "{\"from\": \"" + from + "\", \"to\": \"" + to + "\"}";
		HttpEntity<String> entity = new HttpEntity<>(body, bearer(token));
		String path = "/api/tenant/schedules/" + employeeId + "/generate";
		try {
			return restTemplate.exchange(path, HttpMethod.POST, entity, GenerateResultView.class);
		} catch (org.springframework.web.client.RestClientException e) {
			// Error response bodies (Spring's default {timestamp,status,
			// error,path} shape) don't deserialize as GenerateResultView --
			// TestRestTemplate's no-op error handler means this
			// deserialization failure, not an HTTP status exception, is
			// what we see for every 4xx/5xx. Re-probe as a raw string to
			// recover the real status code (safe: a rejected generate call
			// never mutated anything).
			ResponseEntity<String> raw = restTemplate.exchange(path, HttpMethod.POST, entity, String.class);
			return ResponseEntity.status(raw.getStatusCode()).body(null);
		}
	}

	@Test
	void monthlyOverviewResolvesAssignmentHistory() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long shiftA = createShift(admin.companyId(), "Shift A", "09:00", "17:00", null);
		Long shiftB = createShift(admin.companyId(), "Shift B", "10:00", "18:00", null);
		Long shiftC = createShift(admin.companyId(), "Shift C", "11:00", "19:00", null);
		insertAssignment(admin.companyId(), employeeId, shiftA, "2026-03-01");
		insertAssignment(admin.companyId(), employeeId, shiftB, "2026-03-10");
		insertAssignment(admin.companyId(), employeeId, shiftC, "2026-05-01");

		ResponseEntity<MonthlyOverviewView> response = monthly(admin.accessToken(), employeeId, 2026, 3);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		MonthlyOverviewView body = response.getBody();
		// summaryDate = 2026-03-31 (past month): B is current, next is C.
		assertThat(body.shift().shiftId()).isEqualTo(shiftB);
		assertThat(body.shift().effectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 10));
		assertThat(body.shift().effectiveTo()).isEqualTo(LocalDate.of(2026, 4, 30));
		assertThat(body.days()).hasSize(31);
		// The exact "latest effective_from <= date" rule: A on days 1-9, B from day 10.
		assertThat(body.days().get(0).name()).isEqualTo("Shift A");
		assertThat(body.days().get(8).name()).isEqualTo("Shift A");
		assertThat(body.days().get(9).name()).isEqualTo("Shift B");
		assertThat(body.days().get(30).name()).isEqualTo("Shift B");
		assertThat(body.days().get(9).startTime()).isEqualTo(LocalTime.of(10, 0));
	}

	@Test
	void monthlyOverviewWithoutAssignmentReturnsScaffolding() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());

		ResponseEntity<MonthlyOverviewView> response = monthly(admin.accessToken(), employeeId, 2026, 3);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		MonthlyOverviewView body = response.getBody();
		assertThat(body.shift()).isNull();
		assertThat(body.weeklyRestDays()).isEmpty();
		assertThat(body.officialHolidays()).isEmpty();
		assertThat(body.days()).isEmpty();
	}

	@Test
	void weeklyRestDaysAreShiftUnionCompany() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		// Arabic token on the shift exercises the legacy token map end to end.
		Long shift = createShift(admin.companyId(), "Day", "09:00", "17:00", "الجمعة");
		setCompanyWeeklyOffDays(admin.companyId(), "Sat");
		insertAssignment(admin.companyId(), employeeId, shift, "2026-03-01");

		MonthlyOverviewView body = monthly(admin.accessToken(), employeeId, 2026, 3).getBody();

		assertThat(body.weeklyRestDays())
				.containsExactly(new WeeklyRestDayView(5, "Friday"), new WeeklyRestDayView(6, "Saturday"));
		// 2026-03-06 is a Friday: rest label suppresses the shift columns.
		ScheduleDayView friday = body.days().get(5);
		assertThat(friday.scheduleDate()).isEqualTo(LocalDate.of(2026, 3, 6));
		assertThat(friday.exception()).isEqualTo("Weekly rest");
		assertThat(friday.name()).isNull();
		assertThat(friday.startTime()).isNull();
		// 2026-03-07 is a Saturday: company setting alone also marks rest.
		assertThat(body.days().get(6).exception()).isEqualTo("Weekly rest");
		// An ordinary weekday keeps the shift snapshot.
		assertThat(body.days().get(1).name()).isEqualTo("Day");
		assertThat(body.days().get(1).exception()).isNull();
	}

	@Test
	void assignWritesManualRowsThatWinOverComputedOnes() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long dayShift = createShift(admin.companyId(), "Day", "09:00", "17:00", "Fri");
		Long nightShift = createShift(admin.companyId(), "Night", "22:00", "06:00", null);
		insertAssignment(admin.companyId(), employeeId, dayShift, "2026-03-01");

		// 2026-03-06 is a Friday -- computed classification would be rest.
		ResponseEntity<Void> response = assign(admin.accessToken(), employeeId, nightShift,
				List.of("2026-03-06", "2026-03-16"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		MonthlyOverviewView body = monthly(admin.accessToken(), employeeId, 2026, 3).getBody();
		ScheduleDayView friday = body.days().get(5);
		// Manual row wins over the computed rest day, exactly as
		// schedule_compute_days_for_range checks manual first.
		assertThat(friday.id()).isNotNull();
		assertThat(friday.name()).isEqualTo("Night");
		assertThat(friday.startTime()).isEqualTo(LocalTime.of(22, 0));
		assertThat(friday.exception()).isNull();
		assertThat(body.days().get(15).name()).isEqualTo("Night");

		// Re-assigning the same date is an upsert, not a duplicate row.
		assign(admin.accessToken(), employeeId, dayShift, List.of("2026-03-06"));
		Integer rows = jdbc().queryForObject(
				"SELECT COUNT(*) FROM employee_schedules WHERE employee_id = ? AND schedule_date = '2026-03-06'::date",
				Integer.class, employeeId);
		assertThat(rows).isEqualTo(1);
		assertThat(monthly(admin.accessToken(), employeeId, 2026, 3).getBody().days().get(5).name())
				.isEqualTo("Day");
	}

	@Test
	void assignRejectsNullDateInList() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long shiftId = createShift(admin.companyId(), "Day", "09:00", "17:00", null);

		String body = "{\"shiftId\": " + shiftId + ", \"dates\": [null]}";
		ResponseEntity<Void> response = restTemplate.exchange(
				"/api/tenant/schedules/" + employeeId + "/assign",
				HttpMethod.POST, new HttpEntity<>(body, bearer(admin.accessToken())), Void.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void assignRejectsUnknownEmployeeOrShift() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long shiftId = createShift(admin.companyId(), "Day", "09:00", "17:00", null);

		assertThat(assign(admin.accessToken(), 999999L, shiftId, List.of("2026-03-02")).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(assign(admin.accessToken(), employeeId, 999999L, List.of("2026-03-02")).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void generateReplacesExistingRowsAndLabelsRestDays() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long dayShift = createShift(admin.companyId(), "Day", "09:00", "17:00", "Fri");
		Long nightShift = createShift(admin.companyId(), "Night", "22:00", "06:00", null);
		setCompanyWeeklyOffDays(admin.companyId(), "Sat");
		insertAssignment(admin.companyId(), employeeId, dayShift, "2026-03-01");
		// A pre-existing manual row inside the range -- regenerate must replace it.
		assign(admin.accessToken(), employeeId, nightShift, List.of("2026-03-02"));
		// And one outside the range -- must survive untouched.
		assign(admin.accessToken(), employeeId, nightShift, List.of("2026-04-01"));

		ResponseEntity<GenerateResultView> response =
				generate(admin.accessToken(), employeeId, "2026-03-01", "2026-03-31");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().count()).isEqualTo(31);
		assertThat(response.getBody().shiftId()).isEqualTo(dayShift);
		assertThat(response.getBody().shiftName()).isEqualTo("Day");

		// Regenerate is destructive by design (legacy's only exposed mode):
		// the manual Night row on Mar 2 became a Day row.
		String mar2Name = jdbc().queryForObject(
				"SELECT name FROM employee_schedules WHERE employee_id = ? AND schedule_date = '2026-03-02'::date",
				String.class, employeeId);
		assertThat(mar2Name).isEqualTo("Day");
		// Rest days persisted with the exception label and no shift columns.
		String mar6Note = jdbc().queryForObject(
				"SELECT exception_note FROM employee_schedules WHERE employee_id = ? AND schedule_date = '2026-03-06'::date",
				String.class, employeeId);
		assertThat(mar6Note).isEqualTo("Weekly rest");
		String mar7Note = jdbc().queryForObject(
				"SELECT exception_note FROM employee_schedules WHERE employee_id = ? AND schedule_date = '2026-03-07'::date",
				String.class, employeeId);
		assertThat(mar7Note).isEqualTo("Weekly rest");
		// The out-of-range manual row survived.
		String apr1Name = jdbc().queryForObject(
				"SELECT name FROM employee_schedules WHERE employee_id = ? AND schedule_date = '2026-04-01'::date",
				String.class, employeeId);
		assertThat(apr1Name).isEqualTo("Night");
	}

	@Test
	void generateWithoutAssignmentOrWithInvertedRangeIsBadRequest() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());

		assertThat(generate(admin.accessToken(), employeeId, "2026-03-01", "2026-03-31").getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);

		Long shiftId = createShift(admin.companyId(), "Day", "09:00", "17:00", null);
		insertAssignment(admin.companyId(), employeeId, shiftId, "2026-03-01");
		assertThat(generate(admin.accessToken(), employeeId, "2026-03-31", "2026-03-01").getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void generateRejectsRangeExceedingCap() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long shiftId = createShift(admin.companyId(), "Day", "09:00", "17:00", null);
		// A valid, in-range assignment so the cap -- not the
		// no-assignment 400 -- is what triggers.
		insertAssignment(admin.companyId(), employeeId, shiftId, "2026-01-01");

		// 2026-01-01..2027-06-30 is well over the 370-day cap.
		assertThat(generate(admin.accessToken(), employeeId, "2026-01-01", "2027-06-30").getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void employeeCreateAndUpdateAppendAssignmentHistory() {
		AuthResponse admin = registerCompanyAdmin();
		Long shiftA = createShift(admin.companyId(), "Shift A", "09:00", "17:00", null);
		Long shiftB = createShift(admin.companyId(), "Shift B", "10:00", "18:00", null);

		// Create with a shift + explicit effective date writes one history row.
		String createBody = "{\"firstName\": \"Shifted\", \"lastName\": \"Emp\", \"phone\": \""
				+ uniquePhone() + "\", \"shiftId\": " + shiftA
				+ ", \"shiftEffectiveFrom\": \"2026-03-01\"}";
		ResponseEntity<String> created = restTemplate.exchange(
				"/api/tenant/employees", HttpMethod.POST,
				new HttpEntity<>(createBody, bearer(admin.accessToken())), String.class);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Long employeeId = jdbc().queryForObject(
				"SELECT id FROM employees WHERE first_name = 'Shifted' AND company_id = ?",
				Long.class, admin.companyId());
		assertThat(assignmentRows(employeeId)).isEqualTo(1);

		// Update to a different shift appends a second row (history, never in-place).
		String updateToB = "{\"firstName\": \"Shifted\", \"lastName\": \"Emp\", \"shiftId\": " + shiftB + "}";
		ResponseEntity<String> updateToBResponse = restTemplate.exchange(
				"/api/tenant/employees/" + employeeId, HttpMethod.PUT,
				new HttpEntity<>(updateToB, bearer(admin.accessToken())), String.class);
		assertThat(updateToBResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(assignmentRows(employeeId)).isEqualTo(2);

		// Re-sending the same shift on a full-replace PUT is a no-op --
		// deviation from legacy's unconditional append, recorded in
		// EmployeeService (legacy's PHP update is patch-shaped; a PUT
		// client echoing shiftId would otherwise grow history per save).
		ResponseEntity<String> noopResponse = restTemplate.exchange(
				"/api/tenant/employees/" + employeeId, HttpMethod.PUT,
				new HttpEntity<>(updateToB, bearer(admin.accessToken())), String.class);
		assertThat(noopResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(assignmentRows(employeeId)).isEqualTo(2);

		// Null shiftId means "no schedule statement", not "unassign".
		String updateNoShift = "{\"firstName\": \"Shifted\", \"lastName\": \"Emp\"}";
		ResponseEntity<String> noShiftResponse = restTemplate.exchange(
				"/api/tenant/employees/" + employeeId, HttpMethod.PUT,
				new HttpEntity<>(updateNoShift, bearer(admin.accessToken())), String.class);
		assertThat(noShiftResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(assignmentRows(employeeId)).isEqualTo(2);
	}

	@Test
	void appendShiftIfChangedComparesAgainstNewestRowNotTodaysAsOf() {
		AuthResponse admin = registerCompanyAdmin();
		Long shiftA = createShift(admin.companyId(), "Shift A", "09:00", "17:00", null);

		// Create with a future-dated effective_from -- the newest (and
		// only) history row is not yet "current" as-of today.
		String createBody = "{\"firstName\": \"Future\", \"lastName\": \"Emp\", \"phone\": \""
				+ uniquePhone() + "\", \"shiftId\": " + shiftA
				+ ", \"shiftEffectiveFrom\": \"2027-01-01\"}";
		ResponseEntity<String> created = restTemplate.exchange(
				"/api/tenant/employees", HttpMethod.POST,
				new HttpEntity<>(createBody, bearer(admin.accessToken())), String.class);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Long employeeId = jdbc().queryForObject(
				"SELECT id FROM employees WHERE first_name = 'Future' AND company_id = ?",
				Long.class, admin.companyId());
		assertThat(assignmentRows(employeeId)).isEqualTo(1);

		// PUT echoing the same shiftId must stay a no-op even though the
		// newest row's effective_from is in the future: the append gate
		// compares against the newest row unconditionally, not "as of
		// today". Before the fix, resolving "current" as-of today found
		// nothing (the row is future-dated), treated this as a change,
		// and appended a second row dated today -- dragging the planned
		// effective date back from 2027-01-01 to today.
		String echoUpdate = "{\"firstName\": \"Future\", \"lastName\": \"Emp\", \"shiftId\": " + shiftA + "}";
		ResponseEntity<String> echoResponse = restTemplate.exchange(
				"/api/tenant/employees/" + employeeId, HttpMethod.PUT,
				new HttpEntity<>(echoUpdate, bearer(admin.accessToken())), String.class);
		assertThat(echoResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(assignmentRows(employeeId)).isEqualTo(1);
		LocalDate effectiveFrom = jdbc().queryForObject(
				"SELECT effective_from FROM employee_shift_assignments WHERE employee_id = ?",
				LocalDate.class, employeeId);
		assertThat(effectiveFrom).isEqualTo(LocalDate.of(2027, 1, 1));
	}

	@Test
	void employeeCreateWithForeignShiftIsNotFound() {
		AuthResponse admin = registerCompanyAdmin();
		AuthResponse other = registerCompanyAdmin();
		Long foreignShift = createShift(other.companyId(), "Foreign", "09:00", "17:00", null);

		String body = "{\"firstName\": \"X\", \"lastName\": \"Y\", \"phone\": \""
				+ uniquePhone() + "\", \"shiftId\": " + foreignShift + "}";
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/employees", HttpMethod.POST,
				new HttpEntity<>(body, bearer(admin.accessToken())), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void monthlyOverviewRequiresSchedulesRead() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		HrFixture hr = loginHrMember(admin.companyId());

		assertThat(monthly(hr.accessToken(), employeeId, 2026, 3).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);

		grantPermission(hr, PermissionKeys.SCHEDULES_READ);
		assertThat(monthly(hr.accessToken(), employeeId, 2026, 3).getStatusCode())
				.isEqualTo(HttpStatus.OK);
	}

	@Test
	void writesRequireSchedulesManage() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long shiftId = createShift(admin.companyId(), "Day", "09:00", "17:00", null);
		HrFixture hr = loginHrMember(admin.companyId());
		grantPermission(hr, PermissionKeys.SCHEDULES_READ);

		assertThat(assign(hr.accessToken(), employeeId, shiftId, List.of("2026-03-02")).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(generate(hr.accessToken(), employeeId, "2026-03-01", "2026-03-31").getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void crossTenantAccessIsUniformNotFound() {
		AuthResponse admin = registerCompanyAdmin();
		AuthResponse other = registerCompanyAdmin();
		Long foreignEmployee = createEmployee(other.companyId());
		Long foreignShift = createShift(other.companyId(), "Foreign", "09:00", "17:00", null);
		Long ownEmployee = createEmployee(admin.companyId());

		assertThat(monthly(admin.accessToken(), foreignEmployee, 2026, 3).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(assign(admin.accessToken(), foreignEmployee, foreignShift, List.of("2026-03-02"))
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(assign(admin.accessToken(), ownEmployee, foreignShift, List.of("2026-03-02"))
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(generate(admin.accessToken(), foreignEmployee, "2026-03-01", "2026-03-31")
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void unauthenticatedCallsAreRejected() {
		assertThat(monthly(null, 1L, 2026, 3).getStatusCode().is2xxSuccessful()).isFalse();
		assertThat(assign(null, 1L, 1L, List.of("2026-03-02")).getStatusCode().is2xxSuccessful()).isFalse();
		assertThat(generate(null, 1L, "2026-03-01", "2026-03-31").getStatusCode().is2xxSuccessful()).isFalse();
	}

	private Integer assignmentRows(Long employeeId) {
		return jdbc().queryForObject(
				"SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = ?",
				Integer.class, employeeId);
	}

}
