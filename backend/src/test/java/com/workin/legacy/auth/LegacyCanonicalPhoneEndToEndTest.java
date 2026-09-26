package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyMariaDb;
import com.workin.legacy.auth.otp.RecordingWhatsAppConfiguration;
import com.workin.legacy.auth.otp.RecordingWhatsAppSender;
import com.workin.legacy.auth.whatsapp.LegacyWhatsAppSender;

/**
 * D-291 over real HTTP against real MariaDB: a phone number is one number
 * however it is written, on every route that reads or writes one (ADR-0020).
 *
 * <p>Every test seeds or resets what it reads, and each uses numbers of its
 * own, so the order they run in does not matter.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(RecordingWhatsAppConfiguration.class)
class LegacyCanonicalPhoneEndToEndTest {

	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String PASSWORD = "Canonical-Owl-4";

	private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);

	private static final long COMPANY = 29101L;
	private static final long OTHER_COMPANY = 29102L;
	private static final long PAIR_NATIONAL = 29103L;
	private static final long PAIR_BARE = 29104L;
	private static final long BRANCH = 29111L;
	private static final long OTHER_BRANCH = 29112L;

	private static final long EMPLOYEE = 291001L;
	private static final long SAUDI = 291002L;
	private static final long STORED_WITHOUT_ZERO = 291003L;

	private static final String EMPLOYEE_PHONE = "01012910001";
	private static final String CODE = "CANON01";
	private static final String OTHER_CODE = "CANON02";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private LegacyWhatsAppSender whatsAppSender;

	static {
		try {
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the D-291 fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	@BeforeEach
	void freshBudgetsAndCodes() throws Exception {
		execute("DELETE FROM platform_admin_login_attempts");
		execute("DELETE FROM otp_codes");
		execute("DELETE FROM otp_request_logs");
		((RecordingWhatsAppSender) this.whatsAppSender).clear();
	}

	// ---------------- login ----------------

	@Test
	void everySpellingOfANumberSignsInToTheSameEmployee() {
		for (String spelling : List.of(EMPLOYEE_PHONE, "+20 10 1291 0001", "0020 10 1291 0001", "(010) 1291-0001",
				"1012910001", "201012910001", "010.1291.0001", "٠١٠١٢٩١٠٠٠١", "０１０１２９１０００１")) {
			ResponseEntity<Map<String, Object>> response =
					post("login_employee", Map.of("phone", spelling, "password", PASSWORD));
			assertThat(response.getStatusCode().value()).as(spelling).isEqualTo(200);
			assertThat(employeeId(response)).as(spelling).isEqualTo(EMPLOYEE);
		}
	}

	@Test
	void anAccountStoredWithoutItsTrunkZeroIsFoundByTheNationalForm() {
		// 20 production employees are stored this way; PHP's exact match
		// found them only by the bare digits.
		for (String spelling : List.of("01012910003", "1012910003", "+201012910003")) {
			assertThat(employeeId(post("login_employee", Map.of("phone", spelling, "password", PASSWORD))))
					.as(spelling).isEqualTo(STORED_WITHOUT_ZERO);
		}
	}

	@Test
	void aSaudiEmployeeSignsInNationallyByTheirRowsCountryAndInternationally() {
		// 0501234567 is also an Egyptian landline; with no country in the
		// login body, the stored row's +966 is the context that reads it.
		for (String spelling : List.of("0501234567", "501234567", "+966 50 123 4567", "00966501234567")) {
			assertThat(employeeId(post("login_employee", Map.of("phone", spelling, "password", PASSWORD))))
					.as(spelling).isEqualTo(SAUDI);
		}
		// The Egyptian reading of those digits is another number, and no one's.
		assertThat(post("login_employee", Map.of("phone", "+20 50 1234567", "password", PASSWORD))
				.getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void circledAndDingbatDigitsAreAnUnknownPhoneWithoutALookup() {
		for (String phone : List.of("⓪①⓪①②⑨①⓪⓪⓪①", "⓿➀⓿➀➁➈➀⓿⓿⓿➀", "01012910001 ext 1", "010ABCDEFGH")) {
			ResponseEntity<Map<String, Object>> response =
					post("login_employee", Map.of("phone", phone, "password", PASSWORD));
			assertThat(response.getStatusCode().value()).as(phone).isEqualTo(401);
		}
	}

	@Test
	void missesInEightSpellingsOfOneNumberSpendItsWholeBudget() {
		List<String> spellings = List.of(EMPLOYEE_PHONE, "+201012910001", "1012910001", "0020 10 1291 0001",
				"(010) 1291-0001", "201012910001", "٠١٠١٢٩١٠٠٠١", "010-1291-0001");
		assertThat(spellings).hasSize(LegacyLoginThrottle.MAX_PAIR_MISSES);
		for (String spelling : spellings) {
			assertThat(post("login_employee", Map.of("phone", spelling, "password", "wrong"))
					.getStatusCode().value()).as(spelling).isEqualTo(401);
		}

		// The right password, in a ninth spelling, on another route: refused.
		assertThat(post("login_employee", Map.of("phone", "+20 10 1291 0001", "password", PASSWORD))
				.getStatusCode().value()).isEqualTo(429);
		assertThat(post("login_desktop", Map.of("phone", "010 1291 0001", "password", PASSWORD, "login_as", "hr"))
				.getStatusCode().value()).isEqualTo(429);
	}

	// ---------------- registration ----------------

	@Test
	void aCompanyRegisteredInOneSpellingSignsInWithAnotherAndCannotRegisterTwice() throws Exception {
		assertThat(post("register_company", Map.of("first_name", "Nour", "last_name", "Adel",
				"phone", "(010) 1291-3004", "password", PASSWORD, "country_code", "+20"))
				.getStatusCode().value()).isEqualTo(201);
		assertThat(row("SELECT CONCAT(phone, '|', country_code) FROM companies WHERE phone = '01012913004'"))
				.as("stored as the national digits beside the number's own dial code")
				.isEqualTo("01012913004|+20");
		RecordingWhatsAppSender.Sent sent = ((RecordingWhatsAppSender) this.whatsAppSender).last();
		assertThat(sent.phone()).isEqualTo("01012913004");
		assertThat(sent.countryCode()).isEqualTo("+20");
		assertThat(row("SELECT COUNT(*) FROM otp_codes WHERE phone = '+201012913004'"))
				.as("the code is keyed on E.164").isEqualTo("1");

		for (String again : List.of("01012913004", "1012913004", "+20 10 1291 3004", "00201012913004")) {
			assertThat(post("register_company", Map.of("first_name", "Nour", "last_name", "Adel",
					"phone", again, "password", PASSWORD)).getStatusCode().value()).as(again).isEqualTo(400);
		}

		execute("UPDATE companies SET otp_verified = 1, profile_completed = 1, status = 'active'"
				+ " WHERE phone = '01012913004'");
		ResponseEntity<Map<String, Object>> login =
				post("login_company", Map.of("phone", "+20 10 1291 3004", "password", PASSWORD));
		assertThat(login.getStatusCode().value()).isEqualTo(200);
		assertThat(companyId(login)).isEqualTo(Long.parseLong(
				row("SELECT id FROM companies WHERE phone = '01012913004'")));
	}

	@Test
	void aLandlineOrAnInvalidNumberCannotRegister() {
		for (String phone : List.of("02 2345 6789", "01312913005", "0101291300", "010ABCDEFGH")) {
			assertThat(post("register_company", Map.of("first_name", "A", "last_name", "B",
					"phone", phone, "password", PASSWORD)).getStatusCode().value()).as(phone).isEqualTo(400);
		}
	}

	@Test
	void joinCompanyComparesCanonicalNumbersInItsOwnScopes() throws Exception {
		assertThat(post("join_company", Map.of("first_name", "Joiner", "phone", "010 1291 4005",
				"password", PASSWORD, "company_code", CODE)).getStatusCode().value()).isEqualTo(201);
		assertThat(row("SELECT CONCAT(phone, '|', country_code) FROM employees WHERE phone = '01012914005'"))
				.isEqualTo("01012914005|+20");

		// Pending at this company, in another spelling: the company-scoped probe.
		assertThat(post("join_company", Map.of("first_name", "Joiner", "phone", "+201012914005",
				"password", PASSWORD, "company_code", CODE)).getStatusCode().value()).isEqualTo(400);

		// An employee of this company, stored without its zero: the same probe.
		assertThat(post("join_company", Map.of("first_name", "Twin", "phone", "01012910003",
				"password", PASSWORD, "company_code", CODE)).getStatusCode().value()).isEqualTo(400);

		// Someone else's number at another company: the global check, 409.
		assertThat(post("join_company", Map.of("first_name", "Twin", "phone", "+20 10 1291 0001",
				"password", PASSWORD, "company_code", OTHER_CODE)).getStatusCode().value()).isEqualTo(409);

		// A rejected join request at this company does not reserve the number
		// for the probe; the global unique index is still what stops the
		// identical row, as it did in PHP.
		execute("UPDATE employees SET join_request_status = 'rejected' WHERE phone = '01012914005'");
		assertThat(post("join_company", Map.of("first_name", "Joiner", "phone", "1012914005",
				"password", PASSWORD, "company_code", CODE)).getStatusCode().value()).isEqualTo(409);
	}

	@Test
	void checkStatusFindsTheEmployeeInAnySpelling() {
		for (String spelling : List.of("+201012910001", "1012910001", "(010) 1291-0001")) {
			Map<String, Object> data = data(post("check_status", Map.of("phone", spelling, "company_id", COMPANY)));
			assertThat(data).as(spelling).containsEntry("screen", "home");
		}
	}

	// ---------------- the duplicate company pairs ----------------

	@Test
	void whenTwoCompaniesHoldOneNumberTheOneStoredAsTypedAnswers() {
		// 16 production pairs look like this: 010... and 10... registered
		// separately. Each owner types the spelling they registered.
		assertThat(companyId(post("login_company", Map.of("phone", "01012922002", "password", PASSWORD))))
				.isEqualTo(PAIR_NATIONAL);
		assertThat(companyId(post("login_company", Map.of("phone", "010 1292 2002", "password", PASSWORD))))
				.isEqualTo(PAIR_NATIONAL);
		assertThat(companyId(post("login_company", Map.of("phone", "1012922002", "password", PASSWORD))))
				.isEqualTo(PAIR_BARE);
		assertThat(companyId(post("login_desktop",
				Map.of("phone", "1012922002", "password", PASSWORD, "login_as", "company"))))
				.isEqualTo(PAIR_BARE);

		// A spelling neither stored: no account is chosen for the caller.
		ResponseEntity<Map<String, Object>> neither =
				post("login_company", Map.of("phone", "+201012922002", "password", PASSWORD));
		assertThat(neither.getStatusCode().value()).isEqualTo(401);
	}

	// ---------------- OTP ----------------

	@Test
	void aResetRequestedInOneSpellingIsCompletedInAnother() throws Exception {
		assertThat(post("forgot_password", Map.of("phone", "+20 10 1291 0001", "type", "employee"))
				.getStatusCode().value()).isEqualTo(200);
		String code = ((RecordingWhatsAppSender) this.whatsAppSender).lastCode();
		assertThat(row("SELECT COUNT(*) FROM otp_codes WHERE phone = '+201012910001'")).isEqualTo("1");

		// A second spelling does not buy a second cooldown.
		assertThat(post("resend_otp", Map.of("phone", "1012910001")).getStatusCode().value()).isEqualTo(400);

		assertThat(post("reset_password", Map.of("phone", "1012910001", "otp", code, "type", "employee",
				"password", "Reset-Owl-5")).getStatusCode().value()).isEqualTo(200);
		assertThat(post("login_employee", Map.of("phone", "(010) 1291-0001", "password", "Reset-Owl-5"))
				.getStatusCode().value()).isEqualTo(200);
		execute("UPDATE employees SET password_hash = '" + HASH + "' WHERE id = " + EMPLOYEE);
	}

	@Test
	void resendRefusesSomethingThatIsNotANumber() {
		assertThat(post("resend_otp", Map.of("phone", "01312345678")).getStatusCode().value()).isEqualTo(400);
		assertThat(((RecordingWhatsAppSender) this.whatsAppSender).sent()).isEmpty();
	}

	// ------------------------------------------------------------------

	private ResponseEntity<Map<String, Object>> post(String route, Map<String, Object> body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return this.restTemplate.exchange(
				URI.create(this.restTemplate.getRootUri() + "/apis/api/auth/" + route + ".php"), HttpMethod.POST,
				new HttpEntity<>(body, headers), new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getBody()).as("%s", response.getStatusCode()).containsKey("data");
		return (Map<String, Object>) response.getBody().get("data");
	}

	@SuppressWarnings("unchecked")
	private static long employeeId(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		return ((Number) ((Map<String, Object>) data(response).get("employee")).get("id")).longValue();
	}

	@SuppressWarnings("unchecked")
	private static long companyId(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		return ((Number) ((Map<String, Object>) data(response).get("company")).get("id")).longValue();
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			company(st, COMPANY, "Canonical Co", "01012911001", CODE);
			company(st, OTHER_COMPANY, "Other Co", "01012911002", OTHER_CODE);
			company(st, PAIR_NATIONAL, "Pair National", "01012922002", null);
			company(st, PAIR_BARE, "Pair Bare", "1012922002", null);
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH + ", " + COMPANY + ", 'Main', 1, '2025-03-01 10:00:00'),"
					+ " (" + OTHER_BRANCH + ", " + OTHER_COMPANY + ", 'Main', 1, '2025-03-01 10:00:00')");
			employee(st, EMPLOYEE, EMPLOYEE_PHONE, "+20", "hr");
			employee(st, SAUDI, "0501234567", "+966", "employee");
			employee(st, STORED_WITHOUT_ZERO, "1012910003", "+20", "employee");
		}
	}

	private static void company(Statement st, long id, String name, String phone, String code) throws Exception {
		st.execute("INSERT INTO companies (id, company_name, company_code, phone, country_code, password_hash,"
				+ " status, otp_verified, profile_completed, created_at) VALUES (" + id + ", '" + name + "', "
				+ (code == null ? "NULL" : "'" + code + "'") + ", '" + phone + "', '+20', '" + HASH
				+ "', 'active', 1, 1, '2025-01-15 09:00:00')");
	}

	private static void employee(Statement st, long id, String phone, String countryCode, String role)
			throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name, last_name,"
				+ " phone, country_code, password_hash, token_version, role, is_active, join_request_status,"
				+ " created_at) VALUES (" + id + ", " + COMPANY + ", " + BRANCH + ", '" + id + "', 'Canon',"
				+ " 'Subject', '" + phone + "', '" + countryCode + "', '" + HASH + "', 1, '" + role
				+ "', 1, 'accepted', '2025-05-01 09:00:00')");
	}

	private static void execute(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute(sql);
		}
	}

	private static String row(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getString(1) : null;
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}
}
