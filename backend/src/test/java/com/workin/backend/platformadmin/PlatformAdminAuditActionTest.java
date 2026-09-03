package com.workin.backend.platformadmin;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.workin.backend.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-0015 prerequisite 10: an administrative action and its audit row must
 * commit together.
 *
 * <p>There is no administrative action yet, so the transaction is driven
 * directly. That is the point being tested -- the propagation contract, not any
 * particular operation -- and it means the first real action inherits a
 * guarantee that was proven rather than intended.
 */
class PlatformAdminAuditActionTest extends AbstractIntegrationTest {

	@Autowired
	private PlatformAdminAuditService auditService;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void anActionAuditRowRollsBackWithTheActionThatFailed() {
		long adminId = createPlatformAdmin();
		TransactionTemplate transaction = new TransactionTemplate(this.transactionManager);

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			this.auditService.recordAction(adminId, PlatformAdminAuditEventType.COMPANY_SUSPENDED,
					"COMPANY", "4242", null, "suspended for non-payment");
			// The action fails after its audit row was written.
			throw new IllegalStateException("the action failed");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(auditRowsFor(adminId))
			.as("an audit row for a suspension that never happened is worse than no row at all")
			.isZero();
	}

	@Test
	void anActionAuditRowCommitsWithTheActionThatSucceeded() {
		long adminId = createPlatformAdmin();
		TransactionTemplate transaction = new TransactionTemplate(this.transactionManager);

		transaction.executeWithoutResult(status ->
			this.auditService.recordAction(adminId, PlatformAdminAuditEventType.COMPANY_SUSPENDED,
					"COMPANY", "4242", "approval-1", "suspended for non-payment"));

		JdbcTemplate jdbc = new JdbcTemplate(this.flywayDataSource);
		assertThat(jdbc.queryForObject(
				"SELECT target_type FROM platform_admin_audit_events WHERE platform_admin_id = ?",
				String.class, adminId)).isEqualTo("COMPANY");
		assertThat(jdbc.queryForObject(
				"SELECT target_id FROM platform_admin_audit_events WHERE platform_admin_id = ?",
				String.class, adminId)).isEqualTo("4242");
		assertThat(jdbc.queryForObject(
				"SELECT step_up_approval_id FROM platform_admin_audit_events WHERE platform_admin_id = ?",
				String.class, adminId)).isEqualTo("approval-1");
	}

	@Test
	void recordingAnActionOutsideATransactionFailsLoudly() {
		long adminId = createPlatformAdmin();

		assertThatThrownBy(() -> this.auditService.recordAction(adminId,
				PlatformAdminAuditEventType.COMPANY_SUSPENDED, "COMPANY", "4242", null, null))
			.as("'same transaction as the action' is not a guarantee anyone can make "
					+ "when there is no transaction")
			.isInstanceOf(IllegalTransactionStateException.class);

		assertThat(auditRowsFor(adminId)).isZero();
	}

	@Test
	void authenticationEventsStillSurviveTheirOwnRollback() {
		long adminId = createPlatformAdmin();
		TransactionTemplate transaction = new TransactionTemplate(this.transactionManager);

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			this.auditService.record(adminId, PlatformAdminAuditEventType.LOGIN_FAILED, "wrong password");
			throw new IllegalStateException("the 401 that follows");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(auditRowsFor(adminId))
			.as("a failed-login record that disappears with the rejection it recorded "
					+ "would be no audit trail at all")
			.isOne();
	}

	// --- helpers ------------------------------------------------------------

	private int auditRowsFor(long adminId) {
		Integer count = new JdbcTemplate(this.flywayDataSource).queryForObject(
				"SELECT COUNT(*) FROM platform_admin_audit_events WHERE platform_admin_id = ?",
				Integer.class, adminId);
		return count == null ? 0 : count;
	}

	private long createPlatformAdmin() {
		return new JdbcTemplate(this.flywayDataSource).queryForObject(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, true) RETURNING id",
				Long.class, "+96" + System.nanoTime(), this.passwordEncoder.encode("irrelevant"));
	}

}
