package com.workin.legacy.workforce;

import java.util.List;
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
 * {@code /apis/api/company_official_holidays/*.php} (Wave 12.5, slice 4).
 *
 * <h2>The one D-087 correction in the wave</h2>
 * <p>PHP's {@code list.php} authenticates any role and then applies
 * {@code require_company_settings_access()} <b>only</b> when that role is
 * COMPANY_ADMIN or HR, so an EMPLOYEE or MANAGER lists holidays freely while an
 * HR user lacking {@code can_company_settings} is refused the same list. The
 * gate protects nothing -- the data is already readable by every less
 * privileged role -- and denies exactly the roles that administer the module.
 *
 * <p>D-087 removes it. The list stays open to <b>any authenticated role</b>,
 * and no gate is added for EMPLOYEE or MANAGER. This <b>broadens</b>
 * COMPANY_ADMIN and HR relative to PHP: an HR user without the permission now
 * receives 200 where PHP answers 403. That is a narrow compatibility
 * correction, <em>not</em> a fail-closed security change, and it is the
 * opposite in direction from D-075 and D-076. The other four endpoints keep the
 * gate, so mutation authority is untouched.
 *
 * <h2>Gate order on the four mutating endpoints</h2>
 * <p>{@code requireAuth} then {@code requireCompanyActive} then
 * {@code can_company_settings}, and only then the id or body -- PHP's order. So
 * a caller lacking the permission gets 403 whether the id is valid, foreign,
 * zero or absent, and never a 400 or 404 that would tell them the id was wrong.
 */
@RestController
@RequestMapping("/apis/api/company_official_holidays")
public class LegacyOfficialHolidayController {

	private final LegacyOfficialHolidayService holidayService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyHrPermissionEnforcer permissionEnforcer;
	private final LegacyMessages messages;

	public LegacyOfficialHolidayController(
			LegacyOfficialHolidayService holidayService, LegacyRequestGuard requestGuard,
			LegacyHrPermissionEnforcer permissionEnforcer, LegacyMessages messages) {
		this.holidayService = holidayService;
		this.requestGuard = requestGuard;
		this.permissionEnforcer = permissionEnforcer;
		this.messages = messages;
	}

	/** {@code list.php}: {@code ok(OK, public_rows($rows), 200, [], pagination_meta(...))}. */
	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		// D-087: bare requireAuth(), and no permission gate on any role.
		LegacyRequestContext context = requestGuard.requireAuth();
		requestGuard.requireCompanyActive(context.companyId());

		LegacyOfficialHolidayService.Page page = holidayService.list(
				context.companyId(), LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "ok"), page.rows(), page.meta());
	}

	/** {@code one.php}: {@code ok(OK, public_row($row))}. */
	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = settingsGuarded();
		return LegacyApiResponse.ok(
				message(request, "ok"), holidayService.one(context.companyId(), requiredId(request)));
	}

	/**
	 * {@code create.php}: {@code ok(OK, public_rows($inserted), 201)}.
	 *
	 * <p>{@code public_rows} -- an <b>array</b> -- at 201, even when one date
	 * was supplied and even when every date renamed an existing row rather than
	 * inserting anything. The status does not distinguish the two.
	 */
	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = settingsGuarded();
		List<Map<String, Object>> rows = holidayService.create(
				context.companyId(), LegacyJsonBody.read(request));
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "ok"), rows));
	}

	/** {@code update.php}: {@code ok(OK, public_row($updated ?? $row))}. */
	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = settingsGuarded();
		long id = requiredId(request);
		Map<String, Object> row = holidayService.update(
				context.companyId(), id, LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "ok"), row);
	}

	/**
	 * {@code delete.php}: {@code ok(OK, null)}.
	 *
	 * <p>The builder omits a null {@code $data}, so the wire shape is exactly
	 * {@code {"success":true,"message":"OK"}} -- the key is absent, not present
	 * and null.
	 */
	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = settingsGuarded();
		holidayService.delete(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "ok"), null);
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	/**
	 * {@code requireAuth([COMPANY_ADMIN, HR]); requireCompanyActive(...);
	 * require_company_settings_access($auth);} -- the four mutating endpoints,
	 * in PHP's order and before any id or body is touched.
	 */
	private LegacyRequestContext settingsGuarded() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		requestGuard.requireCompanyActive(context.companyId());
		permissionEnforcer.require(LegacyHrPermissionKey.CAN_COMPANY_SETTINGS);
		return context;
	}

	/**
	 * {@code required($_GET, [Request::ID]); $id = (int) $_GET[Request::ID];}
	 *
	 * <p>The same rule {@code request_types} uses and <em>not</em>
	 * {@code shifts}': only a missing or exactly empty id is
	 * {@code field_required}. {@code "0"} and {@code "abc"} pass the guard,
	 * cast to 0, and are then refused by
	 * {@code official_holiday_assert_company_row}'s own non-positive check --
	 * without a query reaching the database.
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
