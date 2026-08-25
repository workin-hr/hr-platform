package com.workin.legacy.workforce;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code /apis/api/requests/*.php} (Wave 12.7).
 *
 * <h2>Authority levels</h2>
 * <ul>
 * <li>{@code list} and {@code one} -- a bare {@code requireAuth()}.</li>
 * <li>{@code create}, {@code update}, {@code delete} -- EMPLOYEE only.</li>
 * <li>{@code approve}, {@code reject} -- COMPANY_ADMIN, HR or MANAGER,
 *     with no {@code hr_permissions} gate.</li>
 * </ul>
 *
 * <p>{@code approve} delegates to the D-100 connection-scoped transaction;
 * the other six endpoints keep the original Wave-12.7 slice-1 service shape.
 */
@RestController
@RequestMapping("/apis/api/requests")
public class LegacyRequestController {

	private final LegacyRequestService requestService;
	private final LegacyRequestApprovalService approvalService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyRequestController(
			LegacyRequestService requestService,
			LegacyRequestApprovalService approvalService,
			LegacyRequestGuard requestGuard,
			LegacyMessages messages) {
		this.requestService = requestService;
		this.approvalService = approvalService;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = anyRole();
		LegacyRequestService.Page page = requestService.list(
				context, LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "ok"), page.rows(), page.meta());
	}

	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = anyRole();
		return LegacyApiResponse.ok(message(request, "ok"), requestService.one(context, requiredId(request)));
	}

	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = employeeOnly();
		String locale = messages.resolveLocale(request);
		Map<String, Object> row = requestService.create(context, locale, LegacyJsonBody.read(request));
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "ok"), row));
	}

	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = employeeOnly();
		long id = requiredId(request);
		Map<String, Object> row = requestService.update(context, id, LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "ok"), row);
	}

	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = employeeOnly();
		requestService.delete(context, requiredId(request));
		return LegacyApiResponse.ok(message(request, "ok"), null);
	}

	@RequestMapping("/approve.php")
	public LegacyApiResponse approve(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = decisionRole();
		long id = requiredId(request);
		Map<String, Object> body = LegacyJsonBody.read(request);
		String reply = body.get("reply") == null ? "" : LegacyValues.toPhpString(body.get("reply"));
		Long approverId = context.employeeId() > 0 ? context.employeeId() : null;
		approvalService.approve(
				id, context.companyId(), approverId, reply, messages.resolveLocale(request));
		return LegacyApiResponse.ok(message(request, "decision_recorded"), null);
	}

	@RequestMapping("/reject.php")
	public LegacyApiResponse reject(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = decisionRole();
		long id = requiredId(request);
		Map<String, Object> body = LegacyJsonBody.read(request);
		String reply = body.get("reply") == null ? "" : LegacyValues.toPhpString(body.get("reply"));
		requestService.reject(context, messages.resolveLocale(request), id, reply);
		return LegacyApiResponse.ok(message(request, "decision_recorded"), null);
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	private LegacyRequestContext anyRole() {
		LegacyRequestContext context = requestGuard.requireAuth();
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	private LegacyRequestContext employeeOnly() {
		LegacyRequestContext context = requestGuard.requireAuth(LegacyEmployee.Role.EMPLOYEE);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	private LegacyRequestContext decisionRole() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	private static long requiredId(HttpServletRequest request) {
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		Object id = query.value("id");
		if (id == null || "".equals(id)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
		}
		return LegacyValues.toPhpLong(id);
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}
}
