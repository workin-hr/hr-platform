package com.workin.backend.platformadmin.stepup;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.platformadmin.mfa.PlatformAdminMfaService;
import com.workin.backend.platformadmin.mfa.Totp;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0015 prerequisite 2's four bounds, one test each, plus the race that
 * "single use" actually has to survive.
 */
class PlatformAdminStepUpServiceTest extends AbstractIntegrationTest {


	@Autowired
	private PlatformAdminStepUpService stepUpService;

	@Autowired
	private PlatformAdminMfaService mfaService;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private static final PlatformAdminStepUpService.Request SUSPEND_42 =
			new PlatformAdminStepUpService.Request("COMPANY_SUSPEND", "COMPANY", "42",
					List.of("reason=non-payment"));

	@Test
	void anApprovalAuthorisesExactlyTheRequestItWasMintedFor() {
		Enrolled admin = enrolledAdmin();
		String approval = approve(admin, SUSPEND_42);

		assertThat(consume(admin.id(), approval, SUSPEND_42)).isTrue();
	}

	@Test
	void aWrongCodeMintsNothing() {
		Enrolled admin = enrolledAdmin();

		assertThat(this.stepUpService.approve(admin.id(), SUSPEND_42, "000000"))
			.as("the second factor is the whole point of a step-up")
			.isEmpty();
	}

	// --- bound to the action ------------------------------------------------

	@Test
	void anApprovalForOneActionDoesNotAuthoriseAnother() {
		Enrolled admin = enrolledAdmin();
		String approval = approve(admin, SUSPEND_42);

		assertThat(consume(admin.id(), approval, new PlatformAdminStepUpService.Request(
				"COMPANY_DELETE", "COMPANY", "42", List.of("reason=non-payment"))))
			.as("an approval for 'suspend' must not authorise 'delete'")
			.isFalse();
	}

	// --- bound to the target ------------------------------------------------

	@Test
	void anApprovalForOneCompanyDoesNotAuthoriseAnother() {
		Enrolled admin = enrolledAdmin();
		String approval = approve(admin, SUSPEND_42);

		assertThat(consume(admin.id(), approval, new PlatformAdminStepUpService.Request(
				"COMPANY_SUSPEND", "COMPANY", "43", List.of("reason=non-payment"))))
			.as("the ADR's own example: an approval bound to 'suspend' but not to which "
					+ "company is consumable against a different tenant")
			.isFalse();
	}

	// --- bound to the request digest ----------------------------------------

	@Test
	void changingASecurityRelevantParameterInvalidatesTheApproval() {
		Enrolled admin = enrolledAdmin();
		String approval = approve(admin, SUSPEND_42);

		assertThat(consume(admin.id(), approval, new PlatformAdminStepUpService.Request(
				"COMPANY_SUSPEND", "COMPANY", "42", List.of("reason=something-else"))))
			.isFalse();
	}

	@Test
	void aShiftedParameterBoundaryDoesNotProduceTheSameDigest() {
		String left = new PlatformAdminStepUpService.Request(
				"A", "T", "1", List.of("a", "b|c")).digest();
		String right = new PlatformAdminStepUpService.Request(
				"A", "T", "1", List.of("a|b", "c")).digest();

		assertThat(left)
			.as("a delimiter-joined canonical form would let an attacker who controls two "
					+ "adjacent parameters shift the boundary and reuse an approval")
			.isNotEqualTo(right);
	}

	// --- single use ---------------------------------------------------------

	@Test
	void anApprovalIsSpentByItsFirstUse() {
		Enrolled admin = enrolledAdmin();
		String approval = approve(admin, SUSPEND_42);

		assertThat(consume(admin.id(), approval, SUSPEND_42)).isTrue();
		assertThat(consume(admin.id(), approval, SUSPEND_42)).isFalse();
	}

	@Test
	void concurrentConsumptionSpendsTheApprovalExactlyOnce() throws Exception {
		Enrolled admin = enrolledAdmin();
		String approval = approve(admin, SUSPEND_42);

		try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
			List<Callable<Boolean>> attempts = java.util.Collections.nCopies(8,
					() -> consume(admin.id(), approval, SUSPEND_42));
			long successes = pool.invokeAll(attempts).stream().map(this::get).filter(Boolean::booleanValue).count();

			assertThat(successes)
				.as("a read-then-write would let several racing requests all see it unspent -- "
						+ "which is the shape that only appears under load")
				.isEqualTo(1);
		}
	}

	// --- maximum age --------------------------------------------------------

	@Test
	void anExpiredApprovalAuthorisesNothing() {
		Enrolled admin = enrolledAdmin();
		String approval = approve(admin, SUSPEND_42);
		new JdbcTemplate(this.flywayDataSource).update(
				"UPDATE platform_admin_step_up_approvals SET expires_at = ? WHERE id = ?",
				java.sql.Timestamp.from(Instant.now().minusSeconds(1)), approval);

		assertThat(consume(admin.id(), approval, SUSPEND_42)).isFalse();
	}

	// --- ownership ----------------------------------------------------------

	@Test
	void oneAdministratorCannotSpendAnothersApproval() {
		Enrolled owner = enrolledAdmin();
		Enrolled other = enrolledAdmin();
		String approval = approve(owner, SUSPEND_42);

		assertThat(consume(other.id(), approval, SUSPEND_42))
			.as("an approval id is an opaque string; without scoping, anyone holding one could spend it")
			.isFalse();
		assertThat(consume(owner.id(), approval, SUSPEND_42)).isTrue();
	}

	@Test
	void aGarbageApprovalIdIsRefusedWithoutThrowing() {
		Enrolled admin = enrolledAdmin();

		assertThat(consume(admin.id(), "not-an-approval", SUSPEND_42)).isFalse();
		assertThat(consume(admin.id(), "", SUSPEND_42)).isFalse();
		assertThat(consume(admin.id(), null, SUSPEND_42)).isFalse();
	}

	// --- helpers ------------------------------------------------------------

	private record Enrolled(long id, String seed) {
	}

	private boolean get(Future<Boolean> future) {
		try {
			return future.get();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Consumption is MANDATORY, so it always runs inside a transaction. */
	private boolean consume(long adminId, String approvalId, PlatformAdminStepUpService.Request request) {
		return Boolean.TRUE.equals(new TransactionTemplate(this.transactionManager).execute(
				status -> this.stepUpService.consume(adminId, approvalId, request)));
	}

	private String approve(Enrolled admin, PlatformAdminStepUpService.Request request) {
		// A step later than the one enrolment spent, per prerequisite 12.
		String code = Totp.codeAt(fromBase32(admin.seed()), Totp.timeStepAt(Instant.now()) + 1);
		return this.stepUpService.approve(admin.id(), request, code).orElseThrow();
	}

	private Enrolled enrolledAdmin() {
		long id = new JdbcTemplate(this.flywayDataSource).queryForObject(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, true) RETURNING id",
				Long.class, "+93" + System.nanoTime(), this.passwordEncoder.encode("irrelevant"));
		String token = this.mfaService.issueBootstrapToken(id, id);
		String seed = this.mfaService.beginEnrolment(id, token).orElseThrow();
		this.mfaService.confirmEnrolment(id,
				Totp.codeAt(fromBase32(seed), Totp.timeStepAt(Instant.now())));
		return new Enrolled(id, seed);
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
