package com.workin.backend.requests;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.attendance.CreateAttendanceRequest;
import com.workin.backend.attendance.CreateExceptionTypeRequest;
import com.workin.backend.attendance.ExceptionTypeView;
import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.companysettings.UpdateCompanySettingsRequest;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.LoginRequest;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * Request workflow + this module's F-18 negatives. The approval
 * semantics under test are hr-legacy's request_actions_helper.php,
 * read in full at the pinned Discovery commit and ported exactly:
 * inclusive day count attributed to the from-date's year; 422
 * insufficient-balance only when a balance row exists; a missing row
 * auto-creates at the 21.0-day fallback -- possibly into negative
 * remaining (quirk locked in below); per-day attendance-exception
 * rows that skip days already holding any attendance row, normalized
 * to the new midnight/method-null convention.
 */
class RequestModuleFlowTest extends AbstractIntegrationTest {

	private static final LocalDate FROM = LocalDate.of(2026, 5, 4);
	private static final LocalDate TO = LocalDate.of(2026, 5, 6);

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
				new RegisterCompanyRequest("Requests Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private Long createEmployee(Long companyId) {
		return jdbc().queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'Req', 'Emp') RETURNING id",
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

	private RequestTypeView createRequestType(
			String accessToken, String name, Boolean deductBalance, Boolean addException, Long exceptionTypeId) {
		ResponseEntity<RequestTypeView> response = restTemplate.exchange(
				"/api/tenant/request-types", HttpMethod.POST,
				new HttpEntity<>(new CreateRequestTypeRequest(name, null, deductBalance, null, addException,
						exceptionTypeId), bearer(accessToken)),
				RequestTypeView.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody();
	}

	private void createBalance(String accessToken, Long employeeId, int year, String totalDays) {
		ResponseEntity<LeaveBalanceView> response = restTemplate.exchange(
				"/api/tenant/leave-balances", HttpMethod.POST,
				new HttpEntity<>(new CreateLeaveBalanceRequest(employeeId, (short) year, new BigDecimal(totalDays),
						null, null, null), bearer(accessToken)),
				LeaveBalanceView.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private ResponseEntity<RequestView> createRequest(
			String accessToken, Long employeeId, Long requestTypeId, LocalDate from, LocalDate to) {
		return restTemplate.exchange(
				"/api/tenant/requests", HttpMethod.POST,
				new HttpEntity<>(new CreateRequestRequest(employeeId, requestTypeId, from, to, null, null, "notes"),
						bearer(accessToken)),
				RequestView.class);
	}

	private ResponseEntity<RequestView> approve(String accessToken, Long requestId, String reply) {
		return restTemplate.exchange(
				"/api/tenant/requests/" + requestId + "/approve", HttpMethod.PUT,
				new HttpEntity<>(new ApproveRequestRequest(reply), bearer(accessToken)), RequestView.class);
	}

	private ResponseEntity<String> approveRaw(String accessToken, Long requestId, String reply) {
		return restTemplate.exchange(
				"/api/tenant/requests/" + requestId + "/approve", HttpMethod.PUT,
				new HttpEntity<>(new ApproveRequestRequest(reply), bearer(accessToken)), String.class);
	}

	private ResponseEntity<String> reject(String accessToken, Long requestId, String reply) {
		return restTemplate.exchange(
				"/api/tenant/requests/" + requestId + "/reject", HttpMethod.PUT,
				new HttpEntity<>(new RejectRequestRequest(reply), bearer(accessToken)), String.class);
	}

	@Test
	void requestTypeTogglesRoundTripAndExceptionMappingRules() {
		AuthResponse admin = registerCompanyAdmin();
		Long exceptionTypeId = createExceptionType(admin.accessToken(), "Excused leave");

		RequestTypeView mapped = createRequestType(admin.accessToken(), "Annual leave", true, true, exceptionTypeId);
		assertThat(mapped.deductBalance()).isTrue();
		assertThat(mapped.addAttendanceException()).isTrue();
		assertThat(mapped.exceptionTypeId()).isEqualTo(exceptionTypeId);
		assertThat(mapped.countsAsPaidLeave()).isTrue();
		assertThat(mapped.isActive()).isTrue();

		// exceptionTypeId is silently nulled when the flag is off (legacy rule).
		RequestTypeView unmapped = createRequestType(admin.accessToken(), "Errand", false, false, exceptionTypeId);
		assertThat(unmapped.exceptionTypeId()).isNull();

		// A mapped type with a foreign/nonexistent exception type is a 404.
		AuthResponse companyB = registerCompanyAdmin();
		ResponseEntity<String> foreignMapping = restTemplate.exchange(
				"/api/tenant/request-types", HttpMethod.POST,
				new HttpEntity<>(new CreateRequestTypeRequest("Bad", null, null, null, true, exceptionTypeId),
						bearer(companyB.accessToken())),
				String.class);
		assertThat(foreignMapping.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		ResponseEntity<List<RequestTypeView>> list = restTemplate.exchange(
				"/api/tenant/request-types", HttpMethod.GET, new HttpEntity<>(bearer(admin.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getBody()).extracting(RequestTypeView::id).contains(mapped.id(), unmapped.id());
	}

	@Test
	void requestRoundTripStartsPendingAndValidatesDates() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		RequestTypeView type = createRequestType(admin.accessToken(), "Leave", false, false, null);

		ResponseEntity<RequestView> created = createRequest(admin.accessToken(), employeeId, type.id(), FROM, TO);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().status()).isEqualTo(RequestStatus.PENDING);
		assertThat(created.getBody().decidedAt()).isNull();
		Long id = created.getBody().id();

		ResponseEntity<String> badDates = restTemplate.exchange(
				"/api/tenant/requests", HttpMethod.POST,
				new HttpEntity<>(new CreateRequestRequest(employeeId, type.id(), TO, FROM, null, null, null),
						bearer(admin.accessToken())),
				String.class);
		assertThat(badDates.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		ResponseEntity<RequestView> updated = restTemplate.exchange(
				"/api/tenant/requests/" + id, HttpMethod.PUT,
				new HttpEntity<>(new UpdateRequestRequest(type.id(), FROM, TO.plusDays(1), null, null, "more"),
						bearer(admin.accessToken())),
				RequestView.class);
		assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updated.getBody().toDate()).isEqualTo(TO.plusDays(1));

		ResponseEntity<Void> deleted = restTemplate.exchange(
				"/api/tenant/requests/" + id, HttpMethod.DELETE,
				new HttpEntity<>(bearer(admin.accessToken())), Void.class);
		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
	}

	@Test
	void decidedRequestsAreLockedFromEditDeleteAndRedecision() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		RequestTypeView type = createRequestType(admin.accessToken(), "Leave", false, false, null);
		Long id = createRequest(admin.accessToken(), employeeId, type.id(), FROM, TO).getBody().id();

		ResponseEntity<RequestView> approved = approve(admin.accessToken(), id, "ok");
		assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(approved.getBody().status()).isEqualTo(RequestStatus.APPROVED);
		assertThat(approved.getBody().reply()).isEqualTo("ok");
		assertThat(approved.getBody().decidedAt()).isNotNull();
		assertThat(approved.getBody().approverMembershipId()).isNotNull();

		ResponseEntity<String> editLocked = restTemplate.exchange(
				"/api/tenant/requests/" + id, HttpMethod.PUT,
				new HttpEntity<>(new UpdateRequestRequest(type.id(), FROM, TO, null, null, null),
						bearer(admin.accessToken())),
				String.class);
		ResponseEntity<String> deleteLocked = restTemplate.exchange(
				"/api/tenant/requests/" + id, HttpMethod.DELETE,
				new HttpEntity<>(bearer(admin.accessToken())), String.class);
		ResponseEntity<String> reApprove = approveRaw(admin.accessToken(), id, "again");
		ResponseEntity<String> lateReject = reject(admin.accessToken(), id, "no");

		assertThat(editLocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(deleteLocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(reApprove.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(lateReject.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void approvalDeductsAnInclusiveDayCountFromAnExistingBalance() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		createBalance(admin.accessToken(), employeeId, 2026, "21.0");
		RequestTypeView type = createRequestType(admin.accessToken(), "Annual leave", true, false, null);
		// FROM..TO is 3 calendar days inclusive.
		Long id = createRequest(admin.accessToken(), employeeId, type.id(), FROM, TO).getBody().id();

		assertThat(approve(admin.accessToken(), id, null).getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<List<LeaveBalanceView>> balances = restTemplate.exchange(
				"/api/tenant/leave-balances?employeeId=" + employeeId, HttpMethod.GET,
				new HttpEntity<>(bearer(admin.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(balances.getBody()).hasSize(1);
		assertThat(balances.getBody().get(0).usedDays()).isEqualByComparingTo("3.0");
		assertThat(balances.getBody().get(0).remainingDays()).isEqualByComparingTo("18.0");
	}

	@Test
	void insufficientExistingBalanceIsA422() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		createBalance(admin.accessToken(), employeeId, 2026, "2.0");
		RequestTypeView type = createRequestType(admin.accessToken(), "Annual leave", true, false, null);
		Long id = createRequest(admin.accessToken(), employeeId, type.id(), FROM, TO).getBody().id();

		ResponseEntity<String> response = approveRaw(admin.accessToken(), id, null);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

		// The request stays pending and the balance untouched.
		ResponseEntity<RequestView> still = restTemplate.exchange(
				"/api/tenant/requests/" + id, HttpMethod.GET,
				new HttpEntity<>(bearer(admin.accessToken())), RequestView.class);
		assertThat(still.getBody().status()).isEqualTo(RequestStatus.PENDING);
		BigDecimal used = jdbc().queryForObject(
				"SELECT used_days FROM leave_balances WHERE employee_id = ?", BigDecimal.class, employeeId);
		assertThat(used).isEqualByComparingTo("0.0");
	}

	@Test
	void configuredAccrualDrivesAutoCreatedBalances() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		restTemplate.exchange(
				"/api/tenant/company-settings", HttpMethod.PUT,
				new HttpEntity<>(new UpdateCompanySettingsRequest(null, null, null, null, new BigDecimal("15.5"), null),
						bearer(admin.accessToken())),
				String.class);
		RequestTypeView type = createRequestType(admin.accessToken(), "Annual leave", true, false, null);
		Long id = createRequest(admin.accessToken(), employeeId, type.id(), FROM, TO).getBody().id();

		assertThat(approve(admin.accessToken(), id, null).getStatusCode()).isEqualTo(HttpStatus.OK);

		Map<String, Object> row = jdbc().queryForMap(
				"SELECT total_days, used_days FROM leave_balances WHERE employee_id = ?", employeeId);
		assertThat((BigDecimal) row.get("total_days")).isEqualByComparingTo("15.5");
		assertThat((BigDecimal) row.get("used_days")).isEqualByComparingTo("3.0");
	}

	@Test
	void aMissingBalanceRowAutoCreatesAtTheFallbackEvenIntoNegativeRemaining() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		RequestTypeView type = createRequestType(admin.accessToken(), "Annual leave", true, false, null);
		// 30 days inclusive, no balance row: legacy's insufficiency check
		// passes when no row exists, then the side effect auto-creates
		// total=21.0/used=30 -- negative remaining, ported deliberately.
		Long id = createRequest(admin.accessToken(), employeeId, type.id(),
				LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)).getBody().id();

		assertThat(approve(admin.accessToken(), id, null).getStatusCode()).isEqualTo(HttpStatus.OK);

		Map<String, Object> row = jdbc().queryForMap(
				"SELECT total_days, used_days, remaining_days, year FROM leave_balances WHERE employee_id = ?",
				employeeId);
		assertThat((BigDecimal) row.get("total_days")).isEqualByComparingTo("21.0");
		assertThat((BigDecimal) row.get("used_days")).isEqualByComparingTo("30.0");
		assertThat((BigDecimal) row.get("remaining_days")).isEqualByComparingTo("-9.0");
		assertThat(((Number) row.get("year")).intValue()).isEqualTo(2026);
	}

	@Test
	void approvalCreatesPerDayAttendanceExceptionsSkippingExistingAttendanceDays() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		Long exceptionTypeId = createExceptionType(admin.accessToken(), "Excused leave");
		RequestTypeView type = createRequestType(admin.accessToken(), "Excused", false, true, exceptionTypeId);

		// Seed a real punch on the middle day of the range via the API.
		Instant midDayPunch = FROM.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().plus(9, ChronoUnit.HOURS);
		ResponseEntity<String> punch = restTemplate.exchange(
				"/api/tenant/attendance", HttpMethod.POST,
				new HttpEntity<>(new CreateAttendanceRequest(employeeId, midDayPunch, null, "app", null, null,
						null, null), bearer(admin.accessToken())),
				String.class);
		assertThat(punch.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		Long id = createRequest(admin.accessToken(), employeeId, type.id(), FROM, TO).getBody().id();
		assertThat(approve(admin.accessToken(), id, null).getStatusCode()).isEqualTo(HttpStatus.OK);

		// 3-day range, middle day skipped: exactly 2 exception rows, at
		// UTC midnight, method null, the mapped type.
		List<Map<String, Object>> exceptionRows = jdbc().queryForList(
				"SELECT check_in, method, exception_type_id FROM attendance "
						+ "WHERE employee_id = ? AND exception_type_id IS NOT NULL ORDER BY check_in",
				employeeId);
		assertThat(exceptionRows).hasSize(2);
		assertThat(((java.sql.Timestamp) exceptionRows.get(0).get("check_in")).toInstant())
				.isEqualTo(FROM.atStartOfDay(ZoneOffset.UTC).toInstant());
		assertThat(((java.sql.Timestamp) exceptionRows.get(1).get("check_in")).toInstant())
				.isEqualTo(TO.atStartOfDay(ZoneOffset.UTC).toInstant());
		assertThat(exceptionRows).allSatisfy(row -> {
			assertThat(row.get("method")).isNull();
			assertThat(((Number) row.get("exception_type_id")).longValue()).isEqualTo(exceptionTypeId);
		});
	}

	@Test
	void noExceptionRowsWithoutTheFlagOrTheMapping() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		// Flag on but no mapping: legacy's company-default fallback
		// resolver is an open question -- the slice conservatively skips.
		RequestTypeView unmappedType = createRequestType(admin.accessToken(), "Unmapped", false, true, null);
		Long id = createRequest(admin.accessToken(), employeeId, unmappedType.id(), FROM, TO).getBody().id();

		assertThat(approve(admin.accessToken(), id, null).getStatusCode()).isEqualTo(HttpStatus.OK);

		Long exceptionRows = jdbc().queryForObject(
				"SELECT COUNT(*) FROM attendance WHERE employee_id = ?", Long.class, employeeId);
		assertThat(exceptionRows).isZero();
	}

	@Test
	void rejectRequiresAReplyAndHasNoSideEffects() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		createBalance(admin.accessToken(), employeeId, 2026, "21.0");
		RequestTypeView type = createRequestType(admin.accessToken(), "Annual leave", true, false, null);
		Long id = createRequest(admin.accessToken(), employeeId, type.id(), FROM, TO).getBody().id();

		ResponseEntity<String> blankReply = restTemplate.exchange(
				"/api/tenant/requests/" + id + "/reject", HttpMethod.PUT,
				new HttpEntity<>(new RejectRequestRequest("  "), bearer(admin.accessToken())), String.class);
		assertThat(blankReply.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		assertThat(reject(admin.accessToken(), id, "denied").getStatusCode()).isEqualTo(HttpStatus.OK);
		BigDecimal used = jdbc().queryForObject(
				"SELECT used_days FROM leave_balances WHERE employee_id = ?", BigDecimal.class, employeeId);
		assertThat(used).isEqualByComparingTo("0.0");
	}

	@Test
	void crossTenantAndForeignReferenceOperationsAreIndistinguishable404s() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long employeeA = createEmployee(companyA.companyId());
		Long employeeB = createEmployee(companyB.companyId());
		RequestTypeView typeA = createRequestType(companyA.accessToken(), "Leave A", false, false, null);
		RequestTypeView typeB = createRequestType(companyB.accessToken(), "Leave B", false, false, null);
		Long requestA = createRequest(companyA.accessToken(), employeeA, typeA.id(), FROM, TO).getBody().id();

		ResponseEntity<String> foreignEmployee = restTemplate.exchange(
				"/api/tenant/requests", HttpMethod.POST,
				new HttpEntity<>(new CreateRequestRequest(employeeA, typeB.id(), FROM, TO, null, null, null),
						bearer(companyB.accessToken())),
				String.class);
		ResponseEntity<String> foreignType = restTemplate.exchange(
				"/api/tenant/requests", HttpMethod.POST,
				new HttpEntity<>(new CreateRequestRequest(employeeB, typeA.id(), FROM, TO, null, null, null),
						bearer(companyB.accessToken())),
				String.class);
		assertThat(foreignEmployee.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(foreignType.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		ResponseEntity<String> crossGet = restTemplate.exchange(
				"/api/tenant/requests/" + requestA, HttpMethod.GET,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossUpdate = restTemplate.exchange(
				"/api/tenant/requests/" + requestA, HttpMethod.PUT,
				new HttpEntity<>(new UpdateRequestRequest(typeB.id(), FROM, TO, null, null, null),
						bearer(companyB.accessToken())),
				String.class);
		ResponseEntity<String> crossDelete = restTemplate.exchange(
				"/api/tenant/requests/" + requestA, HttpMethod.DELETE,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossApprove = approveRaw(companyB.accessToken(), requestA, null);
		ResponseEntity<String> crossReject = reject(companyB.accessToken(), requestA, "no");

		assertThat(crossGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossUpdate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossApprove.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossReject.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		ResponseEntity<List<RequestView>> listB = restTemplate.exchange(
				"/api/tenant/requests", HttpMethod.GET, new HttpEntity<>(bearer(companyB.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(listB.getBody()).extracting(RequestView::id).doesNotContain(requestA);
	}

	@Test
	void readManageAndApproveAreThreeSeparateCapabilities() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		RequestTypeView type = createRequestType(admin.accessToken(), "Leave", false, false, null);
		Long pendingId = createRequest(admin.accessToken(), employeeId, type.id(), FROM, TO).getBody().id();

		HrFixture reader = loginHrMember(admin.companyId());
		allowPermission(reader, PermissionKeys.REQUESTS_READ);
		HrFixture manager = loginHrMember(admin.companyId());
		allowPermission(manager, PermissionKeys.REQUESTS_MANAGE);

		ResponseEntity<List<RequestView>> list = restTemplate.exchange(
				"/api/tenant/requests", HttpMethod.GET, new HttpEntity<>(bearer(reader.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

		// requests.read alone: no create, no approve.
		assertThat(createRequest(reader.accessToken(), employeeId, type.id(), FROM, TO).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(approveRaw(reader.accessToken(), pendingId, null).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);

		// requests.manage alone: can create, cannot approve.
		assertThat(createRequest(manager.accessToken(), employeeId, type.id(), FROM.plusMonths(1), TO.plusMonths(1))
				.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(approveRaw(manager.accessToken(), pendingId, null).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unauthenticatedAccessNeverSucceeds() {
		for (String path : List.of("/api/tenant/requests", "/api/tenant/request-types")) {
			ResponseEntity<String> response = restTemplate.exchange(
					path, HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);
			assertThat(response.getStatusCode().is2xxSuccessful()).as(path).isFalse();
		}
	}

	private static String uniquePhone() {
		return "+2020" + System.nanoTime() % 100_000_000L;
	}

}
