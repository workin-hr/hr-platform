package com.workin.backend.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.platformadmin.web.PlatformAdminSessionInventory;

/**
 * Every authentication outcome is attributed to the administrator's row
 * (ADR-0015 prerequisite 10, kept under ADR-0018): the login, the miss, the
 * logout, and a revocation of the other sessions.
 */
class PlatformAdminAuditTest extends AbstractIntegrationTest {

	@Autowired
	@Qualifier("legacyDataSource")
	private DataSource legacyDataSource;

	@Autowired
	private PlatformAdminLoginService loginService;

	@Autowired
	private PlatformAdminSessionInventory sessions;

	private long adminId() {
		return new JdbcTemplate(this.legacyDataSource).queryForObject(
				"SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
	}

	private List<String> eventTypes() {
		return new JdbcTemplate(this.legacyDataSource).queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE platform_admin_id = ? ORDER BY id",
				String.class, adminId());
	}

	private void clear() {
		new JdbcTemplate(this.legacyDataSource).update("DELETE FROM platform_admin_audit_events");
		new JdbcTemplate(this.legacyDataSource).update("DELETE FROM platform_admin_login_attempts");
	}

	@Test
	void successfulLoginIsAttributed() {
		clear();
		assertThat(this.loginService.login(TEST_ADMIN_PASSWORD, "10.0.0.1")).isPresent();
		assertThat(eventTypes()).containsExactly("LOGIN");
	}

	@Test
	void aMissIsAttributedToTheAdministratorItWasAgainst() {
		clear();
		assertThat(this.loginService.login("wrong password", "10.0.0.2")).isEmpty();
		// There is one administrator, so every miss is against it -- unlike the
		// per-phone model this replaced, where an unknown phone had nobody to
		// attribute to.
		assertThat(eventTypes()).containsExactly("LOGIN_FAILED");
	}

	@Test
	void revokingTheOtherSessionsIsAttributed() {
		clear();
		this.sessions.revokeEverything(adminId(), "the-current-one");
		assertThat(eventTypes()).containsExactly("ALL_SESSIONS_REVOKED");
	}

}
