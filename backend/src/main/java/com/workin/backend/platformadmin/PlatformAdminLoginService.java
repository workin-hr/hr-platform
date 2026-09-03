package com.workin.backend.platformadmin;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.workin.backend.i18n.ApiException;
import com.workin.backend.i18n.MessageKeys;

/**
 * Authenticates a platform administrator by phone/password. There is no
 * self-registration counterpart -- platform-admin accounts are only
 * ever created by {@link PlatformAdminBootstrap} or, in later work, an
 * authenticated admin-management endpoint (not built in this slice).
 *
 * <p>Failed attempts against a <em>known</em> admin are audit-attributed
 * (wrong password / inactive account); attempts against unknown phones
 * cannot be attributed to an individual and stay in structured logging
 * only. The audit write commits in its own transaction
 * ({@link PlatformAdminAuditService}), so the 401 thrown right after it
 * cannot roll it back.
 *
 * <p><b>Both surfaces authenticate here</b> -- the bearer API and the JTE admin
 * UI (ADR-0015). That is deliberate: the ADR names authorization drift between
 * the two as a risk, so hardening added here is inherited by both rather than
 * implemented twice and diverging.
 *
 * <h2>Throttling and the unknown-identifier path (ADR-0015 prerequisite 3)</h2>
 *
 * An earlier version of this method threw the moment {@code findByPhone} missed,
 * and ran BCrypt only for an existing row. That gave an unauthenticated caller
 * two things: a timing oracle telling them which phone numbers are
 * administrators, and an unlimited budget against the ones that are not. Both
 * are closed here, and neither can be closed in a caller:
 *
 * <ul>
 * <li>a miss verifies the supplied password against a <b>fixed dummy hash</b>,
 * so the expensive work happens either way;</li>
 * <li>a miss consumes the <b>same budget</b> as a hit.</li>
 * </ul>
 *
 * <p>An exhausted budget answers with the same 401 and the same message as a
 * wrong password. That is a deliberate trade: a distinct status would tell an
 * attacker exactly when to back off, and it would change the bearer API's
 * response contract, which ADR-0015 prerequisite 8 will revisit on its own
 * terms. The cost is that a locked-out administrator sees "invalid credentials"
 * rather than "try again later"; the lockout is visible to operators through
 * {@code platform_admin_audit_events} instead.
 */
@Service
public class PlatformAdminLoginService {

	/**
	 * A real BCrypt hash of a value no caller can supply, used only to spend the
	 * same CPU on a miss as on a hit. It must be a well-formed hash of the same
	 * cost as production's, or the timing signal it exists to remove comes back.
	 */
	private static final String DUMMY_HASH =
			"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final PlatformAdminRepository platformAdminRepository;
	private final PasswordEncoder passwordEncoder;
	private final PlatformAdminAuditService auditService;
	private final PlatformAdminLoginThrottle throttle;

	public PlatformAdminLoginService(
			PlatformAdminRepository platformAdminRepository,
			PasswordEncoder passwordEncoder,
			PlatformAdminAuditService auditService,
			PlatformAdminLoginThrottle throttle) {
		this.platformAdminRepository = platformAdminRepository;
		this.passwordEncoder = passwordEncoder;
		this.auditService = auditService;
		this.throttle = throttle;
	}

	public PlatformAdmin login(PlatformAdminLoginRequest request) {
		String phone = request.phone();

		if (this.throttle.isExhausted(phone)) {
			// Deliberately no password verification: a spent budget is refused
			// before the credential is examined at all, so a throttled caller
			// cannot use the endpoint as an oracle even with a correct password.
			throw invalidCredentials();
		}

		PlatformAdmin admin = this.platformAdminRepository.findByPhone(phone).orElse(null);
		// Always verify something, so a miss and a hit cost the same.
		boolean passwordMatches = this.passwordEncoder.matches(
				request.password(), admin != null ? admin.getPasswordHash() : DUMMY_HASH);

		if (admin == null || !passwordMatches || !admin.isActive()) {
			this.throttle.recordFailure(phone);
			if (admin != null) {
				this.auditService.record(admin.getId(), PlatformAdminAuditEventType.LOGIN_FAILED,
						admin.isActive() ? "wrong password" : "inactive account");
			}
			throw invalidCredentials();
		}

		this.throttle.clear(phone);
		this.auditService.record(admin.getId(), PlatformAdminAuditEventType.LOGIN, null);
		return admin;
	}

	private static ApiException invalidCredentials() {
		return new ApiException(HttpStatus.UNAUTHORIZED, MessageKeys.AUTH_INVALID_CREDENTIALS);
	}

}
