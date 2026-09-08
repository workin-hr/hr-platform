package com.workin.backend.platformadmin;

import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * The dashboard's login: one administrator, one password (ADR-0018).
 *
 * <p>This is {@code doAdminLogin()} with the guards PHP does not have. PHP
 * compares the submitted password against a constant with {@code hash_equals}
 * and locks the <em>session</em> after five misses -- a lock a fresh session
 * walks straight past. Here the password is a bcrypt hash in
 * {@code platform_admins}, the miss budget is spent per client
 * ({@link PlatformAdminLoginThrottle}), and both outcomes are audited.
 *
 * <p>The budget is per client and not global on purpose. With a single
 * identifier to guess at, a global budget would let anyone lock the
 * administrator out for fifteen minutes at a time, indefinitely, from
 * anywhere; a per-client budget slows a guesser without handing them that.
 */
@Service
public class PlatformAdminLoginService {

	/**
	 * The one row this login reads. The column is named {@code phone} because
	 * the table predates the model (ADR-0015 had individual accounts); the
	 * value is a fixed identifier, never dialled.
	 */
	public static final String ADMIN_IDENTIFIER = "admin";

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

	/**
	 * @param clientKey what the miss budget is charged to -- the client
	 *     address, as the controller sees it behind the proxy
	 * @return the administrator when the password is right, the budget has
	 *     not been spent and the account is active; empty otherwise, with no
	 *     indication of which
	 */
	public Optional<PlatformAdmin> login(String password, String clientKey) {
		String budget = "web:" + clientKey;
		if (this.throttle.isExhausted(budget)) {
			return Optional.empty();
		}
		PlatformAdmin admin = this.platformAdminRepository.findByPhone(ADMIN_IDENTIFIER).orElse(null);
		// The comparison runs even with no row, so "not provisioned" costs the
		// same as "wrong password" and neither is measurable from outside.
		boolean matches = this.passwordEncoder.matches(
				password == null ? "" : password, admin != null ? admin.getPasswordHash() : DUMMY_HASH);
		if (admin == null || !matches || !admin.isActive()) {
			this.throttle.recordFailure(budget);
			if (admin != null) {
				this.auditService.record(admin.getId(), PlatformAdminAuditEventType.LOGIN_FAILED,
						admin.isActive() ? "wrong password" : "inactive account");
			}
			return Optional.empty();
		}
		this.throttle.clear(budget);
		this.auditService.record(admin.getId(), PlatformAdminAuditEventType.LOGIN, null);
		return Optional.of(admin);
	}

}
