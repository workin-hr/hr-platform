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
 * The notifications delete honours the row's company, against a real MariaDB.
 *
 * <p>Legacy guards exactly one case here: a company-scoped session may delete
 * only its own company's row ({@code pages/notifications/page.php:59-69},
 * the {@code if ($isComp)} branch and its {@code error_db} flash). An
 * unscoped administrator deletes by id, which is the platform's own capability.
 *
 * <p>Driven against the service rather than over HTTP, because no HTTP session
 * this surface issues can currently produce a scoped audience --
 * {@code AdminViewModelAdvice#session} returns {@link DashboardSession#admin}
 * unconditionally until the owner and HR logins arrive (ADR-0016, R-044). The
 * check is written now because that is when its absence is visible: after those
 * logins ship, a missing company comparison looks exactly like the page
 * working.
 *
 * <p>The send path stays deliberately cross-tenant -- that is the platform
 * broadcast, and {@code BroadcastStoreTest}'s
 * {@code anAllEmployeesBroadcastCountsEveryActiveEmployee} is what pins it.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminNotificationDeleteScopeTest {

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
