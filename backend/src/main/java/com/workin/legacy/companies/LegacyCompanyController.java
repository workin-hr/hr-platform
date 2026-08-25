package com.workin.legacy.companies;

import java.util.Map;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.authorization.LegacyHrPermissionEnforcer;
import com.workin.legacy.authorization.LegacyHrPermissionKey;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/** Frozen PHP-compatible {@code /apis/api/company/*.php} (Wave 12.10). */
@RestController
@RequestMapping("/apis/api/company")
public class LegacyCompanyController {

	private final LegacyCompanyService service;
	private final LegacyRequestGuard guard;
	private final LegacyHrPermissionEnforcer permissionEnforcer;
	private final LegacyMessages messages;

	public LegacyCompanyController(LegacyCompanyService service, LegacyRequestGuard guard,
			LegacyHrPermissionEnforcer permissionEnforcer, LegacyMessages messages) {
		this.service = service;
		this.guard = guard;
		this.permissionEnforcer = permissionEnforcer;
		this.messages = messages;
	}

	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		long companyId = companySettingsRole();
		Map<String, Object> row = service.update(companyId, LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "profile_updated"), row);
	}

	/**
	 * {@code upload_logo.php}: POST, no {@code require_company_settings_access}
	 * call unlike {@code update.php} -- the role guard alone is the gate.
	 */
	@RequestMapping("/upload_logo.php")
	public LegacyApiResponse uploadLogo(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = writeRole();
		Map<String, Object> row = service.uploadLogo(context.companyId(), multipartFile(request, "logo"));
		return LegacyApiResponse.ok(message(request, "logo_uploaded"), row);
	}

	/** {@code upload_commercial_reg.php}: POST, same guard as {@code upload_logo.php}. */
	@RequestMapping("/upload_commercial_reg.php")
	public LegacyApiResponse uploadCommercialReg(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = writeRole();
		Map<String, Object> row = service.uploadCommercialReg(context.companyId(), multipartFile(request, "file"));
		// PHP's own upload_commercial_reg.php reuses LOGO_UPLOADED's message key
		// verbatim (helpers/functions.php has no dedicated one) -- reproduced,
		// not corrected.
		return LegacyApiResponse.ok(message(request, "logo_uploaded"), row);
	}

	private LegacyRequestContext writeRole() {
		LegacyRequestContext context = guard.requireAuth(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	/**
	 * {@code require_company_settings_access()} ({@code hr_permissions.php:159-164}):
	 * {@code requireAuth} + {@code requireCompanyActive}, then, since every
	 * legacy token Phase 1 issues is employee-typed (D-042 -- no company-type
	 * token exists yet, {@link LegacyHrPermissionEnforcer}'s own boundary),
	 * {@code can_company_settings} is checked unconditionally -- for
	 * {@code company_admin} as much as {@code hr}, exactly as PHP's own
	 * {@code type === 'employee'} branch does once that branch is the only
	 * reachable one. Denial is legacy's {@code forbidden} key in the PHP
	 * envelope, not the platform's {@code error.forbidden}.
	 */
	private long companySettingsRole() {
		LegacyRequestContext context = writeRole();
		if (!permissionEnforcer.has(LegacyHrPermissionKey.CAN_COMPANY_SETTINGS)) {
			throw new LegacyApiException(403, "forbidden");
		}
		return context.companyId();
	}

	private static org.springframework.web.multipart.MultipartFile multipartFile(
			HttpServletRequest request, String partName) {
		if (request instanceof org.springframework.web.multipart.MultipartHttpServletRequest multipart) {
			return multipart.getFile(partName);
		}
		return null;
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}
}
