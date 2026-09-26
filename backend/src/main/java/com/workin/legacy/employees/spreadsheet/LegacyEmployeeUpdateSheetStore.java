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

	/**
	 * What one chunk's rows name, as the single-row checks would answer it,
	 * in <b>one statement</b>: a {@code UNION ALL} with one arm per question
	 * and per {@link #CHUNK} identifiers --
	 * <ul>
	 * <li>shifts in the company ({@code shift_belongs_to_company()});</li>
	 * <li>every {@code department_branches} row for the departments, with no
	 *     company predicate and no active check
	 *     ({@code department_belongs_to_branch()});</li>
	 * <li>the departments that are in the company and active
	 *     ({@code department_belongs_to_company()});</li>
	 * <li>each active job title's department
	 *     ({@code job_title_belongs_to_department()}, no company predicate,
	 *     D-075);</li>
	 * <li>every non-rejected employee row stored in any spelling of the
	 *     numbers ({@code employee_phone_exists_globally()}'s candidates; the
	 *     caller verifies each row's own {@code (phone, country_code)} as
	 *     {@link com.workin.legacy.phone.PhoneLookup} does).</li>
	 * </ul>
	 * Every arm names its columns, since the first arm present names the
	 * result's. One statement because it runs once per chunk outside any
	 * transaction:
	 * each extra statement would be another round trip, and each checkout
	 * already costs two (D-099).
	 */
	public References references(Collection<Long> shiftIds, Collection<Long> departmentIds,
			Collection<Long> jobTitleIds, Collection<CanonicalPhone> phones, long companyId) {
		List<String> arms = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		for (List<Long> chunk : chunks(shiftIds)) {
			arms.add("SELECT 's' AS kind, s.id AS a, NULL AS b, NULL AS phone, NULL AS country_code FROM shifts s"
					+ " WHERE s.id IN (" + placeholders(chunk.size()) + ") AND s.company_id = ?");
			params.addAll(chunk);
			params.add(companyId);
		}
		for (List<Long> chunk : chunks(departmentIds)) {
			arms.add("SELECT 'l' AS kind, department_id AS a, branch_id AS b, NULL AS phone, NULL AS country_code"
					+ " FROM department_branches"
					+ " WHERE department_id IN (" + placeholders(chunk.size()) + ")");
			params.addAll(chunk);
			arms.add("SELECT 'd' AS kind, id AS a, NULL AS b, NULL AS phone, NULL AS country_code"
					+ " FROM departments WHERE id IN (" + placeholders(chunk.size())
					+ ") AND company_id = ? AND is_active = 1");
			params.addAll(chunk);
			params.add(companyId);
		}
		for (List<Long> chunk : chunks(jobTitleIds)) {
			arms.add("SELECT 'j' AS kind, id AS a, department_id AS b, NULL AS phone, NULL AS country_code"
					+ " FROM job_titles WHERE id IN ("
					+ placeholders(chunk.size()) + ") AND is_active = 1 AND department_id IS NOT NULL");
			params.addAll(chunk);
		}
		for (List<CanonicalPhone> chunk : chunks(phones)) {
			LinkedHashSet<String> spellings = new LinkedHashSet<>();
			for (CanonicalPhone phone : chunk) {
				spellings.addAll(phone.storedSpellings());
			}
			arms.add("SELECT 'p' AS kind, id AS a, NULL AS b, phone, country_code"
					+ " FROM employees WHERE phone IN ("
					+ placeholders(spellings.size()) + ") AND COALESCE(join_request_status, 'accepted') <> 'rejected'");
			params.addAll(spellings);
		}
		References references = new References(
				new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashMap<>(), new ArrayList<>());
		if (arms.isEmpty()) {
			return references;
		}
		this.jdbcTemplate.query(String.join(" UNION ALL ", arms), rs -> {
			long a = rs.getLong("a");
			switch (rs.getString("kind")) {
				case "s" -> references.shifts().add(a);
				case "l" -> references.departmentBranches().add(new DepartmentBranch(a, rs.getLong("b")));
				case "d" -> references.activeDepartments().add(a);
				case "j" -> references.activeJobTitleDepartments().put(a, rs.getLong("b"));
				default -> {
					Map<String, Object> holder = new java.util.LinkedHashMap<>();
					holder.put("id", a);
					holder.put("phone", rs.getString("phone"));
					holder.put("country_code", rs.getString("country_code"));
					references.phoneHolders().add(holder);
				}
			}
		}, params.toArray());
		return references;
	}

	/** {@link #references}' answer. */
	public record References(Set<Long> shifts, Set<DepartmentBranch> departmentBranches,
			Set<Long> activeDepartments, Map<Long, Long> activeJobTitleDepartments,
			List<Map<String, Object>> phoneHolders) {
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
