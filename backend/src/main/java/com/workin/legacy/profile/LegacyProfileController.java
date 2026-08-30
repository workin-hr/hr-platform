package com.workin.legacy.profile;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyPublicRow;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code apis/api/profile/*.php}. Seven routes landed in Wave 13.2; the two
 * phone-change routes landed in Wave 13.1a with the OTP layer they share with
 * {@code auth}.
 *
 * <p>Eight of the nine check the HTTP method first, as every other legacy
 * module does. {@code profile/employee.php} does <b>not</b>: it authenticates
 * first and dispatches on the method afterwards, so an anonymous {@code PATCH}
 * to it is a 401 where the same request to any sibling is a 405. That
 * inversion is reproduced in {@link #employee} rather than normalised away.
 *
 * <p>The module also disagrees with itself about the status for "wrong session
 * type": {@code delete_account_preview.php} answers <b>401</b> and the two
 * phone-change routes answer <b>403</b>, for the same condition. Both are
 * preserved.
 */
@RestController
public class LegacyProfileController {

	private final LegacyProfileService service;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyProfileController(
			LegacyProfileService service, LegacyRequestGuard requestGuard, LegacyMessages messages) {
		this.service = service;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	/**
	 * {@code profile/employee.php}: GET reads, PUT updates, everything else is
	 * 405 -- but only after {@code requireAuth()} has run.
	 *
	 * <p>The role list admits all four roles, so the guard that actually blocks
	 * a company-type session is the {@code if (!$employee_id)} below it, which
	 * answers 401 rather than 403.
	 */
	@RequestMapping("/apis/api/profile/employee.php")
	public LegacyApiResponse employee(HttpServletRequest request) {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR,
				LegacyEmployee.Role.MANAGER, LegacyEmployee.Role.EMPLOYEE);
		if (context.employeeId() == 0) {
			throw new LegacyApiException(401, "unauthorized");
		}

		if ("GET".equals(request.getMethod())) {
			return LegacyApiResponse.ok(message(request, "employee_profile"), LegacyPublicRow.of(
					service.employeeProfile(context.employeeId(), context.companyId())));
		}
		if ("PUT".equals(request.getMethod())) {
			return LegacyApiResponse.ok(message(request, "employee_updated"), LegacyPublicRow.of(
					service.updateEmployeeProfile(
							context.employeeId(), context.companyId(), LegacyJsonBody.read(request))));
		}
		throw new LegacyApiException(405, "invalid_method");
	}

	@RequestMapping("/apis/api/profile/company.php")
	public LegacyApiResponse company(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context =
				requestGuard.requireAuth(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		return LegacyApiResponse.ok(message(request, "company_profile"), service.companyProfile(context));
	}

	@RequestMapping("/apis/api/profile/change_password.php")
	public LegacyApiResponse changePassword(HttpServletRequest request) {
		requireMethod(request, "POST");
		service.changePassword(requestGuard.requireAuth(), LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "password_changed"), null);
	}

	@RequestMapping("/apis/api/profile/logout.php")
	public LegacyApiResponse logout(HttpServletRequest request) {
		requireMethod(request, "POST");
		service.logout(requestGuard.requireAuth(), LegacyJsonBody.read(request),
				messages.resolveLocale(request));
		return LegacyApiResponse.ok(message(request, "ok"), null);
	}

	@RequestMapping("/apis/api/profile/register_push_token.php")
	public LegacyApiResponse registerPushToken(HttpServletRequest request) {
		requireMethod(request, "POST");
		service.registerPushToken(requestGuard.requireAuth(), LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "push_token_registered"), null);
	}

	@RequestMapping("/apis/api/profile/delete_account_preview.php")
	public LegacyApiResponse deleteAccountPreview(HttpServletRequest request) {
		requireMethod(request, "GET");
		return LegacyApiResponse.ok(message(request, "company_delete_preview"),
				service.deleteAccountPreview(requestGuard.requireAuth(), messages.resolveLocale(request)));
	}

	/**
	 * {@code delete_account.php}. The two branches answer differently: the
	 * company branch returns {@code {"deleted_related_records": [...]}} under
	 * {@code company_account_deleted}, and the employee branch returns no data
	 * at all under a bare {@code ok}.
	 */
	@RequestMapping("/apis/api/profile/delete_account.php")
	public LegacyApiResponse deleteAccount(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyProfileService.DeleteResult result = service.deleteAccount(
				requestGuard.requireAuth(), LegacyJsonBody.read(request), messages.resolveLocale(request));
		if (result instanceof LegacyProfileService.DeleteResult.CompanyDeleted deleted) {
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("deleted_related_records", deleted.deletedRelated());
			return LegacyApiResponse.ok(message(request, "company_account_deleted"), data);
		}
		return LegacyApiResponse.ok(message(request, "ok"), null);
	}

	/** Company sessions only, and it refuses with **403** where the delete preview uses 401. */
	@RequestMapping("/apis/api/profile/request_phone_change.php")
	public LegacyApiResponse requestPhoneChange(HttpServletRequest request) {
		requireMethod(request, "POST");
		service.requestPhoneChange(request, requestGuard.requireAuth(),
				LegacyJsonBody.read(request), messages.resolveLocale(request));
		return LegacyApiResponse.ok(message(request, "otp_sent"), null);
	}

	@RequestMapping("/apis/api/profile/confirm_phone_change.php")
	public LegacyApiResponse confirmPhoneChange(HttpServletRequest request) {
		requireMethod(request, "POST");
		return LegacyApiResponse.ok(message(request, "phone_changed"),
				service.confirmPhoneChange(requestGuard.requireAuth(), LegacyJsonBody.read(request)));
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
