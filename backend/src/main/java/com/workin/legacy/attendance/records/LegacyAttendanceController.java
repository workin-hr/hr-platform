package com.workin.legacy.attendance.records;

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
 * `/apis/api/attendance/{one,create,update,delete,delete_range}.php`
 * (Wave 12.6 slices 1a-i and 1a-ii).
 *
 * <h2>Authority is checked inside the endpoint, not by `requireAuth`</h2>
 * <p>All three call a <b>bare</b> `requireAuth()`, so every authenticated role
 * passes the guard. `delete.php` and `delete_range.php` then apply their own
 * `in_array($auth['role'], [COMPANY_ADMIN, HR])` test and answer `forbidden`
 * 403 -- which is a different mechanism from passing a role list to
 * `requireAuth`, and a different error path. Reproduced where PHP puts it:
 * after `requireCompanyActive`, before any id is read.
 *
 * <p>`one.php` has no role list at all. Its access control is per-row and
 * lives in the service, after the record is fetched.
 *
 * <h2>The id key and its failure differ from earlier waves</h2>
 * <p>These endpoints use `(int) ($_GET['id'] ?? 0)` and fail with
 * `invalid_id` -- not `shifts`' `id_required`, and not the `field_required`
 * plus `{field}` placeholder that `request_types` and
 * `company_official_holidays` use. Three modules, three different answers to a
 * missing id.
 */
@RestController
@RequestMapping("/apis/api/attendance")
public class LegacyAttendanceController {

	private final LegacyAttendanceService attendanceService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyAttendanceController(
			LegacyAttendanceService attendanceService, LegacyRequestGuard requestGuard,
			LegacyMessages messages) {
		this.attendanceService = attendanceService;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	/** `one.php`: `ok(SUCCESS, $attendance_row)` -- the raw row, not `public_row()`. */
	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = authenticated();
		long id = requiredId(request);
		return LegacyApiResponse.ok(
				message(request, "success"), attendanceService.one(context, id));
	}

	/** `create.php`: `ok(SUCCESS, public_row($inserted_row), 201)`. */
	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = authenticated();
		requireAdministrative(context);
		Map<String, Object> row = attendanceService.create(
				context.companyId(), LegacyJsonBody.read(request));
		return ResponseEntity.status(201)
				.body(LegacyApiResponse.ok(message(request, "success"), row));
	}

	/**
	 * `update.php`: `ok(ATTENDANCE_RECORD_UPDATED, public_row($updated_row))`.
	 *
	 * <p>The clear-both branch **deletes** the row and still answers
	 * `attendance_record_updated`, with `ok(..., null)` -- so the envelope
	 * omits `data` entirely rather than sending null.
	 */
	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = authenticated();
		requireAdministrative(context);
		long id = requiredId(request);
		LegacyAttendanceService.UpdateOutcome outcome = attendanceService.update(
				context.companyId(), id, LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(
				message(request, "attendance_record_updated"), outcome.row());
	}

	/** `delete.php`: `ok(SUCCESS)` -- no `data` key. */
	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = authenticated();
		requireAdministrative(context);
		attendanceService.delete(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "success"), null);
	}

	/**
	 * `delete_range.php`:
	 * `ok(ATTENDANCE_RANGE_DELETED, {count, from, to}, 200, [count => "n"])`.
	 *
	 * <p>The fourth argument is the message-placeholder map, so `{count}` in
	 * the catalog text is substituted with the same number that appears in
	 * `data`.
	 */
	@RequestMapping("/delete_range.php")
	public LegacyApiResponse deleteRange(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = authenticated();
		requireAdministrative(context);
		LegacyAttendanceService.RangeOutcome outcome = attendanceService.deleteRange(
				context.companyId(), LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(
				messages.translate(
						messages.resolveLocale(request), "attendance_range_deleted", outcome.replace()),
				outcome.data());
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	/** `requireAuth(); requireCompanyActive($company_id);` -- no role list. */
	private LegacyRequestContext authenticated() {
		LegacyRequestContext context = requestGuard.requireAuth();
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	/**
	 * `if (!in_array($auth['role'], [COMPANY_ADMIN, HR], true)) fail(FORBIDDEN, 403);`
	 * -- an explicit in-endpoint test, so a MANAGER authenticates successfully
	 * and is then refused, rather than being rejected by the auth guard.
	 */
	private static void requireAdministrative(LegacyRequestContext context) {
		if (context.role() != LegacyEmployee.Role.COMPANY_ADMIN
				&& context.role() != LegacyEmployee.Role.HR) {
			throw new LegacyApiException(403, "forbidden");
		}
	}

	/**
	 * `$id = (int) ($_GET['id'] ?? 0); if (!$id) fail(INVALID_ID);`
	 *
	 * <p>The cast runs first, so anything casting to zero -- absent, empty,
	 * `"0"`, `"abc"` -- is `invalid_id`, while `"12abc"` casts to 12 and
	 * proceeds. A negative id passes the guard and then simply misses, because
	 * `attendance_record_full()` refuses a non-positive id before querying.
	 */
	private static long requiredId(HttpServletRequest request) {
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		long id = LegacyValues.toPhpLong(query.value("id"));
		if (id == 0L) {
			throw new LegacyApiException(400, "invalid_id");
		}
		return id;
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}

}
