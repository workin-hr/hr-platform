package com.workin.backend.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.security.OpaqueTokens;
import com.workin.backend.tenancy.IdentityMembershipIndexService;

/**
 * The tenant-domain session state machine (ADR-0005 Target Design items
 * 1-3): issue, rotate-with-reuse-detection, logout, revoke-all.
 *
 * <p>Every method that must persist a revocation and <em>also</em>
 * answer 401 returns a result object instead of throwing -- a
 * RuntimeException inside the transaction would roll the revocation
 * back, silently disarming reuse detection. Controllers translate empty
 * results to 401.
 */
@Service
public class RefreshTokenService {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

	private final RefreshTokenRepository refreshTokenRepository;
	private final IdentityRepository identityRepository;
	private final IdentityMembershipIndexService membershipIndexService;
	private final long refreshTokenTtlSeconds;

	public RefreshTokenService(
			RefreshTokenRepository refreshTokenRepository,
			IdentityRepository identityRepository,
			IdentityMembershipIndexService membershipIndexService,
			@Value("${app.jwt.refresh-token-ttl-seconds:5184000}") long refreshTokenTtlSeconds) {
		this.refreshTokenRepository = refreshTokenRepository;
		this.identityRepository = identityRepository;
		this.membershipIndexService = membershipIndexService;
		this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
	}

	@Transactional
	public IssuedRefreshToken issue(Long identityId, Long membershipId, Long companyId) {
		String rawToken = OpaqueTokens.newToken();
		UUID familyId = UUID.randomUUID();
		refreshTokenRepository.save(new RefreshToken(
				identityId, membershipId, companyId, familyId,
				OpaqueTokens.sha256Hex(rawToken),
				Instant.now().plusSeconds(refreshTokenTtlSeconds)));
		return new IssuedRefreshToken(rawToken, familyId);
	}

	@Transactional
	public Optional<RotatedSession> rotate(String presentedToken) {
		Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash(OpaqueTokens.sha256Hex(presentedToken));
		if (found.isEmpty()) {
			return Optional.empty();
		}
		RefreshToken current = found.get();

		if (current.getStatus() != RefreshTokenStatus.ACTIVE) {
			revokeFamilyForReuse(current);
			return Optional.empty();
		}
		if (current.getExpiresAt().isBefore(Instant.now())) {
			return Optional.empty();
		}
		if (refreshTokenRepository.transitionIfStatus(
				current.getId(), RefreshTokenStatus.ACTIVE, RefreshTokenStatus.ROTATED) != 1) {
			revokeFamilyForReuse(current);
			return Optional.empty();
		}

		boolean identityActive = identityRepository.findById(current.getIdentityId())
				.map(Identity::isActive)
				.orElse(false);
		boolean membershipActive = membershipIndexService
				.findMembershipsForIdentity(current.getIdentityId()).stream()
				.anyMatch(membership -> membership.membershipId().equals(current.getMembershipId()));
		if (!identityActive || !membershipActive) {
			refreshTokenRepository.setStatusForFamily(current.getFamilyId(), RefreshTokenStatus.REVOKED);
			return Optional.empty();
		}

		String rawToken = OpaqueTokens.newToken();
		refreshTokenRepository.save(new RefreshToken(
				current.getIdentityId(), current.getMembershipId(), current.getCompanyId(), current.getFamilyId(),
				OpaqueTokens.sha256Hex(rawToken),
				Instant.now().plusSeconds(refreshTokenTtlSeconds)));
		return Optional.of(new RotatedSession(
				rawToken, current.getFamilyId(),
				current.getIdentityId(), current.getMembershipId(), current.getCompanyId()));
	}

	@Transactional
	public void logout(String presentedToken) {
		refreshTokenRepository.findByTokenHash(OpaqueTokens.sha256Hex(presentedToken))
				.ifPresent(token -> refreshTokenRepository
						.setStatusForFamily(token.getFamilyId(), RefreshTokenStatus.REVOKED));
	}

	@Transactional
	public void revokeAllForIdentity(Long identityId) {
		refreshTokenRepository.setStatusForIdentity(identityId, RefreshTokenStatus.REVOKED);
	}

	private void revokeFamilyForReuse(RefreshToken presented) {
		log.warn("Refresh-token reuse detected for identity {} family {} -- revoking the whole family",
				presented.getIdentityId(), presented.getFamilyId());
		refreshTokenRepository.setStatusForFamily(presented.getFamilyId(), RefreshTokenStatus.REVOKED);
	}

	public record IssuedRefreshToken(String rawToken, UUID familyId) {
	}

	public record RotatedSession(String rawToken, UUID familyId, Long identityId, Long membershipId, Long companyId) {
	}

}
