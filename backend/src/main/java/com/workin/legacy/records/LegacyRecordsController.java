package com.workin.legacy.records;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.authorization.LegacyHrPermissionEnforcer;
import com.workin.legacy.authorization.LegacyHrPermissionKey;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Wave 13.4a: {@code assets/*.php} and {@code administrative_decisions/*.php}.
 *
 * <p>Ten endpoints, two modules, and <b>they do not agree on anything</b>: one
 * enforces no permission at all and the other requires {@code can_employees} on
 * every route but its list; one uses {@code filter_var(FILTER_VALIDATE_BOOLEAN)}
 * for its boolean and the other an exact {@code === 1}; one admits EMPLOYEE to
 * its list and the other admits EMPLOYEE to its list <em>with a different row
 * filter</em>. Each difference is legacy's and each is preserved.
 */
@RestController
public class LegacyRecordsController {

	private final LegacyAssetService assetService;
	private final LegacyAdministrativeDecisionService decisionService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyHrPermissionEnforcer permissionEnforcer;
	private final LegacyMessages messages;

	public LegacyRecordsController(
			LegacyAssetService assetService, LegacyAdministrativeDecisionService decisionService,
			LegacyRequestGuard requestGuard, LegacyHrPermissionEnforcer permissionEnforcer,
			LegacyMessages messages) {
		this.assetService = assetService;
		this.decisionService = decisionService;
		this.requestGuard = requestGuard;
		this.permissionEnforcer = permissionEnforcer;
		this.messages = messages;
	}

	// ---------------- assets ----------------

	@RequestMapping("/apis/api/assets/list.php")
	public LegacyApiResponse assetList(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = active(LegacyEmployee.Role.COMPANY_ADMIN,
				LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER, LegacyEmployee.Role.EMPLOYEE);
		LegacyAssetService.Page page = assetService.list(
				context, LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "ok"), page.rows(), page.meta());
	}

	/** No EMPLOYEE here, unlike the list beside it. */
	@RequestMapping("/apis/api/assets/one.php")
	public LegacyApiResponse assetOne(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = active(LegacyEmployee.Role.COMPANY_ADMIN,
				LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		return LegacyApiResponse.ok(message(request, "ok"),
				assetService.one(context.companyId(), requiredId(request)));
	}

	@RequestMapping("/apis/api/assets/create.php")
	public ResponseEntity<LegacyApiResponse> assetCreate(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = active(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		Map<String, Object> row = assetService.create(context.companyId(), LegacyJsonBody.read(request));
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "ok"), row));
	}

	@RequestMapping("/apis/api/assets/update.php")
	public LegacyApiResponse assetUpdate(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = active(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		long id = requiredId(request);
		return LegacyApiResponse.ok(message(request, "ok"),
				assetService.update(context.companyId(), id, LegacyJsonBody.read(request)));
	}

	/** {@code ok(OK, ['deleted' => true])} -- a body, unlike the decision delete. */
	@RequestMapping("/apis/api/assets/delete.php")
	public LegacyApiResponse assetDelete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = active(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		assetService.delete(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "ok"), Map.of("deleted", true));
	}

	// ---------------- administrative_decisions ----------------

	/**
	 * A bare {@code requireAuth()} then hand-written role logic: EMPLOYEE
	 * passes with no permission check, ADMIN and MANAGER pass unconditionally,
	 * and HR alone needs {@code can_employees}.
	 */
	@RequestMapping("/apis/api/administrative_decisions/list.php")
	public LegacyApiResponse decisionList(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = requestGuard.requireAuth();
		requestGuard.requireCompanyActive(context.companyId());

		if (context.role() != LegacyEmployee.Role.EMPLOYEE) {
			if (context.role() != LegacyEmployee.Role.COMPANY_ADMIN
					&& context.role() != LegacyEmployee.Role.HR
					&& context.role() != LegacyEmployee.Role.MANAGER) {
				throw new LegacyApiException(403, "forbidden");
			}
			if (context.role() == LegacyEmployee.Role.HR) {
				permissionEnforcer.require(LegacyHrPermissionKey.CAN_EMPLOYEES);
			}
		}

		LegacyAdministrativeDecisionService.Page page = decisionService.list(
				context, LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "ok"), page.rows(), page.meta());
	}

	@RequestMapping("/apis/api/administrative_decisions/one.php")
	public LegacyApiResponse decisionOne(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = decisionManager();
		return LegacyApiResponse.ok(message(request, "ok"),
				decisionService.one(context.companyId(), requiredId(request)));
	}

	@RequestMapping("/apis/api/administrative_decisions/create.php")
	public ResponseEntity<LegacyApiResponse> decisionCreate(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = decisionManager();
		Map<String, Object> row =
				decisionService.create(context.companyId(), LegacyJsonBody.read(request));
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "ok"), row));
	}

	@RequestMapping("/apis/api/administrative_decisions/update.php")
	public LegacyApiResponse decisionUpdate(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = decisionManager();
		long id = requiredId(request);
		return LegacyApiResponse.ok(message(request, "ok"),
				decisionService.update(context.companyId(), id, LegacyJsonBody.read(request)));
	}

	/** {@code ok(OK, null)} -- no {@code data} key, unlike the asset delete. */
	@RequestMapping("/apis/api/administrative_decisions/delete.php")
	public LegacyApiResponse decisionDelete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = decisionManager();
		decisionService.delete(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "ok"), null);
	}

	// ---------------- shared ----------------

	private LegacyRequestContext active(LegacyEmployee.Role... roles) {
		LegacyRequestContext context = requestGuard.requireAuth(roles);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	/** ADMIN or HR, active company, and {@code can_employees} on every route. */
	private LegacyRequestContext decisionManager() {
		LegacyRequestContext context = active(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		permissionEnforcer.require(LegacyHrPermissionKey.CAN_EMPLOYEES);
		return context;
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	/**
	 * {@code required($_GET, [ID]); $id = (int) $_GET[ID];}
	 *
	 * <p>Only a missing or exactly-empty id is {@code field_required};
	 * {@code "0"} and {@code "abc"} pass the guard, cast to 0, and then match
	 * no row -- a 404, not a 400.
	 */
	private static long requiredId(HttpServletRequest request) {
		Object id = LegacyQueryParameters.parse(request.getQueryString()).value("id");
		if (id == null || "".equals(id)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
		}
		return LegacyValues.toPhpLong(id);
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}
}
