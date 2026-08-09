package com.workin.backend.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
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

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * Locale selection (?lang -> Accept-Language -> English) and the
 * {code, message} error contract, exercised through real endpoints.
 */
class I18nFlowTest extends AbstractIntegrationTest {

	private static final AtomicLong PHONE = new AtomicLong(8_000_000_000L);

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	private static String uniquePhone() {
		return "+2" + PHONE.incrementAndGet();
	}

	private AuthResponse registerCompanyAdmin() {
		return restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("I18n Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private HttpHeaders bearer(String accessToken, String acceptLanguage) {
		HttpHeaders headers = new HttpHeaders();
		if (accessToken != null) {
			headers.setBearerAuth(accessToken);
		}
		if (acceptLanguage != null) {
			headers.set(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
		}
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getError(String token, String url, String acceptLanguage) {
		return restTemplate.exchange(
				url, HttpMethod.GET, new HttpEntity<>(bearer(token, acceptLanguage)), Map.class).getBody();
	}

	private Long jdbcCreateEmployee(Long companyId) {
		return new org.springframework.jdbc.core.JdbcTemplate(flywayDataSource).queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'I18n', 'Emp') RETURNING id",
				Long.class, companyId);
	}

	private void jdbcCreateAssignment(Long companyId, Long employeeId) {
		org.springframework.jdbc.core.JdbcTemplate jdbc =
				new org.springframework.jdbc.core.JdbcTemplate(flywayDataSource);
		Long shiftId = jdbc.queryForObject(
				"INSERT INTO shifts (company_id, name, start_time, end_time) "
						+ "VALUES (?, 'Day', '09:00'::time, '17:00'::time) RETURNING id",
				Long.class, companyId);
		jdbc.update(
				"INSERT INTO employee_shift_assignments (company_id, employee_id, shift_id, effective_from) "
						+ "VALUES (?, ?, ?, '2026-01-01'::date)",
				companyId, employeeId, shiftId);
	}

	@Test
	void keyedErrorsCarryStableCodeAndLocalizedMessage() {
		String phone = uniquePhone();
		restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("I18n Dup Co", phone, "correct horse battery staple"),
				AuthResponse.class);

		// Duplicate registration, Arabic requested via param.
		ResponseEntity<Map> duplicate = restTemplate.postForEntity(
				"/api/auth/register?lang=ar",
				new RegisterCompanyRequest("I18n Dup Co", phone, "correct horse battery staple"),
				Map.class);
		assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(duplicate.getBody().get("code")).isEqualTo("auth.phone_already_registered");
		assertThat(duplicate.getBody().get("message")).isEqualTo("رقم الهاتف مسجّل مسبقاً");

		// Wrong password, English default.
		ResponseEntity<Map> badLogin = restTemplate.postForEntity(
				"/api/auth/login",
				new com.workin.backend.identity.LoginRequest(phone, "wrong password entirely"),
				Map.class);
		assertThat(badLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(badLogin.getBody().get("code")).isEqualTo("auth.invalid_credentials");
		assertThat(badLogin.getBody().get("message")).isEqualTo("Invalid credentials");
	}

	@Test
	void messageFormatArgsRenderInsideLocalizedText() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = jdbcCreateEmployee(admin.companyId());
		jdbcCreateAssignment(admin.companyId(), employeeId);

		String body = "{\"from\": \"2026-01-01\", \"to\": \"2027-06-30\"}";
		ResponseEntity<Map> response = restTemplate.exchange(
				"/api/tenant/schedules/" + employeeId + "/generate?lang=ar", HttpMethod.POST,
				new HttpEntity<>(body, bearer(admin.accessToken(), null)), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().get("code")).isEqualTo("schedule.range_exceeds_max");
		assertThat((String) response.getBody().get("message")).isEqualTo("النطاق يتجاوز 370 يوماً");
	}

	@Test
	void bareNotFoundRendersGenericCodeInBothLanguages() {
		AuthResponse admin = registerCompanyAdmin();

		Map<String, Object> en = getError(admin.accessToken(), "/api/tenant/shifts/999999", null);
		assertThat(en.get("code")).isEqualTo("error.not_found");
		assertThat(en.get("message")).isEqualTo("Not found");

		Map<String, Object> ar = getError(admin.accessToken(), "/api/tenant/shifts/999999?lang=ar", null);
		assertThat(ar.get("code")).isEqualTo("error.not_found");
		assertThat(ar.get("message")).isEqualTo("غير موجود");
	}

	@Test
	void localePrecedenceParamBeatsHeaderAndUnknownFallsBackToEnglish() {
		AuthResponse admin = registerCompanyAdmin();

		// Header alone selects Arabic.
		assertThat(getError(admin.accessToken(), "/api/tenant/shifts/999999", "ar").get("message"))
				.isEqualTo("غير موجود");
		// Param beats a contradicting header.
		assertThat(getError(admin.accessToken(), "/api/tenant/shifts/999999?lang=en", "ar").get("message"))
				.isEqualTo("Not found");
		// Regioned tag matches its language.
		assertThat(getError(admin.accessToken(), "/api/tenant/shifts/999999?lang=ar-EG", null).get("message"))
				.isEqualTo("غير موجود");
		// Unsupported language falls back to English.
		assertThat(getError(admin.accessToken(), "/api/tenant/shifts/999999?lang=fr", null).get("message"))
				.isEqualTo("Not found");
	}

	@Test
	void validationFailureListsLocalizedFieldMessages() {
		AuthResponse admin = registerCompanyAdmin();
		// shifts create requires name/startTime/endTime; an empty body trips @NotBlank/@NotNull.
		ResponseEntity<Map> response = restTemplate.exchange(
				"/api/tenant/shifts?lang=ar", HttpMethod.POST,
				new HttpEntity<>("{}", bearer(admin.accessToken(), null)), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Map<String, Object> body = response.getBody();
		assertThat(body.get("code")).isEqualTo("error.validation");
		assertThat(body.get("message")).isEqualTo("فشل التحقق من البيانات");
		assertThat((Iterable<Map<String, Object>>) body.get("fields"))
				.anySatisfy(field -> {
					assertThat(field.get("field")).isEqualTo("name");
					assertThat(field.get("message")).isEqualTo("يجب ألا يكون فارغاً");
				});
	}

	/**
	 * Issue #70. Before {@code ApiSecurityErrorHandler} existed, this
	 * returned Spring Boot's {@code {timestamp, status, error, path}}
	 * instead — and nothing caught it, because every unauthenticated
	 * assertion in the suite only checked that the call had not
	 * succeeded. Access tokens expire every 15 minutes, so this is the
	 * error clients hit most often; it gets a real assertion now.
	 */
	@Test
	void aMissingTokenIsAnOnContractUnauthorizedInBothLanguages() {
		ResponseEntity<Map> english = restTemplate.exchange(
				"/api/tenant/employees", HttpMethod.GET,
				new HttpEntity<>(bearer(null, null)), Map.class);

		assertThat(english.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(english.getBody().get("code")).isEqualTo("error.unauthorized");
		assertThat(english.getBody().get("message")).isEqualTo("Unauthorized");
		// The old default shape is gone, not merely supplemented.
		assertThat(english.getBody()).doesNotContainKeys("timestamp", "status", "error", "path");

		ResponseEntity<Map> arabic = restTemplate.exchange(
				"/api/tenant/employees?lang=ar", HttpMethod.GET,
				new HttpEntity<>(bearer(null, null)), Map.class);

		assertThat(arabic.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(arabic.getBody().get("code")).isEqualTo("error.unauthorized");
		assertThat(arabic.getBody().get("message")).isEqualTo("غير مصرّح");
	}

	@Test
	void anExpiredOrMalformedTokenIsAlsoOnContract() {
		ResponseEntity<Map> response = restTemplate.exchange(
				"/api/tenant/employees", HttpMethod.GET,
				new HttpEntity<>(bearer("not.a.valid.jwt", null)), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody().get("code")).isEqualTo("error.unauthorized");
	}

	/** The platform-admin chain is separate; it gets the same treatment. */
	@Test
	void thePlatformAdminChainIsOnContractToo() {
		ResponseEntity<Map> response = restTemplate.exchange(
				"/api/platform-admin/me", HttpMethod.GET,
				new HttpEntity<>(bearer(null, null)), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody().get("code")).isEqualTo("error.unauthorized");
	}

	/**
	 * The permit for {@code /error} exists so a controller-thrown
	 * ResponseStatusException keeps its real status through the servlet
	 * ERROR dispatch. Adding an entry point must not have disturbed
	 * that: a 404 has to stay a 404 rather than collapsing into the new
	 * 401.
	 */
	@Test
	void controllerThrownStatusesStillSurviveTheErrorDispatch() {
		AuthResponse admin = registerCompanyAdmin();

		Map<String, Object> body = getError(admin.accessToken(), "/api/tenant/employees/99999999", null);

		assertThat(body.get("code")).isEqualTo("error.not_found");
	}

}
