package com.workin.legacy.payroll;

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

/** Frozen PHP-compatible {@code /apis/api/advances/*.php}. */
@RestController
@RequestMapping("/apis/api/advances")
public class LegacyAdvanceController {

	private final LegacyAdvanceService service;
	private final LegacyRequestGuard guard;
	private final LegacyMessages messages;

	public LegacyAdvanceController(LegacyAdvanceService service, LegacyRequestGuard guard, LegacyMessages messages) {
		this.service = service;
		this.guard = guard;
		this.messages = messages;
	}

	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = generalRole();
		LegacyAdvanceService.Page page = service.list(context, LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "advances"), page.rows(), page.meta());
	}

	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = generalRole();
		return LegacyApiResponse.ok(message(request, "success"), service.one(context, requiredId(request)));
	}

	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = generalRole();
		Map<String, Object> row = service.create(context, LegacyJsonBody.read(request));
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "advance_requested"), row));
	}

	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = generalRole();
		long id = requiredId(request);
		Map<String, Object> body = LegacyJsonBody.read(request); // PHP reads body before its scoped row lookup.
		return LegacyApiResponse.ok(message(request, "success"), service.update(context, id, body));
	}

	@RequestMapping("/approve.php")
	public LegacyApiResponse approve(HttpServletRequest request) {
		requireMethod(request, "PUT");
		administrativeRole();
		return LegacyApiResponse.ok(message(request, "approve_advance"), service.approve(requiredId(request)));
	}

	@RequestMapping("/reject.php")
	public LegacyApiResponse reject(HttpServletRequest request) {
		requireMethod(request, "PUT");
		administrativeRole();
		long id = requiredId(request);
		return LegacyApiResponse.ok(message(request, "reject_advance"), service.reject(id, LegacyJsonBody.read(request)));
	}

	@RequestMapping("/pay.php")
	public LegacyApiResponse pay(HttpServletRequest request) {
		requireMethod(request, "PUT");
		administrativeRole();
		long id = requiredId(request);
		return LegacyApiResponse.ok(message(request, "success"), service.pay(id, LegacyJsonBody.read(request)));
	}

	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = generalRole();
		service.delete(context, requiredId(request));
		return LegacyApiResponse.ok(message(request, "success"), null);
	}

	private LegacyRequestContext generalRole() {
		LegacyRequestContext context = guard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.EMPLOYEE);
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	private LegacyRequestContext administrativeRole() {
		LegacyRequestContext context = guard.requireAuth(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	private static long requiredId(HttpServletRequest request) {
		long id = LegacyValues.toPhpLong(LegacyQueryParameters.parse(request.getQueryString()).value("id"));
		if (id == 0L) {
			throw new LegacyApiException(400, "invalid_id");
		}
		return id;
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
