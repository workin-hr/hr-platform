package com.workin.backend.platformadmin;

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
		refreshTokenRepository.save(new PlatformAdminRefreshToken(
				platformAdminId, familyId,
				OpaqueTokens.sha256Hex(rawToken),
				Instant.now().plusSeconds(refreshTokenTtlSeconds)));
		return new IssuedRefreshToken(rawToken, familyId);
	}

	@Transactional
	public Optional<RotatedSession> rotate(String presentedToken) {
		Optional<PlatformAdminRefreshToken> found = refreshTokenRepository
				.findByTokenHash(OpaqueTokens.sha256Hex(presentedToken));
		if (found.isEmpty()) {
			return Optional.empty();
		}
		PlatformAdminRefreshToken current = found.get();

		if (current.getStatus() != PlatformAdminSessionStatus.ACTIVE) {
			revokeFamilyForReuse(current);
			return Optional.empty();
		}
		if (current.getExpiresAt().isBefore(Instant.now())) {
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
		refreshTokenRepository.save(new PlatformAdminRefreshToken(
				current.getPlatformAdminId(), current.getFamilyId(),
				OpaqueTokens.sha256Hex(rawToken),
				Instant.now().plusSeconds(refreshTokenTtlSeconds)));
		return Optional.of(new RotatedSession(rawToken, current.getFamilyId(), current.getPlatformAdminId()));
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

	public record IssuedRefreshToken(String rawToken, UUID familyId) {
	}

	public record RotatedSession(String rawToken, UUID familyId, Long platformAdminId) {
	}

}
