package com.workin.backend.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.LoginRequest;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * Attendance manual-entry flow + this module's F-18 negatives, plus
 * its two load-bearing legacy rules (business-rule-extraction.md):
 * the 2-hour minimum gap between consecutive punches (enforced on HR
 * manual entry in legacy too, create.php lines 67-73) and the
 * punches-XOR-exception-day convention (a row is real punches or a
 * category-only exception day, never both -- V21's CHECK carries the
 * DB-enforceable half).
 */
class AttendanceModuleFlowTest extends AbstractIntegrationTest {

	private static final Instant BASE = Instant.parse("2026-03-02T09:00:00Z");

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

	private AuthResponse registerCompanyAdmin() {
		return restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Attendance Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private Long createEmployee(Long companyId) {
		return jdbc().queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'Att', 'Emp') RETURNING id",
				Long.class, companyId);
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

	private void allowPermission(HrFixture hr, String permissionKey) {
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
		return headers;
	}

	private Long createExceptionType(String accessToken, String name) {
		return restTemplate.exchange(
				"/api/tenant/exception-types", HttpMethod.POST,
				new HttpEntity<>(new CreateExceptionTypeRequest(name), bearer(accessToken)),
				ExceptionTypeView.class).getBody().id();
	}

	private static CreateAttendanceRequest punch(Long employeeId, Instant checkIn, Instant checkOut) {
		return new CreateAttendanceRequest(employeeId, checkIn, checkOut, "app", null, null, null, null);
	}

	private static CreateAttendanceRequest exception(Long employeeId, LocalDate date, Long exceptionTypeId) {
		return new CreateAttendanceRequest(employeeId, null, null, null, null, null, date, exceptionTypeId);
	}

	private ResponseEntity<AttendanceView> create(String accessToken, CreateAttendanceRequest request) {
		return restTemplate.exchange(
				"/api/tenant/attendance", HttpMethod.POST,
				new HttpEntity<>(request, bearer(accessToken)), AttendanceView.class);
	}

	private ResponseEntity<String> createRaw(String accessToken, CreateAttendanceRequest request) {
		return restTemplate.exchange(
				"/api/tenant/attendance", HttpMethod.POST,
				new HttpEntity<>(request, bearer(accessToken)), String.class);
	}

	@Test
	void punchRoundTripCreateListGetUpdateDelete() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());

		ResponseEntity<AttendanceView> created = create(admin.accessToken(), punch(employeeId, BASE, null));
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().checkIn()).isEqualTo(BASE);
		assertThat(created.getBody().checkOut()).isNull();
		assertThat(created.getBody().method()).isEqualTo("app");
		Long id = created.getBody().id();

		ResponseEntity<List<AttendanceView>> list = restTemplate.exchange(
				"/api/tenant/attendance", HttpMethod.GET, new HttpEntity<>(bearer(admin.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getBody()).extracting(AttendanceView::id).contains(id);

		ResponseEntity<AttendanceView> updated = restTemplate.exchange(
				"/api/tenant/attendance/" + id, HttpMethod.PUT,
				new HttpEntity<>(new UpdateAttendanceRequest(BASE, BASE.plus(8, ChronoUnit.HOURS), "app",
						null, null, null, null), bearer(admin.accessToken())),
				AttendanceView.class);
		assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updated.getBody().checkOut()).isEqualTo(BASE.plus(8, ChronoUnit.HOURS));

		ResponseEntity<Void> deleted = restTemplate.exchange(
				"/api/tenant/attendance/" + id, HttpMethod.DELETE,
				new HttpEntity<>(bearer(admin.accessToken())), Void.class);
		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		ResponseEntity<String> afterDelete = restTemplate.exchange(
				"/api/tenant/attendance/" + id, HttpMethod.GET,
				new HttpEntity<>(bearer(admin.accessToken())), String.class);
		assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void exceptionDayStoresUtcMidnightCheckInAndNullCheckout() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long typeId = createExceptionType(admin.accessToken(), "Excused absence");
		LocalDate date = LocalDate.of(2026, 3, 10);

		ResponseEntity<AttendanceView> created = create(admin.accessToken(), exception(employeeId, date, typeId));
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().checkIn())
				.isEqualTo(date.atStartOfDay(ZoneOffset.UTC).toInstant());
		assertThat(created.getBody().checkOut()).isNull();
		assertThat(created.getBody().method()).isNull();
		assertThat(created.getBody().exceptionTypeId()).isEqualTo(typeId);
	}

	@Test
	void mixedEmptyAndMethodlessShapesAreRejected() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long typeId = createExceptionType(admin.accessToken(), "Excused");

		// Both shapes at once.
		ResponseEntity<String> mixed = createRaw(admin.accessToken(), new CreateAttendanceRequest(
				employeeId, BASE, null, "app", null, null, LocalDate.of(2026, 3, 10), typeId));
		// Neither shape.
		ResponseEntity<String> empty = createRaw(admin.accessToken(), new CreateAttendanceRequest(
				employeeId, null, null, null, null, null, null, null));
		// Punch without method.
		ResponseEntity<String> methodless = createRaw(admin.accessToken(), new CreateAttendanceRequest(
				employeeId, BASE, null, null, null, null, null, null));
		// Exception shape with a stray punch field.
		ResponseEntity<String> strayCheckout = createRaw(admin.accessToken(), new CreateAttendanceRequest(
				employeeId, null, BASE, null, null, null, LocalDate.of(2026, 3, 10), typeId));

		assertThat(mixed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(methodless.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(strayCheckout.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void clearingBothPunchesWithoutAnExceptionIsA400NotASilentDelete() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long id = create(admin.accessToken(), punch(employeeId, BASE, BASE.plus(8, ChronoUnit.HOURS)))
				.getBody().id();

		// Legacy update.php would delete the row here; deliberate non-port.
		ResponseEntity<String> cleared = restTemplate.exchange(
				"/api/tenant/attendance/" + id, HttpMethod.PUT,
				new HttpEntity<>(new UpdateAttendanceRequest(null, null, null, null, null, null, null),
						bearer(admin.accessToken())),
				String.class);
		assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		// The row survives.
		ResponseEntity<AttendanceView> still = restTemplate.exchange(
				"/api/tenant/attendance/" + id, HttpMethod.GET,
				new HttpEntity<>(bearer(admin.accessToken())), AttendanceView.class);
		assertThat(still.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void theDatabaseItselfRejectsAnExceptionRowWithACheckout() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long typeId = createExceptionType(admin.accessToken(), "Backstop");

		assertThatThrownBy(() -> jdbc().update(
				"INSERT INTO attendance (employee_id, company_id, check_in, check_out, exception_type_id) "
						+ "VALUES (?, ?, ?, ?, ?)",
				employeeId, admin.companyId(),
				java.sql.Timestamp.from(BASE), java.sql.Timestamp.from(BASE.plusSeconds(3600)), typeId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void twoHourGapRuleOnCreate() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		assertThat(create(admin.accessToken(), punch(employeeId, BASE, null)).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);

		// 119 minutes after -> conflict; equal timestamp -> conflict.
		assertThat(createRaw(admin.accessToken(), punch(employeeId, BASE.plus(119, ChronoUnit.MINUTES), null))
				.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(createRaw(admin.accessToken(), punch(employeeId, BASE, null))
				.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		// Exactly 120 minutes after -> allowed (legacy guard is < 120).
		assertThat(create(admin.accessToken(), punch(employeeId, BASE.plus(120, ChronoUnit.MINUTES), null))
				.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		// Backdated more than 120 minutes before the first punch -> allowed
		// (legacy's guard only fires in the after-direction).
		assertThat(create(admin.accessToken(), punch(employeeId, BASE.minus(130, ChronoUnit.MINUTES), null))
				.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void exceptionRowsAreExcludedFromTheGapGuard() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long typeId = createExceptionType(admin.accessToken(), "Excused");

		// Exception day whose midnight check_in is 60 min before the punch.
		LocalDate date = LocalDate.of(2026, 3, 10);
		assertThat(create(admin.accessToken(), exception(employeeId, date, typeId)).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);

		Instant punchTime = date.atStartOfDay(ZoneOffset.UTC).toInstant().plus(60, ChronoUnit.MINUTES);
		assertThat(create(admin.accessToken(), punch(employeeId, punchTime, null)).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void twoHourGapRuleOnUpdateExcludesTheRowItself() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long first = create(admin.accessToken(), punch(employeeId, BASE, null)).getBody().id();
		Long second = create(admin.accessToken(), punch(employeeId, BASE.plus(5, ChronoUnit.HOURS), null))
				.getBody().id();

		// Re-saving the row at its own time must not self-collide.
		ResponseEntity<AttendanceView> unchanged = restTemplate.exchange(
				"/api/tenant/attendance/" + first, HttpMethod.PUT,
				new HttpEntity<>(new UpdateAttendanceRequest(BASE, BASE.plus(4, ChronoUnit.HOURS), "app",
						null, null, null, null), bearer(admin.accessToken())),
				AttendanceView.class);
		assertThat(unchanged.getStatusCode()).isEqualTo(HttpStatus.OK);

		// Moving the second punch to within 119 min of the first -> conflict.
		ResponseEntity<String> moved = restTemplate.exchange(
				"/api/tenant/attendance/" + second, HttpMethod.PUT,
				new HttpEntity<>(new UpdateAttendanceRequest(BASE.plus(60, ChronoUnit.MINUTES), null, "app",
						null, null, null, null), bearer(admin.accessToken())),
				String.class);
		assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void createWithAForeignOrNonexistentEmployeeOrExceptionTypeIsAnIndistinguishable404() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long employeeA = createEmployee(companyA.companyId());
		Long employeeB = createEmployee(companyB.companyId());
		Long typeA = createExceptionType(companyA.accessToken(), "Company A type");

		ResponseEntity<String> foreignEmployee = createRaw(companyB.accessToken(), punch(employeeA, BASE, null));
		ResponseEntity<String> nonexistentEmployee = createRaw(companyB.accessToken(), punch(999_999_999L, BASE, null));
		ResponseEntity<String> foreignType = createRaw(companyB.accessToken(),
				exception(employeeB, LocalDate.of(2026, 3, 10), typeA));
		ResponseEntity<String> nonexistentType = createRaw(companyB.accessToken(),
				exception(employeeB, LocalDate.of(2026, 3, 10), 999_999_999L));

		assertThat(foreignEmployee.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(nonexistentEmployee.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(foreignType.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(nonexistentType.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantOperationsAreIndistinguishable404s() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long employeeA = createEmployee(companyA.companyId());
		Long id = create(companyA.accessToken(), punch(employeeA, BASE, null)).getBody().id();

		ResponseEntity<String> crossGet = restTemplate.exchange(
				"/api/tenant/attendance/" + id, HttpMethod.GET,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossUpdate = restTemplate.exchange(
				"/api/tenant/attendance/" + id, HttpMethod.PUT,
				new HttpEntity<>(new UpdateAttendanceRequest(BASE, null, "app", null, null, null, null),
						bearer(companyB.accessToken())),
				String.class);
		ResponseEntity<String> crossDelete = restTemplate.exchange(
				"/api/tenant/attendance/" + id, HttpMethod.DELETE,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);

		assertThat(crossGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossUpdate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		ResponseEntity<List<AttendanceView>> listB = restTemplate.exchange(
				"/api/tenant/attendance", HttpMethod.GET, new HttpEntity<>(bearer(companyB.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(listB.getBody()).extracting(AttendanceView::id).doesNotContain(id);

		// Foreign employeeId as a list filter yields an empty list, not
		// that employee's history.
		ResponseEntity<List<AttendanceView>> filtered = restTemplate.exchange(
				"/api/tenant/attendance?employeeId=" + employeeA, HttpMethod.GET,
				new HttpEntity<>(bearer(companyB.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(filtered.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(filtered.getBody()).isEmpty();
	}

	@Test
	void listDateRangeFilterUsesCheckInDates() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long inRange = create(admin.accessToken(), punch(employeeId, BASE, null)).getBody().id();
		Long outOfRange = create(admin.accessToken(),
				punch(employeeId, BASE.plus(30, ChronoUnit.DAYS), null)).getBody().id();

		ResponseEntity<List<AttendanceView>> list = restTemplate.exchange(
				"/api/tenant/attendance?employeeId=" + employeeId + "&from=2026-03-01&to=2026-03-05",
				HttpMethod.GET, new HttpEntity<>(bearer(admin.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getBody()).extracting(AttendanceView::id).contains(inRange).doesNotContain(outOfRange);
	}

	@Test
	void readDoesNotImplyCorrect() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		HrFixture reader = loginHrMember(admin.companyId());
		allowPermission(reader, PermissionKeys.ATTENDANCE_READ);

		ResponseEntity<List<AttendanceView>> list = restTemplate.exchange(
				"/api/tenant/attendance", HttpMethod.GET, new HttpEntity<>(bearer(reader.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<String> createDenied = createRaw(reader.accessToken(), punch(employeeId, BASE, null));
		assertThat(createDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unauthenticatedAccessNeverSucceeds() {
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/attendance", HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);
		assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
	}

	private static String uniquePhone() {
		return "+2017" + System.nanoTime() % 100_000_000L;
	}

}
