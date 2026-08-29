package com.workin.legacy.auth.registration;

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
import com.workin.legacy.auth.otp.RecordingWhatsAppConfiguration;
import com.workin.legacy.auth.otp.RecordingWhatsAppSender;
import com.workin.legacy.auth.whatsapp.LegacyWhatsAppSender;

/**
 * Wave 13.1b: the nine account-lifecycle routes of {@code apis/api/auth/}.
 *
 * <p>The assertions concentrate on the places where two endpoints that look
 * interchangeable are not — the three different ways a company is found by
 * phone, the two different meanings of "join", and the three status codes the
 * two logins disagree about.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(RecordingWhatsAppConfiguration.class)
@ActiveProfiles("phase1-mysql")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyRegistrationEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String OPTIONS = "/apis/api/auth/get_company_registration_options.php";
	private static final String LOOKUP = "/apis/api/auth/lookup_company.php";
	private static final String CHECK_STATUS = "/apis/api/auth/check_status.php";
	private static final String REGISTER_COMPANY = "/apis/api/auth/register_company.php";
	private static final String REGISTER_EMPLOYEE = "/apis/api/auth/register_employee.php";
	private static final String JOIN = "/apis/api/auth/join_company.php";
	private static final String LOGIN_COMPANY = "/apis/api/auth/login_company.php";
	private static final String LOGIN_DESKTOP = "/apis/api/auth/login_desktop.php";

	private static final long ACTIVE_COMPANY = 33001L;
	private static final long PENDING_COMPANY = 33002L;
	private static final long UNVERIFIED_COMPANY = 33003L;
	private static final long SUSPENDED_COMPANY = 33004L;
	private static final long MID_ONBOARDING_COMPANY = 33006L;
	private static final long SECOND_MID_ONBOARDING = 33007L;
	private static final long HR = 330011L;
	private static final long STAFF = 330012L;
	private static final long BRANCH = 33011L;

	private static final String ACTIVE_PHONE = "01000033001";
	private static final String PENDING_PHONE = "01000033002";
	private static final String UNVERIFIED_PHONE = "01000033003";
	private static final String SUSPENDED_PHONE = "01000033004";
	private static final String HR_PHONE = "01000330011";
	private static final String STAFF_PHONE = "01000330012";
	private static final String CODE = "WORKIN01";
	private static final String PASSWORD = "secret123";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private LegacyWhatsAppSender whatsAppSender;

	@Autowired
	private PasswordEncoder passwordEncoder;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the registration fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		// LegacyFileUploads defaults to the relative path "uploads", which in a
		// Gradle run resolves under backend/ and leaves files in the worktree.
		registry.add("app.legacy-uploads.path", () -> UPLOAD_DIR.toString());
	}

	private static final java.nio.file.Path UPLOAD_DIR = createUploadDir();

	private static java.nio.file.Path createUploadDir() {
		try {
			java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("legacy-registration-uploads-");
			dir.toFile().deleteOnExit();
			return dir;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private RecordingWhatsAppSender recorder() {
		return (RecordingWhatsAppSender) whatsAppSender;
	}

	// ---------------- get_company_registration_options ----------------

	@Test
	@Order(1)
	@SuppressWarnings("unchecked")
	void theRegistrationOptionsAreThreeOrderedLists() {
		Map<String, Object> data = (Map<String, Object>) data(send(OPTIONS, HttpMethod.GET, null));
		assertThat(data).containsOnlyKeys("activities", "titles", "sizes");
		assertThat((List<Map<String, Object>>) data.get("sizes")).first()
				.satisfies(row -> assertThat(row).containsKeys("id", "name", "min_employees", "max_employees"));
		assertThat(send(OPTIONS, HttpMethod.POST, null).getStatusCode().value())
				.as("the only GET in the module")
				.isEqualTo(405);
	}

	// ---------------- lookup_company ----------------

	/** An invalid code never falls back to the id; only an *empty* one does. */
	@Test
	@Order(2)
	@SuppressWarnings("unchecked")
	void lookupFallsBackToTheIdOnlyWhenTheCodeIsEmpty() {
		Map<String, Object> byCode = (Map<String, Object>) data(
				post(LOOKUP, "{\"company_code\":\"" + CODE.toLowerCase(java.util.Locale.ROOT) + "\"}"));
		assertThat(byCode)
				.as("the lookup upper-cases, so a lower-case code still matches")
				.containsEntry("company_id", (int) ACTIVE_COMPANY)
				.containsEntry("company_name", "Active Co")
				.containsEntry("has_active_branch", true);

		assertThat(post(LOOKUP, "{\"company_code\":\"ab\",\"company_id\":" + ACTIVE_COMPANY + "}")
				.getStatusCode().value())
				.as("too short: company_code_invalid, not a fallback to the id")
				.isEqualTo(400);

		Map<String, Object> byId = (Map<String, Object>) data(
				post(LOOKUP, "{\"company_id\":" + PENDING_COMPANY + "}"));
		assertThat(byId).containsEntry("company_status", "pending")
				.as("a company with no branch")
				.containsEntry("has_active_branch", false);

		assertThat(post(LOOKUP, "{}").getStatusCode().value())
				.as("neither: the error names company_code")
				.isEqualTo(400);
		assertThat(post(LOOKUP, "{\"company_code\":\"NOSUCHCODE\"}").getStatusCode().value())
				.isEqualTo(404);
	}

	// ---------------- check_status ----------------

	/** All four outcomes are 200 -- it routes a screen, it does not guard. */
	@Test
	@Order(3)
	@SuppressWarnings("unchecked")
	void checkStatusAnswers200ForEveryOutcome() {
		Map<String, Object> notFound = (Map<String, Object>) data(
				post(CHECK_STATUS, "{\"phone\":\"01099999999\",\"company_id\":" + ACTIVE_COMPANY + "}"));
		assertThat(notFound).containsEntry("screen", "enter_company_code");

		Map<String, Object> home = (Map<String, Object>) data(
				post(CHECK_STATUS, "{\"phone\":\"" + HR_PHONE + "\",\"company_id\":" + ACTIVE_COMPANY + "}"));
		assertThat(home).containsEntry("screen", "home").containsEntry("role", "hr");

		Map<String, Object> deactivated = (Map<String, Object>) data(
				post(CHECK_STATUS, "{\"phone\":\"" + STAFF_PHONE + "\",\"company_id\":" + ACTIVE_COMPANY + "}"));
		assertThat(deactivated).containsEntry("screen", "enter_company_code").containsKey("message");
	}

	/** An exact phone match: a differently-formatted number is "not found". */
	@Test
	@Order(4)
	@SuppressWarnings("unchecked")
	void checkStatusMatchesThePhoneExactly() {
		Map<String, Object> data = (Map<String, Object>) data(
				post(CHECK_STATUS, "{\"phone\":\"+2" + HR_PHONE + "\",\"company_id\":" + ACTIVE_COMPANY + "}"));
		assertThat(data)
				.as("no phone_sql_match_clause here, unlike join_company")
				.containsEntry("screen", "enter_company_code");
	}

	// ---------------- register_company ----------------

	@Test
	@Order(4)
	@SuppressWarnings("unchecked")
	void registeringACompanyCreatesAPendingUnverifiedRowAndSendsAnOtp() {
		recorder().clear();
		ResponseEntity<Map<String, Object>> response = post(REGISTER_COMPANY,
				"{\"first_name\":\"Nadia\",\"last_name\":\"Owner\",\"phone\":\"01000033099\","
						+ "\"password\":\"" + PASSWORD + "\",\"country_code\":\"+20\"}");
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);

		Map<String, Object> company = (Map<String, Object>) ((Map<String, Object>)
				response.getBody().get("data")).get("company");
		assertThat(company)
				.containsEntry("company_name", null)
				.containsEntry("status", "pending")
				.containsEntry("otp_verified", 0)
				.containsEntry("profile_completed", 0)
				.containsEntry("first_name", "Nadia");
		assertThat(company).as("public_row() applies").doesNotContainKey("password_hash");
		assertThat(recorder().last().phone()).isEqualTo("01000033099");
	}

	/** A blank-after-trim name fails even though the raw body carries a value. */
	@Test
	@Order(5)
	void registerCompanyTrimsBeforeCheckingRequiredness() {
		assertThat(post(REGISTER_COMPANY,
				"{\"first_name\":\"   \",\"last_name\":\"X\",\"phone\":\"01000033098\",\"password\":\"p\"}")
				.getStatusCode().value()).isEqualTo(400);
	}

	/**
	 * {@code trim((string) ($body[COUNTRY_CODE] ?? '+20'))} -- {@code ??}
	 * treats an explicit null exactly like an absent key, so a body carrying
	 * {@code "country_code": null} registers with the {@code +20} default
	 * rather than being rejected for an empty country.
	 */
	@Test
	@Order(6)
	void anExplicitlyNullCountryCodeTakesTheDefault() {
		assertThat(post(REGISTER_COMPANY, "{\"first_name\":\"N\",\"last_name\":\"C\",\"phone\":"
				+ "\"01000033097\",\"password\":\"" + PASSWORD + "\",\"country_code\":null}")
				.getStatusCode().value())
				.as("null is not an empty country -- ?? falls through to +20")
				.isEqualTo(201);
		assertThat(scalar("SELECT country_code FROM companies WHERE phone = '01000033097'"))
				.isEqualTo("+20");
	}

	/** Its duplicate probe is an exact match, so a variant spelling is not caught. */
	@Test
	@Order(7)
	void registerCompanyDuplicateCheckIsExactSoAVariantSlipsThrough() {
		assertThat(post(REGISTER_COMPANY, "{\"first_name\":\"A\",\"last_name\":\"B\",\"phone\":\""
				+ ACTIVE_PHONE + "\",\"password\":\"p\"}").getStatusCode().value())
				.as("the same spelling is refused")
				.isEqualTo(400);

		recorder().clear();
		assertThat(post(REGISTER_COMPANY, "{\"first_name\":\"A\",\"last_name\":\"B\",\"phone\":\""
				+ ACTIVE_PHONE.substring(1) + "\",\"password\":\"p\"}").getStatusCode().value())
				.as("the same number without its leading zero is NOT caught -- the probe is exact,"
						+ " unlike join_company's variant-aware one")
				.isEqualTo(201);
	}

	// ---------------- register_employee vs join_company ----------------

	/**
	 * <b>{@code register_employee.php} cannot succeed against the frozen
	 * schema.</b> Its INSERT names only {@code (company_id, phone,
	 * password_hash, role)} and omits {@code branch_id}, which is
	 * {@code NOT NULL} with <b>no default</b>. Under legacy's non-strict
	 * {@code sql_mode=''} the column takes an implicit {@code 0}, and
	 * {@code fk_employee_branch} requires it to reference an existing
	 * {@code branches.id} -- which {@code 0} never is, because ids
	 * auto-increment from 1.
	 *
	 * <p>So every call is a foreign-key failure, and this is why
	 * {@code join_company.php} exists: it resolves the company's first active
	 * branch and writes it. Ported as-is (D-058) and recorded as R-017.
	 */
	@Test
	@Order(8)
	void registerEmployeeCannotSucceedAgainstTheFrozenSchema() throws Exception {
		long before = employeeCount(ACTIVE_COMPANY);
		assertThat(post(REGISTER_EMPLOYEE, "{\"phone\":\"01000033077\",\"password\":\"" + PASSWORD
				+ "\",\"company_code\":\"" + ACTIVE_PHONE + "\"}").getStatusCode().value())
				.as("branch_id defaults to 0 and the foreign key rejects it")
				.isEqualTo(500);
		assertThat(employeeCount(ACTIVE_COMPANY)).as("and nothing is written").isEqualTo(before);
	}

	/** Its earlier guards still run, so a bad company code is a clean 404. */
	@Test
	@Order(9)
	void registerEmployeeStillValidatesBeforeItFails() {
		assertThat(post(REGISTER_EMPLOYEE, "{\"phone\":\"01000033077\",\"password\":\"" + PASSWORD
				+ "\",\"company_code\":\"" + SUSPENDED_PHONE + "\"}").getStatusCode().value())
				.as("the company is found but is not active")
				.isEqualTo(404);
		assertThat(post(REGISTER_EMPLOYEE, "{\"phone\":\"01000033077\",\"password\":\"" + PASSWORD
				+ "\"}").getStatusCode().value()).isEqualTo(400);
	}

	/**
	 * {@code join_company.php} is the path that works: it resolves the first
	 * active branch, and creates a <b>pending, inactive</b> employee -- where
	 * {@code register_employee.php} would have created an immediately-accepted
	 * one, had it been able to insert at all.
	 */
	@Test
	@Order(10)
	@SuppressWarnings("unchecked")
	void joiningCreatesAPendingInactiveEmployeeOnTheFirstActiveBranch() {
		ResponseEntity<Map<String, Object>> joined = post(JOIN,
				"{\"first_name\":\"Joiner\",\"phone\":\"01000033078\",\"password\":\"" + PASSWORD
						+ "\",\"company_code\":\"" + CODE + "\",\"country_code\":\"+20\"}");
		assertThat(joined.getStatusCode().value()).as("%s", joined.getBody()).isEqualTo(201);
		Map<String, Object> payload = (Map<String, Object>) joined.getBody().get("data");
		assertThat(payload).containsKeys("token", "employee");
		Map<String, Object> employee = (Map<String, Object>) payload.get("employee");
		assertThat(employee)
				.containsEntry("join_request_status", "pending")
				.containsEntry("is_active", 0)
				.containsEntry("first_name", "Joiner")
				.containsEntry("branch_id", (int) BRANCH);
		assertThat(employee).doesNotContainKeys("password_hash", "token_version");
	}

	/** Joining notifies both the employee and the company. */
	@Test
	@Order(11)
	void joiningNotifiesBothSides() throws Exception {
		assertThat(notificationTypes(ACTIVE_COMPANY))
				.contains("join_request_submitted")
				.as("one to the employee and one to the company")
				.hasSizeGreaterThanOrEqualTo(2);
	}

	/**
	 * {@code resolve_employee_name_from_body()}'s two fallbacks -- splitting a
	 * single {@code name} field, and inventing {@code Pending-<phone>} -- are
	 * <b>unreachable from this endpoint</b>, because {@code required($body,
	 * [FIRST_NAME, ...])} rejects an absent or empty {@code first_name} before
	 * the resolver ever runs.
	 *
	 * <p>Worth pinning rather than assuming: the helper is shared, its
	 * fallbacks are real, and a future caller could reach them -- but a request
	 * that supplies only {@code name} is a 400 here, not a split.
	 */
	@Test
	@Order(12)
	void theFullNameFallbackIsUnreachableBecauseFirstNameIsRequired() {
		ResponseEntity<Map<String, Object>> response = post(JOIN,
				"{\"name\":\"Ana Maria de Souza\",\"phone\":\"01000033079\","
						+ "\"password\":\"" + PASSWORD + "\",\"company_code\":\"" + CODE + "\"}");
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().get("message").toString()).contains("first_name");

		// required() rejects "" but NOT "  " -- it is isset() && !== '', not a
		// trim. So a whitespace-only first_name passes the guard, and
		// resolve_employee_name_from_body() then trims it to empty and reaches
		// its "Pending-<phone>" fallback. The fallback is therefore REACHABLE
		// through this endpoint, by exactly one input shape.
		assertThat(post(JOIN, "{\"first_name\":\"  \",\"phone\":\"01000033079\",\"password\":\""
				+ PASSWORD + "\",\"company_code\":\"" + CODE + "\"}").getStatusCode().value())
				.isEqualTo(201);
		assertThat(storedFirstName("01000033079"))
				.as("the fallback name legacy invents, not an empty string")
				.isEqualTo("Pending-01000033079");
	}

	/**
	 * <b>R-019.</b> {@code join_company.php} resolves the dial code, validates
	 * the phone against it, and then leaves it out of the INSERT -- nine
	 * columns, and {@code country_code} is not one of them. So a non-Egyptian
	 * joiner's row stores NULL, and a later {@code forgot_password.php} falls
	 * back to {@code +20} and builds the wrong WhatsApp JID.
	 *
	 * <p>Legacy's defect, not the port's, so it is pinned rather than fixed:
	 * writing the column here would make a joined employee's row differ between
	 * the two systems on a column other endpoints read.
	 */
	@Test
	@Order(13)
	void aNonEgyptianJoinerHasNoCountryCodeStored() throws Exception {
		ResponseEntity<Map<String, Object>> joined = post(JOIN,
				"{\"first_name\":\"Saudi\",\"phone\":\"0512345678\",\"password\":\"" + PASSWORD
						+ "\",\"company_code\":\"" + CODE + "\",\"country_code\":\"+966\"}");
		assertThat(joined.getStatusCode().value()).as("%s", joined.getBody()).isEqualTo(201);

		assertThat(storedCountryCode("0512345678"))
				.as("validated against +966, then discarded")
				.isNull();
	}

	/**
	 * A rejected applicant passes {@code join_company.php}'s own duplicate probe
	 * -- which is company-scoped and treats {@code rejected} as absent -- and is
	 * then stopped by the <b>global</b> {@code UNIQUE KEY phone} on
	 * {@code employees}. So "a rejected applicant may re-apply" is true of the
	 * check and false of the outcome.
	 *
	 * <p>That is what the {@code try/catch} around the INSERT is for, and why
	 * the answer is <b>409 {@code phone_already_used_try_login}</b> rather than
	 * the 400 the probe would have given: PHP inspects the driver's message for
	 * the word "phone" to choose between two messages.
	 */
	@Test
	@Order(14)
	void aRejectedApplicantPassesTheProbeAndIsStoppedByTheUniqueIndex() throws Exception {
		ResponseEntity<Map<String, Object>> pending = post(JOIN,
				"{\"first_name\":\"Joiner\",\"phone\":\"01000033078\",\"password\":\""
						+ PASSWORD + "\",\"company_code\":\"" + CODE + "\"}");
		assertThat(pending.getStatusCode().value()).as("still pending: the probe refuses").isEqualTo(400);

		setJoinStatus("01000033078", "rejected");
		ResponseEntity<Map<String, Object>> rejected = post(JOIN,
				"{\"first_name\":\"Joiner\",\"phone\":\"01000033078\",\"password\":\""
						+ PASSWORD + "\",\"company_code\":\"" + CODE + "\"}");
		assertThat(rejected.getStatusCode().value())
				.as("the probe now lets it past, and the unique index does not")
				.isEqualTo(409);
		assertThat(rejected.getBody().get("message").toString()).contains("already");
	}

	/**
	 * The <b>cross-company</b> uniqueness branch, which needs a phone that is
	 * taken at a <em>different</em> company than the one being joined.
	 *
	 * <p>An earlier version used a phone belonging to the same company it was
	 * joining, so {@code joinRequestAlreadyExists()} answered 400 and the 409
	 * was never reached — the test asserted 400 while its name promised 409.
	 * The reviewer caught it; the fixture now seeds the other company.
	 *
	 * <p><b>What this can and cannot pin.</b> It pins the outcome: a phone held
	 * at another company cannot join, with 409
	 * {@code phone_already_used_try_login}. It does <b>not</b> isolate
	 * {@code employee_phone_exists_globally()} as the branch that produced it,
	 * and cannot — disabling that check leaves the test green, because
	 * {@code employees.phone} is <em>globally</em> UNIQUE, so the INSERT then
	 * fails and the duplicate-entry catch answers with the same status and the
	 * same message key. The explicit check and the index are redundant for this
	 * case and indistinguishable from outside. Established by disabling the
	 * check and watching the test pass, which is worth recording rather than
	 * leaving as an assumption about coverage.
	 */
	@Test
	@Order(15)
	void aPhoneAlreadyUsedAtAnotherCompanyIs409() throws Exception {
		seedForeignEmployee("01000033055");
		try {
			ResponseEntity<Map<String, Object>> response = post(JOIN,
					"{\"first_name\":\"X\",\"phone\":\"01000033055\",\"password\":\"" + PASSWORD
							+ "\",\"company_code\":\"" + CODE + "\"}");
			assertThat(response.getStatusCode().value())
					.as("employee_phone_exists_globally() -- a different company, so the "
							+ "company-scoped probe above it does not fire")
					.isEqualTo(409);
			assertThat(response.getBody().get("message").toString())
					.as("phone_already_used_try_login, not phone_registered_in_company")
					.contains("already registered");
		} finally {
			removeForeignEmployee();
		}
	}

	/**
	 * The company's <b>own</b> phone bypasses both global-uniqueness checks, so
	 * an owner may create an employee account for themselves.
	 */
	@Test
	@Order(16)
	void theCompanysOwnPhoneBypassesTheGlobalUniquenessChecks() {
		assertThat(post(JOIN, "{\"first_name\":\"Owner\",\"phone\":\"" + ACTIVE_PHONE
				+ "\",\"password\":\"" + PASSWORD + "\",\"company_code\":\"" + CODE + "\"}")
				.getStatusCode().value())
				.as("phones_are_equivalent() with the company's own number skips both 409s")
				.isEqualTo(201);
	}

	/** A company with no active branch cannot be joined. */
	@Test
	@Order(17)
	void acompanyWithNoActiveBranchCannotBeJoined() {
		assertThat(post(JOIN, "{\"first_name\":\"X\",\"phone\":\"01000033076\",\"password\":\"" + PASSWORD
				+ "\",\"company_code\":\"NOBRANCH1\"}").getStatusCode().value())
				.as("the company is active but has no active branch")
				.isEqualTo(400);
	}

	// ---------------- login_company vs login_desktop ----------------

	/** The two logins report an unknown phone and a wrong password differently. */
	@Test
	@Order(13)
	void theTwoLoginsDisagreeAboutWhatToTellTheCaller() {
		ResponseEntity<Map<String, Object>> mobileUnknown =
				post(LOGIN_COMPANY, "{\"phone\":\"01099999999\",\"password\":\"x\"}");
		assertThat(mobileUnknown.getStatusCode().value()).isEqualTo(401);
		assertThat(mobileUnknown.getBody().get("message").toString()).contains("Invalid phone or password");

		ResponseEntity<Map<String, Object>> desktopUnknown = post(LOGIN_DESKTOP,
				"{\"phone\":\"01099999999\",\"password\":\"x\",\"login_as\":\"company\"}");
		assertThat(desktopUnknown.getStatusCode().value()).isEqualTo(401);
		assertThat(desktopUnknown.getBody().get("message").toString())
				.as("desktop tells the caller the phone is unknown; mobile does not")
				.doesNotContain("Invalid phone or password");
	}

	/** An unverified company gets 200 with `otp_required`, and an OTP is sent. */
	@Test
	@Order(14)
	@SuppressWarnings("unchecked")
	void anUnverifiedCompanyIsToldToVerifyAndIsSentACode() {
		recorder().clear();
		Map<String, Object> data = (Map<String, Object>) data(
				post(LOGIN_COMPANY, "{\"phone\":\"" + UNVERIFIED_PHONE + "\",\"password\":\"" + PASSWORD + "\"}"));
		assertThat(data).containsEntry("otp_required", true).containsKey("company");
		assertThat(data).as("no token is issued").doesNotContainKey("token");
		assertThat(recorder().last().phone()).isEqualTo(UNVERIFIED_PHONE);

		// The second attempt does not re-send: hasRecentForPhone() suppresses it.
		recorder().clear();
		post(LOGIN_COMPANY, "{\"phone\":\"" + UNVERIFIED_PHONE + "\",\"password\":\"" + PASSWORD + "\"}");
		assertThat(recorder().sent()).isEmpty();
	}

	/** A pending company *can* log in; a suspended one cannot. */
	@Test
	@Order(15)
	@SuppressWarnings("unchecked")
	void aPendingCompanyLogsInButASuspendedOneDoesNot() {
		Map<String, Object> data = (Map<String, Object>) data(
				post(LOGIN_COMPANY, "{\"phone\":\"" + PENDING_PHONE + "\",\"password\":\"" + PASSWORD + "\"}"));
		assertThat(data).containsKey("token");

		assertThat(post(LOGIN_COMPANY,
				"{\"phone\":\"" + SUSPENDED_PHONE + "\",\"password\":\"" + PASSWORD + "\"}")
				.getStatusCode().value()).isEqualTo(403);
	}

	/** Desktop's HR branch admits HR only, and attaches the permission object. */
	@Test
	@Order(16)
	@SuppressWarnings("unchecked")
	void theDesktopHrBranchAdmitsHrOnly() {
		Map<String, Object> data = (Map<String, Object>) data(post(LOGIN_DESKTOP,
				"{\"phone\":\"" + HR_PHONE + "\",\"password\":\"" + PASSWORD + "\",\"login_as\":\"hr\"}"));
		assertThat(data).containsKeys("token", "company", "employee");
		Map<String, Object> employee = (Map<String, Object>) data.get("employee");
		assertThat((Map<String, Object>) employee.get("permissions")).containsEntry("can_employees", 1);
		assertThat(employee).doesNotContainKey("can_employees");

		assertThat(post(LOGIN_DESKTOP, "{\"phone\":\"" + ACTIVE_PHONE + "\",\"password\":\"" + PASSWORD
				+ "\",\"login_as\":\"hr\"}").getStatusCode().value())
				.as("a company admin's phone is not an HR employee")
				.isEqualTo(401);
	}

	/** `login_as` accepts five spellings and rejects everything else by name. */
	@Test
	@Order(17)
	void loginAsAcceptsFiveSpellings() {
		ResponseEntity<Map<String, Object>> bad = post(LOGIN_DESKTOP,
				"{\"phone\":\"" + ACTIVE_PHONE + "\",\"password\":\"" + PASSWORD + "\",\"login_as\":\"admin\"}");
		assertThat(bad.getStatusCode().value()).isEqualTo(400);
		assertThat(bad.getBody().get("message").toString()).contains("login_as");

		assertThat(post(LOGIN_DESKTOP, "{\"phone\":\"" + ACTIVE_PHONE + "\",\"password\":\"" + PASSWORD
				+ "\",\"login_as\":\"  COMPANY_ADMIN  \"}").getStatusCode().value())
				.as("trimmed and lower-cased before matching")
				.isEqualTo(200);
	}

	/** Desktop logs the company in and writes the two onboarding notifications once. */
	@Test
	@Order(18)
	void desktopCompanyLoginEnsuresOnboardingExactlyOnce() throws Exception {
		long before = notificationCount(PENDING_COMPANY, "company_welcome");
		post(LOGIN_DESKTOP, "{\"phone\":\"" + PENDING_PHONE + "\",\"password\":\"" + PASSWORD
				+ "\",\"login_as\":\"company\"}");
		post(LOGIN_DESKTOP, "{\"phone\":\"" + PENDING_PHONE + "\",\"password\":\"" + PASSWORD
				+ "\",\"login_as\":\"company\"}");
		assertThat(notificationCount(PENDING_COMPANY, "company_welcome"))
				.as("idempotent by query, so two logins write one welcome")
				.isEqualTo(before + 1);
		assertThat(notificationCount(PENDING_COMPANY, "company_pending_review")).isEqualTo(1);
	}

	/** `login_company.php` does NOT write onboarding notifications; only desktop does. */
	@Test
	@Order(19)
	void mobileCompanyLoginDoesNotWriteOnboardingNotifications() throws Exception {
		long before = notificationCount(ACTIVE_COMPANY, "company_welcome");
		post(LOGIN_COMPANY, "{\"phone\":\"" + ACTIVE_PHONE + "\",\"password\":\"" + PASSWORD + "\"}");
		assertThat(notificationCount(ACTIVE_COMPANY, "company_welcome")).isEqualTo(before);
	}

	// ---------------- complete_company_registration ----------------

	/**
	 * <b>R-016.</b> The endpoint is unauthenticated, takes {@code company_id}
	 * from {@code $_POST}, and returns a <b>company-admin token for it</b>. This
	 * test is the proof: it registers no session, presents no credential, names
	 * a company it does not own, and is handed a token that the rest of the API
	 * accepts.
	 *
	 * <p>Asserted rather than merely documented, so that anyone who later
	 * removes the token has to change a test that says why it is there.
	 */
	@Test
	@Order(20)
	@SuppressWarnings("unchecked")
	void completingARegistrationHandsAnAnonymousCallerACompanyAdminToken() throws Exception {
		ResponseEntity<Map<String, Object>> response = completeRegistration(MID_ONBOARDING_COMPANY, "");
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);

		Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
		assertThat(data).containsKeys("company", "token");
		assertThat((String) data.get("token")).as("a real token, not a placeholder").isNotBlank();

		Map<String, Object> company = (Map<String, Object>) data.get("company");
		assertThat(company)
				.containsEntry("company_name", "Taken Over Ltd")
				.containsEntry("profile_completed", 1);
		assertThat(company).doesNotContainKey("password_hash");

		// The token works: the company profile endpoint accepts it.
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth((String) data.get("token"));
		ResponseEntity<Map<String, Object>> profile = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/apis/api/profile/company.php"), HttpMethod.GET,
				new HttpEntity<>(null, headers), new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(profile.getStatusCode().value())
				.as("the token minted from a caller-supplied id is a working session")
				.isEqualTo(200);

		assertThat(branchCount(MID_ONBOARDING_COMPANY))
				.as("a main branch is created because the company had none")
				.isEqualTo(1);
	}

	/**
	 * Its two state gates, the fact that they run after the scalar checks --
	 * and that <b>nothing is written to disk</b> until all of them pass.
	 *
	 * <p>PHP reaches {@code uploadFile()} only after the company row and both
	 * state gates, so a request rejected above them stores no file. Java
	 * evaluates arguments eagerly, so an earlier draft that passed the stored
	 * URLs as constructor arguments wrote both files for every one of these
	 * four rejections -- a divergence from the ported order, and a way for an
	 * unauthenticated caller to accumulate orphaned files. The file count is
	 * asserted, not just the status.
	 */
	@Test
	@Order(21)
	void completeRegistrationChecksItsScalarsBeforeTheCompanyAndWritesNoFileUntilTheyPass()
			throws Exception {
		long before = uploadedFileCount();

		assertThat(completeRegistration(0L, "").getStatusCode().value())
				.as("company_id is checked first, before the row is even fetched")
				.isEqualTo(400);
		assertThat(completeRegistration(9_999_999L, "").getStatusCode().value()).isEqualTo(404);
		assertThat(completeRegistration(UNVERIFIED_COMPANY, "").getStatusCode().value())
				.as("otp_verified = 0")
				.isEqualTo(403);
		assertThat(completeRegistration(ACTIVE_COMPANY, "").getStatusCode().value())
				.as("profile_completed = 1 already")
				.isEqualTo(400);

		assertThat(uploadedFileCount())
				.as("four rejections above the uploads, and not one byte written")
				.isEqualTo(before);
	}

	/**
	 * <b>Both</b> uploads run before <b>either</b> is checked, so a request
	 * that passes every gate above them and supplies only the commercial
	 * register stores that file and then answers {@code no_file_uploaded} for
	 * the missing logo.
	 *
	 * <p>That is PHP's own sequence — two `uploadFile()` statements, then two
	 * `if (!$url)` checks — and it is the reason a review finding asking to
	 * check the logo *between* them was declined: it would have made the port
	 * store one fewer file than legacy for this input. The orphan is real and
	 * legacy's; this test is what keeps the ordering from being "tidied" on the
	 * strength of that reasoning a second time.
	 */
	@Test
	@Order(23)
	void bothUploadsRunBeforeEitherIsChecked() throws Exception {
		long before = uploadedFileCount();
		ResponseEntity<Map<String, Object>> response =
				completeRegistrationWithoutLogo(SECOND_MID_ONBOARDING);
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().get("message").toString()).contains("file");
		assertThat(uploadedFileCount())
				.as("the commercial register was stored before the logo check ran")
				.isEqualTo(before + 1);
	}

	/** An optional code must still be valid and unique when supplied. */
	@Test
	@Order(24)
	void completeRegistrationValidatesAnOptionalCode() {
		assertThat(completeRegistration(SECOND_MID_ONBOARDING, "ab").getStatusCode().value())
				.as("too short")
				.isEqualTo(400);
		assertThat(completeRegistration(SECOND_MID_ONBOARDING, CODE).getStatusCode().value())
				.as("already taken by the active company")
				.isEqualTo(400);
		assertThat(completeRegistration(SECOND_MID_ONBOARDING, "FRESHCODE1").getStatusCode().value())
				.isEqualTo(201);
	}

	/** The same request with no {@code logo} part at all. */
	private ResponseEntity<Map<String, Object>> completeRegistrationWithoutLogo(long companyId) {
		org.springframework.util.MultiValueMap<String, Object> form = completeRegistrationForm(companyId, "");
		form.remove("logo");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/apis/api/auth/complete_company_registration.php"),
				HttpMethod.POST, new HttpEntity<>(form, headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private ResponseEntity<Map<String, Object>> completeRegistration(long companyId, String code) {
		org.springframework.util.MultiValueMap<String, Object> form =
				completeRegistrationForm(companyId, code);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/apis/api/auth/complete_company_registration.php"),
				HttpMethod.POST, new HttpEntity<>(form, headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private org.springframework.util.MultiValueMap<String, Object> completeRegistrationForm(
			long companyId, String code) {
		org.springframework.util.MultiValueMap<String, Object> form =
				new org.springframework.util.LinkedMultiValueMap<>();
		form.add("company_id", String.valueOf(companyId));
		form.add("company_name", "Taken Over Ltd");
		form.add("main_branch_address", "1 Anywhere St");
		form.add("company_title_id", "33061");
		form.add("company_activity_id", "33051");
		form.add("company_size_id", "33071");
		if (!code.isEmpty()) {
			form.add("company_code", code);
		}
		form.add("logo", pngPart("logo.png"));
		form.add("commercial_reg", pngPart("reg.png"));
		return form;
	}

	/** Every file under the temp upload root, both subdirectories included. */
	private static long uploadedFileCount() throws Exception {
		if (!java.nio.file.Files.exists(UPLOAD_DIR)) {
			return 0;
		}
		try (var paths = java.nio.file.Files.walk(UPLOAD_DIR)) {
			return paths.filter(java.nio.file.Files::isRegularFile).count();
		}
	}

	/** A real PNG signature -- the uploader sniffs bytes, not the declared type. */
	private static org.springframework.core.io.Resource pngPart(String filename) {
		byte[] png = new byte[] {
			(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
			0, 0, 0, 13, 'I', 'H', 'D', 'R' };
		return new org.springframework.core.io.ByteArrayResource(png) {
			@Override
			public String getFilename() {
				return filename;
			}
		};
	}

	// ---------------- method guards ----------------

	@Test
	@Order(20)
	void everyPostRouteRejectsAGet() {
		for (String route : List.of(LOOKUP, CHECK_STATUS, REGISTER_COMPANY, REGISTER_EMPLOYEE,
				JOIN, LOGIN_COMPANY, LOGIN_DESKTOP,
				"/apis/api/auth/complete_company_registration.php")) {
			assertThat(send(route, HttpMethod.GET, null).getStatusCode().value())
					.as("%s", route)
					.isEqualTo(405);
		}
	}

	// ---------------- fixture ----------------

	private static Object data(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		assertThat(response.getBody()).containsEntry("success", true);
		return response.getBody().get("data");
	}

	private Object data(ResponseEntity<Map<String, Object>> response, int expectedStatus) {
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(expectedStatus);
		return response.getBody().get("data");
	}

	private ResponseEntity<Map<String, Object>> post(String path, String body) {
		return send(path, HttpMethod.POST, body);
	}

	private Object postExpecting(String path, String body, int expectedStatus) {
		return data(send(path, HttpMethod.POST, body), expectedStatus);
	}

	private ResponseEntity<Map<String, Object>> send(String path, HttpMethod method, String body) {
		HttpHeaders headers = new HttpHeaders();
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method,
				new HttpEntity<>(body, headers), new ParameterizedTypeReference<Map<String, Object>>() { });
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

	private static String joinStatus(long employeeId) {
		return scalar("SELECT join_request_status FROM employees WHERE id = " + employeeId);
	}

	private static String storedFirstName(String phone) {
		return scalar("SELECT first_name FROM employees WHERE phone = '" + phone + "'");
	}

	/** An employee at a company other than the one being joined. */
	private static void seedForeignEmployee(String phone) {
		execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, country_code, password_hash, role, is_active, token_version,"
				+ " join_request_status, created_at) VALUES (330055, " + PENDING_COMPANY + ", " + BRANCH
				+ ", '330055', 'F', 'L', '" + phone + "', '+20', 'x', 'employee', 1, 1, 'accepted',"
				+ " '2019-04-01 08:00:00')");
	}

	private static void removeForeignEmployee() {
		execute("DELETE FROM employees WHERE id = 330055");
	}

	private static String storedCountryCode(String phone) {
		return scalar("SELECT country_code FROM employees WHERE phone = '" + phone + "'");
	}

	private static long employeeCount(long companyId) {
		return Long.parseLong(scalar("SELECT COUNT(*) FROM employees WHERE company_id = " + companyId));
	}

	private static long isActive(long employeeId) {
		return Long.parseLong(scalar("SELECT is_active FROM employees WHERE id = " + employeeId));
	}

	private static void setJoinStatus(String phone, String status) {
		execute("UPDATE employees SET join_request_status = '" + status + "' WHERE phone = '" + phone + "'");
	}

	private static long notificationCount(long companyId, String type) {
		return Long.parseLong(scalar("SELECT COUNT(*) FROM notifications WHERE company_id = " + companyId
				+ " AND notification_type = '" + type + "'"));
	}

	private static List<String> notificationTypes(long companyId) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT notification_type FROM notifications WHERE company_id = " + companyId)) {
			List<String> types = new java.util.ArrayList<>();
			while (rs.next()) {
				types.add(rs.getString(1));
			}
			return types;
		}
	}

	private static void seed() throws Exception {
		String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("SET time_zone = '" + LegacyRuntimeOffset.DEFAULT + "'");
			// phone_countries is empty in the frozen dump but seeded in production.
			// Without a +966 row, phone_country_resolve_code() falls back to +20
			// and the non-default-country case cannot be exercised at all.
			st.execute("INSERT INTO phone_countries (id, country_code, name_ar, name_en, phone_length,"
					+ " phone_prefixes, is_active, sort_order) VALUES"
					+ " (33081, '+20', 'مصر', 'Egypt', 11, '[\"010\",\"011\",\"012\",\"015\"]', 1, 1),"
					+ " (33082, '+966', 'السعودية', 'Saudi Arabia', 10, '[\"05\"]', 1, 2)");
			st.execute("INSERT INTO company_activities (id, name) VALUES (33051, 'Software')");
			st.execute("INSERT INTO company_titles (id, name) VALUES (33061, 'LLC')");
			st.execute("INSERT INTO company_sizes (id, name, min_employees, max_employees) VALUES"
					+ " (33071, 'Small', 1, 50)");

			company(st, ACTIVE_COMPANY, "Active Co", ACTIVE_PHONE, CODE, "active", 1, 1, hash);
			company(st, PENDING_COMPANY, "Pending Co", PENDING_PHONE, "PENDING01", "pending", 1, 1, hash);
			company(st, UNVERIFIED_COMPANY, "Unverified Co", UNVERIFIED_PHONE, "UNVER01", "pending", 0, 1, hash);
			company(st, SUSPENDED_COMPANY, "Suspended Co", SUSPENDED_PHONE, "SUSP01", "suspended", 1, 1, hash);
			// Active, verified, profile complete -- but with no active branch.
			company(st, 33005L, "No Branch Co", "01000033005", "NOBRANCH1", "active", 1, 1, hash);
			// The R-016 window: OTP verified, profile not completed.
			company(st, MID_ONBOARDING_COMPANY, null, "01000033006", null, "pending", 1, 0, hash);
			company(st, SECOND_MID_ONBOARDING, null, "01000033007", null, "pending", 1, 0, hash);

			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES ("
					+ BRANCH + ", " + ACTIVE_COMPANY + ", 'Main', 1, '2019-03-01 10:00:00')");

			employee(st, HR, HR_PHONE, "hr", 1, hash);
			employee(st, STAFF, STAFF_PHONE, "employee", 0, hash);
			st.execute("INSERT INTO hr_permissions (employee_id, can_employees) VALUES (" + HR + ", 1)");
		}
	}

	private static void company(
			Statement st, long id, String name, String phone, String code, String status,
			int otpVerified, int profileCompleted, String hash) throws Exception {
		st.execute("INSERT INTO companies (id, company_name, company_code, phone, country_code,"
				+ " password_hash, status, otp_verified, profile_completed, created_at) VALUES ("
				+ id + ", " + quoted(name) + ", " + quoted(code) + ", '" + phone + "', '+20', '" + hash
				+ "', '" + status + "', " + otpVerified + ", " + profileCompleted
				+ ", '2019-01-15 09:00:00')");
	}

	private static String quoted(String value) {
		return value == null ? "NULL" : "'" + value + "'";
	}

	private static long branchCount(long companyId) {
		return Long.parseLong(scalar("SELECT COUNT(*) FROM branches WHERE company_id = " + companyId));
	}

	private static void employee(
			Statement st, long id, String phone, String role, int active, String hash) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, country_code, password_hash, role, is_active, token_version,"
				+ " join_request_status, created_at) VALUES (" + id + ", " + ACTIVE_COMPANY + ", " + BRANCH
				+ ", '" + id + "', 'F', 'L', '" + phone + "', '+20', '" + hash + "', '" + role + "', "
				+ active + ", 1, 'accepted', '2019-04-01 08:00:00')");
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
		try (InputStream in =
				LegacyRegistrationEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
