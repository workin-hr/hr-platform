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
	private static final long SAUDI_COMPANY = 29105L;
	private static final long RECODED_COMPANY = 29106L;
	private static final long RECODED_TWIN_COMPANY = 29107L;
	private static final long SAUDI_DIGITS_COMPANY = 29109L;
	private static final long EMIRATI_DIGITS_COMPANY = 29110L;

	private static final long EMPLOYEE = 291001L;
	private static final long SAUDI = 291002L;
	private static final long STORED_WITHOUT_ZERO = 291003L;
	private static final long RECODED = 291004L;
	private static final long SAUDI_HOLDER = 291005L;
	private static final long EMIRATI_TWIN = 291006L;
	private static final long BRITISH = 291007L;
	private static final long EMIRATI = 291008L;
	private static final long SAUDI_DIGITS = 291010L;
	private static final long EMIRATI_DIGITS = 291011L;
	private static final long MISCODED = 291012L;
	private static final long PADDED = 291013L;

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

	@Test
	void resendDeliversOnlyToACountryTheProductOffers() {
		// A valid British mobile: the gateway would deliver it, but no account
		// may hold it, so the route is not a free sender to it.
		ResponseEntity<Map<String, Object>> response = post("resend_otp", Map.of("phone", "+447911123456"));
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody()).containsEntry("message", "Phone number is not valid for the selected country");
		assertThat(((RecordingWhatsAppSender) this.whatsAppSender).sent()).isEmpty();

		assertThat(post("resend_otp", Map.of("phone", "+966 50 111 2233")).getStatusCode().value())
				.as("an offered country is still sent to, account or not").isEqualTo(200);
	}

	@Test
	void forgotPasswordDoesNotDeliverToAStoredNumberOutsideTheOfferedCountries() {
		ResponseEntity<Map<String, Object>> response =
				post("forgot_password", Map.of("phone", "+447911123457", "type", "employee"));
		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody()).containsEntry("message", "Phone not found");
		assertThat(((RecordingWhatsAppSender) this.whatsAppSender).sent()).isEmpty();
	}

	@Test
	void anEmployeeResetIsCheckedAgainstTheEmployeesNumberNotACompanysOtherReading() throws Exception {
		// 0501234590 is the employee's +971 number and, read in +966, the
		// company's 501234590: forgot_password keys the code on the employee's.
		assertThat(post("forgot_password", Map.of("phone", "0501234590", "type", "employee"))
				.getStatusCode().value()).isEqualTo(200);
		assertThat(row("SELECT phone FROM otp_codes")).isEqualTo("+971501234590");
		String code = ((RecordingWhatsAppSender) this.whatsAppSender).lastCode();

		ResponseEntity<Map<String, Object>> verified = post("verify_otp", Map.of("phone", "0501234590",
				"otp", code, "type", "employee", "purpose", "password_reset"));
		assertThat(verified.getStatusCode().value()).as("%s", verified.getBody()).isEqualTo(200);
		ResponseEntity<Map<String, Object>> reset = post("reset_password", Map.of("phone", "0501234590",
				"otp", code, "type", "employee", "password", "Reset-Owl-6"));
		assertThat(reset.getStatusCode().value()).as("%s", reset.getBody()).isEqualTo(200);
		assertThat(employeeId(post("login_employee", Map.of("phone", "+971 50 123 4590", "password", "Reset-Owl-6"))))
				.isEqualTo(EMIRATI);
		execute("UPDATE employees SET password_hash = '" + HASH + "' WHERE id = " + EMIRATI);
	}

	@Test
	void aResetForOneOfTwoEmployeesHoldingTheSameDigitsActsOnTheNumberTheCodeWasSentTo() throws Exception {
		// 501234592 is one employee in +966 and, with its zero, another in
		// +971; the Emirati asks for the code, then types the digits nationally.
		assertThat(post("forgot_password", Map.of("phone", "+971501234592", "type", "employee"))
				.getStatusCode().value()).isEqualTo(200);
		String code = ((RecordingWhatsAppSender) this.whatsAppSender).lastCode();
		try {
			ResponseEntity<Map<String, Object>> verified = post("verify_otp", Map.of("phone", "0501234592",
					"otp", code, "type", "employee", "purpose", "password_reset"));
			assertThat(verified.getStatusCode().value()).as("%s", verified.getBody()).isEqualTo(200);
			ResponseEntity<Map<String, Object>> reset = post("reset_password", Map.of("phone", "0501234592",
					"otp", code, "type", "employee", "password", "Reset-Owl-7"));
			assertThat(reset.getStatusCode().value()).as("%s", reset.getBody()).isEqualTo(200);
			assertThat(employeeId(post("login_employee", Map.of("phone", "+971501234592", "password", "Reset-Owl-7"))))
					.isEqualTo(EMIRATI_DIGITS);
			assertThat(employeeId(post("login_employee", Map.of("phone", "+966501234592", "password", PASSWORD))))
					.as("the other number's account is untouched").isEqualTo(SAUDI_DIGITS);
		} finally {
			execute("UPDATE employees SET password_hash = '" + HASH + "' WHERE id IN ("
					+ SAUDI_DIGITS + ", " + EMIRATI_DIGITS + ")");
		}
	}

	@Test
	void aResetForOneOfTwoCompaniesHoldingTheSameDigitsActsOnTheNumberTheCodeWasSentTo() throws Exception {
		assertThat(post("forgot_password", Map.of("phone", "+971501234593", "type", "company"))
				.getStatusCode().value()).isEqualTo(200);
		String code = ((RecordingWhatsAppSender) this.whatsAppSender).lastCode();
		try {
			ResponseEntity<Map<String, Object>> reset = post("reset_password", Map.of("phone", "0501234593",
					"otp", code, "type", "company", "password", "Reset-Owl-8"));
			assertThat(reset.getStatusCode().value()).as("%s", reset.getBody()).isEqualTo(200);
			assertThat(companyId(post("login_company", Map.of("phone", "+971501234593", "password", "Reset-Owl-8"))))
					.isEqualTo(EMIRATI_DIGITS_COMPANY);
			assertThat(companyId(post("login_company", Map.of("phone", "+966501234593", "password", PASSWORD))))
					.as("the other number's company is untouched").isEqualTo(SAUDI_DIGITS_COMPANY);
		} finally {
			execute("UPDATE companies SET password_hash = '" + HASH + "' WHERE id IN ("
					+ SAUDI_DIGITS_COMPANY + ", " + EMIRATI_DIGITS_COMPANY + ")");
		}
	}

	@Test
	void aCodeAuthorisesOnlyItsOwnNumberAndOneBothReadingsHoldAuthorisesNeither() throws Exception {
		assertThat(post("forgot_password", Map.of("phone", "+966501234592", "type", "employee"))
				.getStatusCode().value()).isEqualTo(200);
		String saudiCode = ((RecordingWhatsAppSender) this.whatsAppSender).lastCode();
		try {
			ResponseEntity<Map<String, Object>> other = post("reset_password", Map.of("phone", "+971501234592",
					"otp", saudiCode, "type", "employee", "password", "Stolen-Owl-1"));
			assertThat(other.getStatusCode().value()).as("%s", other.getBody()).isEqualTo(400);

			assertThat(post("forgot_password", Map.of("phone", "+971501234592", "type", "employee"))
					.getStatusCode().value()).isEqualTo(200);
			// expires_at is the table's auto-updating TIMESTAMP, so it is set too.
			execute("UPDATE otp_codes SET code = '4321', expires_at = NOW() + INTERVAL 10 MINUTE");
			ResponseEntity<Map<String, Object>> both = post("reset_password", Map.of("phone", "0501234592",
					"otp", "4321", "type", "employee", "password", "Stolen-Owl-2"));
			assertThat(both.getStatusCode().value()).as("%s", both.getBody()).isEqualTo(400);
			assertThat(both.getBody()).as("the answer an unknown code gets")
					.isEqualTo(other.getBody());

			for (String phone : List.of("+966501234592", "+971501234592")) {
				assertThat(post("login_employee", Map.of("phone", phone, "password", PASSWORD))
						.getStatusCode().value()).as(phone).isEqualTo(200);
			}
		} finally {
			execute("UPDATE employees SET password_hash = '" + HASH + "' WHERE id IN ("
					+ SAUDI_DIGITS + ", " + EMIRATI_DIGITS + ")");
		}
	}

	// ---------------- a country code written without the phone ----------------

	@Test
	void aBlankCountryCodeUnderWhichTheNumberIsUnchangedIsNoChange() throws Exception {
		// 01012910004 in +20 is the same number read in Egypt, which is what a
		// blank code means: the write is saved and the code stored as sent.
		String token = token(post("login_employee", Map.of("phone", "01012910004", "password", PASSWORD)));
		try {
			Map<String, Object> body = new java.util.HashMap<>();
			body.put("first_name", "Blank");
			body.put("country_code", null);
			ResponseEntity<Map<String, Object>> profile =
					send("/apis/api/profile/employee.php", HttpMethod.PUT, token, body);
			assertThat(profile.getStatusCode().value()).as("%s", profile.getBody()).isEqualTo(200);
			assertThat(row("SELECT CONCAT(first_name, '|', COALESCE(country_code, 'NULL')) FROM employees WHERE id = "
					+ RECODED)).isEqualTo("Blank|NULL");

			ResponseEntity<Map<String, Object>> update = send("/apis/api/employees/update.php?id=" + RECODED,
					HttpMethod.PUT, companyToken("01012911001"), Map.of("country_code", "", "last_name", "Blank"));
			assertThat(update.getStatusCode().value()).as("%s", update.getBody()).isEqualTo(200);
			assertThat(row("SELECT CONCAT(last_name, '|', country_code) FROM employees WHERE id = " + RECODED))
					.isEqualTo("Blank|");
			assertThat(employeeId(post("login_employee", Map.of("phone", "01012910004", "password", PASSWORD))))
					.isEqualTo(RECODED);

			ResponseEntity<Map<String, Object>> company = send("/apis/api/company/update.php", HttpMethod.PUT,
					companyToken("01012911006"), Map.of("country_code", ""));
			assertThat(company.getStatusCode().value()).as("%s", company.getBody()).isEqualTo(200);
			assertThat(row("SELECT CONCAT(phone, '|', country_code) FROM companies WHERE id = " + RECODED_COMPANY))
					.as("stored as sent, as on the employee routes").isEqualTo("01012911006|");
		} finally {
			execute("UPDATE employees SET first_name = 'Canon', last_name = 'Subject', country_code = '+20'"
					+ " WHERE id = " + RECODED);
			execute("UPDATE companies SET country_code = '+20' WHERE id = " + RECODED_COMPANY);
		}
	}

	@Test
	void aCodePaddedWithNulIsTheCodeItPads() throws Exception {
		// Stored by the write as it is read -- never as a value a reader would
		// take for another code -- so the account keeps signing in.
		String token = token(post("login_employee", Map.of("phone", "+966501234570", "password", PASSWORD)));
		ResponseEntity<Map<String, Object>> profile = send("/apis/api/profile/employee.php", HttpMethod.PUT, token,
				Map.of("country_code", "+966\u0000"));
		assertThat(profile.getStatusCode().value()).as("%s", profile.getBody()).isEqualTo(200);
		assertThat(row("SELECT HEX(country_code) FROM employees WHERE id = " + SAUDI_HOLDER))
				.isEqualTo("2B393636");
		assertThat(employeeId(post("login_employee", Map.of("phone", "+966501234570", "password", PASSWORD))))
				.isEqualTo(SAUDI_HOLDER);
		assertThat(post("forgot_password", Map.of("phone", "+966501234570", "type", "employee"))
				.getStatusCode().value()).isEqualTo(200);

		try {
			ResponseEntity<Map<String, Object>> update = send("/apis/api/employees/update.php?id=" + RECODED,
					HttpMethod.PUT, companyToken("01012911001"), Map.of("country_code", "\u0000"));
			assertThat(update.getStatusCode().value()).as("%s", update.getBody()).isEqualTo(200);
			assertThat(row("SELECT HEX(country_code) FROM employees WHERE id = " + RECODED)).isEmpty();
			assertThat(employeeId(post("login_employee", Map.of("phone", "01012910004", "password", PASSWORD))))
					.isEqualTo(RECODED);

			ResponseEntity<Map<String, Object>> company = send("/apis/api/company/update.php", HttpMethod.PUT,
					companyToken("01012911006"), Map.of("country_code", "+20\u0000"));
			assertThat(company.getStatusCode().value()).as("%s", company.getBody()).isEqualTo(200);
			assertThat(row("SELECT HEX(country_code) FROM companies WHERE id = " + RECODED_COMPANY))
					.isEqualTo("2B3230");
			assertThat(companyId(post("login_company", Map.of("phone", "01012911006", "password", PASSWORD))))
					.isEqualTo(RECODED_COMPANY);
		} finally {
			execute("UPDATE employees SET country_code = '+20' WHERE id = " + RECODED);
			execute("UPDATE companies SET country_code = '+20' WHERE id = " + RECODED_COMPANY);
		}
	}

	@Test
	void aStoredCodePaddedWithNulIsReadAsTheCodeItPads() throws Exception {
		for (String spelling : List.of("0501234595", "+966 50 123 4595")) {
			assertThat(employeeId(post("login_employee", Map.of("phone", spelling, "password", PASSWORD))))
					.as(spelling).isEqualTo(PADDED);
		}
		assertThat(post("forgot_password", Map.of("phone", "0501234595", "type", "employee"))
				.getStatusCode().value()).isEqualTo(200);
		assertThat(row("SELECT phone FROM otp_codes")).isEqualTo("+966501234595");
	}

	@Test
	void aBlankCountryCodeThatChangesTheNumberIsReadAsEgyptLikeAnyOtherCode() throws Exception {
		// 01012910010 is no number in +966, and an Egyptian mobile: a blank
		// code re-reads it as Egypt's, and it is stored with Egypt's code.
		ResponseEntity<Map<String, Object>> update = send("/apis/api/employees/update.php?id=" + MISCODED,
				HttpMethod.PUT, companyToken("01012911001"), Map.of("country_code", ""));
		assertThat(update.getStatusCode().value()).as("%s", update.getBody()).isEqualTo(200);
		assertThat(row("SELECT CONCAT(phone, '|', country_code) FROM employees WHERE id = " + MISCODED))
				.isEqualTo("01012910010|+20");
		assertThat(employeeId(post("login_employee", Map.of("phone", "+201012910010", "password", PASSWORD))))
				.isEqualTo(MISCODED);

		// 501234570 in +966 is this employee; in Egypt it is a landline: the
		// blank code is refused as that number, not as a missing field.
		String token = token(post("login_employee", Map.of("phone", "+966501234570", "password", PASSWORD)));
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("country_code", null);
		ResponseEntity<Map<String, Object>> profile =
				send("/apis/api/profile/employee.php", HttpMethod.PUT, token, body);
		assertThat(profile.getStatusCode().value()).isEqualTo(400);
		assertThat(profile.getBody()).containsEntry("message", "Phone number is not valid for the selected country");
		assertThat(row("SELECT country_code FROM employees WHERE id = " + SAUDI_HOLDER)).isEqualTo("+966");
	}


	@Test
	void aProfileCannotMoveItsNumberIntoACountryWhereItIsNoNumber() throws Exception {
		String token = token(post("login_employee", Map.of("phone", "01012910004", "password", PASSWORD)));
		for (String code : List.of("+966", "Egypt")) {
			ResponseEntity<Map<String, Object>> response =
					send("/apis/api/profile/employee.php", HttpMethod.PUT, token, Map.of("country_code", code));
			assertThat(response.getStatusCode().value()).as(code).isEqualTo(400);
			assertThat(response.getBody()).as(code).containsEntry("message", "Phone number is not valid for the selected country");
		}
		assertThat(row("SELECT CONCAT(phone, '|', country_code) FROM employees WHERE id = " + RECODED))
				.isEqualTo("01012910004|+20");
		assertThat(employeeId(post("login_employee", Map.of("phone", "01012910004", "password", PASSWORD))))
				.isEqualTo(RECODED);
		assertThat(post("forgot_password", Map.of("phone", "01012910004", "type", "employee"))
				.getStatusCode().value()).isEqualTo(200);

		// The code already stored changes no number, and is written as before.
		// (The login above started a new session.)
		token = token(post("login_employee", Map.of("phone", "01012910004", "password", PASSWORD)));
		assertThat(send("/apis/api/profile/employee.php", HttpMethod.PUT, token,
				Map.of("country_code", "+20", "first_name", "Still")).getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void aProfileCannotMoveItsDigitsOntoAnotherAccountsNumber() throws Exception {
		// 0501234570 in +971 is this employee; the same digits in +966 are
		// another's, stored without their zero.
		String token = token(post("login_employee", Map.of("phone", "+971501234570", "password", PASSWORD)));
		ResponseEntity<Map<String, Object>> response =
				send("/apis/api/profile/employee.php", HttpMethod.PUT, token, Map.of("country_code", "+966"));
		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody()).containsEntry("message", "Phone already exists");
		assertThat(row("SELECT country_code FROM employees WHERE id = " + EMIRATI_TWIN)).isEqualTo("+971");
	}

	@Test
	void anEmployeeUpdateValidatesACountryCodeAgainstTheStoredPhone() throws Exception {
		// The company updating an employee, and an HR employee updating itself.
		String company = companyToken("01012911001");
		String hr = token(post("login_employee", Map.of("phone", EMPLOYEE_PHONE, "password", PASSWORD)));
		for (Map.Entry<Long, String> target : Map.of(RECODED, company, EMPLOYEE, hr).entrySet()) {
			ResponseEntity<Map<String, Object>> response = send("/apis/api/employees/update.php?id=" + target.getKey(),
					HttpMethod.PUT, target.getValue(), Map.of("country_code", "+966"));
			assertThat(response.getStatusCode().value()).as("employee %d: %s", target.getKey(), response.getBody())
					.isEqualTo(400);
			assertThat(response.getBody()).containsEntry("message", "Phone number is not valid for the selected country");
		}
		assertThat(row("SELECT CONCAT(phone, '|', country_code) FROM employees WHERE id = " + EMPLOYEE))
				.isEqualTo(EMPLOYEE_PHONE + "|+20");

		// A number that does hold in the new country is stored as that number.
		execute("UPDATE employees SET phone = '501234591', country_code = '+20' WHERE id = " + RECODED);
		try {
			assertThat(send("/apis/api/employees/update.php?id=" + RECODED, HttpMethod.PUT, company,
					Map.of("country_code", "+966")).getStatusCode().value()).isEqualTo(200);
			assertThat(row("SELECT CONCAT(phone, '|', country_code) FROM employees WHERE id = " + RECODED))
					.isEqualTo("0501234591|+966");
			assertThat(employeeId(post("login_employee", Map.of("phone", "+966501234591", "password", PASSWORD))))
					.isEqualTo(RECODED);
		} finally {
			execute("UPDATE employees SET phone = '01012910004', country_code = '+20' WHERE id = " + RECODED);
		}
	}

	@Test
	void aCompanyUpdateValidatesACountryCodeAgainstTheStoredPhone() throws Exception {
		String token = companyToken("01012911006");
		for (String code : List.of("+966", "Egypt")) {
			ResponseEntity<Map<String, Object>> response =
					send("/apis/api/company/update.php", HttpMethod.PUT, token, Map.of("country_code", code));
			assertThat(response.getStatusCode().value()).as(code).isEqualTo(400);
			assertThat(response.getBody()).as(code).containsEntry("message", "Phone number is not valid for the selected country");
		}
		assertThat(row("SELECT CONCAT(phone, '|', country_code) FROM companies WHERE id = " + RECODED_COMPANY))
				.isEqualTo("01012911006|+20");
		assertThat(companyId(post("login_company", Map.of("phone", "01012911006", "password", PASSWORD))))
				.isEqualTo(RECODED_COMPANY);

		// Digits another company holds in the new country: refused as
		// registering that number would be.
		ResponseEntity<Map<String, Object>> twin = send("/apis/api/company/update.php", HttpMethod.PUT,
				companyToken("+971501234571"), Map.of("country_code", "+966"));
		assertThat(twin.getStatusCode().value()).isEqualTo(400);
		assertThat(twin.getBody()).containsEntry("message", "Phone already registered");
		assertThat(row("SELECT country_code FROM companies WHERE id = " + RECODED_TWIN_COMPANY)).isEqualTo("+971");
	}

	// ------------------------------------------------------------------

	private String companyToken(String phone) {
		return token(post("login_company", Map.of("phone", phone, "password", PASSWORD)));
	}

	@SuppressWarnings("unchecked")
	private static String token(ResponseEntity<Map<String, Object>> login) {
		assertThat(login.getStatusCode().value()).as("%s", login.getBody()).isEqualTo(200);
		return (String) data(login).get("token");
	}

	private ResponseEntity<Map<String, Object>> send(String path, HttpMethod method, String token,
			Map<String, Object> body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(token);
		return this.restTemplate.exchange(URI.create(this.restTemplate.getRootUri() + path), method,
				new HttpEntity<>(body, headers), new ParameterizedTypeReference<Map<String, Object>>() { });
	}

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
			employee(st, RECODED, "01012910004", "+20", "employee");
			employee(st, SAUDI_HOLDER, "501234570", "+966", "employee");
			employee(st, EMIRATI_TWIN, "0501234570", "+971", "employee");
			employee(st, BRITISH, "07911123457", "+44", "employee");
			employee(st, EMIRATI, "0501234590", "+971", "employee");
			company(st, SAUDI_COMPANY, "Saudi Co", "501234590", "+966", null);
			company(st, RECODED_COMPANY, "Recoded Co", "01012911006", "+20", null);
			company(st, RECODED_TWIN_COMPANY, "Recoded Twin", "0501234571", "+971", null);
			company(st, 29108L, "Saudi Twin", "501234571", "+966", null);
			employee(st, SAUDI_DIGITS, "501234592", "+966", "employee");
			employee(st, EMIRATI_DIGITS, "0501234592", "+971", "employee");
			employee(st, MISCODED, "01012910010", "+966", "employee");
			employee(st, PADDED, "0501234595", "+966", "employee");
			// PHP's trim() reads through the NUL; so must every Java reader.
			st.execute("UPDATE employees SET country_code = CONCAT('+966', CHAR(0)) WHERE id = " + PADDED);
			company(st, SAUDI_DIGITS_COMPANY, "Saudi Digits", "501234593", "+966", null);
			company(st, EMIRATI_DIGITS_COMPANY, "Emirati Digits", "0501234593", "+971", null);
		}
	}

	private static void company(Statement st, long id, String name, String phone, String code) throws Exception {
		company(st, id, name, phone, "+20", code);
	}

	private static void company(Statement st, long id, String name, String phone, String countryCode, String code)
			throws Exception {
		st.execute("INSERT INTO companies (id, company_name, company_code, phone, country_code, password_hash,"
				+ " status, otp_verified, profile_completed, created_at) VALUES (" + id + ", '" + name + "', "
				+ (code == null ? "NULL" : "'" + code + "'") + ", '" + phone + "', '" + countryCode + "', '" + HASH
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
