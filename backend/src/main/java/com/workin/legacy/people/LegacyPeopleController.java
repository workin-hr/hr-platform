package com.workin.legacy.people;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
 * Wave 13.4c: {@code employee_docs/*.php}, {@code complaints/*.php} and
 * {@code company_join_requests/*.php}.
 *
 * <p>{@code complaints/create.php} is the <b>sixth</b> entry in
 * {@link com.workin.legacy.wire.LegacyPhpRoutes}' public category and the first
 * that <b>writes</b>. The data argument that justifies the other five does not
 * transfer: this one persists caller-supplied name, phone and message from an
 * anonymous source, so rate limiting, spam and PII retention are live questions
 * for it that do not arise for a read-only lookup. Ported as-is by explicit
 * owner decision (D-132) with those questions recorded rather than answered.
 */
@RestController
public class LegacyPeopleController {

	private final LegacyEmployeeDocService docService;
	private final LegacyComplaintService complaintService;
	private final LegacyJoinRequestService joinRequestService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyPeopleController(
			LegacyEmployeeDocService docService, LegacyComplaintService complaintService,
			LegacyJoinRequestService joinRequestService, LegacyRequestGuard requestGuard,
			LegacyMessages messages) {
		this.docService = docService;
		this.complaintService = complaintService;
		this.joinRequestService = joinRequestService;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	// ---------------- employee_docs ----------------

	@RequestMapping("/apis/api/employee_docs/list.php")
	public LegacyApiResponse docList(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = anyRole();
		LegacyEmployeeDocService.Page page = docService.list(
				context, LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "documents"), page.rows(), page.meta());
	}

	/**
	 * Multipart, and the only endpoint in this wave that is not JSON-bodied.
	 *
	 * <p>{@code employee_id} and {@code doc_type} come from {@code $_POST} --
	 * the multipart <b>body</b> — not the query string. {@code @RequestParam}
	 * merges the two, so {@code ?employee_id=<coworker>} on a request whose body
	 * carries only the file part would have been honoured here and ignored by
	 * legacy. That is not a cosmetic difference on this route: the employee id
	 * selects <em>whose</em> file the document is attached to.
	 */
	@RequestMapping("/apis/api/employee_docs/upload.php")
	public ResponseEntity<LegacyApiResponse> docUpload(HttpServletRequest request) {
		// The method check must run FIRST, as it does in PHP. Declaring the file
		// as a @RequestParam MultipartFile would make Spring resolve -- and fail
		// to convert -- a scalar `?file=x` during argument binding, answering
		// before this line and turning what legacy calls invalid_method into
		// something else entirely.
		requireMethod(request, "POST");
		LegacyRequestContext context = anyRole();
		String employeeId = formField(request, "employee_id");
		String docType = formField(request, "doc_type");
		Map<String, Object> row = docService.upload(context, employeeId, docType, uploadedFile(request));
		return ResponseEntity.status(201)
				.body(LegacyApiResponse.ok(message(request, "document_uploaded"), row));
	}

	/**
	 * POST, not PUT -- this module updates over POST while the rest of Item 13
	 * uses PUT.
	 *
	 * <p><b>Both values come from {@code $_POST}, not the query string.</b>
	 * {@code required($_POST, [ID, DOC_TYPE])} reads the request <em>body</em>,
	 * so {@code POST update.php?id=1&doc_type=x} with an empty body is
	 * {@code field_required} in legacy. Spring's {@code @RequestParam} merges
	 * query parameters with form fields and would have accepted it, so the form
	 * body is read directly instead -- otherwise the port is strictly more
	 * permissive than the endpoint it reproduces.
	 */
	@RequestMapping("/apis/api/employee_docs/update.php")
	public LegacyApiResponse docUpdate(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = anyRole();
		String id = formField(request, "id");
		String docType = formField(request, "doc_type");
		if (id == null || id.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
		}
		if (docType == null || docType.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "doc_type"));
		}
		return LegacyApiResponse.ok(message(request, "document_updated"),
				docService.update(context, LegacyValues.toPhpLong(id), docType));
	}

	/**
	 * The uploaded file, resolved <b>after</b> the method check.
	 *
	 * <p>Taken from the request rather than bound as a {@code @RequestParam} so
	 * that a non-multipart request, or a scalar {@code ?file=x}, cannot fail
	 * during argument resolution before {@code requireMethod} has run.
	 */
	private static MultipartFile uploadedFile(HttpServletRequest request) {
		if (request instanceof org.springframework.web.multipart.MultipartHttpServletRequest multipart) {
			return multipart.getFile("file");
		}
		return null;
	}

	/**
	 * A {@code $_POST} field: the request <b>body</b> only, never the query
	 * string.
	 *
	 * <p>{@code request.getParameter()} merges the two, which is exactly the
	 * behaviour being avoided, and the body cannot simply be re-read either --
	 * for {@code application/x-www-form-urlencoded} the container has already
	 * consumed the input stream to build the parameter map.
	 *
	 * <p>So the two sources are separated by position. The servlet spec has the
	 * container present query-string values <em>before</em> body values for the
	 * same name, so anything beyond the number of occurrences in the query
	 * string came from the body. The <b>last</b> such value is taken, because
	 * PHP's {@code parse_str()} keeps the final duplicate.
	 *
	 * <p>A multipart request needs none of that: {@code getPart()} reads the
	 * body directly and never sees the query string.
	 */
	private static String formField(HttpServletRequest request, String name) {
		String contentType = request.getContentType();
		if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT)
				.startsWith("multipart/form-data")) {
			try {
				jakarta.servlet.http.Part part = request.getPart(name);
				// A part carrying a filename is a FILE. PHP puts those in $_FILES
				// and never in $_POST, so reading its bytes as a form value would
				// accept input legacy does not see -- and on this route that input
				// selects whose record the document lands on.
				if (part == null || part.getSubmittedFileName() != null) {
					return null;
				}
				try (java.io.InputStream in = part.getInputStream()) {
					return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				}
			} catch (Exception ex) {
				return null;
			}
		}

		// PHP normalizes dots and spaces in external field names, so a body
		// carrying `doc.type` or `doc type` populates $_POST['doc_type'] and the
		// required-field guard passes. The servlet container does not, so the
		// body is matched on the normalized name rather than the raw one.
		String[] merged = null;
		int fromQueryString = 0;
		for (java.util.Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
			if (!normalizePhpFieldName(entry.getKey()).equals(name)) {
				continue;
			}
			merged = merged == null ? entry.getValue()
					: concat(merged, entry.getValue());
			fromQueryString += countInQueryString(request, entry.getKey());
		}
		if (merged == null) {
			return null;
		}
		return merged.length <= fromQueryString ? null : merged[merged.length - 1];
	}

	/** {@code parse_str()}'s external-name normalization: dots and spaces become underscores. */
	private static String normalizePhpFieldName(String name) {
		return name.replace('.', '_').replace(' ', '_');
	}

	private static String[] concat(String[] first, String[] second) {
		String[] out = java.util.Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, out, first.length, second.length);
		return out;
	}

	private static int countInQueryString(HttpServletRequest request, String rawName) {
		String query = request.getQueryString();
		if (query == null) {
			return 0;
		}
		int count = 0;
		for (String pair : query.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			String key = java.net.URLDecoder.decode(pair.split("=", 2)[0],
					java.nio.charset.StandardCharsets.UTF_8);
			if (key.equals(rawName)) {
				count++;
			}
		}
		return count;
	}

	@RequestMapping("/apis/api/employee_docs/delete.php")
	public LegacyApiResponse docDelete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = anyRole();
		docService.delete(context, requiredId(request));
		return LegacyApiResponse.ok(message(request, "document_deleted"), null);
	}

	// ---------------- complaints ----------------

	/**
	 * <b>Unauthenticated by design.</b> A token is read when present and
	 * ignored when absent; neither case is rejected.
	 */
	@RequestMapping("/apis/api/complaints/create.php")
	public LegacyApiResponse complaintCreate(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = requestGuard.optionalAuth();
		complaintService.create(context, LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "complaint_submitted"), null);
	}

	@RequestMapping("/apis/api/complaints/list.php")
	public LegacyApiResponse complaintList(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = administrative();
		LegacyComplaintService.Page page = complaintService.list(
				context.companyId(), LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "complaints"), page.rows(), page.meta());
	}

	@RequestMapping("/apis/api/complaints/update.php")
	public LegacyApiResponse complaintUpdate(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = administrative();
		return LegacyApiResponse.ok(message(request, "ok"), complaintService.update(
				context.companyId(), invalidIdOrValue(request), LegacyJsonBody.read(request)));
	}

	@RequestMapping("/apis/api/complaints/delete.php")
	public LegacyApiResponse complaintDelete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = administrative();
		complaintService.delete(context.companyId(), invalidIdOrValue(request));
		return LegacyApiResponse.ok(message(request, "complaint_deleted"), null);
	}

	// ---------------- company_join_requests ----------------

	@RequestMapping("/apis/api/company_join_requests/list.php")
	public LegacyApiResponse joinRequestList(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = administrative();
		LegacyJoinRequestService.Page page = joinRequestService.list(
				context.companyId(), LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "ok"), page.rows(), page.meta());
	}

	@RequestMapping("/apis/api/company_join_requests/accept.php")
	public LegacyApiResponse joinRequestAccept(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = administrative();
		return LegacyApiResponse.ok(message(request, "join_request_accepted"),
				joinRequestService.accept(context.companyId(), context.employeeId(),
						requiredId(request), messages.resolveLocale(request)));
	}

	@RequestMapping("/apis/api/company_join_requests/reject.php")
	public LegacyApiResponse joinRequestReject(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = administrative();
		return LegacyApiResponse.ok(message(request, "join_request_rejected"),
				joinRequestService.reject(context.companyId(), requiredId(request),
						messages.resolveLocale(request)));
	}

	// ---------------- shared ----------------

	private LegacyRequestContext anyRole() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR,
				LegacyEmployee.Role.MANAGER, LegacyEmployee.Role.EMPLOYEE);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	private LegacyRequestContext administrative() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	private static long requiredId(HttpServletRequest request) {
		Object id = LegacyQueryParameters.parse(request.getQueryString()).value("id");
		if (id == null || "".equals(id)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
		}
		return LegacyValues.toPhpLong(id);
	}

	/**
	 * {@code complaints} guards its id with {@code (int) ($_GET['id'] ?? 0)} and
	 * a {@code <= 0} test that answers {@code invalid_id} -- not the
	 * {@code required()}/{@code field_required} pair the rest of the wave uses.
	 */
	private static long invalidIdOrValue(HttpServletRequest request) {
		long id = LegacyValues.toPhpLong(
				LegacyQueryParameters.parse(request.getQueryString()).value("id"));
		if (id <= 0) {
			throw new LegacyApiException(400, "invalid_id");
		}
		return id;
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}
}
