package com.workin.backend.platformadmin.mfa;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.security.OpaqueTokens;

/**
 * TOTP enrolment and verification for platform administrators (ADR-0015
 * prerequisites 1 and 12, D-152).
 *
 * <p>The flow this implements is D-152's, and each step exists to close a
 * specific hole:
 *
 * <ol>
 * <li>An operator issues a bootstrap token for one named administrator. It is
 * random, server-generated, short-lived, single-use, and stored hashed.</li>
 * <li>Enrolment requires the password <em>and</em> that token. A
 * password-authenticated session alone may not claim the first factor -- that
 * is the whole point: otherwise whoever reaches the login first with a stolen
 * password binds their own authenticator.</li>
 * <li>The seed is shown exactly once and is never retrievable afterwards.</li>
 * <li>The token is spent the moment enrolment succeeds.</li>
 * <li>The factor is <em>bound</em> only after a code has actually verified, not
 * when the seed was handed over.</li>
 * </ol>
 *
 * <p>Issuance, use and revocation are each audited, which is why prerequisite 10
 * had to grow first.
 */
@Service
public class PlatformAdminMfaService {

	/**
	 * How long a bootstrap token lives. Short, because it is delivered
	 * out of band to a person who is about to use it -- not a credential to
	 * carry around.
	 */
	public static final Duration BOOTSTRAP_TOKEN_TTL = Duration.ofMinutes(30);

	private final PlatformAdminMfaRepository mfaRepository;
	private final PlatformAdminMfaBootstrapTokenRepository bootstrapTokenRepository;
	private final PlatformAdminAuditService auditService;
	private final TotpSeedCipher seedCipher;

	public PlatformAdminMfaService(
			PlatformAdminMfaRepository mfaRepository,
			PlatformAdminMfaBootstrapTokenRepository bootstrapTokenRepository,
			PlatformAdminAuditService auditService,
			TotpSeedCipher seedCipher) {
		this.mfaRepository = mfaRepository;
		this.bootstrapTokenRepository = bootstrapTokenRepository;
		this.auditService = auditService;
		this.seedCipher = seedCipher;
	}

	/** Whether this administrator has a verified second factor. */
	@Transactional(readOnly = true)
	public boolean isBound(long platformAdminId) {
		return this.mfaRepository.findById(platformAdminId)
				.map(PlatformAdminMfa::isBound)
				.orElse(false);
	}

	/**
	 * Issues a bootstrap token, returning the raw value <b>once</b>.
	 *
	 * <p>Any live token already outstanding for this administrator is revoked
	 * first. Two live tokens would mean two people could each complete an
	 * enrolment, and the second would silently replace the first's factor.
	 *
	 * @param issuedBy the administrator performing the issuance, for attribution
	 */
	@Transactional
	public String issueBootstrapToken(long platformAdminId, long issuedBy) {
		Instant now = Instant.now();
		revokeOutstandingTokens(platformAdminId, now);

		String rawToken = OpaqueTokens.newToken();
		this.bootstrapTokenRepository.save(new PlatformAdminMfaBootstrapToken(
				platformAdminId, OpaqueTokens.sha256Hex(rawToken), now, now.plus(BOOTSTRAP_TOKEN_TTL)));
		this.auditService.recordAction(issuedBy, PlatformAdminAuditEventType.MFA_BOOTSTRAP_TOKEN_ISSUED,
				"PLATFORM_ADMIN", String.valueOf(platformAdminId), null, null);
		return rawToken;
	}

	@Transactional
	public void revokeBootstrapTokens(long platformAdminId, long revokedBy) {
		if (revokeOutstandingTokens(platformAdminId, Instant.now()) > 0) {
			this.auditService.recordAction(revokedBy, PlatformAdminAuditEventType.MFA_BOOTSTRAP_TOKEN_REVOKED,
					"PLATFORM_ADMIN", String.valueOf(platformAdminId), null, null);
		}
	}

	/**
	 * Begins enrolment: consumes nothing yet, but requires a live bootstrap
	 * token, and returns the seed for display exactly once.
	 *
	 * <p>The token is not spent here. It is spent when a code verifies, so an
	 * interrupted enrolment -- the operator closes the page before scanning --
	 * does not burn the token and strand the administrator.
	 *
	 * @return the seed in base32 for an authenticator app, or empty if the
	 *     bootstrap token is not live for this administrator
	 */
	@Transactional
	public Optional<String> beginEnrolment(long platformAdminId, String rawBootstrapToken) {
		if (liveToken(platformAdminId, rawBootstrapToken).isEmpty()) {
			return Optional.empty();
		}
		byte[] seed = Totp.newSeed();
		// Replaces any previous unbound enrolment for this administrator: a
		// half-finished enrolment must not keep a seed alive that nobody holds.
		this.mfaRepository.findById(platformAdminId).ifPresent(existing -> {
			if (!existing.isBound()) {
				this.mfaRepository.delete(existing);
				this.mfaRepository.flush();
			}
		});
		this.mfaRepository.save(new PlatformAdminMfa(
				platformAdminId, this.seedCipher.encrypt(seed, platformAdminId), Instant.now()));
		return Optional.of(Totp.toBase32(seed));
	}

	/**
	 * Completes enrolment: verifies a code, binds the factor and spends the
	 * bootstrap token, all in one transaction.
	 *
	 * <p>Authorised by there being an outstanding token and an unbound seed for
	 * this administrator, both of which only {@link #beginEnrolment} can have
	 * created, and it required the token.
	 *
	 * @return whether the code verified
	 */
	@Transactional
	public boolean confirmEnrolment(long platformAdminId, String code) {
		Instant now = Instant.now();
		// Whichever token is still outstanding for this administrator -- there
		// is at most one, because issuing revokes the rest.
		//
		// The raw token is deliberately NOT carried from the begin step to here.
		// It would have to live in the session, and the session is persisted to
		// the same database that stores the token's hash, which would undo the
		// point of hashing it. The gate is beginEnrolment(): an unbound seed
		// cannot exist without a live token having been presented.
		Optional<PlatformAdminMfaBootstrapToken> token = this.bootstrapTokenRepository
				.findByPlatformAdminIdAndUsedAtIsNullAndRevokedAtIsNull(platformAdminId).stream()
				.filter(candidate -> candidate.isLiveAt(now))
				.findFirst();
		if (token.isEmpty()) {
			return false;
		}
		if (!verifyCode(platformAdminId, code, now)) {
			return false;
		}
		token.get().markUsed(now);
		this.auditService.recordAction(platformAdminId, PlatformAdminAuditEventType.MFA_BOOTSTRAP_TOKEN_USED,
				"PLATFORM_ADMIN", String.valueOf(platformAdminId), null, null);
		this.auditService.recordAction(platformAdminId, PlatformAdminAuditEventType.MFA_ENROLLED,
				"PLATFORM_ADMIN", String.valueOf(platformAdminId), null, null);
		return true;
	}

	/**
	 * Verifies a code for an administrator whose factor is already bound.
	 *
	 * <p>Refuses an unbound factor outright: a seed that has never been
	 * confirmed is not a second factor, and accepting codes against it would
	 * make step 5 of the enrolment ceremony decorative.
	 */
	@Transactional
	public boolean verify(long platformAdminId, String code) {
		return this.mfaRepository.findById(platformAdminId)
				.filter(PlatformAdminMfa::isBound)
				.map(mfa -> verifyCode(platformAdminId, code, Instant.now()))
				.orElse(false);
	}

	/**
	 * The shared verification step, including prerequisite 12's single-use rule.
	 *
	 * <p>Single use applies to the <em>code</em>, not only to whatever the code
	 * authorises. Within one accepted window the same six digits would otherwise
	 * be replayable -- between the login step and a step-up, or to mint several
	 * individually-single-use approvals bound to different targets. Recording the
	 * last accepted time step and refusing anything at or below it closes that.
	 */
	private boolean verifyCode(long platformAdminId, String code, Instant now) {
		PlatformAdminMfa mfa = this.mfaRepository.findById(platformAdminId).orElse(null);
		if (mfa == null) {
			return false;
		}
		byte[] seed = this.seedCipher.decrypt(mfa.seed(), platformAdminId);
		OptionalLong matched = Totp.matchingTimeStep(seed, code, now);
		if (matched.isEmpty()) {
			return false;
		}
		Long lastAccepted = mfa.getLastAcceptedTimeStep();
		if (lastAccepted != null && matched.getAsLong() <= lastAccepted) {
			return false;
		}
		mfa.recordAcceptedCode(matched.getAsLong(), now);
		return true;
	}

	private Optional<PlatformAdminMfaBootstrapToken> liveToken(long platformAdminId, String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return Optional.empty();
		}
		return this.bootstrapTokenRepository.findByTokenHash(OpaqueTokens.sha256Hex(rawToken))
				.filter(token -> token.getPlatformAdminId() == platformAdminId)
				.filter(token -> token.isLiveAt(Instant.now()));
	}

	private int revokeOutstandingTokens(long platformAdminId, Instant now) {
		List<PlatformAdminMfaBootstrapToken> outstanding = this.bootstrapTokenRepository
				.findByPlatformAdminIdAndUsedAtIsNullAndRevokedAtIsNull(platformAdminId);
		outstanding.forEach(token -> token.markRevoked(now));
		return outstanding.size();
	}

}
