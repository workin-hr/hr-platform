package com.workin.backend.platformadmin.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyMariaDb;

/**
 * {@code /admin/settings} over real HTTP against a real MariaDB.
 *
 * <p>Three tabs over three platform-level tables, so there is no tenant rule
 * to test. What there is instead is a set of integrity rules that are cheap to
 * lose in a port: a content key that must be on an allowlist, an option value
 * that must stop changing once companies depend on it, an option that must not
 * be deleted while they do, and a definition key that no edit path writes.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminSettingsEndToEndTest {

	/** A database of this class's own, inside the shared container. */
	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF =
			Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	private static final String PATH = "/admin/settings";

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.platform-admin.password", () -> PASSWORD);
		registry.add("app.platform-admin.actions.enabled", () -> "true");
	}

	@Autowired
	private TestRestTemplate restTemplate;


	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private javax.sql.DataSource legacyDataSource;

	private JdbcTemplate jdbc;

	private String cookie;

	private long companyA;

	private long companyB;

	private long branchA;

	private long branchB;

	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		// Children first: employees cascade from several tables, and a leftover
		// row blocks the company delete rather than failing loudly.
		this.jdbc.update("DELETE FROM company_setting_values");
		this.jdbc.update("DELETE FROM company_settings");
		this.jdbc.update("DELETE FROM setting_allowed_values");
		this.jdbc.update("DELETE FROM setting_definitions");
		this.jdbc.update("DELETE FROM app_content");
		this.jdbc.update("DELETE FROM configs");
		this.jdbc.update("DELETE FROM attendance");
		this.jdbc.update("DELETE FROM employees");
		this.jdbc.update("DELETE FROM branches");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2101" + System.nanoTime() % 100_000_000L;
		// One administrator, one password (ADR-0018): the bootstrap provisioned
		// the row from the configured password when the context started.
		long adminId = this.jdbc.queryForObject(
				"SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
		Page login = page("/admin/login", null);
		this.cookie = cookieOf(post("/admin/login", login.cookie(), login.csrf(), "password", PASSWORD));

		this.companyA = createCompany("Alpha Co");
		this.companyB = createCompany("Beta Co");
		this.branchA = createBranch(this.companyA, "Alpha HQ");
		this.branchB = createBranch(this.companyB, "Beta HQ");
	}

	/**
	 * {@code employees.branch_id} is NOT NULL with a foreign key, so an employee
	 * cannot exist without a real branch -- the constraint R-055 records.
	 */
	private long createBranch(long companyId, String name) {
		this.jdbc.update("INSERT INTO branches (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, 1, NOW())", companyId, name);
		return this.jdbc.queryForObject(
				"SELECT id FROM branches WHERE company_id = ? AND name = ?",
				Long.class, companyId, name);
	}

	private long createEmployee(long companyId, String name, String phone) {
		long branchId = companyId == this.companyA ? this.branchA : this.branchB;
		this.jdbc.update("INSERT INTO employees (company_id, branch_id, first_name, last_name,"
				+ " phone, password_hash, role, join_request_status, is_active, created_at)"
				+ " VALUES (?, ?, ?, '', ?, ?, 'employee', 'accepted', 1, NOW())",
				companyId, branchId, name, phone, this.passwordEncoder.encode(PASSWORD));
		return this.jdbc.queryForObject(
				"SELECT id FROM employees WHERE phone = ?", Long.class, phone);
	}

	private void punch(long employeeId, String checkIn, String checkOut) {
		this.jdbc.update("INSERT INTO attendance (employee_id, check_in, check_out, method)"
				+ " VALUES (?, ?, ?, 'app')", employeeId, checkIn, checkOut);
	}

	private long createRequestType(long companyId, String name) {
		this.jdbc.update("INSERT INTO request_types (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, 1, NOW())", companyId, name);
		return this.jdbc.queryForObject(
				"SELECT id FROM request_types WHERE company_id = ? AND name = ?",
				Long.class, companyId, name);
	}

	/**
	 * {@code requests.request_type_id} is NOT NULL behind a RESTRICT foreign
	 * key, so every request has a type and the page's LEFT JOIN can never miss.
	 * A type is created on demand rather than passed as null.
	 */
	private void request(long employeeId, String createdAt, String status) {
		request(employeeId, createdAt, status, defaultType());
	}

	private Long cachedType;

	private long defaultType() {
		if (this.cachedType == null) {
			this.cachedType = createRequestType(this.companyA, "Leave");
		}
		return this.cachedType;
	}

	private void request(long employeeId, String createdAt, String status, Long typeId) {
		this.jdbc.update("INSERT INTO requests (employee_id, request_type_id, status, created_at)"
				+ " VALUES (?, ?, ?, ?)", employeeId, typeId, status, createdAt);
	}

	private long createDefinition(String key, String labelAr, String labelEn) {
		this.jdbc.update("INSERT INTO setting_definitions (setting_key, label_ar, label_en,"
				+ " is_multi, is_required, sort_order) VALUES (?, ?, ?, 0, 0, 0)",
				key, labelAr, labelEn);
		return this.jdbc.queryForObject(
				"SELECT id FROM setting_definitions WHERE setting_key = ?", Long.class, key);
	}

	private long createOption(long definitionId, String value) {
		this.jdbc.update("INSERT INTO setting_allowed_values (setting_definition_id, value,"
				+ " label_ar, label_en, sort_order) VALUES (?, ?, NULL, NULL, 0)",
				definitionId, value);
		return optionId(definitionId, value);
	}

	private Long optionId(long definitionId, String value) {
		List<Long> ids = this.jdbc.queryForList(
				"SELECT id FROM setting_allowed_values WHERE setting_definition_id = ?"
						+ " AND value = ?", Long.class, definitionId, value);
		return ids.isEmpty() ? null : ids.get(0);
	}

	/** Makes one company depend on an option, which is what pins and protects it. */
	private void useOption(long optionId) {
		useOption(optionId, this.companyA);
	}

	private void useOption(long optionId, long companyId) {
		Long definitionId = this.jdbc.queryForObject(
				"SELECT setting_definition_id FROM setting_allowed_values WHERE id = ?",
				Long.class, optionId);
		List<Long> existing = this.jdbc.queryForList(
				"SELECT id FROM company_settings WHERE company_id = ? AND setting_definition_id = ?",
				Long.class, companyId, definitionId);
		long companySetting;
		if (existing.isEmpty()) {
			this.jdbc.update("INSERT INTO company_settings (company_id, setting_definition_id)"
					+ " VALUES (?, ?)", companyId, definitionId);
			companySetting = this.jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		}
		else {
			companySetting = existing.get(0);
		}
		this.jdbc.update("INSERT INTO company_setting_values (company_setting_id,"
				+ " setting_allowed_value_id) VALUES (?, ?)", companySetting, optionId);
	}

	private int countOptions(long definitionId) {
		return this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM setting_allowed_values WHERE setting_definition_id = ?",
				Integer.class, definitionId);
	}

	private String optionField(long id, String column) {
		return this.jdbc.queryForObject(
				"SELECT " + column + " FROM setting_allowed_values WHERE id = ?", String.class, id);
	}

	private String definitionField(long id, String column) {
		return this.jdbc.queryForObject(
				"SELECT " + column + " FROM setting_definitions WHERE id = ?", String.class, id);
	}

	private String contentValue(String key, String column) {
		return this.jdbc.queryForObject(
				"SELECT " + column + " FROM app_content WHERE content_key = ?", String.class, key);
	}

	private String configValue(String key) {
		return this.jdbc.queryForObject(
				"SELECT config_value FROM configs WHERE config_key = ?", String.class, key);
	}

	private long createCompany(String name) {
		String phone = "01" + System.nanoTime() % 1_000_000_000L;
		this.jdbc.update("INSERT INTO companies (company_name, phone, password_hash, status,"
				+ " otp_verified, profile_completed, created_at)"
				+ " VALUES (?, ?, ?, 'active', 1, 1, NOW())",
				name, phone, this.passwordEncoder.encode(PASSWORD));
		return this.jdbc.queryForObject(
				"SELECT id FROM companies WHERE phone = ?", Long.class, phone);
	}

	/** An employees row in the state a join request is: role employee, a status. */
	private long createJoinRequest(long companyId, String name, String phone, String status) {
		long branchId = companyId == this.companyA ? this.branchA : this.branchB;
		this.jdbc.update("INSERT INTO employees (company_id, branch_id, first_name, last_name,"
				+ " phone, password_hash, role, join_request_status, is_active, created_at)"
				+ " VALUES (?, ?, ?, '', ?, ?, 'employee', ?, 1, NOW())",
				companyId, branchId, name, phone,
				this.passwordEncoder.encode(PASSWORD), status);
		return this.jdbc.queryForObject(
				"SELECT id FROM employees WHERE phone = ?", Long.class, phone);
	}

	private String statusOf(long id) {
		return this.jdbc.queryForObject(
				"SELECT join_request_status FROM employees WHERE id = ?", String.class, id);
	}

	private boolean exists(long id) {
		return this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE id = ?", Integer.class, id) > 0;
	}

	private int auditCount() {
		return this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_admin_audit_events", Integer.class);
	}

	// ------------------------------------------------------------ content tab

	@Test
	void allThreeDocumentsRenderEvenWhenNoneHasEverBeenSaved() {
		String html = body(PATH + "&tab=app_content");
		assertThat(html).contains("compliance", "how_to_use", "terms_and_conditions");
		assertThat(html).contains("Compliance policy", "How to use", "Terms &amp; conditions");
	}

	@Test
	void savingADocumentStoresBothLanguages() {
		postForm("action", "save_content", "content_key", "compliance",
				"content_value_ar", "نص عربي", "content_value_en", "English text");
		assertThat(contentValue("compliance", "content_value_ar")).isEqualTo("نص عربي");
		assertThat(contentValue("compliance", "content_value_en")).isEqualTo("English text");
	}

	@Test
	void savingTwiceUpdatesRatherThanDuplicating() {
		postForm("action", "save_content", "content_key", "how_to_use",
				"content_value_ar", "أول", "content_value_en", "first");
		postForm("action", "save_content", "content_key", "how_to_use",
				"content_value_ar", "ثاني", "content_value_en", "second");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM app_content WHERE content_key = 'how_to_use'",
				Integer.class)).isEqualTo(1);
		assertThat(contentValue("how_to_use", "content_value_en")).isEqualTo("second");
	}

	@Test
	void aContentKeyOutsideTheAllowlistIsRefused() {
		// The allowlist is the only thing stopping a posted field creating an
		// arbitrary content row.
		int before = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM app_content", Integer.class);
		postForm("action", "save_content", "content_key", "arbitrary_key",
				"content_value_ar", "x", "content_value_en", "x");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM app_content", Integer.class))
				.isEqualTo(before);
	}

	@Test
	void savingADocumentReturnsToItsOwnSection() {
		ResponseEntity<String> response = post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "save_content", "content_key", "terms_and_conditions",
				"content_value_ar", "a", "content_value_en", "b");
		assertThat(response.getHeaders().getLocation().toString())
				.contains("tab=app_content").contains("section=terms_and_conditions");
	}

	// ---------------------------------------------------------- templates tab

	@Test
	void aDefinitionCanBeRelabelledButNotReKeyed() {
		long id = createDefinition("leave_kind", "إجازة", "Leave kind");
		postForm("action", "edit_definition", "id", String.valueOf(id),
				"label_ar", "نوع الإجازة", "label_en", "Leave type",
				"description_ar", "", "description_en", "desc", "sort_order", "5");

		assertThat(definitionField(id, "label_en")).isEqualTo("Leave type");
		assertThat(definitionField(id, "description_en")).isEqualTo("desc");
		assertThat(definitionField(id, "description_ar")).as("blank stores as NULL").isNull();
		assertThat(definitionField(id, "setting_key"))
				.as("the key is the identity and no edit path writes it")
				.isEqualTo("leave_kind");
	}

	@Test
	void aDefinitionWithABlankLabelIsRefused() {
		long id = createDefinition("k1", "ع", "E");
		postForm("action", "edit_definition", "id", String.valueOf(id),
				"label_ar", "  ", "label_en", "Still here", "sort_order", "0");
		assertThat(definitionField(id, "label_en")).isEqualTo("E");
	}

	@Test
	void anOptionIsAddedAndItsBlankLabelsStoreAsNull() {
		long definition = createDefinition("k2", "ع", "E");
		postForm("action", "add_option", "setting_definition_id", String.valueOf(definition),
				"value", "annual", "label_ar", "", "label_en", "Annual", "sort_order", "3");

		Long option = optionId(definition, "annual");
		assertThat(option).isNotNull();
		assertThat(optionField(option, "label_ar")).isNull();
		assertThat(optionField(option, "label_en")).isEqualTo("Annual");
	}

	@Test
	void aDuplicateValueWithinTheSameDefinitionIsRefused() {
		long definition = createDefinition("k3", "ع", "E");
		createOption(definition, "annual");
		postForm("action", "add_option", "setting_definition_id", String.valueOf(definition),
				"value", "annual", "label_en", "Second", "sort_order", "0");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM setting_allowed_values WHERE setting_definition_id = ?"
						+ " AND value = 'annual'", Integer.class, definition)).isEqualTo(1);
	}

	@Test
	void theSameValueUnderADifferentDefinitionIsAllowed() {
		// The uniqueness is per definition, not global.
		long first = createDefinition("k4", "ع", "E");
		long second = createDefinition("k5", "ع", "E");
		createOption(first, "annual");
		postForm("action", "add_option", "setting_definition_id", String.valueOf(second),
				"value", "annual", "label_en", "Annual", "sort_order", "0");
		assertThat(optionId(second, "annual")).isNotNull();
	}

	@Test
	void aValueLongerThanOneHundredAndTwentyCharactersIsRefused() {
		long definition = createDefinition("k6", "ع", "E");
		postForm("action", "add_option", "setting_definition_id", String.valueOf(definition),
				"value", "x".repeat(121), "label_en", "Too long", "sort_order", "0");
		assertThat(countOptions(definition)).isZero();

		postForm("action", "add_option", "setting_definition_id", String.valueOf(definition),
				"value", "x".repeat(120), "label_en", "Just fits", "sort_order", "0");
		assertThat(countOptions(definition)).isEqualTo(1);
	}

	@Test
	void anOptionInUseKeepsItsValueButStaysRelabellable() {
		// The rule that matters most here: companies are keyed on the stored
		// value, so an edit may rename the option and never re-code it.
		long definition = createDefinition("k7", "ع", "E");
		long option = createOption(definition, "annual");
		useOption(option);

		postForm("action", "edit_option", "id", String.valueOf(option),
				"value", "changed", "label_en", "Renamed", "sort_order", "9");

		assertThat(optionField(option, "value")).as("pinned by the company using it")
				.isEqualTo("annual");
		assertThat(optionField(option, "label_en")).as("labels stay editable")
				.isEqualTo("Renamed");
		assertThat(this.jdbc.queryForObject(
				"SELECT sort_order FROM setting_allowed_values WHERE id = ?",
				Integer.class, option)).isEqualTo(9);
	}

	@Test
	void anUnusedOptionMayHaveItsValueChanged() {
		long definition = createDefinition("k8", "ع", "E");
		long option = createOption(definition, "annual");
		postForm("action", "edit_option", "id", String.valueOf(option),
				"value", "changed", "label_en", "E", "sort_order", "0");
		assertThat(optionField(option, "value")).isEqualTo("changed");
	}

	@Test
	void anOptionCannotBeMovedToAnotherDefinition() {
		// setting_definition_id is not in the update statement at all.
		long first = createDefinition("k9", "ع", "E");
		long second = createDefinition("k10", "ع", "E");
		long option = createOption(first, "annual");
		postForm("action", "edit_option", "id", String.valueOf(option),
				"setting_definition_id", String.valueOf(second),
				"value", "annual", "label_en", "E", "sort_order", "0");
		assertThat(this.jdbc.queryForObject(
				"SELECT setting_definition_id FROM setting_allowed_values WHERE id = ?",
				Long.class, option)).isEqualTo(first);
	}

	@Test
	void anOptionInUseCannotBeDeleted() {
		long definition = createDefinition("k11", "ع", "E");
		long option = createOption(definition, "annual");
		useOption(option);
		postForm("action", "delete_option", "id", String.valueOf(option));
		assertThat(countOptions(definition)).as("still there").isEqualTo(1);
	}

	@Test
	void anUnusedOptionIsDeleted() {
		long definition = createDefinition("k12", "ع", "E");
		long option = createOption(definition, "annual");
		postForm("action", "delete_option", "id", String.valueOf(option));
		assertThat(countOptions(definition)).isZero();
	}

	@Test
	void theUsageCountIsCompaniesAndTwoOfThemCountTwice() {
		// COUNT(DISTINCT company_id). A single company cannot select the same
		// option twice -- company_setting_values has a unique key on the pair,
		// which is why the distinct-ness only shows with two companies.
		long definition = createDefinition("k13", "ع", "E");
		long option = createOption(definition, "annual");
		useOption(option, this.companyA);
		assertThat(usage(option)).isEqualTo(1);
		useOption(option, this.companyB);
		assertThat(usage(option)).isEqualTo(2);
		assertThat(body(PATH + "&tab=setting_templates")).contains("badge-yellow");
	}

	private int usage(long option) {
		return this.jdbc.queryForObject(
				"SELECT COUNT(DISTINCT cs.company_id) FROM company_setting_values csv"
						+ " INNER JOIN company_settings cs ON cs.id = csv.company_setting_id"
						+ " WHERE csv.setting_allowed_value_id = ?", Integer.class, option);
	}

	// ------------------------------------------------------------- system tab

	@Test
	void booleansSaveAsWordsAndReadBackFromEitherVocabulary() {
		postForm("action", "save_configs", "show_banners", "1");
		assertThat(configValue("show_banners")).as("stored as the word").isEqualTo("true");

		// A row written before this format, using 1, still shows as on.
		this.jdbc.update("UPDATE configs SET config_value = '1' WHERE config_key = 'show_banners'");
		assertThat(body(PATH + "&tab=system")).contains("show_banners");
	}

	@Test
	void anOmittedBooleanBecomesFalse() {
		postForm("action", "save_configs", "show_banners", "1");
		assertThat(configValue("show_banners")).isEqualTo("true");
		// The whole tab is posted every time; a key the form did not send is
		// written as its normalised empty value, which for a boolean is false.
		postForm("action", "save_configs");
		assertThat(configValue("show_banners")).isEqualTo("false");
	}

	@Test
	void aNumberIsFlooredAtZeroAndTakesALeadingNumericPrefix() {
		postForm("action", "save_configs", "min_android_build_number", "-5");
		assertThat(configValue("min_android_build_number")).isEqualTo("0");
		postForm("action", "save_configs", "min_android_build_number", "42abc");
		assertThat(configValue("min_android_build_number")).isEqualTo("42");
	}

	@Test
	void aDateIsNormalisedOrDiscarded() {
		postForm("action", "save_configs",
				"attendance_excel_import_available_from", "2026-9-6");
		assertThat(configValue("attendance_excel_import_available_from")).isEqualTo("2026-09-06");
		postForm("action", "save_configs",
				"attendance_excel_import_available_from", "06/09/2026");
		assertThat(configValue("attendance_excel_import_available_from")).isEmpty();
	}

	@Test
	void aUrlIsStoredWithoutValidation() {
		// Legacy validates nothing here, so neither does this.
		postForm("action", "save_configs", "website_url", "  not a url  ");
		assertThat(configValue("website_url")).isEqualTo("not a url");
	}

	@Test
	void everyDefinedKeyIsWrittenOnEverySave() {
		postForm("action", "save_configs", "website_url", "https://example.test");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM configs", Integer.class))
				.as("all sixteen defined keys").isEqualTo(16);
	}

	// -------------------------------------------------------- tabs and aliases

	@Test
	void anUnknownTabFallsBackToContent() {
		assertThat(body(PATH + "&tab=nonsense")).contains("app-content-page");
	}

	@Test
	void theTwoAliasRoutesRedirectIntoTheirTab() {
		ResponseEntity<String> templates = get("/admin/setting_templates", this.cookie);
		assertThat(templates.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(templates.getHeaders().getLocation().toString())
				.contains("/admin/settings?tab=setting_templates");

		ResponseEntity<String> content = get("/admin/app_content&section=how_to_use", this.cookie);
		assertThat(content.getHeaders().getLocation().toString())
				.contains("tab=app_content").contains("section=how_to_use");
	}

	@Test
	void theAliasEncodesTheSectionItPassesThrough() {
		ResponseEntity<String> response =
				get("/admin/app_content&section=a%26tab%3Dsystem", this.cookie);
		assertThat(response.getHeaders().getLocation().toString())
				.as("the ampersand stays encoded, so it cannot append a parameter")
				.doesNotContain("&tab=system")
				.contains("section=a%26tab%3Dsystem");
	}

	@Test
	void theContentAliasRedirectsAndDoesNotCheckForAnAdministrator() {
		// Legacy's content/page.php calls requireLogin() and redirects, with no
		// isAdmin() -- unlike app_content and setting_templates, which check.
		// The asymmetry is kept; the page it lands on refuses a
		// non-administrator either way.
		ResponseEntity<String> response = get("/admin/content", this.cookie);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation().toString())
				.contains("/admin/settings?tab=app_content");
	}

	@Test
	void thePageIsUnreachableWithoutASession() {
		assertThat(get(PATH, null).getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(get("/admin/app_content", null).getStatusCode()).isEqualTo(HttpStatus.FOUND);
	}

	@Test
	void aPostWithoutTheCsrfTokenIsRefused() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + this.cookie);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("action", "save_configs");
		assertThat(this.restTemplate.exchange(PATH, HttpMethod.POST,
				new HttpEntity<>(form, headers), String.class).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void everyWriteIsAudited() {
		long definition = createDefinition("k14", "ع", "E");
		int before = auditCount();
		postForm("action", "add_option", "setting_definition_id", String.valueOf(definition),
				"value", "annual", "label_en", "A", "sort_order", "0");
		postForm("action", "save_content", "content_key", "compliance",
				"content_value_ar", "a", "content_value_en", "b");
		postForm("action", "save_configs", "website_url", "https://example.test");
		assertThat(auditCount()).isEqualTo(before + 3);
	}

	@Test
	void thePageCarriesTheRightAssetsPerTab() {
		assertThat(body(PATH + "&tab=app_content"))
				.contains("app-content.css").contains("app-content.js");
		assertThat(body(PATH + "&tab=setting_templates"))
				.contains("setting-templates.js").doesNotContain("app-content.js");
		assertThat(body(PATH + "&tab=system"))
				.contains("settings.css").doesNotContain("setting-templates.js");
	}

	private String postForm(String... fields) {
		return postTo(PATH, fields);
	}

	private String postTo(String path, String... fields) {
		return post(path, this.cookie, page(PATH, this.cookie).csrf(), fields).getBody();
	}

	private String body(String path) {
		return get(path, this.cookie).getBody();
	}

	/**
	 * Every request carries {@code lang=en}. Callers append their own
	 * parameters with a leading {@code &}, and this puts the {@code ?} in front
	 * of the lot.
	 */
	private ResponseEntity<String> get(String path, String sessionCookie) {
		HttpHeaders headers = new HttpHeaders();
		if (sessionCookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		}
		return this.restTemplate.exchange(withLang(path), HttpMethod.GET,
				new HttpEntity<>(headers), String.class);
	}

	private Page page(String path, String sessionCookie) {
		ResponseEntity<String> response = get(path, sessionCookie);
		String resolved = sessionCookie != null ? sessionCookie : tryCookieOf(response);
		return new Page(response, resolved, csrfOf(response));
	}

	private ResponseEntity<String> post(
			String path, String sessionCookie, Csrf csrf, String... fields) {
		HttpHeaders headers = new HttpHeaders();
		// Without the charset, FormHttpMessageConverter writes ISO-8859-1 and
		// Arabic arrives corrupted. A browser sends UTF-8 because the page
		// declares it, so this makes the harness match a real client.
		headers.setContentType(new MediaType(MediaType.APPLICATION_FORM_URLENCODED,
				java.nio.charset.StandardCharsets.UTF_8));
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		for (int index = 0; index < fields.length; index += 2) {
			form.add(fields[index], fields[index + 1]);
		}
		form.add(csrf.name(), csrf.value());
		return this.restTemplate.exchange(withLang(path), HttpMethod.POST,
				new HttpEntity<>(form, headers), String.class);
	}

	private static String withLang(String path) {
		int firstParameter = path.indexOf('&');
		return firstParameter < 0
				? path + "?lang=en"
				: path.substring(0, firstParameter) + "?lang=en" + path.substring(firstParameter);
	}

	private record Csrf(String name, String value) {
	}

	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	private static Csrf csrfOf(ResponseEntity<String> response) {
		Matcher matcher = CSRF.matcher(response.getBody());
		assertThat(matcher.find()).as("expected a CSRF token").isTrue();
		return new Csrf(matcher.group(1), matcher.group(2));
	}

	private static String cookieOf(ResponseEntity<String> response) {
		String value = tryCookieOf(response);
		assertThat(value).as("expected a session cookie").isNotNull();
		return value;
	}

	private static String tryCookieOf(ResponseEntity<String> response) {
		List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
		if (cookies == null) {
			return null;
		}
		return cookies.stream()
				.filter(value -> value.startsWith("WORKIN_ADMIN_SESSION="))
				.map(header -> {
					int start = header.indexOf('=') + 1;
					int end = header.indexOf(';', start);
					return end < 0 ? header.substring(start) : header.substring(start, end);
				})
				.findFirst().orElse(null);
	}


	private static byte[] fromBase32(String seed) {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int buffer = 0;
		int bits = 0;
		for (char character : seed.toCharArray()) {
			int value = alphabet.indexOf(character);
			if (value < 0) {
				continue;
			}
			buffer = (buffer << 5) | value;
			bits += 5;
			if (bits >= 8) {
				out.write((buffer >> (bits - 8)) & 0xFF);
				bits -= 8;
			}
		}
		return out.toByteArray();
	}

}
