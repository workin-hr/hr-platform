package com.workin.legacy.attendance.records;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyPagination;

/**
 * The raw queries {@code attendance/list.php} (both branches) and
 * {@code attendance/stats.php}'s aggregate branch issue.
 * {@code attendance/employee_monthly_attendance.php}'s own raw fetch lives
 * here too.
 */
@Repository
public class LegacyAttendanceReportStore {

	/** {@code sql_employee_display_name('e')} ({@code functions.php:169-176}). */
	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,'')))";

	/** {@code sql_manager_same_branch_scope('e', ...)} ({@code functions.php:673-678}). */
	private static final String MANAGER_SCOPE =
			"e.branch_id = (SELECT eb.branch_id FROM employees eb WHERE eb.id = ? AND eb.company_id = ? LIMIT 1)";

	/** {@code sql_employee_roster_join_clause('e')}: accepted join requests and legacy rows. */
	private static final String ROSTER_CLAUSE = "COALESCE(e.join_request_status, 'accepted') = 'accepted'";

	/** The natural employee-code ordering every attendance listing query shares. */
	private static final String EMPLOYEE_CODE_ORDER = """
			CASE
				WHEN e.employee_code REGEXP '^[0-9]+$' THEN CAST(e.employee_code AS UNSIGNED)
				ELSE NULL
			END ASC,
			e.employee_code ASC""";

	/** {@code list.php}'s non-fill_days role-driven scope. */
	public record ListFilter(
			Long ownEmployeeId, Long companyId, Long filterEmployeeId, Long branchId, Long departmentId,
			String dateFrom, String dateTo, String search) {
	}

	private final JdbcTemplate jdbcTemplate;

	public LegacyAttendanceReportStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	private static String where(ListFilter filter, List<Object> binds) {
		StringBuilder sql = new StringBuilder("WHERE ");
		if (filter.ownEmployeeId() != null) {
			sql.append("a.employee_id=?");
			binds.add(filter.ownEmployeeId());
		} else {
			sql.append("e.company_id=?");
			binds.add(filter.companyId());
			if (filter.filterEmployeeId() != null) {
				sql.append(" AND a.employee_id=?");
				binds.add(filter.filterEmployeeId());
			}
			if (filter.branchId() != null) {
				sql.append(" AND e.branch_id=?");
				binds.add(filter.branchId());
			}
			if (filter.departmentId() != null) {
				sql.append(" AND e.department_id=?");
				binds.add(filter.departmentId());
			}
		}
		if (filter.dateFrom() != null) {
			sql.append(" AND DATE(a.check_in)>=?");
			binds.add(filter.dateFrom());
		}
		if (filter.dateTo() != null) {
			sql.append(" AND DATE(a.check_in)<=?");
			binds.add(filter.dateTo());
		}
		if (filter.search() != null) {
			if (filter.search().matches("^\\d+$")) {
				sql.append(" AND e.employee_code LIKE ?");
				binds.add("%" + filter.search() + "%");
			} else {
				sql.append(" AND (").append(DISPLAY_NAME).append(" LIKE ? OR e.employee_code LIKE ?)");
				String like = "%" + filter.search() + "%";
				binds.add(like);
				binds.add(like);
			}
		}
		return sql.toString();
	}

	public long count(ListFilter filter) {
		List<Object> binds = new ArrayList<>();
		String sql = "SELECT COUNT(*) FROM attendance a JOIN employees e ON e.id = a.employee_id " + where(filter, binds);
		Long total = jdbcTemplate.queryForObject(sql, Long.class, binds.toArray());
		return total == null ? 0L : total;
	}

	/** {@code list.php}'s non-fill_days branch: the full joined row set, paginated. */
	public List<Map<String, Object>> list(ListFilter filter, LegacyPagination.Params pagination) {
		List<Object> binds = new ArrayList<>();
		String sql = """
				SELECT a.*, %s AS employee_name, e.employee_code AS employee_code, e.photo_url AS photo_url,
					TIMESTAMPDIFF(MINUTE, a.check_in, a.check_out) AS duration_minutes,
					br.name AS branch_name, s.name AS department_name, jt.name AS job_title_name,
					et.id AS exception_type_id, et.name AS exception_type_name,
					(
						SELECT sh.name FROM employee_shift_assignments esa
						INNER JOIN shifts sh ON sh.id = esa.shift_id
						WHERE esa.employee_id = e.id
						ORDER BY esa.effective_from DESC, esa.id DESC LIMIT 1
					) AS shift_name,
					(
						SELECT
							CASE
								WHEN TIMESTAMPDIFF(MINUTE,
									STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.start_time), '%%Y-%%m-%%d %%H:%%i:%%s'),
									STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.end_time), '%%Y-%%m-%%d %%H:%%i:%%s')
								) < 0
								THEN TIMESTAMPDIFF(MINUTE,
									STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.start_time), '%%Y-%%m-%%d %%H:%%i:%%s'),
									STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.end_time), '%%Y-%%m-%%d %%H:%%i:%%s')
								) + 1440
								ELSE TIMESTAMPDIFF(MINUTE,
									STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.start_time), '%%Y-%%m-%%d %%H:%%i:%%s'),
									STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.end_time), '%%Y-%%m-%%d %%H:%%i:%%s')
								)
							END
						FROM employee_shift_assignments esa
						INNER JOIN shifts sh ON sh.id = esa.shift_id
						WHERE esa.employee_id = e.id
						ORDER BY esa.effective_from DESC, esa.id DESC LIMIT 1
					) AS expected_duration_minutes
				FROM attendance a
				JOIN employees e ON e.id = a.employee_id
				LEFT JOIN branches br ON e.branch_id = br.id
				LEFT JOIN departments s ON s.id = e.department_id
				LEFT JOIN job_titles jt ON jt.id = e.job_title_id
				LEFT JOIN exception_types et ON et.id = a.exception_type_id
				%s
				ORDER BY %s, a.check_in ASC, a.id ASC
				LIMIT ? OFFSET ?"""
				.formatted(DISPLAY_NAME, where(filter, binds), EMPLOYEE_CODE_ORDER);
		binds.add(pagination.limit());
		binds.add(pagination.offset());
		return jdbcTemplate.query(sql, rowMapper(), binds.toArray());
	}

	/** {@code list.php}'s {@code fill_days} branch: the employee roster it expands into calendars. */
	public record RosterEmployee(
			long id, String employeeName, String employeeCode, String photoUrl, String branchName,
			String departmentName, String jobTitleName) {
	}

	public List<RosterEmployee> rosterForFillDays(
			long companyId, Long employeeId, Long branchId, Long departmentId, String search) {
		List<Object> binds = new ArrayList<>();
		StringBuilder sql = new StringBuilder("WHERE e.company_id=?");
		binds.add(companyId);
		if (employeeId != null) {
			sql.append(" AND e.id=?");
			binds.add(employeeId);
		} else {
			if (branchId != null) {
				sql.append(" AND e.branch_id=?");
				binds.add(branchId);
			}
			if (departmentId != null) {
				sql.append(" AND e.department_id=?");
				binds.add(departmentId);
			}
		}
		if (search != null) {
			if (search.matches("^\\d+$")) {
				sql.append(" AND e.employee_code LIKE ?");
				binds.add("%" + search + "%");
			} else {
				sql.append(" AND (").append(DISPLAY_NAME).append(" LIKE ? OR e.employee_code LIKE ?)");
				String like = "%" + search + "%";
				binds.add(like);
				binds.add(like);
			}
		}
		sql.append(" AND ").append(ROSTER_CLAUSE).append(" AND e.is_active = 1");

		String query = """
				SELECT e.id, %s AS employee_name, e.employee_code AS employee_code, e.photo_url AS photo_url,
					br.name AS branch_name, s.name AS department_name, jt.name AS job_title_name
				FROM employees e
				LEFT JOIN branches br ON e.branch_id = br.id
				LEFT JOIN departments s ON s.id = e.department_id
				LEFT JOIN job_titles jt ON jt.id = e.job_title_id
				%s
				ORDER BY %s, e.id ASC"""
				.formatted(DISPLAY_NAME, sql, EMPLOYEE_CODE_ORDER);
		return jdbcTemplate.query(query, (rs, index) -> new RosterEmployee(
				rs.getLong("id"), rs.getString("employee_name"), rs.getString("employee_code"),
				rs.getString("photo_url"), rs.getString("branch_name"), rs.getString("department_name"),
				rs.getString("job_title_name")), binds.toArray());
	}

	/** {@code stats.php}'s aggregate (non-per-employee) branch. */
	public record AggregateStats(long presentDays, long totalDurationMinutes) {
	}

	public AggregateStats aggregateStats(
			long companyId, Long branchId, Long departmentId, String dateFrom, String dateTo) {
		List<Object> binds = new ArrayList<>();
		StringBuilder sql = new StringBuilder("WHERE e.company_id=?");
		binds.add(companyId);
		if (branchId != null) {
			sql.append(" AND e.branch_id=?");
			binds.add(branchId);
		}
		if (departmentId != null) {
			sql.append(" AND e.department_id=?");
			binds.add(departmentId);
		}
		if (dateFrom != null) {
			sql.append(" AND DATE(a.check_in)>=?");
			binds.add(dateFrom);
		}
		if (dateTo != null) {
			sql.append(" AND DATE(a.check_in)<=?");
			binds.add(dateTo);
		}
		String whereSql = sql.toString();

		Long presentDays = jdbcTemplate.queryForObject(
				"SELECT COUNT(DISTINCT DATE(a.check_in)) FROM attendance a JOIN employees e"
						+ " ON e.id = a.employee_id " + whereSql + " AND a.check_out IS NOT NULL",
				Long.class, binds.toArray());
		Long totalDuration = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(TIMESTAMPDIFF(MINUTE, a.check_in, a.check_out)), 0) FROM attendance a"
						+ " JOIN employees e ON e.id = a.employee_id " + whereSql + " AND a.check_out IS NOT NULL",
				Long.class, binds.toArray());
		return new AggregateStats(presentDays == null ? 0L : presentDays, totalDuration == null ? 0L : totalDuration);
	}

	/**
	 * {@code official_holidays_count_in_range()}
	 * ({@code official_holidays_helper.php:85-99}): {@code stats.php}'s
	 * aggregate branch counts every holiday row in range, not distinct dates --
	 * two holidays sharing a date count twice, unlike {@code holidaysByDate}'s
	 * map.
	 */
	public long officialHolidayCountInRange(long companyId, String from, String to) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM company_official_holidays WHERE company_id = ? AND holiday_date BETWEEN ? AND ?",
				Long.class, companyId, from, to);
		return count == null ? 0L : count;
	}

	/** {@code employee_monthly_attendance.php}'s raw fetch: every column, plus the raw duration. */
	public List<Map<String, Object>> monthRows(long employeeId, int month, int year) {
		return jdbcTemplate.query("""
				SELECT *, TIMESTAMPDIFF(MINUTE, check_in, check_out) AS duration_minutes
				FROM attendance
				WHERE employee_id = ? AND MONTH(check_in) = ? AND YEAR(check_in) = ?
				ORDER BY check_in ASC""", rowMapper(), employeeId, month, year);
	}

	private static RowMapper<Map<String, Object>> rowMapper() {
		return (ResultSet rs, int index) -> {
			ResultSetMetaData meta = rs.getMetaData();
			Map<String, Object> row = new LinkedHashMap<>();
			for (int column = 1; column <= meta.getColumnCount(); column++) {
				row.put(meta.getColumnLabel(column), LegacyJdbcValues.read(rs, column, meta.getColumnType(column)));
			}
			return row;
		};
	}

}
