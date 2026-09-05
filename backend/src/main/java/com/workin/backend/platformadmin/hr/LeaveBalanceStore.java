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
 * {@code hr_paginate_leave_balances()} and the writes
 * {@code leave_balances.php} makes.
 *
 * <h2>Why this is not {@code LegacyLeaveBalanceStore}</h2>
 * <p>The API already has a store for this table, and it is deliberately not
 * reused. Its {@code WHERE} opens with an unconditional {@code e.company_id=?}
 * -- that is a tenant guarantee the bearer API depends on, and it is not
 * something to make optional. The dashboard's administrator view is the
 * opposite shape: no company at all, every company's rows in one table. Two
 * queries with two different guarantees, kept apart so neither can be relaxed
 * to suit the other.
 *
 * <p>The ordering differs too: the API sorts by year then employee code for a
 * client's list, the dashboard by {@code lb.id DESC} so the newest row an
 * operator added is at the top.
 */
@Repository
@Profile("phase1-mysql")
public class LeaveBalanceStore {

	/** {@code dashboard_employee_display_name_sql('e')} for the shipped schema. */
	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,'')))";

	/**
	 * {@code dashboard_employee_code_sql('e')} for the shipped schema.
	 *
	 * <p>Legacy picks this expression at runtime from the columns the database
	 * actually has -- a split name or a single {@code name}, an
	 * {@code employee_code} or none. That branching existed to let one codebase
	 * run against several vintages of the schema. This port targets the one
	 * vendored schema, so the branch is resolved here rather than reproduced;
	 * `check_legacy_schema_drift.py` is what would catch the assumption
	 * breaking.
	 */
	private static final String EMP_CODE =
			"COALESCE(NULLIF(TRIM(e.employee_code), ''), CAST(e.id AS CHAR))";

	/** {@code COALESCE(e.join_request_status,'accepted') = 'accepted'}. */
	private static final String ROSTER =
			"COALESCE(e.join_request_status, 'accepted') = 'accepted'";

	private final JdbcTemplate jdbcTemplate;

	public LeaveBalanceStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static RowMapper<LeaveBalance> mapper(boolean withCompany) {
		return (rs, rowNum) -> new LeaveBalance(
				rs.getLong("id"),
				rs.getLong("employee_id"),
				rs.getLong("company_id"),
				withCompany ? rs.getString("company_name") : null,
				rs.getString("emp_code"),
				rs.getString("employee_name"),
				rs.getInt("year"),
				rs.getBigDecimal("total_days"),
				rs.getBigDecimal("used_days"),
				rs.getBigDecimal("remaining_days"));
	}

	/**
	 * @param year the balance year, which unlike every filter on the org pages
	 *             is <b>not optional</b>: the page always narrows to one year,
	 *             defaulting to the current one
	 */
	public DashboardPage<LeaveBalance> paginate(
			DashboardListFilters filters, int year, boolean showCompany) {
		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder("lb.year = ?");
		params.add(year);
		if (filters.companyId() > 0) {
			where.append(" AND e.company_id = ?");
			params.add(filters.companyId());
		}
		// Employees who have not been accepted onto the roster are not staff
		// yet and their balances are not shown.
		where.append(" AND ").append(ROSTER);
		if (!filters.search().isEmpty()) {
			where.append(" AND (").append(DISPLAY_NAME).append(" LIKE ? OR ")
					.append(EMP_CODE).append(" LIKE ?)");
			params.add("%" + filters.search() + "%");
			params.add("%" + filters.search() + "%");
		}

		String join = showCompany ? " JOIN companies c ON c.id = e.company_id" : "";
		String companyCol = showCompany ? ", c.company_name" : "";

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM leave_balance lb JOIN employees e ON e.id = lb.employee_id"
						+ join + " WHERE " + where,
				Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		List<LeaveBalance> rows = this.jdbcTemplate.query(
				"SELECT lb.*, e.company_id" + companyCol + ", "
						+ DISPLAY_NAME + " AS employee_name, " + EMP_CODE + " AS emp_code,"
						+ " (lb.total_days - lb.used_days) AS remaining_days"
						+ " FROM leave_balance lb JOIN employees e ON e.id = lb.employee_id" + join
						+ " WHERE " + where
						+ " ORDER BY lb.id DESC"
						+ " LIMIT ? OFFSET ?",
				mapper(showCompany), pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	/**
	 * The company a balance row belongs to, through its employee, or
	 * {@code null} when the row is missing.
	 *
	 * <p>This is the lookup <b>R-046</b> turns on: the table carries no
	 * {@code company_id}, so nothing about the row itself says who owns it.
	 */
	public Long companyOf(long id) {
		if (id <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT e.company_id FROM leave_balance lb JOIN employees e ON e.id = lb.employee_id"
						+ " WHERE lb.id = ?", Long.class, id);
		return found.isEmpty() ? null : found.get(0);
	}

	/** The company an employee belongs to, for the add path's check. */
	public Long employeeCompany(long employeeId) {
		if (employeeId <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT company_id FROM employees WHERE id = ?", Long.class, employeeId);
		return found.isEmpty() ? null : found.get(0);
	}

	/** {@code hr_employees_picker_list()}: who a balance can be added for. */
	public List<LeaveBalance.EmployeeOption> employeeOptions(long companyId) {
		if (companyId > 0) {
			return this.jdbcTemplate.query(
					"SELECT e.id, " + EMP_CODE + " AS emp_code, " + DISPLAY_NAME + " AS employee_name"
							+ " FROM employees e WHERE e.company_id = ? AND e.is_active = 1 AND " + ROSTER
							+ " ORDER BY employee_name",
					(rs, rowNum) -> new LeaveBalance.EmployeeOption(
							rs.getLong("id"), rs.getString("emp_code"),
							rs.getString("employee_name"), null),
					companyId);
		}
		// Unfiltered, this is every active employee on the platform. It is the
		// administrator's view only, and it is capped: the picker is a dropdown
		// and 3,783 options is not one.
		return this.jdbcTemplate.query(
				"SELECT e.id, " + EMP_CODE + " AS emp_code, " + DISPLAY_NAME + " AS employee_name,"
						+ " c.company_name FROM employees e"
						+ " JOIN companies c ON c.id = e.company_id"
						+ " WHERE e.is_active = 1 AND " + ROSTER
						+ " ORDER BY c.company_name, employee_name LIMIT 500",
				(rs, rowNum) -> new LeaveBalance.EmployeeOption(
						rs.getLong("id"), rs.getString("emp_code"),
						rs.getString("employee_name"), rs.getString("company_name")));
	}

	public long insert(long employeeId, int year, BigDecimal totalDays) {
		org.springframework.jdbc.support.KeyHolder keys =
				new org.springframework.jdbc.support.GeneratedKeyHolder();
		this.jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					"INSERT INTO leave_balance (employee_id, year, total_days, used_days)"
							+ " VALUES (?, ?, ?, 0)",
					java.sql.Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, employeeId);
			statement.setInt(2, year);
			statement.setBigDecimal(3, totalDays);
			return statement;
		}, keys);
		Number key = keys.getKey();
		return key == null ? 0L : key.longValue();
	}

	public int update(long id, BigDecimal totalDays, BigDecimal usedDays) {
		return this.jdbcTemplate.update(
				"UPDATE leave_balance SET total_days = ?, used_days = ? WHERE id = ?",
				totalDays, usedDays, id);
	}

	/**
	 * A <b>hard</b> delete, which is what {@code dbDelete()} does and what this
	 * page has always done -- unlike the org pages, whose delete deactivates.
	 * Nothing references a balance row, so there is nothing to orphan; it is
	 * simply irreversible, which is half of why R-046 mattered here.
	 */
	public int delete(long id) {
		return this.jdbcTemplate.update("DELETE FROM leave_balance WHERE id = ?", id);
	}

}
