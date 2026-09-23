package com.workin.legacy.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code workforce_planning/*.php} -- planned against actual headcount.
 *
 * <h2>All three write paths validate their foreign ids</h2>
 * <p>{@link #create}, {@link #saveTarget} and {@link #update} each check that
 * the branch, the department (when non-zero) and the job title belong to the
 * caller's company, answering {@code branch_not_found} /
 * {@code department_not_found} / {@code job_title_not_found} otherwise.
 * {@code update} checks each key the body actually carries, because a field it
 * omits is not being written.
 *
 * <p>Two details decide whether that holds. The id <b>written</b> is the id
 * that was <b>checked</b>: binding PDO's string instead would let MariaDB round
 * {@code "28011.9"} to 28012 under this datasource's empty {@code sql_mode},
 * and 28012 is whatever company owns the next id. And editing asks only
 * ownership -- {@code create.php}'s extra {@code is_active} rule for job titles
 * is kept only on {@code create}, so a target planned against a title that was
 * deactivated afterwards can still be re-saved by the client that owns it.
 *
 * <p><b>This diverges from legacy deliberately</b> (D-277, reversing D-131 for
 * this surface on the owner's instruction). Until 2026-09-23 {@code saveTarget}
 * and {@code update} checked none of the three, which let a company admin write
 * another company's {@code branch_id} into their own planning row and read that
 * branch's <b>name</b> back out of {@code list.php}; that was reproduced
 * deliberately under D-058 and filed upstream, where it is still open. Legacy
 * is unchanged, so the two systems now answer differently for a request no
 * legitimate client makes.
 */
@Service
public class LegacyWorkforcePlanningService {

	private final LegacyWorkforcePlanningStore store;

	public LegacyWorkforcePlanningService(LegacyWorkforcePlanningStore store) {
		this.store = store;
	}

	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	public Page list(long companyId, LegacyQueryParameters query) {
		List<String> where = new ArrayList<>(List.of("wt.company_id = ?"));
		List<Object> binds = new ArrayList<>(List.of(companyId));

		for (String field : List.of("branch_id", "department_id", "job_title_id")) {
			if (!LegacyValues.isPhpEmpty(query.value(field))) {
				where.add("wt." + field + " = ?");
				binds.add(LegacyValues.toPhpLong(query.value(field)));
			}
		}

		// The search matches the job title's name only -- not the branch or the
		// department, despite both being selected in the same row.
		String search = LegacyPagination.searchQueryParam(query);
		if (search != null) {
			where.add("jt.name LIKE ?");
			binds.add("%" + search + "%");
		}

		LegacyPagination.Params pagination = LegacyPagination.params(query);
		long total = store.count(where, binds);
		return new Page(
				store.page(where, binds, pagination.limit(), pagination.offset()),
				LegacyPagination.meta(total, pagination));
	}

	public Map<String, Object> one(long companyId, long id) {
		Map<String, Object> row = store.one(companyId, id);
		if (row == null) {
			throw new LegacyApiException(404, "not_found");
		}
		return row;
	}

	/**
	 * {@code create.php} -- the one write path that validates.
	 *
	 * <p>The department check is skipped entirely when the id is zero, because
	 * {@code department_id} defaults to 0 in the schema and legacy treats that
	 * as "no department" rather than as a foreign key.
	 */
	public Map<String, Object> create(long companyId, Map<String, Object> body) {
		Ids ids = requiredIds(body);
		requireOwnBranch(companyId, ids.branchId());
		requireOwnDepartment(companyId, ids.departmentId());
		requireOwnActiveJobTitle(companyId, ids.jobTitleId());

		long id = store.insert(
				companyId, ids.branchId(), ids.departmentId(), ids.jobTitleId(), ids.planned());
		return store.byId(id);
	}

	/**
	 * {@code save_target.php} -- a backward-compatible upsert for older clients.
	 *
	 * <p>Its response is {@code {"saved": true}} rather than the row, so a
	 * caller cannot tell from the reply whether it created or updated one.
	 *
	 * <p>The three org keys are validated exactly as {@code create.php} validates
	 * them. Legacy validates none of them here, which is the cross-tenant
	 * disclosure D-131 recorded and this surface no longer reproduces (D-277).
	 */
	public void saveTarget(long companyId, Map<String, Object> body) {
		Ids ids = requiredIds(body);
		requireOwnBranch(companyId, ids.branchId());
		requireOwnDepartment(companyId, ids.departmentId());
		requireOwnJobTitle(companyId, ids.jobTitleId());
		store.upsert(companyId, ids.branchId(), ids.departmentId(), ids.jobTitleId(), ids.planned());
	}

	/**
	 * {@code update.php} -- a four-column whitelist, three of which are foreign
	 * ids.
	 *
	 * <p>Each of those three is validated when the body carries it, so an edit
	 * cannot move a row onto another company's branch, department or job title --
	 * D-176's rule, which the dashboard port already applied to this same table
	 * (D-177), now applied here too (D-277). A field the body omits is not
	 * checked, because it is not being written.
	 */
	public Map<String, Object> update(long companyId, long id, Map<String, Object> body) {
		if (!store.existsForCompany(companyId, id)) {
			throw new LegacyApiException(404, "not_found");
		}
		List<String> assignments = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		for (String field : List.of("branch_id", "department_id", "job_title_id", "planned_count")) {
			if (!body.containsKey(field)) {
				continue;
			}
			Object raw = body.get(field);
			assignments.add("`" + field + "`=?");
			if (field.equals("planned_count")) {
				// PDO binds a scalar unchanged and converts an array or object to
				// the literal "Array"; only that second case needs coercing.
				values.add(LegacyValues.toPdoBindValue(raw));
				continue;
			}
			// The three org ids are written as the long that was checked, which is
			// also what create.php's insert binds. Binding PDO's string instead
			// would let a check and its write disagree: `28011.9` is checked as
			// `(int) 28011.9` = 28011, but MariaDB rounds the string "28011.9" to
			// 28012 on an INT column under this datasource's empty sql_mode, and
			// 28012 is whatever company owns the next id.
			long checked = LegacyValues.toPhpLong(raw);
			switch (field) {
				case "branch_id" -> requireOwnBranch(companyId, checked);
				case "department_id" -> requireOwnDepartment(companyId, checked);
				default -> requireOwnJobTitle(companyId, checked);
			}
			values.add(checked);
		}
		if (assignments.isEmpty()) {
			throw new LegacyApiException(400, "nothing_to_update");
		}
		store.update(companyId, id, assignments, values);
		// Re-read by id alone, without the company filter the UPDATE carried.
		return store.byId(id);
	}

	public void delete(long companyId, long id) {
		if (!store.existsForCompany(companyId, id)) {
			throw new LegacyApiException(404, "not_found");
		}
		store.delete(companyId, id);
	}

	private void requireOwnBranch(long companyId, long branchId) {
		if (!store.branchBelongsToCompany(branchId, companyId)) {
			throw new LegacyApiException(404, "branch_not_found");
		}
	}

	/**
	 * Zero is "no department", not a foreign key -- the schema's default, and what
	 * legacy treats it as -- so it is the one value that skips the check.
	 */
	private void requireOwnDepartment(long companyId, long departmentId) {
		if (departmentId > 0 && !store.departmentBelongsToCompany(departmentId, companyId)) {
			throw new LegacyApiException(404, "department_not_found");
		}
	}

	/** {@code create.php}'s own rule, which also requires the title to be active. */
	private void requireOwnActiveJobTitle(long companyId, long jobTitleId) {
		if (!store.jobTitleBelongsToCompany(jobTitleId, companyId)) {
			throw new LegacyApiException(404, "job_title_not_found");
		}
	}

	/**
	 * Ownership only, for the two paths that edit a row that already exists.
	 *
	 * <p>What D-277 adds to {@code update.php} and {@code save_target.php} is a
	 * tenant boundary, not {@code create.php}'s activity rule: a target planned
	 * against a job title that was deactivated afterwards is still this company's
	 * own row, and an older client re-sending the whole row -- which is what
	 * {@code save_target.php} exists for -- would otherwise be refused an edit it
	 * used to be allowed, including an edit of {@code planned_count} alone.
	 */
	private void requireOwnJobTitle(long companyId, long jobTitleId) {
		if (!store.jobTitleOwnedByCompany(jobTitleId, companyId)) {
			throw new LegacyApiException(404, "job_title_not_found");
		}
	}

	private record Ids(long branchId, long departmentId, long jobTitleId, long planned) {
	}

	/**
	 * {@code required($body, [BRANCH_ID, DEPARTMENT_ID, JOB_TITLE_ID, PLANNED_COUNT])}.
	 *
	 * <p>All four keys must be present, and {@code planned_count} is floored at
	 * zero by {@code max(0, (int) ...)} -- so a negative plan is stored as 0
	 * rather than rejected.
	 */
	private static Ids requiredIds(Map<String, Object> body) {
		for (String field : List.of("branch_id", "department_id", "job_title_id", "planned_count")) {
			Object value = body.get(field);
			if (value == null || "".equals(value)) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", field));
			}
		}
		return new Ids(
				LegacyValues.toPhpLong(body.get("branch_id")),
				LegacyValues.toPhpLong(body.get("department_id")),
				LegacyValues.toPhpLong(body.get("job_title_id")),
				Math.max(0, LegacyValues.toPhpLong(body.get("planned_count"))));
	}
}
