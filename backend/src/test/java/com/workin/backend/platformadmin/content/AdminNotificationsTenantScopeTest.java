package com.workin.backend.platformadmin.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.workin.backend.BackendApplication;
import com.workin.backend.platformadmin.web.DashboardSession;
import com.workin.legacy.LegacyMariaDb;

/**
 * Both of the notifications page's writes honour the session's company, against
 * a real MariaDB.
 *
 * <p>Legacy has three rules here and this covers all three:
 *
 * <ul>
 * <li>a scoped session may delete only its own company's row
 * ({@code page.php:59-69}, the {@code if ($isComp)} branch and its
 * {@code error_db} flash);</li>
 * <li>a scoped session's broadcast goes to its own company when none is posted,
 * and a different company posted is <em>refused</em>
 * ({@code helper.php:328-334});</li>
 * <li>the all-employees audience is the administrator's alone
 * ({@code helper.php:336-350}).</li>
 * </ul>
 *
 * <p>Driven against the service rather than over HTTP, because no HTTP session
 * this surface issues can currently produce a scoped audience --
 * {@code AdminViewModelAdvice#session} returns {@link DashboardSession#admin}
 * unconditionally until the owner and HR logins arrive (ADR-0016, R-044). They
 * are written now because that is when their absence is visible: after those
 * logins ship, a missing company comparison looks exactly like the page
 * working.
 *
 * <p>An administrator's own broadcast stays cross-tenant, which is the page, and
 * {@code BroadcastStoreTest.anAllEmployeesBroadcastCountsEveryActiveEmployee}
 * pins that it reaches every company.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminNotificationsTenantScopeTest {

	/** A database of this class's own, inside the shared container. */
	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String PASSWORD = "correct horse battery staple";

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
	private BroadcastAdminService service;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private javax.sql.DataSource legacyDataSource;

	private JdbcTemplate jdbc;

	private long adminId;

	private long companyA;

	private long companyB;

	@BeforeEach
	void seed() {
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM notifications");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");
		// Children first: an employees row references its branch.
		this.jdbc.update("DELETE FROM employees");
		this.jdbc.update("DELETE FROM branches");
		this.adminId = this.jdbc.queryForObject(
				"SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
		this.companyA = createCompany("Alpha Co");
		this.companyB = createCompany("Beta Co");
	}

	@Test
	void aScopedSessionCannotDeleteAnotherCompanysNotification() {
		long foreign = notification(this.companyB, "Beta's own");

		BroadcastAdminService.Result result =
				this.service.delete(DashboardSession.company(this.companyA), this.adminId, foreign);

		assertThat(result.ok()).isFalse();
		assertThat(result.errorKey()).as("legacy flashes error_db on that branch").isEqualTo("error_db");
		assertThat(exists(foreign)).as("the row is still there").isTrue();
		assertThat(auditEvents()).as("a refusal writes no audit event").isZero();
	}

	/**
	 * The other half, without which the guard could be "refuse everything" and
	 * the case above would still pass.
	 */
	@Test
	void aScopedSessionDeletesItsOwnCompanysNotification() {
		long own = notification(this.companyA, "Alpha's own");

		BroadcastAdminService.Result result =
				this.service.delete(DashboardSession.company(this.companyA), this.adminId, own);

		assertThat(result.ok()).isTrue();
		assertThat(exists(own)).isFalse();
		assertThat(auditEvents()).isEqualTo(1);
	}

	@Test
	void anAdministratorStillDeletesAnyCompanysNotification() {
		long alpha = notification(this.companyA, "Alpha's own");
		long beta = notification(this.companyB, "Beta's own");
		DashboardSession unscoped = DashboardSession.admin(0);

		assertThat(this.service.delete(unscoped, this.adminId, alpha).ok()).isTrue();
		assertThat(this.service.delete(unscoped, this.adminId, beta).ok())
				.as("legacy's unscoped arm deletes by id, and that is the platform's capability")
				.isTrue();
		assertThat(exists(alpha)).isFalse();
		assertThat(exists(beta)).isFalse();
	}

	/**
	 * An administrator filtered to one company with {@code ?company_id=} is not
	 * scoped -- the filter is a view, the scope is who you are. This is the
	 * distinction {@code DashboardSession.isScopedToOneCompany()} draws, and
	 * getting it backwards would take the page's delete away from the platform.
	 */
	@Test
	void anAdministratorFilteredToOneCompanyIsNotScopedAndMayStillDeleteAnothers() {
		long foreign = notification(this.companyB, "Beta's own");

		BroadcastAdminService.Result result = this.service.delete(
				DashboardSession.admin(this.companyA), this.adminId, foreign);

		assertThat(result.ok()).isTrue();
		assertThat(exists(foreign)).isFalse();
	}

	@Test
	void aRowThatIsNotThereIsRefusedForEitherAudience() {
		long gone = notification(this.companyA, "Alpha's own");
		this.jdbc.update("DELETE FROM notifications WHERE id = ?", gone);

		assertThat(this.service.delete(DashboardSession.admin(0), this.adminId, gone).errorKey())
				.isEqualTo("error_not_found");
		assertThat(this.service.delete(
				DashboardSession.company(this.companyA), this.adminId, gone).errorKey())
				.as("the scoped arm cannot resolve a company for a row that is gone")
				.isEqualTo("error_db");
	}

	@Test
	void aScopedSessionsBroadcastGoesToItsOwnCompanyWhenItNamesNone() {
		employee(this.companyA, "Alpha", "Worker");
		employee(this.companyB, "Beta", "Worker");

		BroadcastAdminService.Result result = this.service.send(
				DashboardSession.company(this.companyA), this.adminId,
				"company_employees", "Title", "Body", null, true);

		assertThat(result.ok()).isTrue();
		assertThat(result.recipients()).isEqualTo(1);
		assertThat(companiesWritten()).containsExactly(this.companyA);
	}

	@Test
	void aScopedSessionCannotBroadcastToAnotherCompany() {
		// Legacy refuses rather than retargeting, and the page cannot even send
		// this -- it forces the company before the dispatch. The dispatch refuses
		// anyway, and the dispatch is the authority.
		employee(this.companyA, "Alpha", "Worker");
		employee(this.companyB, "Beta", "Worker");

		BroadcastAdminService.Result result = this.service.send(
				DashboardSession.company(this.companyA), this.adminId,
				"company_employees", "Title", "Body", this.companyB, true);

		assertThat(result.ok()).isFalse();
		assertThat(result.errorKey()).isEqualTo("error_required");
		assertThat(rowCount()).as("nothing was written for either company").isZero();
	}

	@Test
	void aScopedSessionCannotBroadcastToEveryCompany() {
		employee(this.companyA, "Alpha", "Worker");
		employee(this.companyB, "Beta", "Worker");

		BroadcastAdminService.Result result = this.service.send(
				DashboardSession.company(this.companyA), this.adminId,
				"all_employees", "Title", "Body", null, true);

		assertThat(result.ok()).isFalse();
		assertThat(result.errorKey()).isEqualTo("error_required");
		assertThat(rowCount()).isZero();
	}

	/**
	 * The other half again: an administrator's broadcast is the page, and a guard
	 * that refused everyone would pass the three cases above.
	 */
	@Test
	void anAdministratorStillBroadcastsToEveryCompanyAndToAChosenOne() {
		employee(this.companyA, "Alpha", "Worker");
		employee(this.companyB, "Beta", "Worker");
		DashboardSession unscoped = DashboardSession.admin(0);

		assertThat(this.service.send(unscoped, this.adminId,
				"all_employees", "Everyone", "Body", null, true).recipients()).isEqualTo(2);
		assertThat(this.service.send(unscoped, this.adminId,
				"company_employees", "Just Beta", "Body", this.companyB, true).recipients())
				.isEqualTo(1);
		assertThat(companiesWritten()).containsExactlyInAnyOrder(this.companyA, this.companyB);
	}

	/** An administrator filtered to one company is not scoped here either. */
	@Test
	void anAdministratorFilteredToOneCompanyMayStillBroadcastToAnother() {
		employee(this.companyB, "Beta", "Worker");

		BroadcastAdminService.Result result = this.service.send(
				DashboardSession.admin(this.companyA), this.adminId,
				"company_employees", "Title", "Body", this.companyB, true);

		assertThat(result.ok()).isTrue();
		assertThat(companiesWritten()).containsExactly(this.companyB);
	}

	private void employee(long companyId, String first, String last) {
		this.jdbc.update("INSERT INTO branches (company_id, name, is_active, created_at)"
				+ " VALUES (?, 'HQ', 1, NOW())", companyId);
		Long branchId = this.jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		this.jdbc.update("INSERT INTO employees (company_id, branch_id, first_name, last_name,"
				+ " phone, password_hash, role, is_active, created_at)"
				+ " VALUES (?, ?, ?, ?, ?, 'x', 'employee', 1, NOW())",
				companyId, branchId, first, last, "01" + System.nanoTime() % 1_000_000_000L);
	}

	private java.util.List<Long> companiesWritten() {
		return this.jdbc.queryForList(
				"SELECT DISTINCT company_id FROM notifications ORDER BY company_id", Long.class);
	}

	private int rowCount() {
		return this.jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
	}

	private long notification(long companyId, String title) {
		this.jdbc.update("INSERT INTO notifications (company_id, recipient_kind, title, body,"
				+ " notification_type, is_read, created_at)"
				+ " VALUES (?, 'company', ?, 'body', 'system', 0, NOW())", companyId, title);
		return this.jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private boolean exists(long id) {
		return this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM notifications WHERE id = ?", Integer.class, id) == 1;
	}

	private int auditEvents() {
		return this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_admin_audit_events", Integer.class);
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
}
