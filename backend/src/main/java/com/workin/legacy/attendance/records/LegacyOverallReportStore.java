package com.workin.legacy.attendance.records;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The single employee query {@code overall_attendance_report_build()} issues
 * ({@code overall_attendance_report_helper.php:117-176}), before its
 * per-employee loop.
 *
 * <h2>Its ordering is not the attendance listing's ordering</h2>
 * <p>{@link LegacyAttendanceReportStore} orders on
 * {@code CASE WHEN employee_code REGEXP '^[0-9]+$' THEN CAST(... AS UNSIGNED) ELSE NULL END};
 * this query orders on {@code CAST(NULLIF(employee_code, '') AS UNSIGNED)}.
 * They are different expressions and sort mixed alphanumeric codes
 * differently -- MySQL's cast of {@code "12abc"} is {@code 12}, where the
 * REGEXP guard yields NULL. Two legacy queries, two orderings; SQL-observable
 * ordering is part of the contract (D-111), so this one keeps its own.
 *
 * <h2>Two columns PHP computes and never reads -- dropped under D-124</h2>
 * <p>The {@code att} subquery aggregates {@code total_duration_minutes} and
 * {@code total_expected_minutes}, including a correlated per-row lookup of the
 * employee's shift. <b>The builder reads neither.</b> Its loop uses only
 * {@code present_days} and {@code exception_days}; the row's own duration comes
 * from {@code attendance_period_work_minutes()} instead, and its expected
 * minutes never leave that helper.
 *
 * <p>They were reproduced faithfully at first: dropping them is an optimisation
 * rather than a fidelity fix, and D-058 puts the burden of proof on the change.
 * <b>D-124 discharged that burden and they are gone</b> -- measured at 3.0 ms
 * against 9.2 ms over 60 employees x 60 days, with nothing client-visible
 * changed because no response field was ever fed by either column.
 */
@Repository
public class LegacyOverallReportStore {

	/** {@code sql_employee_display_name('e')} ({@code functions.php:169-176}). */
	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,'')))";

	/** {@code sql_employee_roster_join_clause('e')}. */
	private static final String ROSTER_CLAUSE = "COALESCE(e.join_request_status, 'accepted') = 'accepted'";

	/** {@code sql_manager_same_branch_scope('e', $employeeId, $companyId)} ({@code functions.php:673-678}). */
	private static final String MANAGER_SCOPE =
			"e.branch_id = (SELECT eb.branch_id FROM employees eb WHERE eb.id = ? AND eb.company_id = ? LIMIT 1)";

	/** One employee row, exactly the columns the builder's loop is handed. */
	public record EmployeeRow(
			long id, String name, String employeeCode, String photoUrl, String jobTitleName,
			String branchName, String departmentName, int presentDays, int exceptionDays) {
	}

	/** The role-driven scope the builder applies in its WHERE clause. */
	public record Scope(
			long companyId, Long selfEmployeeId, Long managerEmployeeId,
			Long filterEmployeeId, Long branchId, Long departmentId, String search) {
	}

	private final JdbcTemplate jdbcTemplate;

	public LegacyOverallReportStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * {@code data_export_fingerprints_sheet()}'s own employee query
	 * ({@code data_export_helper.php:243-261}) -- <b>not</b> the report's.
	 *
	 * <p>Three rules differ from {@link #employees}, in the same endpoint file:
	 * it orders with the {@code REGEXP '^[0-9]+$'} guard the attendance listing
	 * uses rather than the report's bare cast; a purely numeric search matches
	 * the employee <b>code only</b>, where the report always searches name or
	 * code; and it filters {@code is_active = 1}, which the report does not. It
	 * also has no attendance subquery, because the sheet builds each employee's
	 * days from the range calendar instead.
	 */
	public List<EmployeeRow> fingerprintEmployees(Scope scope) {
		List<Object> binds = new ArrayList<>();
		StringBuilder where = new StringBuilder("e.company_id = ?");
		binds.add(scope.companyId());

		if (scope.selfEmployeeId() != null) {
			where.append(" AND e.id = ?");
			binds.add(scope.selfEmployeeId());
		} else {
			if (scope.filterEmployeeId() != null) {
				where.append(" AND e.id = ?");
				binds.add(scope.filterEmployeeId());
			}
			if (scope.branchId() != null) {
				where.append(" AND e.branch_id = ?");
				binds.add(scope.branchId());
			}
			if (scope.departmentId() != null) {
				where.append(" AND e.department_id = ?");
				binds.add(scope.departmentId());
			}
		}

		if (scope.search() != null) {
			String like = "%" + scope.search() + "%";
			if (scope.search().matches("\\d+")) {
				// A numeric search is a code lookup here; the report would still
				// have matched a name containing those digits.
				where.append(" AND e.employee_code LIKE ?");
				binds.add(like);
			} else {
				where.append(" AND (").append(DISPLAY_NAME).append(" LIKE ? OR e.employee_code LIKE ?)");
				binds.add(like);
				binds.add(like);
			}
		}

		where.append(" AND ").append(ROSTER_CLAUSE).append(" AND e.is_active = 1");

		if (scope.managerEmployeeId() != null) {
			where.append(" AND ").append(MANAGER_SCOPE);
			binds.add(scope.managerEmployeeId());
			binds.add(scope.companyId());
		}

		String sql = """
				SELECT e.id, %s AS name, e.employee_code AS employee_code
				FROM employees AS e
				WHERE %s
				ORDER BY
				  CASE WHEN e.employee_code REGEXP '^[0-9]+$'
				    THEN CAST(e.employee_code AS UNSIGNED) ELSE NULL END ASC,
				  e.employee_code ASC,
				  e.id ASC
				""".formatted(DISPLAY_NAME, where);

		return jdbcTemplate.query(sql, (rs, rowNum) -> new EmployeeRow(
				rs.getLong("id"), rs.getString("name"), rs.getString("employee_code"),
				null, null, null, null, 0, 0), binds.toArray());
	}

	public List<EmployeeRow> employees(Scope scope, String periodFrom, String rangeTo) {
		List<Object> binds = new ArrayList<>();
		// The subquery's range binds come first: it appears in the FROM clause,
		// ahead of every WHERE placeholder.
		binds.add(periodFrom);
		binds.add(rangeTo);

		StringBuilder where = new StringBuilder("e.company_id = ? AND ").append(ROSTER_CLAUSE);
		binds.add(scope.companyId());

		if (scope.selfEmployeeId() != null) {
			// role === EMPLOYEE: forced to self, and the three id filters below
			// are not applied at all -- reachable through export.php, which
			// authenticates any role, not through overall_report.php.
			where.append(" AND e.id = ?");
			binds.add(scope.selfEmployeeId());
		} else {
			if (scope.filterEmployeeId() != null) {
				where.append(" AND e.id = ?");
				binds.add(scope.filterEmployeeId());
			}
			if (scope.branchId() != null) {
				where.append(" AND e.branch_id = ?");
				binds.add(scope.branchId());
			}
			if (scope.departmentId() != null) {
				where.append(" AND e.department_id = ?");
				binds.add(scope.departmentId());
			}
		}

		if (scope.search() != null) {
			where.append(" AND (").append(DISPLAY_NAME).append(" LIKE ? OR e.employee_code LIKE ?)");
			String like = "%" + scope.search() + "%";
			binds.add(like);
			binds.add(like);
		}

		// Applied after the search, exactly where legacy appends it: a manager
		// is scoped in addition to any filter, never instead of one.
		if (scope.managerEmployeeId() != null) {
			where.append(" AND ").append(MANAGER_SCOPE);
			binds.add(scope.managerEmployeeId());
			binds.add(scope.companyId());
		}

		String sql = """
				SELECT
				  e.id,
				  %s AS name,
				  e.employee_code AS employee_code,
				  e.photo_url AS photo_url,
				  jt.name AS job_title_name,
				  b.name AS branch_name,
				  s.name AS department_name,
				  COALESCE(att.present_days, 0) AS present_days,
				  COALESCE(att.exception_days, 0) AS exception_days
				FROM employees e
				LEFT JOIN job_titles jt ON jt.id = e.job_title_id
				LEFT JOIN branches AS b ON b.id = e.branch_id
				LEFT JOIN departments AS s ON s.id = e.department_id
				LEFT JOIN (
				  SELECT
				    a.employee_id,
				    COUNT(DISTINCT DATE(a.check_in)) AS present_days,
				    COUNT(DISTINCT CASE
				      WHEN a.exception_type_id IS NOT NULL THEN DATE(a.check_in)
				    END) AS exception_days
				  FROM attendance a
				  WHERE DATE(a.check_in) BETWEEN ? AND ?
				  GROUP BY a.employee_id
				) att ON att.employee_id = e.id
				WHERE %s
				ORDER BY CAST(NULLIF(e.employee_code, '') AS UNSIGNED) ASC,
				         e.employee_code ASC,
				         e.first_name ASC,
				         e.last_name ASC
				""".formatted(DISPLAY_NAME, where);

		return jdbcTemplate.query(sql, (rs, rowNum) -> new EmployeeRow(
				rs.getLong("id"),
				rs.getString("name"),
				rs.getString("employee_code"),
				rs.getString("photo_url"),
				rs.getString("job_title_name"),
				rs.getString("branch_name"),
				rs.getString("department_name"),
				rs.getInt("present_days"),
				rs.getInt("exception_days")), binds.toArray());
	}
}
