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
 */
@Service
public class PlatformAdminLoginService {

	private final PlatformAdminRepository platformAdminRepository;
	private final PasswordEncoder passwordEncoder;
	private final PlatformAdminAuditService auditService;

	public PlatformAdminLoginService(
			PlatformAdminRepository platformAdminRepository,
			PasswordEncoder passwordEncoder,
			PlatformAdminAuditService auditService) {
		this.platformAdminRepository = platformAdminRepository;
		this.passwordEncoder = passwordEncoder;
		this.auditService = auditService;
	}

	public PlatformAdmin login(PlatformAdminLoginRequest request) {
		PlatformAdmin admin = platformAdminRepository.findByPhone(request.phone())
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, MessageKeys.AUTH_INVALID_CREDENTIALS));
		if (!passwordEncoder.matches(request.password(), admin.getPasswordHash()) || !admin.isActive()) {
			auditService.record(admin.getId(), PlatformAdminAuditEventType.LOGIN_FAILED,
					admin.isActive() ? "wrong password" : "inactive account");
			throw new ApiException(HttpStatus.UNAUTHORIZED, MessageKeys.AUTH_INVALID_CREDENTIALS);
		}
		auditService.record(admin.getId(), PlatformAdminAuditEventType.LOGIN, null);
		return admin;
	}

}
