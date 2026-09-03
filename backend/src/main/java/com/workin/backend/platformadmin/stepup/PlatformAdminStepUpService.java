package com.workin.backend.platformadmin.stepup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.mfa.PlatformAdminMfaService;

/**
 * Mints and consumes step-up approvals (ADR-0015 prerequisite 2).
 *
 * <p>An approval is minted by verifying a fresh TOTP code and is bound, at that
 * moment, to <em>one</em> action against <em>one</em> target with <em>one</em>
 * set of security-relevant parameters. Consumption recomputes all of that from
 * the request about to be performed and refuses anything that does not match.
 *
 * <p>The digest is the part that is easy to get wrong. It must be computed by
 * the server from the request it is <em>actually going to execute</em>, never
 * taken from the caller: a digest the client supplies proves only that the
 * client can compute a digest.
 */
@Service
public class PlatformAdminStepUpService {

	/**
	 * Maximum age. Short enough that an approval cannot be harvested and used
	 * later, long enough to read a confirmation page.
	 */
	public static final Duration MAX_AGE = Duration.ofMinutes(5);

	private static final SecureRandom RANDOM = new SecureRandom();

	private final PlatformAdminStepUpApprovalRepository approvalRepository;
	private final PlatformAdminMfaService mfaService;
	private final PlatformAdminAuditService auditService;

	public PlatformAdminStepUpService(
			PlatformAdminStepUpApprovalRepository approvalRepository,
			PlatformAdminMfaService mfaService,
			PlatformAdminAuditService auditService) {
		this.approvalRepository = approvalRepository;
		this.mfaService = mfaService;
		this.auditService = auditService;
	}

	/**
	 * The canonical description of what an approval authorises.
	 *
	 * @param action the canonical operation, not a URL
	 * @param targetType what kind of thing it acts on
	 * @param targetId which one
	 * @param parameters the security-relevant request parameters, in a fixed order
	 */
	public record Request(String action, String targetType, String targetId, List<String> parameters) {

		/**
		 * The digest the approval is bound to.
		 *
		 * <p>Length-prefixed rather than joined by a separator: with a plain
		 * delimiter, {@code ["a", "b|c"]} and {@code ["a|b", "c"]} produce the
		 * same string, so an attacker who controls two adjacent parameters could
		 * shift a boundary and reuse an approval for a materially different
		 * request.
		 */
		String digest() {
			StringBuilder canonical = new StringBuilder();
			for (String part : List.of(this.action, this.targetType, this.targetId)) {
				canonical.append(part.length()).append(':').append(part).append('|');
			}
			for (String parameter : this.parameters) {
				String value = parameter == null ? "" : parameter;
				canonical.append(value.length()).append(':').append(value).append('|');
			}
			return sha256(canonical.toString());
		}

	}

	/**
	 * Verifies a TOTP code and mints an approval bound to this exact request.
	 *
	 * @return the approval id, or empty if the code was not accepted
	 */
	@Transactional
	public Optional<String> approve(long platformAdminId, Request request, String totpCode) {
		// The second factor is the whole point of a step-up: without this the
		// approval would be minted by whoever already holds the session, which
		// is exactly the case a step-up exists to distrust.
		if (!this.mfaService.verify(platformAdminId, totpCode)) {
			return Optional.empty();
		}
		Instant now = Instant.now();
		byte[] raw = new byte[24];
		RANDOM.nextBytes(raw);
		String id = HexFormat.of().formatHex(raw);
		this.approvalRepository.save(new PlatformAdminStepUpApproval(
				id, platformAdminId, request.action(), request.targetType(), request.targetId(),
				request.digest(), now, now.plus(MAX_AGE)));
		this.auditService.recordAction(platformAdminId, PlatformAdminAuditEventType.STEP_UP_APPROVED,
				request.targetType(), request.targetId(), id, request.action());
		return Optional.of(id);
	}

	/**
	 * Consumes an approval for the request about to be performed.
	 *
	 * <p>Joins the caller's transaction, so the approval is spent in the same
	 * transaction as the action it authorises: an action that rolls back must
	 * not leave its approval spent, and an approval spent without its action
	 * would be worse.
	 *
	 * @return whether the approval was valid for exactly this request
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean consume(long platformAdminId, String approvalId, Request request) {
		if (approvalId == null || approvalId.isBlank()) {
			return false;
		}
		// Scoped to the caller: an approval id is an opaque string, and without
		// this an administrator could spend somebody else's.
		Optional<PlatformAdminStepUpApproval> found =
				this.approvalRepository.findByIdAndPlatformAdminId(approvalId, platformAdminId);
		if (found.isEmpty()) {
			return false;
		}
		PlatformAdminStepUpApproval approval = found.get();
		Instant now = Instant.now();

		boolean matches = approval.getConsumedAt() == null
				&& now.isBefore(approval.getExpiresAt())
				&& approval.getAction().equals(request.action())
				&& approval.getTargetType().equals(request.targetType())
				&& approval.getTargetId().equals(request.targetId())
				// Recomputed here, from the request being performed.
				&& MessageDigest.isEqual(
						approval.getRequestDigest().getBytes(StandardCharsets.US_ASCII),
						request.digest().getBytes(StandardCharsets.US_ASCII));
		if (!matches) {
			return false;
		}
		// The database decides the single-use race, not the check above.
		return this.approvalRepository.consume(approvalId, now) == 1;
	}

	/** Expired approvals can never authorise anything; they are not kept. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int purgeExpired() {
		return this.approvalRepository.deleteExpiredBefore(Instant.now());
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

}
