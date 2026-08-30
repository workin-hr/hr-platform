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
 * <h2>Only one of the three write paths validates its foreign ids</h2>
 * <p>{@link #create} checks that the branch, the department (when non-zero) and
 * the job title all belong to the caller's company, answering
 * {@code branch_not_found} / {@code department_not_found} /
 * {@code job_title_not_found} otherwise.
 *
 * <p>{@link #saveTarget} and {@link #update} check <b>none of them</b>. They
 * accept any integer and store it against the caller's own {@code company_id}.
 * Combined with the store's untenanted name joins, that lets a company admin
 * write another company's {@code branch_id} into their own planning row and
 * then read that branch's <b>name</b> back out of {@code list.php} -- and the
 * same for departments and job titles. Iterating ids enumerates a competitor's
 * organizational structure.
 *
 * <p><b>This is reproduced deliberately and is not a defect introduced by the
 * port</b> (D-058, D-131). It is filed upstream as a security issue, it is
 * demonstrated by a regression rather than described, and it must be fixed in
 * legacy first so that the two systems stay comparable. Nothing here should be
 * read as an endorsement of the behaviour.
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

		if (!store.branchBelongsToCompany(ids.branchId(), companyId)) {
			throw new LegacyApiException(404, "branch_not_found");
		}
		if (ids.departmentId() > 0 && !store.departmentBelongsToCompany(ids.departmentId(), companyId)) {
			throw new LegacyApiException(404, "department_not_found");
		}
		if (!store.jobTitleBelongsToCompany(ids.jobTitleId(), companyId)) {
			throw new LegacyApiException(404, "job_title_not_found");
		}

		long id = store.insert(
				companyId, ids.branchId(), ids.departmentId(), ids.jobTitleId(), ids.planned());
		return store.byId(id);
	}

	/**
	 * {@code save_target.php} -- a backward-compatible upsert for older clients,
	 * with <b>no ownership validation at all</b>.
	 *
	 * <p>Its response is {@code {"saved": true}} rather than the row, so a
	 * caller cannot tell from the reply whether it created or updated one.
	 */
	public void saveTarget(long companyId, Map<String, Object> body) {
		Ids ids = requiredIds(body);
		store.upsert(companyId, ids.branchId(), ids.departmentId(), ids.jobTitleId(), ids.planned());
	}

	/**
	 * {@code update.php} -- a four-column whitelist, three of which are foreign
	 * ids that are written straight through with no ownership check.
	 */
	public Map<String, Object> update(long companyId, long id, Map<String, Object> body) {
		if (!store.existsForCompany(companyId, id)) {
			throw new LegacyApiException(404, "not_found");
		}
		List<String> assignments = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		for (String field : List.of("branch_id", "department_id", "job_title_id", "planned_count")) {
			if (body.containsKey(field)) {
				assignments.add("`" + field + "`=?");
				// PDO binds a scalar unchanged and converts an array or object to
				// the literal "Array"; only that second case needs coercing.
				values.add(LegacyValues.toPdoBindValue(body.get(field)));
			}
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
