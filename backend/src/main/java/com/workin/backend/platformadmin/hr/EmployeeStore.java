package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.backend.platformadmin.web.DashboardListFilters;
import com.workin.backend.platformadmin.web.DashboardPage;

/**
 * The queries the employees page makes
 * ({@code employee_paginate_list()}, {@code employee_helper.php:386-477}).
 *
 * <p>Legacy probes {@code INFORMATION_SCHEMA} at runtime to decide whether
 * {@code employees} has {@code first_name}, {@code employee_code} and
 * {@code join_request_status} -- a compatibility shim for a schema this
 * deployment left behind. All three exist in the target schema, so the port
 * takes the true branch of each unconditionally rather than carrying a probe
 * whose answer is already known.
 */
@Repository
@Profile("phase1-mysql")
public class EmployeeStore {

	private final JdbcTemplate jdbcTemplate;

	public EmployeeStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** {@code dashboard_employee_display_name_sql()}, split-name branch. */
	private static final String NAME_SQL =
			"TRIM(CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,'')))";

	/** {@code dashboard_employee_code_sql()}: the id stands in for a blank code. */
	private static final String CODE_SQL =
			"COALESCE(NULLIF(TRIM(e.employee_code), ''), CAST(e.id AS CHAR))";

	private static final String LATEST_SHIFT =
			"(SELECT sh.name FROM employee_shift_assignments esa"
					+ " INNER JOIN shifts sh ON sh.id = esa.shift_id"
					+ " WHERE esa.employee_id = e.id"
					+ " ORDER BY esa.effective_from DESC, esa.id DESC LIMIT 1) AS shift_name";

	private static final String LATEST_SALARY =
			"(SELECT sc.basic_salary FROM salary_contracts sc WHERE sc.employee_id = e.id"
					+ " ORDER BY sc.effective_from DESC, sc.id DESC LIMIT 1) AS basic_salary";

	private static final RowMapper<Employee> MAPPER = (rs, rowNum) -> new Employee(
			rs.getLong("id"),
			rs.getLong("company_id"),
			rs.getString("company_name"),
			rs.getString("emp_code"),
			rs.getString("employee_name"),
			rs.getString("phone"),
			rs.getString("country_code"),
			rs.getInt("is_active") == 1,
			rs.getString("hire_date"),
			rs.getString("created_at"),
			rs.getString("photo_url"),
			(Integer) rs.getObject("contract_duration_months"),
			rs.getString("branch_name"),
			rs.getString("department_name"),
			rs.getString("job_title_name"),
			rs.getString("shift_name"),
			rs.getBigDecimal("basic_salary"));

	/**
	 * @param filters the list filters, whose {@code companyId} is already the
	 *     scoped session's own company where one applies
	 * @param status {@code all}, {@code active} or {@code inactive}
	 */
	public DashboardPage<Employee> paginate(
			DashboardListFilters filters, String status, long branchId, long departmentId,
			long jobTitleId, String hireFrom, String hireTo) {

		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder("1=1");
		if (filters.companyId() > 0) {
			where.append(" AND e.company_id = ?");
			params.add(filters.companyId());
		}
		if (!filters.search().isEmpty()) {
			// Six columns, in legacy's order. The id is matched as text, so
			// searching "12" finds employee 12 and employee 512 alike.
			where.append(" AND (e.first_name LIKE ? OR e.last_name LIKE ?"
					+ " OR CONCAT(e.first_name, ' ', e.last_name) LIKE ?"
					+ " OR e.employee_code LIKE ? OR CAST(e.id AS CHAR) LIKE ?"
					+ " OR e.phone LIKE ?)");
			String like = "%" + filters.search() + "%";
			for (int i = 0; i < 6; i++) {
				params.add(like);
			}
		}
		// A pending join request is not an employee yet, and legacy hides one
		// from this list on every path.
		where.append(" AND COALESCE(e.join_request_status, 'accepted') = 'accepted'");
		if ("active".equals(status)) {
			where.append(" AND e.is_active = 1");
		}
		else if ("inactive".equals(status)) {
			where.append(" AND e.is_active = 0");
		}
		if (branchId > 0) {
			where.append(" AND e.branch_id = ?");
			params.add(branchId);
		}
		if (departmentId > 0) {
			where.append(" AND e.department_id = ?");
			params.add(departmentId);
		}
		if (jobTitleId > 0) {
			where.append(" AND e.job_title_id = ?");
			params.add(jobTitleId);
		}
		// Hire date falls back to the created date when it is not set, so a
		// date range never silently drops an employee who has no hire date.
		String hireExpr = "DATE(COALESCE(e.hire_date, e.created_at))";
		if (hireFrom != null && !hireFrom.isEmpty()) {
			where.append(" AND ").append(hireExpr).append(" >= ?");
			params.add(hireFrom);
		}
		if (hireTo != null && !hireTo.isEmpty()) {
			where.append(" AND ").append(hireExpr).append(" <= ?");
			params.add(hireTo);
		}

		// Legacy counts without the companies join the page itself uses, so an
		// employee whose company row is gone counts toward the total and never
		// appears in the list. Reproduced: correcting it would change a number
		// on a page whose rows are otherwise identical.
		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM employees e WHERE " + where,
				Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		List<Employee> rows = this.jdbcTemplate.query(
				"SELECT e.id, e.company_id, " + CODE_SQL + " AS emp_code,"
						+ " " + NAME_SQL + " AS employee_name,"
						+ " e.phone, e.country_code, e.is_active, e.hire_date, e.created_at,"
						+ " e.photo_url, e.contract_duration_months,"
						+ " b.name AS branch_name, s.name AS department_name,"
						+ " jt.name AS job_title_name, c.company_name,"
						+ " " + LATEST_SHIFT + ", " + LATEST_SALARY
						+ " FROM employees e"
						+ " INNER JOIN companies c ON c.id = e.company_id"
						+ " LEFT JOIN branches b ON b.id = e.branch_id"
						+ " LEFT JOIN departments s ON s.id = e.department_id"
						+ " LEFT JOIN job_titles jt ON jt.id = e.job_title_id"
						+ " WHERE " + where
						+ " ORDER BY e.created_at DESC, e.id DESC"
						+ " LIMIT ? OFFSET ?",
				MAPPER, pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	/** The company that owns an employee -- a column on the row itself. */
	public Long companyOf(long id) {
		if (id <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT company_id FROM employees WHERE id = ?", Long.class, id);
		return found.isEmpty() ? null : found.get(0);
	}

	public Employee find(long id) {
		List<Employee> rows = this.jdbcTemplate.query(
				"SELECT e.id, e.company_id, " + CODE_SQL + " AS emp_code,"
						+ " " + NAME_SQL + " AS employee_name,"
						+ " e.phone, e.country_code, e.is_active, e.hire_date, e.created_at,"
						+ " e.photo_url, e.contract_duration_months,"
						+ " b.name AS branch_name, s.name AS department_name,"
						+ " jt.name AS job_title_name, c.company_name,"
						+ " " + LATEST_SHIFT + ", " + LATEST_SALARY
						+ " FROM employees e"
						+ " INNER JOIN companies c ON c.id = e.company_id"
						+ " LEFT JOIN branches b ON b.id = e.branch_id"
						+ " LEFT JOIN departments s ON s.id = e.department_id"
						+ " LEFT JOIN job_titles jt ON jt.id = e.job_title_id"
						+ " WHERE e.id = ?",
				MAPPER, id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * {@code dashboard_employee_code_exists_in_company()}. The code is unique
	 * per company, not globally, and an edit excludes the row being saved.
	 */
	public boolean codeExistsInCompany(long companyId, String code, long excludeId) {
		String normalized = code == null ? "" : code.trim().replaceAll("\\s+", " ");
		if (normalized.isEmpty()) {
			return false;
		}
		String sql = "SELECT COUNT(*) FROM employees WHERE company_id = ? AND employee_code = ?";
		List<Object> params = new ArrayList<>(List.of(companyId, normalized));
		if (excludeId > 0) {
			sql += " AND id <> ?";
			params.add(excludeId);
		}
		Integer found = this.jdbcTemplate.queryForObject(sql, Integer.class, params.toArray());
		return found != null && found > 0;
	}

	/**
	 * <b>R-051</b>: one query per company rather than every company's rows
	 * shipped to the browser. A {@code companyId} of zero is the administrator
	 * with no filter, whose reach is every company.
	 */
	public List<Employee.Option> branchOptions(long companyId) {
		return options("branches", companyId);
	}

	public List<Employee.Option> departmentOptions(long companyId) {
		return options("departments", companyId);
	}

	public List<Employee.Option> jobTitleOptions(long companyId) {
		return options("job_titles", companyId);
	}

	public List<Employee.Option> shiftOptions(long companyId) {
		return options("shifts", companyId);
	}

	private List<Employee.Option> options(String table, long companyId) {
		if (companyId > 0) {
			return this.jdbcTemplate.query(
					"SELECT id, name FROM " + table + " WHERE company_id = ? AND is_active = 1"
							+ " ORDER BY name",
					(rs, rowNum) -> new Employee.Option(
							rs.getLong("id"), rs.getString("name"), null),
					companyId);
		}
		return this.jdbcTemplate.query(
				"SELECT t.id, t.name, c.company_name FROM " + table + " t"
						+ " INNER JOIN companies c ON c.id = t.company_id"
						+ " WHERE t.is_active = 1 ORDER BY c.company_name, t.name",
				(rs, rowNum) -> new Employee.Option(
						rs.getLong("id"), rs.getString("name"), rs.getString("company_name")));
	}

	/** Whether a branch, department, job title or shift is this company's and active. */
	public boolean belongsToCompany(String table, long id, long companyId) {
		if (id <= 0 || companyId <= 0) {
			return false;
		}
		Integer found = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM " + table + " WHERE id = ? AND company_id = ? AND"
						+ " is_active = 1",
				Integer.class, id, companyId);
		return found != null && found > 0;
	}

	public long insert(EmployeeWrite write, String passwordHash) {
		org.springframework.jdbc.support.KeyHolder keys =
				new org.springframework.jdbc.support.GeneratedKeyHolder();
		this.jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					"INSERT INTO employees (company_id, branch_id, department_id, job_title_id,"
							+ " employee_code, first_name, last_name, phone, country_code,"
							+ " password_hash, role, national_id, birth_date, gender, address,"
							+ " hire_date, contract_duration_months,"
							+ " is_mobile_attendance_enabled, is_active, join_request_status,"
							+ " token_version, created_at, updated_at)"
							+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'employee', ?, ?, ?, ?, ?,"
							+ " ?, ?, 1, 'accepted', 1, NOW(), NOW())",
					java.sql.Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, write.companyId());
			statement.setLong(2, write.branchId());
			setNullableLong(statement, 3, write.departmentId());
			setNullableLong(statement, 4, write.jobTitleId());
			statement.setString(5, write.employeeCode());
			statement.setString(6, write.firstName());
			statement.setString(7, write.lastName());
			statement.setString(8, write.phone());
			statement.setString(9, write.countryCode());
			statement.setString(10, passwordHash);
			statement.setString(11, write.nationalId());
			statement.setString(12, write.birthDate());
			statement.setString(13, write.gender());
			statement.setString(14, write.address());
			statement.setString(15, write.hireDate());
			setNullableInt(statement, 16, write.contractDurationMonths());
			statement.setInt(17, write.mobileAttendance() ? 1 : 0);
			return statement;
		}, keys);
		Number key = keys.getKey();
		return key == null ? 0L : key.longValue();
	}

	/**
	 * <b>D-176</b>: {@code company_id} is absent here, as it is in legacy's
	 * own update. What legacy does not do is check that the three foreign keys
	 * belong to the row's company -- {@link EmployeeAdminService} is what holds
	 * them to it.
	 */
	public int update(long id, EmployeeWrite write, String passwordHash) {
		StringBuilder sql = new StringBuilder(
				"UPDATE employees SET first_name = ?, last_name = ?, employee_code = ?,"
						+ " phone = ?, country_code = ?, national_id = ?, gender = ?,"
						+ " birth_date = ?, hire_date = ?, address = ?, branch_id = ?,"
						+ " department_id = ?, job_title_id = ?, contract_duration_months = ?,"
						+ " is_mobile_attendance_enabled = ?");
		List<Object> params = new ArrayList<>();
		params.add(write.firstName());
		params.add(write.lastName());
		params.add(write.employeeCode());
		params.add(write.phone());
		params.add(write.countryCode());
		params.add(write.nationalId());
		params.add(write.gender());
		params.add(write.birthDate());
		params.add(write.hireDate());
		params.add(write.address());
		params.add(write.branchId());
		params.add(write.departmentId());
		params.add(write.jobTitleId());
		params.add(write.contractDurationMonths());
		params.add(write.mobileAttendance() ? 1 : 0);
		// Legacy adds password_hash to the payload only when one was typed, so
		// an edit that leaves the field blank keeps the existing credential.
		if (passwordHash != null) {
			sql.append(", password_hash = ?");
			params.add(passwordHash);
		}
		sql.append(" WHERE id = ?");
		params.add(id);
		return this.jdbcTemplate.update(sql.toString(), params.toArray());
	}

	public int setActive(long id, boolean active) {
		return this.jdbcTemplate.update(
				"UPDATE employees SET is_active = ? WHERE id = ?", active ? 1 : 0, id);
	}

	public int delete(long id) {
		return this.jdbcTemplate.update("DELETE FROM employees WHERE id = ?", id);
	}

	/** Only written when a basic salary was given, and only on the create path. */
	public void insertSalaryContract(long employeeId, EmployeeSalary salary, String effectiveFrom) {
		this.jdbcTemplate.update(
				"INSERT INTO salary_contracts (employee_id, basic_salary, housing_allowance,"
						+ " transport_allowance, food_allowance, risk_allowance, incentives,"
						+ " insurance_deduction, tax_deduction, advances_deduction,"
						+ " fund_deduction, penalty_deduction, effective_from)"
						+ " VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				employeeId, salary.basic(), salary.transport(), salary.food(), salary.risk(),
				salary.incentives(), salary.insurance(), salary.tax(), salary.advances(),
				salary.fund(), salary.penalty(), effectiveFrom);
	}

	/**
	 * The 21-day balance legacy opens for every new employee, inside the same
	 * transaction as the insert.
	 */
	public void insertOpeningLeaveBalance(long employeeId, int year, int totalDays) {
		this.jdbcTemplate.update(
				"INSERT INTO leave_balance (employee_id, year, total_days, used_days)"
						+ " VALUES (?, ?, ?, 0)",
				employeeId, year, totalDays);
	}

	/**
	 * {@code employee_sync_shift_assignment()}: a no-op when the employee
	 * already has that shift from that date, so re-saving a form does not pile
	 * up identical rows.
	 */
	public void syncShiftAssignment(long employeeId, long shiftId, String effectiveFrom) {
		if (shiftId <= 0) {
			return;
		}
		List<java.util.Map<String, Object>> current = this.jdbcTemplate.queryForList(
				"SELECT shift_id, effective_from FROM employee_shift_assignments"
						+ " WHERE employee_id = ? ORDER BY effective_from DESC, id DESC LIMIT 1",
				employeeId);
		if (!current.isEmpty()) {
			java.util.Map<String, Object> row = current.get(0);
			boolean sameShift = ((Number) row.get("shift_id")).longValue() == shiftId;
			boolean sameDate = String.valueOf(row.get("effective_from")).equals(effectiveFrom);
			if (sameShift && sameDate) {
				return;
			}
		}
		this.jdbcTemplate.update(
				"INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from)"
						+ " VALUES (?, ?, ?)",
				employeeId, shiftId, effectiveFrom);
	}

	private static void setNullableLong(java.sql.PreparedStatement statement, int index, Long value)
			throws java.sql.SQLException {
		if (value == null) {
			statement.setNull(index, java.sql.Types.INTEGER);
		}
		else {
			statement.setLong(index, value);
		}
	}

	private static void setNullableInt(
			java.sql.PreparedStatement statement, int index, Integer value)
			throws java.sql.SQLException {
		if (value == null) {
			statement.setNull(index, java.sql.Types.INTEGER);
		}
		else {
			statement.setInt(index, value);
		}
	}

	/** The fields both write paths share. */
	public record EmployeeWrite(
			long companyId, Long branchId, Long departmentId, Long jobTitleId, String employeeCode,
			String firstName, String lastName, String phone, String countryCode, String nationalId,
			String birthDate, String gender, String address, String hireDate,
			Integer contractDurationMonths, boolean mobileAttendance) {
	}

	/** The salary contract's figures, create path only. */
	public record EmployeeSalary(
			BigDecimal basic, BigDecimal transport, BigDecimal food, BigDecimal risk,
			BigDecimal incentives, BigDecimal insurance, BigDecimal tax, BigDecimal advances,
			BigDecimal fund, BigDecimal penalty) {
	}

}
