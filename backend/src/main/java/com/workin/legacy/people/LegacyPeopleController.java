package com.workin.legacy.people;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

	/** Multipart, and the only endpoint in this wave that is not JSON-bodied. */
	@RequestMapping("/apis/api/employee_docs/upload.php")
	public ResponseEntity<LegacyApiResponse> docUpload(
			HttpServletRequest request,
			@RequestParam(value = "employee_id", required = false) String employeeId,
			@RequestParam(value = "doc_type", required = false) String docType,
			@RequestParam(value = "file", required = false) MultipartFile file) {
		requireMethod(request, "POST");
		LegacyRequestContext context = anyRole();
		Map<String, Object> row = docService.upload(context, employeeId, docType, file);
		return ResponseEntity.status(201)
				.body(LegacyApiResponse.ok(message(request, "document_uploaded"), row));
	}

	/** POST, not PUT -- this module updates over POST while the rest of Item 13 uses PUT. */
	@RequestMapping("/apis/api/employee_docs/update.php")
	public LegacyApiResponse docUpdate(
			HttpServletRequest request,
			@RequestParam(value = "id", required = false) String id,
			@RequestParam(value = "doc_type", required = false) String docType) {
		requireMethod(request, "POST");
		LegacyRequestContext context = anyRole();
		if (id == null || id.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
		}
		if (docType == null || docType.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "doc_type"));
		}
		return LegacyApiResponse.ok(message(request, "document_updated"),
				docService.update(context, LegacyValues.toPhpLong(id), docType));
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
