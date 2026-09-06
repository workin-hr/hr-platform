package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.platformadmin.mfa.PlatformAdminMfaService;
import com.workin.backend.platformadmin.mfa.Totp;

/**
 * {@code /admin/workforce_planning} over real HTTP against a real MariaDB.
 *
 * <p>The page where <b>D-176</b> is at its fullest: the table owns its tenant
 * by a {@code company_id} column <i>and</i> points at a branch, a department
 * and a job title that each belong to a company. Legacy resolves one company
 * for the whole payload -- the posted one, for an administrator with no filter
 * -- writes it into {@code company_id} and validates all three foreign keys
 * against it, so a row moves tenant with everything it references and arrives
 * looking native (<b>R-047</b>). Its {@code delete_wp} had no tenant check at
 * all (<b>R-046</b>).
 *
 * <p>Three further legacy behaviours are pinned here rather than reproduced:
 * the unique key the page writes straight through (<b>R-050</b>), the form
 * cascade that ships every company's org structure to every company
 * (<b>R-051</b>), and the {@code actual_count} arithmetic, which is copied
 * exactly including the part that looks like a bug.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminWorkforcePlanningEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
		} catch (Exception ex) {
			throw new IllegalStateException("could not apply the legacy schema", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.platform-admin.mfa.encryption-key", () -> {
			byte[] key = new byte[32];
			new java.security.SecureRandom().nextBytes(key);
			return java.util.Base64.getEncoder().encodeToString(key);
		});
		registry.add("app.platform-admin.actions.enabled", () -> "true");
	}

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private PlatformAdminMfaService mfaService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private javax.sql.DataSource legacyDataSource;

	private JdbcTemplate jdbc;

	private String cookie;

	private long companyA;

	private long companyB;

	private long branchA;

	private long departmentA;

	private long jobTitleA;

	private long branchB;

	private long departmentB;

	private long jobTitleB;



	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM workforce_planning");
		this.jdbc.update("DELETE FROM employees WHERE id > 990000");
		this.jdbc.update("DELETE FROM job_titles");
		this.jdbc.update("DELETE FROM departments");
		this.jdbc.update("DELETE FROM branches");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2101" + System.nanoTime() % 100_000_000L;
		this.jdbc.update("INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, 1)",
				phone, this.passwordEncoder.encode(PASSWORD));
		long adminId = this.jdbc.queryForObject(
				"SELECT id FROM platform_admins WHERE phone = ?", Long.class, phone);

		String token = this.mfaService.issueBootstrapToken(adminId, adminId);
		String seed = this.mfaService.beginEnrolment(adminId, token).orElseThrow();
		assertThat(this.mfaService.confirmEnrolment(adminId, code(seed))).isTrue();

		Page login = page("/admin/login", null);
		String pending = cookieOf(post("/admin/login", login.cookie(), login.csrf(),
				"phone", phone, "password", PASSWORD));
		this.jdbc.update("UPDATE platform_admin_mfa SET last_accepted_time_step = NULL"
				+ " WHERE platform_admin_id = ?", adminId);
		this.cookie = cookieOf(post("/admin/mfa", pending,
				page("/admin/mfa", pending).csrf(), "code", code(seed)));

		this.companyA = createCompany("Alpha Co");
		this.companyB = createCompany("Beta Co");
		this.branchA = createBranch(this.companyA, "Alpha HQ", true);
		this.departmentA = createDepartment(this.companyA, "Alpha Ops", true);
		this.jobTitleA = createJobTitle(this.companyA, "Alpha Fitter", true);
		this.branchB = createBranch(this.companyB, "Beta HQ", true);
		this.departmentB = createDepartment(this.companyB, "Beta Ops", true);
		this.jobTitleB = createJobTitle(this.companyB, "Beta Fitter", true);


	}


	@Test
	void theListShowsPlannedBesideActualAndFlagsAShortfall() {
		// Two employees match the target exactly, so a plan for three is short.
		createEmployeeIn(this.companyA, "A101", this.branchA, this.departmentA, this.jobTitleA);
		createEmployeeIn(this.companyA, "A102", this.branchA, this.departmentA, this.jobTitleA);
		seedPlan(this.companyA, this.branchA, this.departmentA, this.jobTitleA, 3);

		String html = body("/admin/workforce_planning");
		assertThat(html).contains("Alpha HQ", "Alpha Ops", "Alpha Fitter");
		assertThat(html).as("the shortfall is coloured red").contains("text-red");
	}

	@Test
	void theActualCountIgnoresInactiveEmployeesAndOtherCompanies() {
		createEmployeeIn(this.companyA, "A101", this.branchA, this.departmentA, this.jobTitleA);
		long resigned = createEmployeeIn(
				this.companyA, "A102", this.branchA, this.departmentA, this.jobTitleA);
		this.jdbc.update("UPDATE employees SET is_active = 0 WHERE id = ?", resigned);
		seedPlan(this.companyA, this.branchA, this.departmentA, this.jobTitleA, 1);

		assertThat(body("/admin/workforce_planning"))
				.as("one active match, so the plan of 1 is met and shows green")
				.contains("text-green");
	}

	@Test
	void aPlanWithNoDepartmentCanNeverCountAnybody() {
		// R-052. The two tables spell "no department" differently:
		// workforce_planning.department_id is NOT NULL DEFAULT 0, employees'
		// is nullable. Legacy joins them with `e.department_id =
		// wt.department_id`, and NULL = 0 is never true -- so a plan with no
		// department matches nobody at all, and reads as permanently
		// understaffed however many people hold the branch and job title.
		//
		// Reproduced rather than corrected: it changes a number a company sees,
		// so it is a product decision, not something to fix while porting.
		long withDepartment = seedPlan(
				this.companyA, this.branchA, this.departmentA, this.jobTitleA, 1);
		long withoutDepartment = seedPlan(this.companyA, this.branchA, 0, this.jobTitleA, 1);

		createEmployeeIn(this.companyA, "A101", this.branchA, this.departmentA, this.jobTitleA);
		createEmployeeIn(this.companyA, "A102", this.branchA, null, this.jobTitleA);

		assertThat(actualCountOf(withDepartment))
				.as("the employee whose department matches is counted")
				.isEqualTo(1);
		assertThat(actualCountOf(withoutDepartment))
				.as("but the department-less plan counts neither of them -- not the"
						+ " employee with a department, and not the one without")
				.isZero();
	}

	@Test
	void theSearchMatchesTheJobTitleOnlyAndNotTheBranchOrDepartment() {
		seedPlan(this.companyA, this.branchA, this.departmentA, this.jobTitleA, 1);

		assertThat(body("/admin/workforce_planning?search=Fitter")).contains("Alpha Fitter");
		assertThat(body("/admin/workforce_planning?search=Alpha HQ"))
				.as("the branch name is displayed but is not searched")
				.doesNotContain("Alpha Fitter");
		assertThat(body("/admin/workforce_planning?search=Alpha Ops"))
				.as("nor is the department name")
				.doesNotContain("Alpha Fitter");
	}

	@Test
	void everyFilterIsOptionalAndAnAbsentOneNeverBreaksThePage() {
		seedPlan(this.companyA, this.branchA, this.departmentA, this.jobTitleA, 1);

		for (String query : List.of(
				"", "?search=", "?company_id=", "?search=&company_id=", "?page=")) {
			assertThat(get("/admin/workforce_planning" + query, this.cookie).getStatusCode())
					.as("no filters should render, query '%s'", query)
					.isEqualTo(HttpStatus.OK);
		}
	}

	@Test
	void addingAPlanStoresItAgainstTheChosenCompany() {
		postForm("action", "add_wp",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"department_id", String.valueOf(this.departmentA),
				"job_title_id", String.valueOf(this.jobTitleA),
				"planned_count", "4");

		Map<String, Object> row = this.jdbc.queryForMap("SELECT * FROM workforce_planning");
		assertThat(((Number) row.get("company_id")).longValue()).isEqualTo(this.companyA);
		assertThat(((Number) row.get("branch_id")).longValue()).isEqualTo(this.branchA);
		assertThat(((Number) row.get("planned_count")).intValue()).isEqualTo(4);
	}

	@Test
	void aNegativePlannedCountIsFlooredAtZero() {
		postForm("action", "add_wp",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"department_id", "0",
				"job_title_id", String.valueOf(this.jobTitleA),
				"planned_count", "-5");

		assertThat(this.jdbc.queryForObject(
				"SELECT planned_count FROM workforce_planning", Integer.class))
				.as("legacy's max(0, ...)")
				.isZero();
	}

	@Test
	void aPlanMayHaveNoDepartmentButMustHaveABranchAndAJobTitle() {
		postForm("action", "add_wp",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"department_id", "0",
				"job_title_id", String.valueOf(this.jobTitleA),
				"planned_count", "1");
		assertThat(planCount()).as("no department is valid").isEqualTo(1);

		postForm("action", "add_wp",
				"company_id", String.valueOf(this.companyA),
				"branch_id", "0",
				"department_id", String.valueOf(this.departmentA),
				"job_title_id", String.valueOf(this.jobTitleA),
				"planned_count", "1");
		assertThat(planCount()).as("no branch is not").isEqualTo(1);

		postForm("action", "add_wp",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"department_id", String.valueOf(this.departmentA),
				"job_title_id", "0",
				"planned_count", "1");
		assertThat(planCount()).as("nor is no job title").isEqualTo(1);
	}

	@Test
	void anArchivedBranchIsNotAValidTargetEvenForItsOwnCompany() {
		long archived = createBranch(this.companyA, "Alpha Closed", false);

		postForm("action", "add_wp",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(archived),
				"department_id", "0",
				"job_title_id", String.valueOf(this.jobTitleA),
				"planned_count", "1");

		assertThat(planCount())
				.as("org_branch_belongs_to_company() requires is_active = 1")
				.isZero();
	}

	@Test
	void anEditMayRePointAPlanWithinItsOwnCompany() {
		long id = seedPlan(this.companyA, this.branchA, this.departmentA, this.jobTitleA, 2);
		long otherBranch = createBranch(this.companyA, "Alpha Depot", true);
		long otherJob = createJobTitle(this.companyA, "Alpha Welder", true);

		postForm("action", "edit_wp", "id", String.valueOf(id),
				"branch_id", String.valueOf(otherBranch),
				"department_id", "0",
				"job_title_id", String.valueOf(otherJob),
				"planned_count", "9");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT * FROM workforce_planning WHERE id = " + id);
		assertThat(((Number) row.get("branch_id")).longValue()).isEqualTo(otherBranch);
		assertThat(((Number) row.get("job_title_id")).longValue()).isEqualTo(otherJob);
		assertThat(((Number) row.get("department_id")).intValue()).isZero();
		assertThat(((Number) row.get("planned_count")).intValue()).isEqualTo(9);
		assertThat(((Number) row.get("company_id")).longValue())
				.as("and it stays where it was")
				.isEqualTo(this.companyA);
	}

	@Test
	void anEditCannotMoveAPlanToAnotherCompanyThroughThePostedCompanyId() {
		// D-176, direct half. company_id is not among the updated columns, so
		// the posted value has nowhere to land.
		long id = seedPlan(this.companyA, this.branchA, this.departmentA, this.jobTitleA, 2);

		postForm("action", "edit_wp", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyB),
				"branch_id", String.valueOf(this.branchA),
				"department_id", String.valueOf(this.departmentA),
				"job_title_id", String.valueOf(this.jobTitleA),
				"planned_count", "3");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT * FROM workforce_planning WHERE id = " + id);
		assertThat(((Number) row.get("company_id")).longValue())
				.as("the row's own company is authoritative")
				.isEqualTo(this.companyA);
		assertThat(((Number) row.get("planned_count")).intValue())
				.as("and the rest of the edit still applied")
				.isEqualTo(3);
	}

	@Test
	void anEditCannotRePointAPlanAtAnotherCompanysBranchDepartmentOrJobTitle() {
		// D-176, indirect half -- and the reason the invariant names editable
		// foreign keys explicitly. An unfiltered administrator is the session
		// legacy's `if ($cid > 0 && ...)` guard does nothing for.
		long id = seedPlan(this.companyA, this.branchA, this.departmentA, this.jobTitleA, 2);

		postForm("action", "edit_wp", "id", String.valueOf(id),
				"branch_id", String.valueOf(this.branchB),
				"department_id", String.valueOf(this.departmentB),
				"job_title_id", String.valueOf(this.jobTitleB),
				"planned_count", "2");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT * FROM workforce_planning WHERE id = " + id);
		assertThat(((Number) row.get("branch_id")).longValue()).isEqualTo(this.branchA);
		assertThat(((Number) row.get("department_id")).longValue()).isEqualTo(this.departmentA);
		assertThat(((Number) row.get("job_title_id")).longValue()).isEqualTo(this.jobTitleA);
	}

	@Test
	void eachForeignKeyIsCheckedSeparatelySoOneForeignOneIsEnoughToRefuse() {
		long id = seedPlan(this.companyA, this.branchA, this.departmentA, this.jobTitleA, 2);

		// Two of the three belong to company A; only the department is B's.
		postForm("action", "edit_wp", "id", String.valueOf(id),
				"branch_id", String.valueOf(this.branchA),
				"department_id", String.valueOf(this.departmentB),
				"job_title_id", String.valueOf(this.jobTitleA),
				"planned_count", "7");

		assertThat(this.jdbc.queryForObject(
				"SELECT planned_count FROM workforce_planning WHERE id = " + id, Integer.class))
				.as("the whole edit is refused, not just the offending key")
				.isEqualTo(2);
	}

	@Test
	void aDuplicateTargetIsRefusedRatherThanCrashingOnTheUniqueKey() {
		// R-050: legacy writes straight through uq_workforce_target and lets
		// the PDOException escape uncaught.
		seedPlan(this.companyA, this.branchA, this.departmentA, this.jobTitleA, 2);

		ResponseEntity<String> response = postForm("action", "add_wp",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"department_id", String.valueOf(this.departmentA),
				"job_title_id", String.valueOf(this.jobTitleA),
				"planned_count", "5");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("already_exists");
		assertThat(planCount()).isEqualTo(1);
		assertThat(this.jdbc.queryForObject(
				"SELECT planned_count FROM workforce_planning", Integer.class))
				.as("and the existing row is untouched")
				.isEqualTo(2);
	}

	@Test
	void anEditOntoAnExistingTargetIsRefusedButSavingARowOntoItselfIsNot() {
		long first = seedPlan(this.companyA, this.branchA, this.departmentA, this.jobTitleA, 2);
		long otherJob = createJobTitle(this.companyA, "Alpha Welder", true);
		long second = seedPlan(this.companyA, this.branchA, this.departmentA, otherJob, 4);

		postForm("action", "edit_wp", "id", String.valueOf(second),
				"branch_id", String.valueOf(this.branchA),
				"department_id", String.valueOf(this.departmentA),
				"job_title_id", String.valueOf(this.jobTitleA),
				"planned_count", "4");
		assertThat(this.jdbc.queryForObject(
				"SELECT job_title_id FROM workforce_planning WHERE id = " + second, Long.class))
				.as("moving onto the first row's target is refused")
				.isEqualTo(otherJob);

		// The same row saved with its own target unchanged must still save --
		// the duplicate check has to exclude the row being edited.
		postForm("action", "edit_wp", "id", String.valueOf(first),
				"branch_id", String.valueOf(this.branchA),
				"department_id", String.valueOf(this.departmentA),
				"job_title_id", String.valueOf(this.jobTitleA),
				"planned_count", "11");
		assertThat(this.jdbc.queryForObject(
				"SELECT planned_count FROM workforce_planning WHERE id = " + first, Integer.class))
				.isEqualTo(11);
	}

	@Test
	void deletingIsScopedToTheSessionsCompanyFilter() {
		// R-046: legacy's delete_wp had no tenant check of any kind -- not even
		// the `$cid > 0` one its sibling edit_wp carries.
		long planB = seedPlan(this.companyB, this.branchB, this.departmentB, this.jobTitleB, 2);

		// Filter the session to company A, then try to delete B's row by id.
		get("/admin/workforce_planning?company_id=" + this.companyA, this.cookie);
		postForm("action", "delete_wp", "id", String.valueOf(planB));

		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM workforce_planning WHERE id = " + planB, Integer.class))
				.as("a filtered session may not delete another company's row")
				.isEqualTo(1);
	}

	@Test
	void anUnfilteredAdministratorMayStillDeleteAnyCompanysRow() {
		// The other half of the same rule: no filter means every company, and
		// that is the intended cross-company mode rather than an oversight.
		long planB = seedPlan(this.companyB, this.branchB, this.departmentB, this.jobTitleB, 2);

		get("/admin/workforce_planning?company_id=0", this.cookie);
		postForm("action", "delete_wp", "id", String.valueOf(planB));

		assertThat(planCount()).isZero();
	}

	@Test
	void theFormOffersOnlyTheFilteredCompanysOptions() {
		// R-051: legacy embeds every company's branches, departments and job
		// titles as JSON and filters in the browser. Measured against the
		// production copy that is 3,671 rows across 283 companies handed to
		// any company-scoped session.
		String html = get("/admin/workforce_planning?company_id=" + this.companyA + "&action=add",
				this.cookie).getBody();

		assertThat(html).contains("Alpha HQ", "Alpha Ops", "Alpha Fitter");
		assertThat(html)
				.as("another company's org structure must not reach the page at all")
				.doesNotContain("Beta HQ")
				.doesNotContain("Beta Ops")
				.doesNotContain("Beta Fitter");
	}

	@Test
	void anUnfilteredAdministratorSeesEveryCompanysOptionsNamed() {
		String html = get("/admin/workforce_planning?company_id=0&action=add", this.cookie)
				.getBody();

		assertThat(html).contains("Alpha HQ", "Beta HQ");
		assertThat(html)
				.as("and they are told apart by company, as org_option_label() does")
				.contains("Alpha Co")
				.contains("Beta Co");
	}

	@Test
	void anEditFormOffersOnlyTheEditedRowsOwnCompanysOptions() {
		// Even for an administrator with no filter, whose list legitimately
		// spans every company. Offering company B's branches for a row owned by
		// A would be offering a choice D-176 refuses on submit.
		long planA = seedPlan(this.companyA, this.branchA, this.departmentA, this.jobTitleA, 2);

		String html = get("/admin/workforce_planning?company_id=0&action=edit&id=" + planA,
				this.cookie).getBody();

		assertThat(html).contains("Alpha HQ", "Alpha Ops", "Alpha Fitter");
		assertThat(html)
				.doesNotContain("Beta HQ")
				.doesNotContain("Beta Ops")
				.doesNotContain("Beta Fitter");
	}

	@Test
	void everyWriteIsAudited() {
		long id = seedPlan(this.companyA, this.branchA, this.departmentA, this.jobTitleA, 2);
		postForm("action", "delete_wp", "id", String.valueOf(id));

		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_admin_audit_events WHERE target_type ="
						+ " 'workforce_planning'", Integer.class))
				.isEqualTo(1);
	}

	@Test
	void aRowThatDoesNotExistIsRefusedRatherThanSilentlyDoingNothing() {
		ResponseEntity<String> response = postForm("action", "edit_wp", "id", "987654321",
				"branch_id", String.valueOf(this.branchA),
				"department_id", "0",
				"job_title_id", String.valueOf(this.jobTitleA),
				"planned_count", "1");

		assertThat(response.getHeaders().getLocation()).asString().contains("error_db");
	}

	@Test
	void anUnknownActionIsRefused() {
		long id = seedPlan(this.companyA, this.branchA, this.departmentA, this.jobTitleA, 2);
		ResponseEntity<String> response = postForm("action", "drop_everything",
				"id", String.valueOf(id));

		assertThat(response.getHeaders().getLocation()).asString().contains("error_db");
		assertThat(planCount()).isEqualTo(1);
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/workforce_planning", HttpMethod.GET,
				new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	/**
	 * The token comes from {@code ?action=add}, which always renders the form.
	 * The bare list page renders one only when it has rows to delete, so a
	 * test that writes before it reads would otherwise have nowhere to get a
	 * token from.
	 */
	private ResponseEntity<String> postForm(String... fields) {
		return post("/admin/workforce_planning", this.cookie,
				page("/admin/workforce_planning?action=add", this.cookie).csrf(), fields);
	}

	private int planCount() {
		return this.jdbc.queryForObject("SELECT COUNT(*) FROM workforce_planning", Integer.class);
	}

	private int actualCountOf(long planId) {
		return this.jdbc.queryForObject(
				"SELECT (SELECT COUNT(*) FROM employees e WHERE e.company_id = wt.company_id"
						+ " AND e.is_active = 1 AND e.branch_id = wt.branch_id"
						+ " AND e.department_id = wt.department_id"
						+ " AND e.job_title_id = wt.job_title_id)"
						+ " FROM workforce_planning wt WHERE wt.id = ?",
				Integer.class, planId);
	}

	private long seedPlan(
			long companyId, long branchId, long departmentId, long jobTitleId, int planned) {
		this.jdbc.update("INSERT INTO workforce_planning (company_id, branch_id, department_id,"
				+ " job_title_id, planned_count, created_at) VALUES (?, ?, ?, ?, ?, NOW())",
				companyId, branchId, departmentId, jobTitleId, planned);
		return this.jdbc.queryForObject(
				"SELECT MAX(id) FROM workforce_planning", Long.class);
	}

	private long createBranch(long companyId, String name, boolean active) {
		this.jdbc.update("INSERT INTO branches (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, ?, NOW())", companyId, name, active ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT id FROM branches WHERE name = ?", Long.class, name);
	}

	private long createDepartment(long companyId, String name, boolean active) {
		this.jdbc.update("INSERT INTO departments (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, ?, NOW())", companyId, name, active ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT id FROM departments WHERE name = ?", Long.class, name);
	}

	private long createJobTitle(long companyId, String name, boolean active) {
		this.jdbc.update("INSERT INTO job_titles (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, ?, NOW())", companyId, name, active ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT id FROM job_titles WHERE name = ?", Long.class, name);
	}

	/** {@code departmentId} is nullable here, as the column is. */
	private long createEmployeeIn(
			long companyId, String code, long branchId, Long departmentId, long jobTitleId) {
		long id = this.jdbc.queryForObject(
				"SELECT GREATEST(COALESCE(MAX(id), 0) + 1, 990001) FROM employees", Long.class);
		this.jdbc.update("INSERT INTO employees (id, company_id, branch_id, department_id,"
				+ " job_title_id, employee_code, first_name, last_name, role, is_active,"
				+ " is_mobile_attendance_enabled, can_check_in_any_branch, join_request_status,"
				+ " token_version, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, 'Test', 'Employee', 'employee', 1, 1, 0,"
				+ " 'accepted', 1, NOW(), NOW())",
				id, companyId, branchId, departmentId, jobTitleId, code);
		return id;
	}
	private record Csrf(String name, String value) {
	}

	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	private long createCompany(String name) {
		String phone = "01" + System.nanoTime() % 1_000_000_000L;
		this.jdbc.update("INSERT INTO companies (company_name, phone, password_hash, status,"
				+ " otp_verified, profile_completed, created_at)"
				+ " VALUES (?, ?, ?, 'active', 1, 1, NOW())",
				name, phone, this.passwordEncoder.encode(PASSWORD));
		return this.jdbc.queryForObject("SELECT id FROM companies WHERE phone = ?", Long.class, phone);
	}

	private String body(String path) {
		return get(path, this.cookie).getBody();
	}

	private ResponseEntity<String> get(String path, String sessionCookie) {
		HttpHeaders headers = new HttpHeaders();
		if (sessionCookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		}
		return this.restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	private Page page(String path, String sessionCookie) {
		ResponseEntity<String> response = get(path, sessionCookie);
		String resolved = sessionCookie != null ? sessionCookie : tryCookieOf(response);
		return new Page(response, resolved, csrfOf(response));
	}

	private ResponseEntity<String> post(String path, String sessionCookie, Csrf csrf, String... fields) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		for (int index = 0; index < fields.length; index += 2) {
			form.add(fields[index], fields[index + 1]);
		}
		form.add(csrf.name(), csrf.value());
		return this.restTemplate.exchange(path, HttpMethod.POST,
				new HttpEntity<>(form, headers), String.class);
	}

	private static Csrf csrfOf(ResponseEntity<String> response) {
		Matcher matcher = CSRF.matcher(response.getBody());
		assertThat(matcher.find()).as("expected a CSRF token").isTrue();
		return new Csrf(matcher.group(1), matcher.group(2));
	}

	private static String code(String base32Seed) {
		return Totp.codeAt(fromBase32(base32Seed), Totp.timeStepAt(Instant.now()));
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

	private static void applySchema(String resource) throws Exception {
		String sql = new String(AdminWorkforcePlanningEndToEndTest.class.getClassLoader()
				.getResourceAsStream(resource).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
				java.sql.Statement statement = connection.createStatement()) {
			statement.execute("SET SESSION sql_mode = ''");
			for (String piece : sql.split(";\\R")) {
				if (!piece.isBlank()) {
					statement.execute(piece);
				}
			}
		}
	}

}
