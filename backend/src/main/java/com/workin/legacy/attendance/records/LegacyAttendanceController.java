package com.workin.legacy.attendance.records;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.spreadsheet.LegacyAttendanceImportService;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * `/apis/api/attendance/{one,create,update,delete,delete_range,import_excel}.php`
 * (Wave 12.6 slices 1a-i, 1a-ii and 1b).
 *
 * <h2>Authority is checked inside the endpoint, not by `requireAuth`</h2>
 * <p>All <b>six</b> routes call a bare `requireAuth()`, so every
 * authenticated role passes the guard itself. Five of them --
 * `create.php`, `update.php`, `delete.php`, `delete_range.php` and
 * `import_excel.php` -- then apply their own
 * `in_array($auth['role'], [COMPANY_ADMIN, HR])` test and answer
 * `forbidden` 403. That is a different mechanism from passing a role
 * list to `requireAuth`, and a different error path: a MANAGER authenticates
 * successfully and is refused afterwards. Reproduced where PHP puts it, after
 * `requireCompanyActive` and before any id or body is touched.
 *
 * <p>`one.php` has no role list and no in-endpoint role test. Its access
 * control is per-row and lives in the service, applied after the record is
 * fetched.
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
	private final LegacyAttendanceImportService importService;
	private final LegacyCheckInService checkInService;
	private final LegacyAttendanceReportService reportService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyAttendanceController(
			LegacyAttendanceService attendanceService,
			LegacyAttendanceImportService importService, LegacyCheckInService checkInService,
			LegacyAttendanceReportService reportService,
			LegacyRequestGuard requestGuard, LegacyMessages messages) {
		this.attendanceService = attendanceService;
		this.importService = importService;
		this.checkInService = checkInService;
		this.reportService = reportService;
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
		// The body is passed unread. update() reads the scoped row first and
		// answers 404 before it ever calls this -- PHP's order, and the reason
		// a missing id with a scalar body is a 404 rather than a 500.
		LegacyAttendanceService.UpdateOutcome outcome = attendanceService.update(
				context.companyId(), id, () -> LegacyJsonBody.read(request));
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

	/**
	 * `import_excel.php`: POST, admin/HR, a `file` part and an optional
	 * `mappings` form field.
	 *
	 * <p>The two success messages differ in more than wording. XLSX answers
	 * `imported_xlsx` with an `{inserted}` placeholder, CSV answers
	 * `imported_csv` with a `{count}` one -- two catalog keys, two placeholder
	 * names, one number. The choice is made from the format detected in the
	 * uploaded bytes at the start of the request, not from the filename and not
	 * from whatever the reader ended up treating the file as.
	 *
	 * <p>The notification's title and body are resolved here, because they are
	 * `t()` calls in PHP and therefore follow the request's locale.
	 */
	@RequestMapping("/import_excel.php")
	public LegacyApiResponse importExcel(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = authenticated();
		requireAdministrative(context);

		String locale = messages.resolveLocale(request);
		LegacyAttendanceImportService.Outcome outcome = importService.importExcel(
				context,
				multipartFile(request, "file"),
				request.getParameter("mappings"),
				inserted -> new String[] {
					messages.translate(locale, "notif_attendance_imported_title", null),
					messages.translate(locale, "notif_attendance_imported_body",
							Map.of("count", String.valueOf(inserted))),
				});

		String countText = String.valueOf(outcome.inserted());
		return LegacyApiResponse.ok(
				outcome.xlsx()
						? messages.translate(locale, "imported_xlsx", Map.of("inserted", countText))
						: messages.translate(locale, "imported_csv", Map.of("count", countText)),
				outcome.result());
	}

	/**
	 * `check_in.php`: POST, bare `requireAuth()`, D-092 target restriction
	 * inside the service.
	 */
	@RequestMapping("/check_in.php")
	public LegacyApiResponse checkIn(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = authenticated();
		String locale = messages.resolveLocale(request);
		LegacyCheckInService.CheckInOutcome outcome = checkInService.checkIn(
				context, LegacyJsonBody.read(request), messages.translate(locale, "schedule_weekly_rest", null));
		return LegacyApiResponse.ok(
				messages.translate(locale, "check_in_recorded", null),
				ordered("attendance_id", outcome.attendanceId(), "time", outcome.time()));
	}

	/** `check_in_qr.php`: the QR code is proof of presence, so no geofence runs. */
	@RequestMapping("/check_in_qr.php")
	public LegacyApiResponse checkInQr(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = authenticated();
		String locale = messages.resolveLocale(request);
		LegacyCheckInService.CheckInOutcome outcome = checkInService.checkInByQr(
				context, LegacyJsonBody.read(request), messages.translate(locale, "schedule_weekly_rest", null));
		return LegacyApiResponse.ok(
				messages.translate(locale, "qr_check_in_recorded", null),
				ordered("attendance_id", outcome.attendanceId(), "time", outcome.time()));
	}

	/** `check_out.php`: no `required()`, because the GPS falls back to the open row's. */
	@RequestMapping("/check_out.php")
	public LegacyApiResponse checkOut(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = authenticated();
		String locale = messages.resolveLocale(request);
		LegacyCheckInService.CheckOutOutcome outcome = checkInService.checkOut(
				context, LegacyJsonBody.read(request), messages.translate(locale, "schedule_weekly_rest", null));
		return LegacyApiResponse.ok(
				messages.translate(locale, "check_out_recorded", null),
				ordered("duration_minutes", outcome.durationMinutes(), "time", outcome.time()));
	}

	/**
	 * `analyze_excel.php`: the import's dry run.
	 *
	 * <p>Same guards and same availability gate as `import_excel.php`, and the
	 * gate still runs **before** the file is looked at. What differs is the
	 * failure mapping: `analyze` catches `RuntimeException` and maps it, but has
	 * **no transaction and no rollback**, because it writes nothing.
	 */
	@RequestMapping("/analyze_excel.php")
	public LegacyApiResponse analyzeExcel(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = authenticated();
		requireAdministrative(context);

		String locale = messages.resolveLocale(request);
		Map<String, Object> analysis = importService.analyze(
				context, multipartFile(request, "file"),
				messages.translate(locale, "schedule_weekly_rest", null));
		return LegacyApiResponse.ok(
				messages.translate(locale, "attendance_excel_analyzed", null), analysis);
	}

	/**
	 * `$_FILES['file']`. A request that is not multipart at all, or one without
	 * that part, is the "no file" PHP sees.
	 */
	private static MultipartFile multipartFile(HttpServletRequest request, String partName) {
		if (request instanceof MultipartHttpServletRequest multipart) {
			return multipart.getFile(partName);
		}
		return null;
	}

	/**
	 * `list.php`: `fill_days=1` expands each employee into one row per
	 * calendar day; otherwise the paginated joined-row listing. Bare
	 * `requireAuth()` -- every role reaches here and the per-role scoping
	 * (own rows only for `EMPLOYEE`, optional filters otherwise) lives in the
	 * service, identically in both branches.
	 */
	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = authenticated();
		String locale = messages.resolveLocale(request);
		LegacyAttendanceReportService.Listing listing = reportService.list(
				context, LegacyQueryParameters.parse(request.getQueryString()),
				messages.translate(locale, "schedule_weekly_rest", null));
		return LegacyApiResponse.ok(message(request, "attendance_records"), listing.rows(), listing.meta());
	}

	/**
	 * `stats.php`: a target employee id (self for `EMPLOYEE`, an optional
	 * filter otherwise) branches to a day-by-day period walk; none at all is
	 * the company/branch/department aggregate. `is_admin_or_hr` is computed in
	 * PHP and never read, so there is no role list here either.
	 */
	@RequestMapping("/stats.php")
	public LegacyApiResponse stats(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = authenticated();
		String locale = messages.resolveLocale(request);
		Map<String, Object> data = reportService.stats(
				context, LegacyQueryParameters.parse(request.getQueryString()),
				messages.translate(locale, "schedule_weekly_rest", null));
		return LegacyApiResponse.ok(message(request, "success"), data);
	}

	/**
	 * `employee_monthly_attendance.php`: `full_month=1` returns every calendar
	 * day of the month via the range calendar; otherwise the raw attendance
	 * rows for the month, stale-open-session-closed first. The four-role list
	 * on `requireAuth` names every role that exists, so it authenticates
	 * exactly as `authenticated()` does; the `EMPLOYEE` self-only restriction
	 * is the service's own in-endpoint check, same as `reject.php`'s pattern.
	 */
	@RequestMapping("/employee_monthly_attendance.php")
	public LegacyApiResponse employeeMonthlyAttendance(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = authenticated();
		String locale = messages.resolveLocale(request);
		LegacyAttendanceReportService.MonthlyAttendance result = reportService.employeeMonthlyAttendance(
				context, LegacyQueryParameters.parse(request.getQueryString()),
				messages.translate(locale, "schedule_weekly_rest", null));
		return LegacyApiResponse.ok(message(request, "attendance_summary"), result.data(), result.meta());
	}

	/**
	 * A two-entry payload in PHP's key order.
	 *
	 * <p>{@code Map.of} is explicitly unordered, and these payloads are literal
	 * PHP array literals whose order is part of the wire contract.
	 */
	private static Map<String, Object> ordered(String firstKey, Object first, String secondKey,
			Object second) {
		Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put(firstKey, first);
		payload.put(secondKey, second);
		return payload;
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
