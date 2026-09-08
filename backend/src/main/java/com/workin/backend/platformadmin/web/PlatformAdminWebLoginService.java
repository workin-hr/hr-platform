package com.workin.backend.platformadmin.web;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.workin.backend.platformadmin.PlatformAdmin;
import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.PlatformAdminLoginService;

/** The web login's view of {@link PlatformAdminLoginService}. */
@Service
public class PlatformAdminWebLoginService {

	private final PlatformAdminLoginService loginService;
	private final PlatformAdminAuditService auditService;

	public PlatformAdminWebLoginService(PlatformAdminLoginService loginService,
			PlatformAdminAuditService auditService) {
		this.loginService = loginService;
		this.auditService = auditService;
	}

	/** @return the signed-in administrator's id, or empty when the password is refused */
	public Optional<Long> authenticate(String password, String clientKey) {
		return this.loginService.login(password, clientKey).map(PlatformAdmin::getId);
	}

	public void recordLogout(long platformAdminId) {
		this.auditService.record(platformAdminId, PlatformAdminAuditEventType.LOGOUT, "admin web");
	}

}
