package com.workin.legacy.people;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code complaints/*.php}.
 *
 * <h2>{@code create.php} is public, and it writes</h2>
 * <p>It is the only endpoint in Item 13 so far that both accepts anonymous
 * callers and persists their input. Auth is <em>optional</em>:
 * {@code if ($auth = getAuth())} attaches the employee and company when a token
 * is present and leaves both null when it is not.
 *
 * <p><b>An anonymous complaint is unreachable afterwards.</b> It is stored with
 * {@code company_id = NULL}, and {@code list.php} filters
 * {@code c.company_id = ?} — so no company's list can ever return it, and there
 * is no other read path. The row is written and then invisible to the API.
 *
 * <p>Whether that is a defect or a deliberate "contact us" inbox read outside
 * the API is an open question for the owner (bounded C3/C8 pass, finding C3-a).
 * It is <b>not</b> filed upstream on this evidence, and it is ported as-is by
 * explicit owner decision (D-132).
 */
@Service
public class LegacyComplaintService {

	/** The only {@code source} value {@code list}, {@code update} and {@code delete} ever match. */
	private static final String EMPLOYEE_SOURCE = "employee";

	private final LegacyPeopleStore store;

	public LegacyComplaintService(LegacyPeopleStore store) {
		this.store = store;
	}

	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	/**
	 * {@code create.php}.
	 *
	 * @param context the caller's context, or null when unauthenticated
	 */
	public void create(LegacyRequestContext context, Map<String, Object> body) {
		for (String field : List.of("name", "phone", "message")) {
			Object value = body.get(field);
			if (value == null || "".equals(value)) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", field));
			}
		}

		Long employeeId = null;
		Long companyId = null;
		String source = EMPLOYEE_SOURCE;
		if (context != null) {
			// A company-type token carries no employee identity and
			// LegacyRequestContext reports 0 for it. complaints.employee_id is a
			// foreign key to employees.id, so storing 0 would fail the
			// constraint outright -- PHP stores NULL, because
			// `isset($auth[EMPLOYEE_ID]) ? (int) ... : null` never produces a
			// zero for a token that has no employee id at all.
			employeeId = context.employeeId() > 0 ? context.employeeId() : null;
			companyId = context.companyId();
			// An admin or HR submitting through the same endpoint is tagged
			// differently, which is what later separates support tickets from
			// employee complaints -- and what makes list.php's source filter
			// exclude them.
			if (context.role() == LegacyEmployee.Role.COMPANY_ADMIN
					|| context.role() == LegacyEmployee.Role.HR) {
				source = "company_support";
			}
		}

		store.insertComplaint(employeeId, companyId, source,
				LegacyValues.toPhpString(body.get("name")),
				body.get("email") == null ? null : LegacyValues.toPhpString(body.get("email")),
				LegacyValues.toPhpString(body.get("phone")),
				LegacyValues.toPhpString(body.get("message")));
	}

	/**
	 * {@code list.php}: this company's {@code source = 'employee'} complaints.
	 *
	 * <p>The {@code source} filter means a complaint an admin filed through the
	 * same endpoint -- tagged {@code company_support} -- never appears in the
	 * list either, for the same structural reason an anonymous one does not.
	 */
	public Page list(long companyId, LegacyQueryParameters query) {
		List<String> where = new ArrayList<>(List.of("c.company_id=?", "c.source=?"));
		List<Object> binds = new ArrayList<>(List.of(companyId, EMPLOYEE_SOURCE));

		// `$_GET['status'] ?? 'pending'` -- the filter is applied by DEFAULT.
		// Omitting the parameter shows only pending complaints; `?status=all`
		// is the way to see every one; and an unrecognised value falls through
		// the in_array() check and also shows everything, so a typo is wider
		// than the default rather than narrower.
		String status = query.value("status") == null
				? "pending" : LegacyValues.toPhpString(query.value("status"));
		if (!"all".equals(status) && List.of("pending", "done", "closed").contains(status)) {
			where.add("c.status=?");
			binds.add(status);
		}

		String search = LegacyPagination.searchQueryParam(query);
		if (search != null) {
			where.add("(c.name LIKE ? OR c.phone LIKE ? OR c.message LIKE ? OR "
					+ "TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,''))) LIKE ?)");
			String like = "%" + search + "%";
			binds.add(like);
			binds.add(like);
			binds.add(like);
			binds.add(like);
		}

		LegacyPagination.Params pagination = LegacyPagination.params(query);
		long total = store.countComplaints(where, binds);
		return new Page(
				store.complaints(where, binds, pagination.limit(), pagination.offset()),
				LegacyPagination.meta(total, pagination));
	}

	/**
	 * {@code update.php}: reply, status, or both.
	 *
	 * <p>Both fields are read under two key names apiece, and the two guards
	 * differ: {@code reply} uses {@code array_key_exists} (so an explicit
	 * {@code null} or empty string clears it), while {@code status} uses
	 * {@code !empty} (so an empty status is silently ignored rather than
	 * rejected). Supplying neither is {@code field_required}.
	 */
	public Map<String, Object> update(long companyId, long id, Map<String, Object> body) {
		if (!store.complaintOwnedBy(id, companyId, EMPLOYEE_SOURCE)) {
			throw new LegacyApiException(404, "not_found");
		}

		List<String> assignments = new ArrayList<>();
		List<Object> values = new ArrayList<>();

		if (body.containsKey("reply")) {
			String reply = LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("reply")));
			assignments.add("reply=?");
			values.add(reply.isEmpty() ? null : reply);
		}

		if (!LegacyValues.isPhpEmpty(body.get("status"))) {
			String status = LegacyValues.toPhpString(body.get("status"));
			if (!List.of("pending", "done", "closed").contains(status)) {
				throw new LegacyApiException(400, "invalid_input");
			}
			assignments.add("status=?");
			values.add(status);
		}

		if (assignments.isEmpty()) {
			throw new LegacyApiException(400, "field_required");
		}

		store.updateComplaint(id, assignments, values);
		// `ok(OK, $row ? public_row($row) : null)` -- a vanished row answers 200
		// with a null data value rather than 404.
		return store.complaintWithEmployee(id);
	}

	public void delete(long companyId, long id) {
		if (!store.complaintOwnedBy(id, companyId, EMPLOYEE_SOURCE)) {
			throw new LegacyApiException(404, "not_found");
		}
		store.deleteComplaint(id);
	}
}
