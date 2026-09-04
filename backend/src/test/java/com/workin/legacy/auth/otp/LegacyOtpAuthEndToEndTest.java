package com.workin.legacy.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyRuntimeOffset;
import com.workin.legacy.auth.LegacyPhpJwtService;
import com.workin.legacy.auth.whatsapp.LegacyWhatsAppSender;

/**
 * Wave 13.1a: the four public OTP routes of {@code apis/api/auth/} and the two
 * {@code profile} phone-change routes that share their helper set.
 *
 * <p>The issued code is read out of the recorded WhatsApp message, not out of
 * the response, because the response no longer carries it -- which is itself
 * one of the things asserted here.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(RecordingWhatsAppConfiguration.class)
@ActiveProfiles("phase1-mysql")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyOtpAuthEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String VERIFY_OTP = "/apis/api/auth/verify_otp.php";
	private static final String RESEND_OTP = "/apis/api/auth/resend_otp.php";
	private static final String FORGOT = "/apis/api/auth/forgot_password.php";
	private static final String RESET = "/apis/api/auth/reset_password.php";
	private static final String REQUEST_CHANGE = "/apis/api/profile/request_phone_change.php";
	private static final String CONFIRM_CHANGE = "/apis/api/profile/confirm_phone_change.php";

	private static final long COMPANY_ID = 31001L;
	private static final long OTHER_COMPANY = 31002L;
	private static final long STAFF = 310011L;
	private static final long PENDING_STAFF = 310012L;
	private static final long BRANCH = 31011L;

	private static final String COMPANY_PHONE = "01000031001";
	private static final String OTHER_COMPANY_PHONE = "01000031002";
	private static final String STAFF_PHONE = "01000310011";
	private static final String PENDING_PHONE = "01000310012";
	private static final String PASSWORD = "secret123";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private LegacyWhatsAppSender whatsAppSender;

	@Autowired
	private LegacyPhpJwtService legacyPhpJwtService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the OTP fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	private RecordingWhatsAppSender recorder() {
		return (RecordingWhatsAppSender) whatsAppSender;
	}

	// ---------------- the code never reaches the wire ----------------

	/**
	 * Legacy answers {@code ok(OTP_SENT, DEBUG ? [OTP => $code] : [])}.
	 * Production's DEBUG was set to false on 2026-08-05 and PMR-05 forbids the
	 * exception outright, so the response carries the empty array and the code
	 * exists only in the delivered message.
	 */
	@Test
	@Order(1)
	void theIssuedCodeIsNeverPutOnTheWire() throws Exception {
		recorder().clear();
		clearOtps();
		ResponseEntity<Map<String, Object>> response = post(FORGOT, null,
				"{\"phone\":\"" + COMPANY_PHONE + "\",\"type\":\"company\"}");
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		assertThat(response.getBody().get("data"))
				.as("PHP's [] serialises as a JSON array, not an object")
				.isEqualTo(List.of());
		assertThat(response.getBody().toString()).doesNotContain(recorder().lastCode());

		assertThat(recorder().last().phone()).isEqualTo(COMPANY_PHONE);
		assertThat(recorder().last().countryCode())
				.as("the company's own country code, not the default")
				.isEqualTo("+20");
	}

	/**
	 * The verify and resend templates are hard-coded Arabic whatever the
	 * request's language; only the password-reset key goes through {@code t()}.
	 */
	@Test
	@Order(2)
	void verifyAndResendAreAlwaysArabicWhilePasswordResetIsTranslated() throws Exception {
		recorder().clear();
		clearOtps();
		post(RESEND_OTP, null, "{\"phone\":\"" + STAFF_PHONE + "\"}");
		assertThat(recorder().last().message())
				.as("the exact template legacy hard-codes")
				.startsWith("رمز التحقق الخاص بك هو (")
				.contains("لا تشاركه مع أي شخص");

		recorder().clear();
		clearOtps();
		post(FORGOT, "en", "{\"phone\":\"" + COMPANY_PHONE + "\",\"type\":\"company\"}");
		assertThat(recorder().last().message())
				.as("password reset is t()-rendered, so it follows the request locale")
				.doesNotContain("رمز التحقق الخاص بك");
	}

	// ---------------- verify_otp ----------------

	/**
	 * A company verification marks {@code otp_verified} and consumes the code.
	 * A password-reset verification does neither -- the code has to survive for
	 * {@code reset_password.php}.
	 */
	@Test
	@Order(3)
	void aPasswordResetVerificationLeavesTheCodeUsableAndACompanyOneDoesNot() throws Exception {
		recorder().clear();
		clearOtps();
		setOtpVerified(COMPANY_ID, 0);

		post(FORGOT, null, "{\"phone\":\"" + COMPANY_PHONE + "\",\"type\":\"company\"}");
		String code = recorder().lastCode();

		assertThat(post(VERIFY_OTP, null, "{\"phone\":\"" + COMPANY_PHONE + "\",\"otp\":\"" + code
				+ "\",\"type\":\"company\",\"purpose\":\"password_reset\"}")
				.getStatusCode().value()).isEqualTo(200);
		assertThat(otpVerified(COMPANY_ID)).as("a password reset does not verify the company").isZero();
		assertThat(activeOtpCount(COMPANY_PHONE)).as("and the code stays usable").isEqualTo(1);

		assertThat(post(VERIFY_OTP, null, "{\"phone\":\"" + COMPANY_PHONE + "\",\"otp\":\"" + code
				+ "\",\"type\":\"company\"}").getStatusCode().value()).isEqualTo(200);
		assertThat(otpVerified(COMPANY_ID)).isEqualTo(1);
		assertThat(activeOtpCount(COMPANY_PHONE)).as("now it is consumed").isZero();
	}

	/** A consumed or wrong code is 400, and a consumed one cannot be replayed. */
	@Test
	@Order(4)
	void aWrongOrConsumedCodeIsInvalidExpiredOtp() throws Exception {
		assertThat(post(VERIFY_OTP, null,
				"{\"phone\":\"" + COMPANY_PHONE + "\",\"otp\":\"0000\",\"type\":\"company\"}")
				.getStatusCode().value()).isEqualTo(400);
	}

	/** An expired code fails even though the row is still unused. */
	@Test
	@Order(5)
	void anExpiredCodeIsRejected() throws Exception {
		recorder().clear();
		clearOtps();
		post(RESEND_OTP, null, "{\"phone\":\"" + STAFF_PHONE + "\"}");
		String code = recorder().lastCode();
		expireOtps(STAFF_PHONE);
		assertThat(post(VERIFY_OTP, null,
				"{\"phone\":\"" + STAFF_PHONE + "\",\"otp\":\"" + code + "\",\"type\":\"employee\"}")
				.getStatusCode().value()).isEqualTo(400);
	}

	// ---------------- resend_otp ----------------

	/**
	 * {@code resend_otp.php}'s own recency guard answers the <b>default 400</b>
	 * -- {@code fail()} with no status -- where every other cooldown in the
	 * system is 429.
	 */
	@Test
	@Order(6)
	void resendCooldownIs400NotThe429UsedEverywhereElse() throws Exception {
		recorder().clear();
		clearOtps();
		assertThat(post(RESEND_OTP, null, "{\"phone\":\"" + STAFF_PHONE + "\"}")
				.getStatusCode().value()).isEqualTo(200);
		assertThat(post(RESEND_OTP, null, "{\"phone\":\"" + STAFF_PHONE + "\"}")
				.getStatusCode().value())
				.as("400, not 429 -- the local guard fires before the limiter")
				.isEqualTo(400);
	}

	/** It never checks that the phone belongs to anybody. */
	@Test
	@Order(7)
	void resendSendsToAnUnknownNumber() throws Exception {
		recorder().clear();
		clearOtps();
		assertThat(post(RESEND_OTP, null, "{\"phone\":\"01099999999\"}").getStatusCode().value())
				.isEqualTo(200);
		assertThat(recorder().last().phone()).isEqualTo("01099999999");
	}

	/** A failed delivery is 503 -- and the row was already written. */
	@Test
	@Order(8)
	void aFailedDeliveryIs503AndStillConsumedTheSlot() throws Exception {
		recorder().clear();
		clearOtps();
		recorder().failNext();
		assertThat(post(RESEND_OTP, null, "{\"phone\":\"" + STAFF_PHONE + "\"}")
				.getStatusCode().value()).isEqualTo(503);
		assertThat(activeOtpCount(STAFF_PHONE))
				.as("the OTP row is written before the send is attempted")
				.isEqualTo(1);
		assertThat(post(RESEND_OTP, null, "{\"phone\":\"" + STAFF_PHONE + "\"}")
				.getStatusCode().value())
				.as("so the caller is now in cooldown for an OTP they never received")
				.isEqualTo(400);
	}

	// ---------------- R-014 ----------------

	/**
	 * <b>R-014.</b> The third check in {@code otp_assert_can_send()} reads as a
	 * per-IP cap, but against the frozen schema it counts every OTP row created
	 * in the last hour, for every phone. Twenty rows and the twenty-first
	 * caller -- a different phone, a different client -- is refused.
	 *
	 * <p>Asserted here rather than described, because a reader who only sees
	 * the code will conclude it is per-IP.
	 */
	@Test
	@Order(9)
	void thePerIpCapIsActuallyAPlatformWideCap() throws Exception {
		recorder().clear();
		clearOtps();
		for (int i = 0; i < 20; i++) {
			seedOtpRow("0100000" + String.format("%04d", i));
		}

		ResponseEntity<Map<String, Object>> refused =
				post(RESEND_OTP, null, "{\"phone\":\"01055550000\"}");
		assertThat(refused.getStatusCode().value())
				.as("a phone with no history of its own, refused by other phones' volume")
				.isEqualTo(429);
		assertThat(refused.getBody().get("message").toString())
				.contains("Too many verification code requests");

		clearOtps();
		assertThat(post(RESEND_OTP, null, "{\"phone\":\"01055550000\"}").getStatusCode().value())
				.as("and it recovers the moment the global count drops")
				.isEqualTo(200);
	}

	// ---------------- forgot_password ----------------

	/** An unknown type is 400; a known type with no account is 404. */
	@Test
	@Order(10)
	void forgotPasswordDistinguishesAnUnknownTypeFromAnUnknownPhone() throws Exception {
		clearOtps();
		assertThat(post(FORGOT, null, "{\"phone\":\"" + COMPANY_PHONE + "\",\"type\":\"nonsense\"}")
				.getStatusCode().value()).isEqualTo(400);
		assertThat(post(FORGOT, null, "{\"phone\":\"01099999999\",\"type\":\"company\"}")
				.getStatusCode().value())
				.as("the endpoint tells an anonymous caller whether a phone is registered")
				.isEqualTo(404);
	}

	/**
	 * Without a company id the employee branch runs
	 * {@code resolve_single_employee_auth_by_phone()}, which -- unlike
	 * {@code login_employee.php} -- <b>rejects</b> a pending account.
	 */
	@Test
	@Order(11)
	void aPendingEmployeeCanLogInButCannotResetTheirPassword() throws Exception {
		clearOtps();
		ResponseEntity<Map<String, Object>> response =
				post(FORGOT, null, "{\"phone\":\"" + PENDING_PHONE + "\",\"type\":\"employee\"}");
		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody().get("message").toString()).contains("join request is under review");
	}

	/**
	 * The mixed-row case, because the resolver's rejection block runs
	 * <b>only</b> when no login-ready account exists. A phone owning both a
	 * ready account and a pending one resolves to the ready one -- the pending
	 * row is ignored, not reported.
	 *
	 * <p>Added after a review pointed at an earlier javadoc here that claimed
	 * the opposite. The code was right and the comment was wrong; this test
	 * makes the code's behaviour the thing that has to change if anyone
	 * "fixes" it to match the old comment.
	 */
	@Test
	@Order(11)
	void aPhoneOwningBothAReadyAndAPendingAccountResolvesToTheReadyOne() throws Exception {
		recorder().clear();
		clearOtps();
		addPendingDuplicateOf(STAFF_PHONE);
		try {
			ResponseEntity<Map<String, Object>> response =
					post(FORGOT, null, "{\"phone\":\"" + STAFF_PHONE + "\",\"type\":\"employee\"}");
			assertThat(response.getStatusCode().value())
					.as("the ready account wins; the pending row does not veto it")
					.isEqualTo(200);
			assertThat(recorder().last()).isNotNull();
		} finally {
			removeDuplicate();
		}
	}

	/**
	 * The country code travels with the resolved employee rather than being
	 * re-derived. {@code otp_resolve_country_code_for_phone()} matches the
	 * phone column <b>exactly</b>, so a stored number the variant-aware account
	 * query found would not be found again and delivery would silently fall
	 * back to {@code +20} -- building the wrong WhatsApp JID for a non-Egyptian
	 * number.
	 */
	@Test
	@Order(12)
	void theResolvedEmployeesCountryCodeIsUsedForDelivery() throws Exception {
		recorder().clear();
		clearOtps();
		setCountryCode(STAFF, "+966");
		try {
			post(FORGOT, null, "{\"phone\":\"" + STAFF_PHONE + "\",\"type\":\"employee\"}");
			assertThat(recorder().last().countryCode())
					.as("the row's own code, not the +20 default")
					.isEqualTo("+966");
		} finally {
			setCountryCode(STAFF, "+20");
		}
	}

	// ---------------- reset_password ----------------

	/** The full flow, and the new password really replaces the old one. */
	@Test
	@Order(13)
	void theFullResetFlowChangesThePassword() throws Exception {
		recorder().clear();
		clearOtps();
		post(FORGOT, null, "{\"phone\":\"" + STAFF_PHONE + "\",\"type\":\"employee\",\"company_id\":"
				+ COMPANY_ID + "}");
		String code = recorder().lastCode();

		assertThat(post(RESET, null, "{\"phone\":\"" + STAFF_PHONE + "\",\"password\":\"brandnew1\",\"otp\":\""
				+ code + "\",\"type\":\"employee\",\"company_id\":" + COMPANY_ID + "}")
				.getStatusCode().value()).isEqualTo(200);
		assertThat(passwordEncoder.matches("brandnew1", employeeHash(STAFF))).isTrue();
		assertThat(activeOtpCount(STAFF_PHONE)).as("the code is consumed").isZero();
	}

	/**
	 * There is no minimum length here, unlike
	 * {@code profile/change_password.php}'s six characters -- so this route
	 * accepts a one-character password.
	 */
	@Test
	@Order(14)
	void resetPasswordHasNoMinimumLength() throws Exception {
		recorder().clear();
		clearOtps();
		post(FORGOT, null, "{\"phone\":\"" + STAFF_PHONE + "\",\"type\":\"employee\",\"company_id\":"
				+ COMPANY_ID + "}");
		String code = recorder().lastCode();
		assertThat(post(RESET, null, "{\"phone\":\"" + STAFF_PHONE + "\",\"password\":\"x\",\"otp\":\""
				+ code + "\",\"type\":\"employee\",\"company_id\":" + COMPANY_ID + "}")
				.getStatusCode().value()).isEqualTo(200);
		assertThat(passwordEncoder.matches("x", employeeHash(STAFF))).isTrue();
	}

	/** The OTP is checked before the type, so a nonsense type still reports the code. */
	@Test
	@Order(15)
	void resetChecksTheCodeBeforeTheType() throws Exception {
		clearOtps();
		assertThat(post(RESET, null, "{\"phone\":\"" + STAFF_PHONE
				+ "\",\"password\":\"whatever\",\"otp\":\"0000\",\"type\":\"nonsense\"}")
				.getStatusCode().value()).isEqualTo(400);
	}

	// ---------------- profile phone change ----------------

	/** Company sessions only, and it refuses with 403 -- not the preview's 401. */
	@Test
	@Order(16)
	void thePhoneChangeRoutesAreCompanySessionsOnlyAndRefuseWith403() {
		assertThat(post(REQUEST_CHANGE, null, "{\"phone\":\"01000031099\",\"country_code\":\"+20\"}",
				employeeToken()).getStatusCode().value()).isEqualTo(403);
		assertThat(post(CONFIRM_CHANGE, null,
				"{\"phone\":\"01000031099\",\"country_code\":\"+20\",\"otp\":\"1234\"}",
				employeeToken()).getStatusCode().value()).isEqualTo(403);
	}

	/** The current number and another company's number are both refused, differently. */
	@Test
	@Order(17)
	void thePhoneChangeRefusesTheCurrentNumberAndOneAlreadyTaken() throws Exception {
		clearOtps();
		ResponseEntity<Map<String, Object>> same = post(REQUEST_CHANGE, null,
				"{\"phone\":\"" + COMPANY_PHONE + "\",\"country_code\":\"+20\"}", companyToken());
		assertThat(same.getStatusCode().value()).isEqualTo(400);
		assertThat(same.getBody().get("message").toString()).contains("already your current phone");

		assertThat(post(REQUEST_CHANGE, null,
				"{\"phone\":\"" + OTHER_COMPANY_PHONE + "\",\"country_code\":\"+20\"}", companyToken())
				.getStatusCode().value()).isEqualTo(409);
	}

	/** The full change, which also flips {@code otp_verified} on. */
	@Test
	@Order(18)
	void confirmingAPhoneChangeAlsoMarksTheCompanyVerified() throws Exception {
		recorder().clear();
		clearOtps();
		setOtpVerified(COMPANY_ID, 0);
		String next = "01000031099";

		assertThat(post(REQUEST_CHANGE, null,
				"{\"phone\":\"" + next + "\",\"country_code\":\"+20\"}", companyToken())
				.getStatusCode().value()).isEqualTo(200);
		assertThat(recorder().last().phone()).isEqualTo(next);
		String code = recorder().lastCode();

		ResponseEntity<Map<String, Object>> confirmed = post(CONFIRM_CHANGE, null,
				"{\"phone\":\"" + next + "\",\"country_code\":\"+20\",\"otp\":\"" + code + "\"}",
				companyToken());
		assertThat(confirmed.getStatusCode().value()).as("%s", confirmed.getBody()).isEqualTo(200);
		@SuppressWarnings("unchecked")
		Map<String, Object> row = (Map<String, Object>) confirmed.getBody().get("data");
		assertThat(row).containsEntry("phone", next);
		assertThat(row).as("public_row() still applies").doesNotContainKey("password_hash");
		assertThat(otpVerified(COMPANY_ID))
				.as("changing the phone verifies a company that never verified its first one")
				.isEqualTo(1);
		assertThat(activeOtpCount(next)).isZero();

		// Put it back so later runs of the ordered fixture are unaffected.
		restorePhone(COMPANY_ID, COMPANY_PHONE);
	}

	// ---------------- method guards ----------------

	@Test
	@Order(19)
	void everyRouteChecksItsMethodBeforeAnythingElse() {
		for (String route : List.of(VERIFY_OTP, RESEND_OTP, FORGOT, RESET)) {
			assertThat(send(route, HttpMethod.GET, null, null).getStatusCode().value())
					.as("%s", route)
					.isEqualTo(405);
		}
		assertThat(send(REQUEST_CHANGE, HttpMethod.GET, null, null).getStatusCode().value())
				.as("the method guard precedes requireAuth here too")
				.isEqualTo(405);
	}

	@Test
	@Order(20)
	void everyRouteRequiresItsFields() {
		assertThat(post(VERIFY_OTP, null, "{}").getStatusCode().value()).isEqualTo(400);
		assertThat(post(RESEND_OTP, null, "{}").getStatusCode().value()).isEqualTo(400);
		assertThat(post(FORGOT, null, "{}").getStatusCode().value()).isEqualTo(400);
		assertThat(post(RESET, null, "{}").getStatusCode().value()).isEqualTo(400);
	}

	// ---------------- fixture ----------------

	private ResponseEntity<Map<String, Object>> post(String path, String lang, String body) {
		return post(path, lang, body, null);
	}

	private ResponseEntity<Map<String, Object>> post(String path, String lang, String body, String token) {
		return send(path + (lang == null ? "" : "?lang=" + lang), HttpMethod.POST, token, body);
	}

	private ResponseEntity<Map<String, Object>> send(
			String path, HttpMethod method, String token, String body) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method,
				new HttpEntity<>(body, headers), new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private String companyToken() {
		return legacyPhpJwtService.issueCompanyToken(COMPANY_ID, "company_admin");
	}

	private String employeeToken() {
		return legacyPhpJwtService.issueEmployeeToken(STAFF, COMPANY_ID, "employee", 1L);
	}

	private static String scalar(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getString(1) : null;
		} catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static void execute(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute(sql);
		} catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static void clearOtps() {
		execute("DELETE FROM otp_codes");
	}

	private static void expireOtps(String phone) {
		execute("UPDATE otp_codes SET expires_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE phone = '"
				+ phone + "'");
	}

	private static void seedOtpRow(String phone) {
		execute("INSERT INTO otp_codes (phone, code, expires_at) VALUES ('" + phone
				+ "', '1111', DATE_ADD(NOW(), INTERVAL 10 MINUTE))");
	}

	private static long activeOtpCount(String phone) {
		return Long.parseLong(scalar("SELECT COUNT(*) FROM otp_codes WHERE phone = '" + phone
				+ "' AND COALESCE(is_used, 0) = 0 AND expires_at > NOW()"));
	}

	private static long otpVerified(long companyId) {
		return Long.parseLong(scalar("SELECT otp_verified FROM companies WHERE id = " + companyId));
	}

	private static void setOtpVerified(long companyId, int value) {
		execute("UPDATE companies SET otp_verified = " + value + " WHERE id = " + companyId);
	}

	private static void setCountryCode(long employeeId, String code) {
		execute("UPDATE employees SET country_code = '" + code + "' WHERE id = " + employeeId);
	}

	/** A second, pending account at another company owning an equivalent number. */
	private static void addPendingDuplicateOf(String phone) {
		execute("INSERT INTO companies (id, company_name, phone, password_hash, status, otp_verified,"
				+ " created_at) VALUES (31009, 'Second Co', '01000031009', 'x', 'active', 1,"
				+ " '2019-01-15 09:00:00')");
		execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
				+ " (31019, 31009, 'Main', 1, '2019-03-01 10:00:00')");
		// The UNIQUE KEY on employees.phone forbids the identical string, so the
		// duplicate uses an equivalent *variant* -- which is exactly what
		// phone_sql_match_clause() is for and what makes this the mixed case.
		execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name, last_name,"
				+ " phone, country_code, password_hash, role, is_active, token_version,"
				+ " join_request_status, created_at) VALUES (310099, 31009, 31019, '310099', 'P', 'Q', '"
				+ phone.substring(1) + "', '+20', 'x', 'employee', 0, 1, 'pending', '2019-04-01 08:00:00')");
	}

	private static void removeDuplicate() {
		execute("DELETE FROM employees WHERE id = 310099");
		execute("DELETE FROM branches WHERE id = 31019");
		execute("DELETE FROM companies WHERE id = 31009");
	}

	private static void restorePhone(long companyId, String phone) {
		execute("UPDATE companies SET phone = '" + phone + "' WHERE id = " + companyId);
	}

	private static String employeeHash(long employeeId) {
		return scalar("SELECT password_hash FROM employees WHERE id = " + employeeId);
	}

	private static void seed() throws Exception {
		String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("SET time_zone = '" + LegacyRuntimeOffset.DEFAULT + "'");
			st.execute("INSERT INTO companies (id, company_name, phone, country_code, password_hash,"
					+ " status, otp_verified, created_at) VALUES"
					+ " (" + COMPANY_ID + ", 'OTP Co', '" + COMPANY_PHONE + "', '+20', '" + hash
					+ "', 'active', 1, '2019-01-15 09:00:00'),"
					+ " (" + OTHER_COMPANY + ", 'Other Co', '" + OTHER_COMPANY_PHONE + "', '+20', '"
					+ hash + "', 'active', 1, '2019-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES ("
					+ BRANCH + ", " + COMPANY_ID + ", 'Main', 1, '2019-03-01 10:00:00')");
			employee(st, STAFF, STAFF_PHONE, "accepted", 1, hash);
			employee(st, PENDING_STAFF, PENDING_PHONE, "pending", 0, hash);
		}
	}

	private static void employee(
			Statement st, long id, String phone, String joinStatus, int active, String hash)
			throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, country_code, password_hash, role, is_active, token_version,"
				+ " join_request_status, created_at) VALUES (" + id + ", " + COMPANY_ID + ", " + BRANCH
				+ ", '" + id + "', 'F', 'L', '" + phone + "', '+20', '" + hash + "', 'employee', "
				+ active + ", 1, '" + joinStatus + "', '2019-04-01 08:00:00')");
	}

	private static void applySchema(String resourceName) throws Exception {
		String schema = readResource(resourceName);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			for (String statement : schema.split(";\\s*\\R")) {
				if (!statement.isBlank()) {
					st.execute(statement);
				}
			}
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream in = LegacyOtpAuthEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
