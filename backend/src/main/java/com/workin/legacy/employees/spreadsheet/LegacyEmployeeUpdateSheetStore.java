package com.workin.legacy.employees.spreadsheet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.phone.CanonicalPhone;

/**
 * The bulk-update sheet's reads, one statement per chunk of identifiers
 * instead of one per row (D-294).
 *
 * <p>Each method answers, for a whole set of ids, exactly the question one
 * single-row check on {@link com.workin.legacy.employees.LegacyEmployeeStore}
 * answers for one id -- same table, same predicates, nothing added or
 * dropped -- so a row validated against the returned set gets the answer the
 * per-row statement would have given it. The single-row checks stay where they
 * are: the create sheet and the one-employee endpoints still use them.
 *
 * <p>Every {@code IN (...)} list is cut at {@link #CHUNK} identifiers. A sheet
 * has no row cap of its own below the reader's 200,000, and one unbounded list
 * would be a statement whose size the uploader chooses.
 */
@Repository
public class LegacyEmployeeUpdateSheetStore {

	/** The most identifiers one {@code IN (...)} list binds, and the rows one write transaction holds. */
	static final int CHUNK = 500;

	private final JdbcTemplate jdbcTemplate;

	public LegacyEmployeeUpdateSheetStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** The ids among {@code shiftIds} that {@code shift_belongs_to_company()} accepts. */
	public Set<Long> shiftsInCompany(Collection<Long> shiftIds, long companyId) {
		Set<Long> found = new HashSet<>();
		for (List<Long> chunk : chunks(shiftIds)) {
			List<Object> params = new ArrayList<>(chunk);
			params.add(companyId);
			found.addAll(this.jdbcTemplate.queryForList(
					"SELECT s.id FROM shifts s WHERE s.id IN (" + placeholders(chunk.size()) + ") AND s.company_id = ?",
					Long.class, params.toArray()));
		}
		return found;
	}

	/**
	 * Every {@code department_branches} row for these departments, as
	 * {@code department_belongs_to_branch()} reads the junction: no company
	 * predicate, no active check.
	 */
	public Set<DepartmentBranch> departmentBranches(Collection<Long> departmentIds) {
		Set<DepartmentBranch> found = new HashSet<>();
		for (List<Long> chunk : chunks(departmentIds)) {
			this.jdbcTemplate.query(
					"SELECT department_id, branch_id FROM department_branches WHERE department_id IN ("
							+ placeholders(chunk.size()) + ")",
					rs -> {
						found.add(new DepartmentBranch(rs.getLong("department_id"), rs.getLong("branch_id")));
					}, chunk.toArray());
		}
		return found;
	}

	/** The ids among {@code departmentIds} that {@code department_belongs_to_company()} accepts: company and active. */
	public Set<Long> activeDepartmentsInCompany(Collection<Long> departmentIds, long companyId) {
		Set<Long> found = new HashSet<>();
		for (List<Long> chunk : chunks(departmentIds)) {
			List<Object> params = new ArrayList<>(chunk);
			params.add(companyId);
			found.addAll(this.jdbcTemplate.queryForList(
					"SELECT id FROM departments WHERE id IN (" + placeholders(chunk.size())
							+ ") AND company_id = ? AND is_active = 1",
					Long.class, params.toArray()));
		}
		return found;
	}

	/**
	 * Each active job title's department, for {@code job_title_belongs_to_department()}:
	 * {@code id} is the key, so "this title in that department" is one map lookup.
	 * No company predicate, as the single-row check has none (D-075).
	 */
	public Map<Long, Long> activeJobTitleDepartments(Collection<Long> jobTitleIds) {
		Map<Long, Long> found = new HashMap<>();
		for (List<Long> chunk : chunks(jobTitleIds)) {
			this.jdbcTemplate.query(
					"SELECT id, department_id FROM job_titles WHERE id IN (" + placeholders(chunk.size())
							+ ") AND is_active = 1",
					rs -> {
						long department = rs.getLong("department_id");
						if (!rs.wasNull()) {
							found.put(rs.getLong("id"), department);
						}
					}, chunk.toArray());
		}
		return found;
	}

	/**
	 * The rows {@code employee_phone_exists_globally()} would weigh for any of
	 * these numbers: global, every stored spelling of each, a rejected join
	 * request never counting. Candidates only -- the caller verifies each row's
	 * own {@code (phone, country_code)} as {@link com.workin.legacy.phone.PhoneLookup}
	 * does, and applies its own exclusion.
	 */
	public List<Map<String, Object>> phoneHolders(Collection<CanonicalPhone> phones) {
		List<Map<String, Object>> holders = new ArrayList<>();
		for (List<CanonicalPhone> chunk : chunks(phones)) {
			LinkedHashSet<String> spellings = new LinkedHashSet<>();
			for (CanonicalPhone phone : chunk) {
				spellings.addAll(phone.storedSpellings());
			}
			holders.addAll(this.jdbcTemplate.queryForList(
					"SELECT id, phone, country_code FROM employees WHERE phone IN (" + placeholders(spellings.size())
							+ ") AND COALESCE(join_request_status, 'accepted') <> 'rejected'",
					spellings.toArray()));
		}
		return holders;
	}

	/**
	 * The apply step's boundary re-read, for a whole chunk at once: each
	 * employee still in this company, as {@code employee_excel_apply_update()}
	 * re-reads it before writing, with the latest salary contract
	 * {@code employee_excel_apply_salary_patch()} would patch -- by
	 * {@code effective_from DESC, id DESC}, {@code contract_id} null when there
	 * is none. An id missing from the answer is an employee that is gone or in
	 * another company. Values are read as {@link LegacyJdbcValues} reads them,
	 * so {@code hire_date} is the stored text, zero dates included.
	 *
	 * <p>Called inside the write transaction, before any of its writes, so the
	 * contract and the hire date are the ones the row's own writes start from.
	 */
	public Map<Long, Map<String, Object>> applyTargets(Collection<Long> employeeIds, long companyId) {
		Map<Long, Map<String, Object>> targets = new HashMap<>();
		for (List<Long> chunk : chunks(employeeIds)) {
			String in = placeholders(chunk.size());
			List<Object> params = new ArrayList<>(chunk);
			params.addAll(chunk);
			params.add(companyId);
			for (Map<String, Object> row : this.jdbcTemplate.query("""
					SELECT e.id, e.role, e.phone, e.country_code, e.hire_date, c.id AS contract_id
					FROM employees e
					LEFT JOIN (
						SELECT id, employee_id,
							ROW_NUMBER() OVER (PARTITION BY employee_id ORDER BY effective_from DESC, id DESC) AS pick
						FROM salary_contracts WHERE employee_id IN (%s)
					) c ON c.employee_id = e.id AND c.pick = 1
					WHERE e.id IN (%s) AND e.company_id = ?""".formatted(in, in),
					LegacyJdbcValues.rowMapper(), params.toArray())) {
				targets.put(((Number) row.get("id")).longValue(), row);
			}
		}
		return targets;
	}

	/** One {@code department_branches} row. */
	public record DepartmentBranch(long departmentId, long branchId) {
	}

	/** {@code values} cut into lists of at most {@link #CHUNK}, duplicates dropped. */
	static <T> List<List<T>> chunks(Collection<T> values) {
		List<T> distinct = new ArrayList<>(new LinkedHashSet<>(values));
		List<List<T>> chunks = new ArrayList<>();
		for (int from = 0; from < distinct.size(); from += CHUNK) {
			chunks.add(distinct.subList(from, Math.min(distinct.size(), from + CHUNK)));
		}
		return chunks;
	}

	private static String placeholders(int count) {
		return String.join(", ", java.util.Collections.nCopies(count, "?"));
	}
}
