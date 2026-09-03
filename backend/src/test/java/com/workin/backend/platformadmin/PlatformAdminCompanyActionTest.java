package com.workin.backend.platformadmin;

import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.platformadmin.mfa.PlatformAdminMfaService;
import com.workin.backend.platformadmin.stepup.PlatformAdminStepUpService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The administrative action, with the surface deliberately switched on.
 *
 * <p>It ships off — ADR-0015 prerequisite 7 is a deployment condition about the
 * legacy PHP surface being unreachable, which code cannot check. The default is
 * asserted separately in {@link PlatformAdminCompanyActionDisabledTest}; this
 * class turns it on so the guards behind it are exercised rather than hidden
 * behind the outermost one.
 */
@TestPropertySource(properties = "app.platform-admin.actions.enabled=true")
class PlatformAdminCompanyActionTest extends AbstractIntegrationTest {

	@Autowired
	private PlatformAdminCompanyService companyService;

	@Autowired
	private PlatformAdminStepUpService stepUpService;

	@Autowired
	private PlatformAdminCompanyDirectory directory;

	@Autowired
	private PlatformAdminMfaService mfaService;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void aSuspensionWithAValidApprovalChangesTheStatusAndWritesItsAudit() {
		Admin admin = enrolledAdmin();
		long company = createCompany();
		String approval = approve(admin, PlatformAdminCompanyService.ACTION_SUSPEND, company, "non-payment");

		assertThat(this.companyService.apply(admin.id(), true,
				PlatformAdminCompanyService.ACTION_SUSPEND, company, "non-payment", approval))
			.isEqualTo(PlatformAdminCompanyService.Outcome.DONE);

		assertThat(statusOf(company)).isEqualTo("suspended");
		assertThat(auditRow(admin.id()))
			.containsExactly("COMPANY_SUSPENDED", "COMPANY", String.valueOf(company), approval);
	}

	@Test
	void anActionWithoutAStepUpApprovalIsRefused() {
		Admin admin = enrolledAdmin();
		long company = createCompany();

		assertThat(this.companyService.apply(admin.id(), true,
				PlatformAdminCompanyService.ACTION_SUSPEND, company, "non-payment", null))
			.isEqualTo(PlatformAdminCompanyService.Outcome.STEP_UP_REJECTED);
		assertThat(statusOf(company)).isEqualTo("active");
	}

	@Test
	void anApprovalForADifferentCompanyDoesNotAuthoriseThisOne() {
		Admin admin = enrolledAdmin();
		long intended = createCompany();
		long other = createCompany();
		String approval = approve(admin, PlatformAdminCompanyService.ACTION_SUSPEND, intended, "non-payment");

		assertThat(this.companyService.apply(admin.id(), true,
				PlatformAdminCompanyService.ACTION_SUSPEND, other, "non-payment", approval))
			.as("the ADR's own example of what target binding is for")
			.isEqualTo(PlatformAdminCompanyService.Outcome.STEP_UP_REJECTED);
		assertThat(statusOf(other)).isEqualTo("active");
	}

	@Test
	void changingTheReasonAfterApprovalInvalidatesIt() {
		Admin admin = enrolledAdmin();
		long company = createCompany();
		String approval = approve(admin, PlatformAdminCompanyService.ACTION_SUSPEND, company, "non-payment");

		assertThat(this.companyService.apply(admin.id(), true,
				PlatformAdminCompanyService.ACTION_SUSPEND, company, "something else", approval))
			.isEqualTo(PlatformAdminCompanyService.Outcome.STEP_UP_REJECTED);
	}

	@Test
	void anAdministratorWithNoBoundFactorCannotAct() {
		Admin admin = enrolledAdmin();
		long company = createCompany();
		String approval = approve(admin, PlatformAdminCompanyService.ACTION_SUSPEND, company, "non-payment");

		assertThat(this.companyService.apply(admin.id(), false,
				PlatformAdminCompanyService.ACTION_SUSPEND, company, "non-payment", approval))
			.as("D-152: existing rows migrate unbound and must not act until bound")
			.isEqualTo(PlatformAdminCompanyService.Outcome.SECOND_FACTOR_NOT_BOUND);
		assertThat(statusOf(company)).isEqualTo("active");
	}

	@Test
	void anApprovalIsSpentEvenIfTheSameOperatorRepeatsTheAction() {
		Admin admin = enrolledAdmin();
		long company = createCompany();
		String approval = approve(admin, PlatformAdminCompanyService.ACTION_SUSPEND, company, "non-payment");

		this.companyService.apply(admin.id(), true,
				PlatformAdminCompanyService.ACTION_SUSPEND, company, "non-payment", approval);

		assertThat(this.companyService.apply(admin.id(), true,
				PlatformAdminCompanyService.ACTION_SUSPEND, company, "non-payment", approval))
			.isEqualTo(PlatformAdminCompanyService.Outcome.STEP_UP_REJECTED);
	}

	@Test
	void aMistypedCompanyIdDoesNotBurnTheApproval() {
		Admin admin = enrolledAdmin();
		long missing = 987_654_321L;
		String approval = approve(admin, PlatformAdminCompanyService.ACTION_SUSPEND, missing, "non-payment");

		assertThatThrownBy(() -> this.companyService.apply(admin.id(), true,
				PlatformAdminCompanyService.ACTION_SUSPEND, missing, "non-payment", approval))
			.isInstanceOf(PlatformAdminCompanyService.CompanyNotFoundException.class);

		assertThat(consumedAt(approval))
			.as("the rollback must take the approval's consumption with it, or a typo "
					+ "costs the operator a second factor round trip")
			.isNull();
	}

	@Test
	void theAuditRowAndTheStatusChangeCommitTogether() {
		Admin admin = enrolledAdmin();
		long company = createCompany();
		String approval = approve(admin, PlatformAdminCompanyService.ACTION_SUSPEND, company, "non-payment");

		this.companyService.apply(admin.id(), true,
				PlatformAdminCompanyService.ACTION_SUSPEND, company, "non-payment", approval);

		// One transaction: a committed change cannot exist without its audit row.
		assertThat(statusOf(company)).isEqualTo("suspended");
		assertThat(auditRow(admin.id())).isNotEmpty();
	}

	@Test
	void rejectingACompanyRecordsWhy() {
		Admin admin = enrolledAdmin();
		long company = createCompany();
		String approval = approve(admin, PlatformAdminCompanyService.ACTION_REJECT, company,
				"incomplete commercial registration");

		assertThat(this.companyService.apply(admin.id(), true,
				PlatformAdminCompanyService.ACTION_REJECT, company,
				"incomplete commercial registration", approval))
			.isEqualTo(PlatformAdminCompanyService.Outcome.DONE);

		assertThat(statusOf(company)).isEqualTo("rejected");
		assertThat(new JdbcTemplate(this.flywayDataSource).queryForObject(
				"SELECT rejection_reason FROM companies WHERE id = ?", String.class, company))
			.as("the PHP dashboard records why a company was rejected; losing that on "
					+ "the Java side would make the same operation mean less")
			.isEqualTo("incomplete commercial registration");
	}

	@Test
	void approvingAPendingCompanyActivatesItAndLeavesAnyOldReasonAlone() {
		Admin admin = enrolledAdmin();
		long company = createCompany();
		new JdbcTemplate(this.flywayDataSource).update(
				"UPDATE companies SET status = 'pending', rejection_reason = ? WHERE id = ?",
				"an earlier rejection", company);
		String approval = approve(admin, PlatformAdminCompanyService.ACTION_APPROVE, company, "documents verified");

		assertThat(this.companyService.apply(admin.id(), true,
				PlatformAdminCompanyService.ACTION_APPROVE, company, "documents verified", approval))
			.isEqualTo(PlatformAdminCompanyService.Outcome.DONE);

		assertThat(statusOf(company)).isEqualTo("active");
		assertThat(new JdbcTemplate(this.flywayDataSource).queryForObject(
				"SELECT rejection_reason FROM companies WHERE id = ?", String.class, company))
			.as("approving does not clear the previous reason -- PHP leaves it too, and "
					+ "clearing it would erase why the company was once rejected")
			.isEqualTo("an earlier rejection");
	}

	@Test
	void theDetailViewCountsOutstandingWork() {
		long company = createCompany();

		assertThat(this.directory.detail(company))
			.get()
			.satisfies(detail -> {
				assertThat(detail.company().id()).isEqualTo(company);
				assertThat(detail.pendingRequests()).isZero();
				assertThat(detail.pendingAdvances()).isZero();
			});
	}

	@Test
	void theDetailViewIsEmptyForACompanyThatDoesNotExist() {
		assertThat(this.directory.detail(987_654_321L)).isEmpty();
	}

	// --- helpers ------------------------------------------------------------

	private record Admin(long id, String seed) {
	}

	private String approve(Admin admin, String action, long companyId, String reason) {
		String code = com.workin.backend.platformadmin.mfa.Totp.codeAt(
				fromBase32(admin.seed()),
				com.workin.backend.platformadmin.mfa.Totp.timeStepAt(Instant.now()) + 1);
		return this.stepUpService.approve(admin.id(),
				this.companyService.request(action, companyId, reason), code).orElseThrow();
	}

	private String statusOf(long companyId) {
		return new JdbcTemplate(this.flywayDataSource).queryForObject(
				"SELECT status FROM companies WHERE id = ?", String.class, companyId);
	}

	private Object consumedAt(String approvalId) {
		return new JdbcTemplate(this.flywayDataSource).queryForObject(
				"SELECT consumed_at FROM platform_admin_step_up_approvals WHERE id = ?",
				Object.class, approvalId);
	}

	private List<String> auditRow(long adminId) {
		return new JdbcTemplate(this.flywayDataSource).query(
				"SELECT event_type, target_type, target_id, step_up_approval_id "
						+ "FROM platform_admin_audit_events WHERE platform_admin_id = ? "
						+ "AND event_type LIKE 'COMPANY%'",
				(rs, i) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
				adminId)
			.stream().findFirst().orElse(List.of());
	}

	private long createCompany() {
		return new JdbcTemplate(this.flywayDataSource).queryForObject(
				"INSERT INTO companies (name, phone, active, status) VALUES (?, ?, true, 'active') RETURNING id",
				Long.class, "Fixture " + System.nanoTime(), "+92" + System.nanoTime());
	}

	private Admin enrolledAdmin() {
		long id = new JdbcTemplate(this.flywayDataSource).queryForObject(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, true) RETURNING id",
				Long.class, "+91" + System.nanoTime(), this.passwordEncoder.encode("irrelevant"));
		return new Admin(id, PlatformAdminMfaTestSupport.enrol(this.mfaService, id));
	}

	private static byte[] fromBase32(String encoded) {
		final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int buffer = 0;
		int bitsLeft = 0;
		for (char c : encoded.toCharArray()) {
			buffer = (buffer << 5) | alphabet.indexOf(c);
			bitsLeft += 5;
			if (bitsLeft >= 8) {
				out.write((buffer >> (bitsLeft - 8)) & 0xFF);
				bitsLeft -= 8;
			}
		}
		return out.toByteArray();
	}

}
