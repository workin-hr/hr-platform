package com.workin.legacy.people;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.uploads.LegacyFileUploads;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code employee_docs/*.php}.
 *
 * <h2>MANAGER is granted the role and then not honoured</h2>
 * <p>All four endpoints authenticate
 * {@code [COMPANY_ADMIN, HR, MANAGER, EMPLOYEE]}, but the scope checks split
 * those roles two different ways:
 *
 * <ul>
 * <li>{@code list} and {@code upload} test {@code role === EMPLOYEE}, so a
 *     MANAGER passes and may act for <b>any</b> employee in the company;</li>
 * <li>{@code update} and {@code delete} test {@code role not in [ADMIN, HR]},
 *     so a MANAGER falls into the ownership branch and may touch only their
 *     <b>own</b> documents.</li>
 * </ul>
 *
 * <p>So a manager can upload a document to another employee's file and then
 * cannot update or delete it. Found by the bounded C3/C8 pass (finding C3-b),
 * reproduced deliberately, and pinned by a regression — a port that tidied the
 * two checks into one shape would change behaviour for exactly the role that
 * sits between them.
 *
 * <p>{@code employee_docs} has no {@code company_id} of its own, so every path
 * here reaches a row only after its owning employee's company has been checked.
 */
@Service
public class LegacyEmployeeDocService {

	private final LegacyPeopleStore store;
	private final LegacyFileUploads uploads;

	public LegacyEmployeeDocService(LegacyPeopleStore store, LegacyFileUploads uploads) {
		this.store = store;
		this.uploads = uploads;
	}

	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	public Page list(LegacyRequestContext context, LegacyQueryParameters query) {
		long targetEmployeeId = targetEmployee(context, query.value("employee_id"));

		List<String> where = new ArrayList<>(List.of("employee_id=?"));
		List<Object> binds = new ArrayList<>(List.of(targetEmployeeId));

		String search = LegacyPagination.searchQueryParam(query);
		if (search != null) {
			where.add("doc_type LIKE ?");
			binds.add("%" + search + "%");
		}

		LegacyPagination.Params pagination = LegacyPagination.params(query);
		long total = store.countDocs(where, binds);
		return new Page(
				store.docs(where, binds, pagination.limit(), pagination.offset()),
				LegacyPagination.meta(total, pagination));
	}

	/**
	 * {@code upload.php}.
	 *
	 * <p>{@code doc_type} defaults to the literal {@code "other"} when absent,
	 * and is <b>not</b> trimmed or validated against any list.
	 */
	public Map<String, Object> upload(
			LegacyRequestContext context, Object rawEmployeeId, String docType, MultipartFile file) {
		long targetEmployeeId = targetEmployee(context, rawEmployeeId);

		String url = uploads.store(file, "docs");
		if (url == null || url.isEmpty()) {
			throw new LegacyApiException(400, "no_file_uploaded");
		}

		// `$_POST['doc_type'] ?? 'other'` -- the default applies only when the key
		// is ABSENT. An explicitly empty `doc_type=` field exists, so PHP stores
		// the empty string; treating "" as absent would silently write "other".
		long id = store.insertDoc(targetEmployeeId, docType == null ? "other" : docType, url);
		return store.docById(id);
	}

	public Map<String, Object> update(
			LegacyRequestContext context, long docId, String docType) {
		Map<String, Object> doc = ownedDocument(context, docId);
		store.updateDoc(docId, docType);
		// `public_row($updated ?? $row)`: a concurrent delete leaves the re-read
		// empty and PHP renders the row it read *before* the update. The
		// pre-update row here carries `owner_company_id`, an alias this port
		// adds to tenant-check a table with no company of its own -- it is not a
		// column of employee_docs and must not reach a response.
		Map<String, Object> updated = store.docById(docId);
		if (updated != null) {
			return updated;
		}
		Map<String, Object> fallback = new java.util.LinkedHashMap<>(doc);
		fallback.remove("owner_company_id");
		return fallback;
	}

	public void delete(LegacyRequestContext context, long docId) {
		ownedDocument(context, docId);
		store.deleteDoc(docId);
	}

	/**
	 * The {@code list}/{@code upload} scope rule: an EMPLOYEE is pinned to
	 * themselves, everybody else -- <b>including MANAGER</b> -- may name any
	 * employee in the company.
	 *
	 * <p>The employee id falls back to the caller's own when absent, so
	 * {@code list.php} with no parameter lists the caller's documents whatever
	 * their role.
	 */
	private long targetEmployee(LegacyRequestContext context, Object rawEmployeeId) {
		// `(int) ($_GET['employee_id'] ?? (int) ($auth['employee_id'] ?? 0))` --
		// the fallback to self fires only when the key is ABSENT. An explicit
		// `?employee_id=` is present, casts to 0, and fails the guard below, so
		// it is `employee_id_required` rather than a silent fallback to the
		// caller's own documents.
		long target = rawEmployeeId == null
				? context.employeeId()
				: LegacyValues.toPhpLong(rawEmployeeId);
		if (target <= 0) {
			throw new LegacyApiException(400, "employee_id_required");
		}
		if (context.role() == LegacyEmployee.Role.EMPLOYEE && target != context.employeeId()) {
			throw new LegacyApiException(403, "forbidden");
		}
		if (!store.employeeInCompany(target, context.companyId())) {
			throw new LegacyApiException(404, "employee_not_found");
		}
		return target;
	}

	/**
	 * The {@code update}/{@code delete} scope rule, which is <b>not</b> the one
	 * above: anyone who is not ADMIN or HR must own the document.
	 */
	private Map<String, Object> ownedDocument(LegacyRequestContext context, long docId) {
		Map<String, Object> doc = store.docWithOwner(context.companyId(), docId);
		if (doc == null) {
			throw new LegacyApiException(404, "not_found");
		}
		boolean administrative = context.role() == LegacyEmployee.Role.COMPANY_ADMIN
				|| context.role() == LegacyEmployee.Role.HR;
		if (!administrative
				&& LegacyValues.toPhpLong(doc.get("employee_id")) != context.employeeId()) {
			throw new LegacyApiException(403, "forbidden");
		}
		return doc;
	}
}
