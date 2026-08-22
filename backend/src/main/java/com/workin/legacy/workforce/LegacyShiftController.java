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
 * {@code /apis/api/shifts/*.php} (Wave 12.5), on the D-074 literal wire
 * contract.
 *
 * <h2>Authority is uniform here and gates on nothing (D-087)</h2>
 * <p>All five endpoints take COMPANY_ADMIN, HR and MANAGER, and <b>none</b>
 * carries an {@code hr_permissions} check -- not even {@code delete}, which is
 * destructive, and unlike {@code request_types/delete.php} and every
 * {@code company_official_holidays} mutation, which do. That asymmetry is
 * legacy's; D-087 preserves it rather than adding authorization PHP does not
 * have.
 *
 * <h2>The id guard is this module's own</h2>
 * <p>{@code shifts} reads {@code (int) ($_GET['id'] ?? 0)} and fails with
 * {@code id_required} when the result is falsy. Its two sibling modules call
 * {@code required($_GET, [Request::ID])} instead and fail with
 * {@code field_required} plus a {@code {field}} placeholder. Different key,
 * different body -- not homogenised.
 */
@RestController
@RequestMapping("/apis/api/shifts")
public class LegacyShiftController {

	private final LegacyShiftService shiftService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyShiftController(
			LegacyShiftService shiftService, LegacyRequestGuard requestGuard, LegacyMessages messages) {
		this.shiftService = shiftService;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	/** {@code shifts/list.php}: {@code ok(SHIFTS, public_rows($shifts))}. */
	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = management();
		return LegacyApiResponse.ok(message(request, "shifts"), shiftService.list(context.companyId()));
	}

	/** {@code shifts/one.php}: {@code ok(SHIFTS, public_row($shift))} -- the same key as list. */
	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = management();
		long id = requireId(request);
		return LegacyApiResponse.ok(message(request, "shifts"), shiftService.one(context.companyId(), id));
	}

	/** {@code shifts/create.php}: {@code ok(SHIFT_CREATED, public_row($inserted_row), 201)}. */
	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = management();
		Map<String, Object> shift = shiftService.create(context.companyId(), LegacyJsonBody.read(request));
		return ResponseEntity.status(201)
				.body(LegacyApiResponse.ok(message(request, "shift_created"), shift));
	}

	/**
	 * {@code shifts/update.php}: {@code ok(SHIFT_UPDATED, public_row($updated_row))}.
	 *
	 * <p>The acting employee reaches the broadcast as
	 * {@code (int) ($auth['employee_id'] ?? 0) ?: null}, so a zero becomes SQL
	 * NULL rather than a reference to employee 0.
	 */
	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = management();
		long id = requireId(request);
		Map<String, Object> shift = shiftService.update(
				context.companyId(),
				actingEmployeeId(context),
				messages.resolveLocale(request),
				id,
				LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "shift_updated"), shift);
	}

	/** {@code shifts/delete.php}: {@code ok(SHIFT_DELETED)} -- no {@code data} key at all. */
	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = management();
		shiftService.delete(context.companyId(), requireId(request));
		// `ok(SHIFT_DELETED)` passes no $data, and the envelope omits the key
		// entirely rather than sending `"data": null` -- NON_NULL does that.
		return LegacyApiResponse.ok(message(request, "shift_deleted"), null);
	}

	/**
	 * The method check is legacy's first statement, before authentication, so
	 * an unauthenticated POST to a GET endpoint is a 405 and not a 401. Mapped
	 * for every method and checked here for the same reason Wave 12.4 does it:
	 * Spring's own 405 is raised before a handler is chosen and would escape
	 * this module's advice, rendering the platform body instead of the PHP
	 * envelope.
	 */
	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	/** {@code requireAuth([COMPANY_ADMIN, HR, MANAGER]); requireCompanyActive($company_id);} -- all five endpoints. */
	private LegacyRequestContext management() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	/**
	 * {@code $id = (int) ($_GET['id'] ?? 0); if (!$id) fail(ID_REQUIRED);}
	 *
	 * <p>The cast happens first, so the guard rejects anything that casts to
	 * zero -- absent, empty, {@code "0"}, and {@code "abc"} alike -- while
	 * {@code "12abc"} casts to 12 and passes. That is a different rule from the
	 * {@code required()} the other two modules use, where {@code "0"} passes
	 * and {@code "abc"} does too.
	 */
	private static long requireId(HttpServletRequest request) {
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		long id = LegacyValues.toPhpLong(query.value("id"));
		if (id == 0L) {
			throw new LegacyApiException(400, "id_required");
		}
		return id;
	}

	/** {@code (int) ($auth[AuthKey::EMPLOYEE_ID] ?? 0) ?: null}. */
	private static Long actingEmployeeId(LegacyRequestContext context) {
		long employeeId = context.employeeId();
		return employeeId == 0L ? null : employeeId;
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}

}
