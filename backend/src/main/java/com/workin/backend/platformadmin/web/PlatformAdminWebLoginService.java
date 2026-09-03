package com.workin.backend.platformadmin.web;

import org.springframework.stereotype.Service;

import com.workin.backend.i18n.ApiException;
import com.workin.backend.platformadmin.PlatformAdmin;
import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.PlatformAdminLoginRequest;
import com.workin.backend.platformadmin.PlatformAdminLoginService;

/**
 * Authentication for the UI, expressed as an outcome rather than an exception.
 *
 * <p>It <b>delegates to {@link PlatformAdminLoginService}</b> rather than
 * repeating the credential check. ADR-0015 names authorization drift as a risk
 * of this surface -- "if the JTE controllers re-implement checks rather than
 * calling the same services, the UI and the API diverge over time and the UI
 * becomes the weaker path" -- so the only thing added here is the translation
 * from {@link ApiException} to a value the controller can render. Any hardening
 * of the shared service (throttling, timing parity) is inherited by both
 * surfaces automatically, which is the point.
 */
@Service
public class PlatformAdminWebLoginService {

	private final PlatformAdminLoginService loginService;
	private final PlatformAdminAuditService auditService;

	public PlatformAdminWebLoginService(PlatformAdminLoginService loginService,
			PlatformAdminAuditService auditService) {
		this.loginService = loginService;
		this.auditService = auditService;
	}

	/**
	 * @param succeeded whether the credentials were accepted
	 * @param platformAdminId the authenticated administrator, meaningful only when {@code succeeded}
	 * @param phone the administrator's phone, meaningful only when {@code succeeded}
	 */
	public record Outcome(boolean succeeded, long platformAdminId, String phone) {

		static Outcome rejected() {
			return new Outcome(false, 0L, null);
		}

	}

	public Outcome authenticate(String phone, String password) {
		try {
			PlatformAdmin admin = this.loginService.login(new PlatformAdminLoginRequest(phone, password));
			return new Outcome(true, admin.getId(), admin.getPhone());
		}
		catch (ApiException ex) {
			// Every rejection reason collapses to one outcome. The shared service
			// has already written whatever audit attribution was possible.
			return Outcome.rejected();
		}
	}

	public void recordLogout(long platformAdminId) {
		this.auditService.record(platformAdminId, PlatformAdminAuditEventType.LOGOUT, "admin web");
	}

}
