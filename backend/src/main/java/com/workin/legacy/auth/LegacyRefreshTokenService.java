package com.workin.legacy.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.security.OpaqueTokens;
import com.workin.legacy.TenantFilterActivator;
import com.workin.legacy.employees.LegacyEmployeeRepository;

/**
 * Legacy's counterpart to {@code com.workin.backend.identity.RefreshTokenService}
 * (punch-list item #9): issue, rotate-with-reuse-detection, logout,
 * revoke-all -- for a legacy-authenticated identity (an employee row)
 * rather than a PostgreSQL identity/membership pair.
 *
 * <p>The one deliberate divergence is the liveness re-check inside
 * {@link #rotate}. The PostgreSQL service re-checks both identity
 * activity and membership activity, because those are two independently
 * revocable things. Legacy has no separate membership: one
 * {@code employees} row is both, so re-checking {@code employee.active()}
 * once is the complete equivalent, not an abbreviated one.
 *
 * <p>That re-check queries {@code employees}, a tenant-filtered entity,
 * from inside a call that -- like login -- runs before any
 * {@link com.workin.backend.tenancy.TenantScope} is established. Without
 * {@link TenantFilterActivator#deactivateForPreTenantLookup()} first, the
 * {@code NO_TENANT} sentinel {@code TenantAwareJpaTransactionManager}
 * binds to every fresh transaction would make the liveness lookup itself
 * return zero rows unconditionally, turning every legitimate rotation
 * into a false-positive revocation -- the same trap
 * {@code LegacyTenantContextService} already named and avoided.
 *
 * <p>Same reason for returning a result object instead of throwing on the
 * failure paths that must also persist a revocation: a
 * {@code RuntimeException} inside the {@code @Transactional} method would
 * roll the revocation back, silently disarming reuse detection.
 */
@Service
public class LegacyRefreshTokenService {

	private static final Logger log = LoggerFactory.getLogger(LegacyRefreshTokenService.class);

	private final LegacyRefreshTokenRepository legacyRefreshTokenRepository;
	private final LegacyEmployeeRepository legacyEmployeeRepository;
	private final TenantFilterActivator tenantFilterActivator;
	private final long refreshTokenTtlSeconds;

	public LegacyRefreshTokenService(
			LegacyRefreshTokenRepository legacyRefreshTokenRepository,
			LegacyEmployeeRepository legacyEmployeeRepository,
			TenantFilterActivator tenantFilterActivator,
			@Value("${app.jwt.refresh-token-ttl-seconds:5184000}") long refreshTokenTtlSeconds) {
		this.legacyRefreshTokenRepository = legacyRefreshTokenRepository;
		this.legacyEmployeeRepository = legacyEmployeeRepository;
		this.tenantFilterActivator = tenantFilterActivator;
		this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
	}

	@Transactional
	public IssuedLegacyRefreshToken issue(Long employeeId) {
		String rawToken = OpaqueTokens.newToken();
		String familyId = UUID.randomUUID().toString();
		legacyRefreshTokenRepository.save(new LegacyRefreshToken(
				employeeId, familyId, OpaqueTokens.sha256Hex(rawToken),
				Instant.now().plusSeconds(refreshTokenTtlSeconds)));
		return new IssuedLegacyRefreshToken(rawToken, familyId);
	}

	@Transactional
	public Optional<RotatedLegacySession> rotate(String presentedToken) {
		Optional<LegacyRefreshToken> found =
				legacyRefreshTokenRepository.findByTokenHash(OpaqueTokens.sha256Hex(presentedToken));
		if (found.isEmpty()) {
			return Optional.empty();
		}
		LegacyRefreshToken current = found.get();

		if (current.getStatus() != LegacyRefreshTokenStatus.ACTIVE) {
			revokeFamilyForReuse(current);
			return Optional.empty();
		}
		if (current.getExpiresAt().isBefore(Instant.now())) {
			return Optional.empty();
		}
		if (legacyRefreshTokenRepository.transitionIfStatus(
				current.getId(), LegacyRefreshTokenStatus.ACTIVE, LegacyRefreshTokenStatus.ROTATED) != 1) {
			revokeFamilyForReuse(current);
			return Optional.empty();
		}

		tenantFilterActivator.deactivateForPreTenantLookup();
		boolean employeeActive = legacyEmployeeRepository.findById(current.getEmployeeId())
				.map(employee -> employee.active())
				.orElse(false);
		if (!employeeActive) {
			legacyRefreshTokenRepository.setStatusForFamily(current.getFamilyId(), LegacyRefreshTokenStatus.REVOKED);
			return Optional.empty();
		}

		String rawToken = OpaqueTokens.newToken();
		legacyRefreshTokenRepository.save(new LegacyRefreshToken(
				current.getEmployeeId(), current.getFamilyId(), OpaqueTokens.sha256Hex(rawToken),
				Instant.now().plusSeconds(refreshTokenTtlSeconds)));
		return Optional.of(new RotatedLegacySession(rawToken, current.getFamilyId(), current.getEmployeeId()));
	}

	@Transactional
	public void logout(String presentedToken) {
		legacyRefreshTokenRepository.findByTokenHash(OpaqueTokens.sha256Hex(presentedToken))
				.ifPresent(token -> legacyRefreshTokenRepository
						.setStatusForFamily(token.getFamilyId(), LegacyRefreshTokenStatus.REVOKED));
	}

	@Transactional
	public void revokeAllForEmployee(Long employeeId) {
		legacyRefreshTokenRepository.setStatusForEmployee(employeeId, LegacyRefreshTokenStatus.REVOKED);
	}

	private void revokeFamilyForReuse(LegacyRefreshToken presented) {
		log.warn("Legacy refresh-token reuse detected for employee {} family {} -- revoking the whole family",
				presented.getEmployeeId(), presented.getFamilyId());
		legacyRefreshTokenRepository.setStatusForFamily(presented.getFamilyId(), LegacyRefreshTokenStatus.REVOKED);
	}

	public record IssuedLegacyRefreshToken(String rawToken, String familyId) {
	}

	public record RotatedLegacySession(String rawToken, String familyId, Long employeeId) {
	}

}
