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
import com.workin.legacy.authorization.LegacyHrPermissionEnforcer;
import com.workin.legacy.authorization.LegacyHrPermissionKey;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code /apis/api/request_types/*.php} (Wave 12.5, slice 3).
 *
 * <h2>Three different authority levels in one module (D-087)</h2>
 * <ul>
 * <li>{@code list} and {@code one} -- a bare {@code requireAuth()}, so
 *     <b>any</b> authenticated role reads them, an ordinary employee
 *     included. Not narrowed: employee-facing clients legitimately read the
 *     request types available to them.</li>
 * <li>{@code create} and {@code update} -- COMPANY_ADMIN or HR, with no
 *     permission gate.</li>
 * <li>{@code delete} -- the same roles <b>plus</b> {@code can_company_settings}.
 *     The asymmetry is legacy's: creating a request type needs less authority
 *     than deleting one.</li>
 * </ul>
 *
 * <h2>The id guard is not {@code shifts}'</h2>
 * <p>This module calls {@code required($_GET, [Request::ID])}, so a missing or
 * empty id is {@code field_required} carrying {@code {field}} as a message
 * replacement -- while {@code "0"} and {@code "abc"} both <em>pass</em> the
 * guard and cast to 0 at the lookup, which then misses with 404.
 * {@code shifts} rejects those two at the guard with {@code id_required}.
 */
@RestController
@RequestMapping("/apis/api/request_types")
public class LegacyRequestTypeController {

	private final LegacyRequestTypeService requestTypeService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyHrPermissionEnforcer permissionEnforcer;
	private final LegacyMessages messages;

	public LegacyRequestTypeController(
			LegacyRequestTypeService requestTypeService, LegacyRequestGuard requestGuard,
			LegacyHrPermissionEnforcer permissionEnforcer, LegacyMessages messages) {
		this.requestTypeService = requestTypeService;
		this.requestGuard = requestGuard;
		this.permissionEnforcer = permissionEnforcer;
		this.messages = messages;
	}

	/** {@code list.php}: {@code ok(OK, public_rows($rows), 200, [], pagination_meta(...))}. */
	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = anyRole();
		LegacyRequestTypeService.Page page = requestTypeService.list(
				context.companyId(), LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "ok"), page.rows(), page.meta());
	}

	/** {@code one.php}: {@code ok(OK, public_row($request_type))}. */
	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = anyRole();
		return LegacyApiResponse.ok(
				message(request, "ok"), requestTypeService.one(context.companyId(), requiredId(request)));
	}

	/** {@code create.php}: {@code ok(OK, public_row($inserted_row), 201)}. */
	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = administrative();
		Map<String, Object> row = requestTypeService.create(context.companyId(), LegacyJsonBody.read(request));
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "ok"), row));
	}

	/** {@code update.php}: {@code ok(OK, public_row($updated_row))}. */
	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = administrative();
		long id = requiredId(request);
		Map<String, Object> row = requestTypeService.update(
				context.companyId(), id, LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "ok"), row);
	}

	/**
	 * {@code delete.php}: {@code ok(OK)} -- no {@code data} key.
	 *
	 * <p>The {@code can_company_settings} gate sits after
	 * {@code requireCompanyActive} and before the id is read, which is where
	 * PHP puts it, so a caller lacking the permission gets 403 regardless of
	 * whether the id is valid, foreign, or missing entirely.
	 */
	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = administrative();
		permissionEnforcer.require(LegacyHrPermissionKey.CAN_COMPANY_SETTINGS);
		requestTypeService.delete(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "ok"), null);
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	/** {@code requireAuth();} with no role list -- every authenticated role passes. */
	private LegacyRequestContext anyRole() {
		LegacyRequestContext context = requestGuard.requireAuth();
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	/** {@code requireAuth([COMPANY_ADMIN, HR]);}. */
	private LegacyRequestContext administrative() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	/**
	 * {@code required($_GET, [Request::ID]); $id = (int) $_GET[Request::ID];}
	 *
	 * <p>The guard runs on the raw value and the cast happens after, so
	 * {@code "0"} and {@code "abc"} both survive {@code required()} and become
	 * 0 -- which then simply fails to match any row. Only a missing or exactly
	 * empty id is {@code field_required}.
	 */
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
