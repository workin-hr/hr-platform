package com.workin.backend.platformadmin;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.security.OpaqueTokens;

/**
 * Platform-domain session state machine -- the individual-session
 * revocation half of F-26's remaining closure criteria. Same
 * result-object-not-exception contract as the tenant domain's
 * RefreshTokenService: revocation side effects must commit even when
 * the HTTP answer is 401.
 */
@Service
public class PlatformAdminSessionService {

	private static final Logger log = LoggerFactory.getLogger(PlatformAdminSessionService.class);

	private final PlatformAdminRefreshTokenRepository refreshTokenRepository;
	private final PlatformAdminRepository platformAdminRepository;
	private final PlatformAdminAuditService auditService;
	private final long refreshTokenTtlSeconds;

	public PlatformAdminSessionService(
			PlatformAdminRefreshTokenRepository refreshTokenRepository,
			PlatformAdminRepository platformAdminRepository,
			PlatformAdminAuditService auditService,
			@Value("${app.platform-admin.jwt.refresh-token-ttl-seconds:604800}") long refreshTokenTtlSeconds) {
		this.refreshTokenRepository = refreshTokenRepository;
		this.platformAdminRepository = platformAdminRepository;
		this.auditService = auditService;
		this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
	}

	@Transactional
	public IssuedRefreshToken issue(Long platformAdminId) {
		String rawToken = OpaqueTokens.newToken();
		UUID familyId = UUID.randomUUID();
		Instant now = Instant.now();
		refreshTokenRepository.save(new PlatformAdminRefreshToken(
				platformAdminId, familyId,
				OpaqueTokens.sha256Hex(rawToken),
				now.plusSeconds(refreshTokenTtlSeconds),
				now));
		return new IssuedRefreshToken(rawToken, familyId, now.plus(FAMILY_ABSOLUTE_CAP));
	}

	/**
	 * How long a refresh-token family may live from its first authentication,
	 * however often it is rotated (ADR-0015 prerequisite 4).
	 *
	 * <p>Equal to the configured refresh-token lifetime rather than shorter, so
	 * this change bounds the family without shortening any single token that a
	 * client already relies on: what it removes is the *sliding*, not the
	 * window. An administrator who keeps refreshing now re-authenticates once a
	 * week instead of never.
	 *
	 * <p>Deliberately a constant, not configuration: a property that can widen
	 * the cap is a property that can remove it, and this is the highest-privilege
	 * surface in the system.
	 */
	public static final Duration FAMILY_ABSOLUTE_CAP = Duration.ofDays(7);

	@Transactional
	public Optional<RotatedSession> rotate(String presentedToken) {
		Optional<PlatformAdminRefreshToken> found = refreshTokenRepository
				.findByTokenHash(OpaqueTokens.sha256Hex(presentedToken));
		if (found.isEmpty()) {
			return Optional.empty();
		}
		PlatformAdminRefreshToken current = found.get();
		Instant now = Instant.now();

		// The cap, before anything else: a family past its origin + cap is over,
		// however valid the token presented against it still looks.
		if (!now.isBefore(current.getFamilyStartedAt().plus(FAMILY_ABSOLUTE_CAP))) {
			refreshTokenRepository.setStatusForFamily(current.getFamilyId(), PlatformAdminSessionStatus.REVOKED);
			return Optional.empty();
		}

		if (current.getStatus() != PlatformAdminSessionStatus.ACTIVE) {
			revokeFamilyForReuse(current);
			return Optional.empty();
		}
		if (current.getExpiresAt().isBefore(now)) {
			return Optional.empty();
		}
		if (refreshTokenRepository.transitionIfStatus(
				current.getId(), PlatformAdminSessionStatus.ACTIVE, PlatformAdminSessionStatus.ROTATED) != 1) {
			revokeFamilyForReuse(current);
			return Optional.empty();
		}

		boolean adminActive = platformAdminRepository.findById(current.getPlatformAdminId())
				.map(PlatformAdmin::isActive)
				.orElse(false);
		if (!adminActive) {
			refreshTokenRepository.setStatusForFamily(current.getFamilyId(), PlatformAdminSessionStatus.REVOKED);
			return Optional.empty();
		}

		String rawToken = OpaqueTokens.newToken();
		// Clamp: a successor may not outlive the family's cap, or the cap would
		// be enforced on rotation and then handed out again in the token that
		// rotation produced.
		Instant familyEnd = current.getFamilyStartedAt().plus(FAMILY_ABSOLUTE_CAP);
		Instant successorExpiry = earliest(now.plusSeconds(refreshTokenTtlSeconds), familyEnd);
		refreshTokenRepository.save(new PlatformAdminRefreshToken(
				current.getPlatformAdminId(), current.getFamilyId(),
				OpaqueTokens.sha256Hex(rawToken),
				successorExpiry,
				current.getFamilyStartedAt()));
		return Optional.of(new RotatedSession(
				rawToken, current.getFamilyId(), current.getPlatformAdminId(), familyEnd));
	}

	@Transactional
	public void logout(String presentedToken) {
		refreshTokenRepository.findByTokenHash(OpaqueTokens.sha256Hex(presentedToken))
				.ifPresent(token -> {
					refreshTokenRepository
							.setStatusForFamily(token.getFamilyId(), PlatformAdminSessionStatus.REVOKED);
					auditService.record(token.getPlatformAdminId(), PlatformAdminAuditEventType.LOGOUT,
							"family " + token.getFamilyId());
				});
	}

	@Transactional
	public void revokeAllForPlatformAdmin(Long platformAdminId) {
		refreshTokenRepository.setStatusForPlatformAdmin(platformAdminId, PlatformAdminSessionStatus.REVOKED);
		auditService.record(platformAdminId, PlatformAdminAuditEventType.ALL_SESSIONS_REVOKED, null);
	}

	private void revokeFamilyForReuse(PlatformAdminRefreshToken presented) {
		log.warn("Platform-admin refresh-token reuse detected for admin {} family {} -- revoking the whole family",
				presented.getPlatformAdminId(), presented.getFamilyId());
		refreshTokenRepository.setStatusForFamily(presented.getFamilyId(), PlatformAdminSessionStatus.REVOKED);
		auditService.record(presented.getPlatformAdminId(), PlatformAdminAuditEventType.SESSION_REUSE_REVOKED,
				"family " + presented.getFamilyId());
	}

	private static Instant earliest(Instant left, Instant right) {
		return left.isBefore(right) ? left : right;
	}

	/** {@code familyEndsAt} is the deadline an access token must be clamped to. */
	public record IssuedRefreshToken(String rawToken, UUID familyId, Instant familyEndsAt) {
	}

	public record RotatedSession(String rawToken, UUID familyId, Long platformAdminId, Instant familyEndsAt) {
	}

}
