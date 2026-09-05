package com.workin.legacy.employees;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.spreadsheet.LegacyEmployeeTemplate;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

/**
 * The employee module on legacy's own URL surface (D-074):
 * {@code /apis/api/employees/*.php}, the PHP envelope, PHP's message keys.
 * Not {@code /api/legacy/employees} -- D-074 records that shape as
 * implementation drift, so this wave does not extend it.
 *
 * <p>Guard order is PHP's, at every endpoint:
 * {@code requireAuth([roles]); requireCompanyActive($company_id);} then the
 * endpoint's own {@code required()} checks. {@link LegacyRequestGuard} supplies
 * P-7 ({@code token_version}), P-8 (role) and P-9 (active company); the tenant
 * re-derivation behind {@link LegacyRequestContext#companyId()} is Phase 1's own
 * addition (ADR-0012), not legacy's.
 */
@RestController
@RequestMapping("/apis/api/employees")
public class LegacyEmployeeController {

	private final LegacyEmployeeService employeeService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyEmployeeController(
			LegacyEmployeeService employeeService, LegacyRequestGuard requestGuard, LegacyMessages messages) {
		this.employeeService = employeeService;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	/** {@code employees/list.php}: {@code ok(EMPLOYEES, public_rows($rows), 200, [], pagination_meta(...))}. */
	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = management();
		LegacyEmployeeService.Page page = employeeService.list(
				context, LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "employees"), page.rows(), page.meta());
	}

	/** {@code employees/one.php}: {@code ok(EMPLOYEE_PROFILE, public_row($employee))}. */
	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = management();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		requireId(query);
		Map<String, Object> employee = employeeService.one(context, LegacyValues.toPhpLong(query.value("id")));
		return LegacyApiResponse.ok(message(request, "employee_profile"), employee);
	}

	/**
	 * {@code employees/create.php}: POST, admin/HR, then the whole validation
	 * chain, one transaction, and a post-commit re-read rendered as 201.
	 */
	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = administrative();
		// body(), not @RequestBody: a malformed document is an empty array in
		// PHP and has to reach required(), not Spring's own error.
		Map<String, Object> employee = employeeService.create(context, LegacyJsonBody.read(request));
		return ResponseEntity.status(201)
				.body(LegacyApiResponse.ok(message(request, "employee_created"), employee));
	}

	/**
	 * {@code employees/update.php}: PUT, admin/HR, the query id, then a body
	 * that is validated key by key and finally written as whatever survived.
	 * The two notifications are sent after the transaction has committed, so
	 * neither can undo the update.
	 */
	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = administrative();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		requireId(query);
		long employeeId = LegacyValues.toPhpLong(query.value("id"));

		LegacyEmployeeService.UpdateOutcome outcome = employeeService.update(
				context, employeeId, LegacyJsonBody.read(request));
		// t() runs at insert time, so the stored notification text follows this
		// request's locale -- including the {title} placeholder, which takes the
		// job title as the post-commit re-read sees it.
		String locale = messages.resolveLocale(request);
		employeeService.notifyAfterUpdate(
				context, employeeId, outcome,
				messages.translate(locale, "notif_job_title_changed_title", null),
				messages.translate(locale, "notif_job_title_changed_body", Map.of(
						"title", outcome.employee() == null ? "" : jobTitleName(outcome.employee()))),
				messages.translate(locale, "notif_schedule_assigned_title", null),
				messages.translate(locale, "notif_schedule_assigned_body", null));
		return LegacyApiResponse.ok(message(request, "employee_updated"), outcome.employee());
	}

	/** {@code (string) ($row['job_title_name'] ?? '')} for the notification body. */
	private static String jobTitleName(Map<String, Object> employee) {
		Object name = employee.get("job_title_name");
		return name == null ? "" : name.toString();
	}

	/**
	 * {@code employees/upload_photo.php}: POST, and the only employee endpoint
	 * an {@code employee} role may call at all -- for its own row.
	 *
	 * <p>The target defaults to the authenticated employee when no {@code id} is
	 * given, and a falsy target is rejected before anything is read or written.
	 * Nothing checks that the target exists: that check simply is not in the
	 * source, and adding one would change which requests store a file.
	 */
	@RequestMapping("/upload_photo.php")
	public LegacyApiResponse uploadPhoto(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER,
				LegacyEmployee.Role.EMPLOYEE);
		requestGuard.requireCompanyActive(context.companyId());

		// (int) ($_GET['id'] ?? (int) ($auth['employee_id'] ?? 0))
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		long targetEmployeeId = query.value("id") != null
				? LegacyValues.toPhpLong(query.value("id"))
				: context.employeeId();
		if (targetEmployeeId == 0) {
			throw new LegacyApiException(400, "employee_id_required");
		}
		// The employee role may only ever touch its own photo. Every other role
		// may target anybody in the company -- a manager is not limited to its
		// own branch here, unlike on employees/one.php.
		if (context.role() == LegacyEmployee.Role.EMPLOYEE && targetEmployeeId != context.employeeId()) {
			throw new LegacyApiException(403, "forbidden");
		}

		Map<String, Object> employee = employeeService.uploadPhoto(
				context, targetEmployeeId, multipartFile(request, "photo"));
		return LegacyApiResponse.ok(message(request, "photo_uploaded"), employee);
	}

	/**
	 * {@code $_FILES['photo']}. A request that is not multipart at all, or one
	 * without that part, is the same "no file" PHP sees -- and the part is
	 * {@code photo}, not {@code file} ({@code upload_slots.php:10}).
	 */
	private static org.springframework.web.multipart.MultipartFile multipartFile(
			HttpServletRequest request, String partName) {
		if (request instanceof org.springframework.web.multipart.MultipartHttpServletRequest multipart) {
			return multipart.getFile(partName);
		}
		return null;
	}

	/** {@code employees/delete_preview.php}: GET, admin/HR, the id, then fourteen counts. */
	@RequestMapping("/delete_preview.php")
	public LegacyApiResponse deletePreview(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = administrative();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		requireId(query);
		long employeeId = LegacyValues.toPhpLong(query.value("id"));
		return LegacyApiResponse.ok(
				message(request, "employee_delete_preview"),
				previewPayload(request, employeeId, employeeService.deletePreview(context, employeeId)));
	}

	/**
	 * {@code employees/delete.php}: DELETE, admin/HR, the id, and
	 * {@code cascade} read through {@code FILTER_VALIDATE_BOOLEAN}.
	 *
	 * <h2>What a failing cascade answers</h2>
	 * <p>A cascade that throws is <em>not</em> translated here, because
	 * {@code delete.php} does not translate it either:
	 * {@code employee_cascade_delete_related()} rolls back and rethrows, and
	 * nothing catches it. In PHP the client-visible shape then depends on
	 * {@code AppConfig::DEBUG}, whose real value lives in the gitignored
	 * {@code constants.php} -- so it was never established by repository
	 * evidence.
	 *
	 * <p>D-084 settled that: an uncaught exception on these routes answers one
	 * deterministic, non-debug 500,
	 * {@code {"success": false, "message": "Internal server error"}}, with no
	 * {@code data} and no stack detail. So the rollback is proven by database
	 * state in the tests, the exception propagates as it does in PHP, and
	 * {@link com.workin.legacy.wire.LegacyWireExceptionHandler} renders that
	 * envelope. Nothing is pending.
	 */
	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = administrative();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		requireId(query);
		long employeeId = LegacyValues.toPhpLong(query.value("id"));
		boolean cascade = LegacyValues.toPhpFilterBoolean(query.value("cascade"));

		try {
			LegacyEmployeeService.DeleteOutcome outcome = employeeService.delete(context, employeeId, cascade);
			if (outcome.deletedRelatedRecords() == null) {
				// ok(EMPLOYEE_DELETED) -- no data key at all.
				return LegacyApiResponse.ok(message(request, "employee_deleted"), null);
			}
			return LegacyApiResponse.ok(
					message(request, "employee_deleted_with_related"),
					Map.of("deleted_related_records", relatedItems(request, outcome.deletedRelatedRecords())));
		} catch (LegacyEmployeeService.LegacyDeleteBlockedException blocked) {
			throw new LegacyApiException(
					409, "cannot_delete_employee_has_records",
					previewPayload(request, employeeId, blocked.getPreview()));
		}
	}

	/**
	 * {@code employee_delete_preview_payload()}: the four keys, in this order,
	 * with the zero-count categories dropped and the labels translated for this
	 * request's locale.
	 */
	private Map<String, Object> previewPayload(
			HttpServletRequest request, long employeeId,
			List<LegacyEmployeeStore.RelatedRecordCount> counts) {
		List<Map<String, Object>> items = relatedItems(request, counts);
		long total = items.stream().mapToLong(item -> ((Number) item.get("count")).longValue()).sum();
		Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("employee_id", employeeId);
		payload.put("has_related_records", !items.isEmpty());
		payload.put("total_related_records", total);
		payload.put("related_records", items);
		return payload;
	}

	/** One {@code {key, label, count}} per non-zero category, in the helper's order. */
	private List<Map<String, Object>> relatedItems(
			HttpServletRequest request, List<LegacyEmployeeStore.RelatedRecordCount> counts) {
		String locale = messages.resolveLocale(request);
		List<Map<String, Object>> items = new java.util.ArrayList<>();
		for (LegacyEmployeeStore.RelatedRecordCount count : counts) {
			if (count.count() <= 0) {
				continue;
			}
			Map<String, Object> item = new java.util.LinkedHashMap<>();
			item.put("key", count.key());
			item.put("label", messages.translate(locale, count.labelKey(), null));
			item.put("count", count.count());
			items.add(item);
		}
		return items;
	}

	/** {@code employees/deactivate.php}: DELETE, admin/HR, then the scoped write and the notification. */
	@RequestMapping("/deactivate.php")
	public LegacyApiResponse deactivate(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = administrative();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		requireId(query);
		Map<String, Object> employee = employeeService.deactivate(
				context, LegacyValues.toPhpLong(query.value("id")),
				message(request, "notif_employee_deactivated_title"),
				message(request, "notif_employee_deactivated_body"));
		return LegacyApiResponse.ok(message(request, "employee_deactivated"), employee);
	}

	/** {@code employees/reactivate.php}: PUT, admin/HR, no notification. */
	@RequestMapping("/reactivate.php")
	public LegacyApiResponse reactivate(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = administrative();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		requireId(query);
		Map<String, Object> employee = employeeService.reactivate(
				context, LegacyValues.toPhpLong(query.value("id")));
		return LegacyApiResponse.ok(message(request, "employee_reactivated"), employee);
	}

	/**
	 * {@code employees/template_excel.php}: the bulk-import template, as a
	 * download rather than an envelope.
	 *
	 * <p>Alone in this module it has <em>no</em> method guard -- the PHP file
	 * simply has no {@code REQUEST_METHOD} check -- so a POST, a PUT or a HEAD
	 * downloads the template exactly as a GET does. {@code requireMethod} is
	 * therefore not called here, and that is deliberate rather than an omission.
	 * The auth and active-company guards still run, in that order.
	 *
	 * <p>The body is a file, so this handler returns the bytes itself instead of
	 * a {@link LegacyApiResponse}. A guard failure still throws, so failures keep
	 * the PHP envelope; only the success path is a download.
	 */
	/**
	 * {@code employees/stats.php}: GET, admin/HR/manager, the employee list
	 * page's five aggregates.
	 */
	@RequestMapping("/stats.php")
	public LegacyApiResponse stats(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = management();
		Map<String, Object> stats = employeeService.stats(
				context, LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "success"), stats);
	}

	/**
	 * {@code employees/my_team.php}: GET, <em>manager only</em> -- the one
	 * endpoint in this module an admin cannot reach.
	 */
	@RequestMapping("/my_team.php")
	public LegacyApiResponse myTeam(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = requestGuard.requireAuth(LegacyEmployee.Role.MANAGER);
		requestGuard.requireCompanyActive(context.companyId());
		return LegacyApiResponse.ok(message(request, "my_team"), employeeService.myTeam(context));
	}

	/**
	 * {@code employees/analyze_excel.php}: POST, admin/HR, then the uploaded
	 * spreadsheet analyzed row by row without writing anything.
	 *
	 * <p>The locale decides which of the two rejection texts
	 * {@code assert_template_structure()} produces, so it is resolved here and
	 * passed down -- {@code $isAr = app_locale() === 'ar'} in the helper.
	 */
	@RequestMapping("/analyze_excel.php")
	public LegacyApiResponse analyzeExcel(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = administrative();
		boolean arabic = "ar".equals(messages.resolveLocale(request));
		Map<String, Object> analysis = employeeService.analyzeSpreadsheet(
				context, multipartFile(request, "file"), arabic);
		return LegacyApiResponse.ok(message(request, "employees_excel_analyzed"), analysis);
	}

	/**
	 * {@code employees/import_bulk.php}: POST, admin/HR, then the reviewed rows.
	 *
	 * <p>Always 200 once the rows have passed the presence check -- the message
	 * key is the only thing that moves, and only when nothing at all was
	 * inserted. A mixed batch is {@code employees_imported}, not a failure.
	 */
	@RequestMapping("/import_bulk.php")
	public LegacyApiResponse importBulk(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = administrative();
		Map<String, Object> result = employeeService.importSpreadsheetRows(
				context, LegacyJsonBody.read(request));

		boolean nothingInserted = LegacyValues.toPhpLong(result.get("inserted")) == 0
				&& !((List<?>) result.get("failed")).isEmpty();
		return LegacyApiResponse.ok(
				message(request, nothingInserted ? "employees_import_failed" : "employees_imported"), result);
	}

	/**
	 * {@code employees/analyze_excel_update.php}: POST, admin/HR, then the
	 * uploaded sheet analyzed against the <em>existing</em> employees without
	 * writing anything.
	 *
	 * <p>Same shape as {@code analyze_excel.php} and a different meaning: here
	 * an empty cell means "leave that field alone", so a row is valid when its
	 * employee code resolves and nothing that <em>was</em> filled in is wrong.
	 */
	@RequestMapping("/analyze_excel_update.php")
	public LegacyApiResponse analyzeExcelUpdate(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = administrative();
		boolean arabic = "ar".equals(messages.resolveLocale(request));
		Map<String, Object> analysis = employeeService.analyzeSpreadsheetForUpdate(
				context, multipartFile(request, "file"), arabic);
		return LegacyApiResponse.ok(message(request, "employees_excel_analyzed"), analysis);
	}

	/**
	 * {@code employees/update_bulk.php}: POST, admin/HR, then the reviewed rows
	 * applied one at a time.
	 *
	 * <p>Always 200 once the rows have passed the presence check. A partially
	 * successful batch is the normal outcome and is reported as
	 * {@code employees_updated} with a {@code failed} list; only a batch where
	 * <em>nothing</em> was updated and something failed switches the message to
	 * {@code employees_update_failed}. The status never moves.
	 */
	@RequestMapping("/update_bulk.php")
	public LegacyApiResponse updateBulk(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = administrative();
		Map<String, Object> result = employeeService.updateSpreadsheetRows(
				context, LegacyJsonBody.read(request));

		boolean nothingUpdated = LegacyValues.toPhpLong(result.get("updated")) == 0
				&& !((List<?>) result.get("failed")).isEmpty();
		return LegacyApiResponse.ok(
				message(request, nothingUpdated ? "employees_update_failed" : "employees_updated"), result);
	}

	@RequestMapping("/template_excel.php")
	public void templateExcel(HttpServletRequest request, HttpServletResponse response) throws IOException {
		LegacyRequestContext context = administrative();
		LegacyEmployeeService.Template template = employeeService.template(
				context, LegacyQueryParameters.parse(request.getQueryString()));

		// The CSV branch's Content-Type reaches the client as
		// "text/csv;charset=UTF-8" rather than PHP's "text/csv; charset=utf-8".
		// Tomcat intercepts this one header name and re-serializes the value
		// whichever way it is set -- setHeader(), setContentType() or a
		// ResponseEntity all land in the same place -- so the optional whitespace
		// and the charset's case are not ours to choose. RFC 9110 makes both
		// spellings the same media type and every client parses them alike, so
		// this is a formatting difference in a header, not a content one. Noted
		// rather than worked around: the workaround would mean writing raw bytes
		// past the container.
		response.setHeader("Content-Type", template.contentType());
		response.setHeader("Content-Disposition", LegacyEmployeeTemplate.contentDisposition(template.filename()));
		response.setStatus(200);
		response.getOutputStream().write(template.content());
	}

	/**
	 * Every method-guarded endpoint opens with
	 * {@code if ($_SERVER['REQUEST_METHOD'] !== HttpMethod::GET) fail(INVALID_METHOD, 405);}
	 * -- an ordinary first statement, not framework routing. Reproduced the same
	 * way: the routes are mapped for all methods and check inside, because
	 * Spring's own 405 is raised before a handler is chosen, which would leave it
	 * outside this module's advice and render the platform error body instead of
	 * the PHP envelope. {@code template_excel.php}, which has no guard at all,
	 * then needs no exception to this rule.
	 */
	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	/**
	 * {@code requireAuth([COMPANY_ADMIN, HR]); requireCompanyActive($company_id);}
	 * -- the write-side role list, which drops MANAGER. Neither lifecycle
	 * endpoint gates on an {@code hr_permissions} flag: only
	 * {@code employees/update.php} does, and only for an HR session changing
	 * somebody else (D-045/D-057's per-endpoint rule, re-checked here rather
	 * than assumed).
	 */
	private LegacyRequestContext administrative() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	/** {@code requireAuth([COMPANY_ADMIN, HR, MANAGER]); requireCompanyActive($company_id);}. */
	private LegacyRequestContext management() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	/**
	 * {@code required($_GET, [Request::ID])} ({@code functions.php:617-623}):
	 * missing, {@code null} and the exact empty string fail; {@code '0'} passes
	 * {@code required()} and only then casts to {@code 0}. The failure carries
	 * the field name as a {@code {field}} <em>placeholder</em> in the message --
	 * {@code fail(FIELD_REQUIRED, 400, null, [Response::FIELD =&gt; $field])} passes
	 * it as {@code $replace}, not as {@code $data}, so the response body has no
	 * {@code data} key at all.
	 */
	private static void requireId(LegacyQueryParameters query) {
		Object id = query.value("id");
		if (id == null || "".equals(id)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
		}
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}

}
