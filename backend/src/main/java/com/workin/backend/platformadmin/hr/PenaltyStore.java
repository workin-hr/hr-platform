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

/** {@code hr_paginate_penalties()} and the writes {@code penalties.php} makes. */
@Repository
@Profile("phase1-mysql")
public class PenaltyStore {

	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,'')))";

	private static final String EMP_CODE =
			"COALESCE(NULLIF(TRIM(e.employee_code), ''), CAST(e.id AS CHAR))";

	private final JdbcTemplate jdbcTemplate;

	public PenaltyStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static RowMapper<Penalty> mapper(boolean withCompany) {
		return (rs, rowNum) -> new Penalty(
				rs.getLong("id"),
				rs.getLong("employee_id"),
				rs.getLong("company_id"),
				withCompany ? rs.getString("company_name") : null,
				rs.getString("emp_code"),
				rs.getString("employee_name"),
				rs.getString("penalty_type"),
				rs.getBigDecimal("penalty_days"),
				rs.getString("reason"),
				rs.getString("penalty_date"),
				rs.getInt("applied_to_payroll") == 1,
				rs.getString("created_at"));
	}

	/**
	 * @param applied {@code "0"} for not yet applied, {@code "1"} for applied,
	 *                anything else for both -- legacy tests the two strings and
	 *                lets everything else through
	 */
	public DashboardPage<Penalty> paginate(
			DashboardListFilters filters, String applied, String dateFrom, String dateTo,
			boolean showCompany) {
		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder("1=1");
		if (filters.companyId() > 0) {
			where.append(" AND e.company_id = ?");
			params.add(filters.companyId());
		}
		if ("0".equals(applied)) {
			where.append(" AND pen.applied_to_payroll = 0");
		} else if ("1".equals(applied)) {
			where.append(" AND pen.applied_to_payroll = 1");
		}
		if (dateFrom != null && !dateFrom.isBlank()) {
			where.append(" AND pen.penalty_date >= ?");
			params.add(dateFrom.trim());
		}
		if (dateTo != null && !dateTo.isBlank()) {
			where.append(" AND pen.penalty_date <= ?");
			params.add(dateTo.trim());
		}
		if (!filters.search().isEmpty()) {
			where.append(" AND (").append(DISPLAY_NAME).append(" LIKE ? OR ")
					.append(EMP_CODE).append(" LIKE ?)");
			params.add("%" + filters.search() + "%");
			params.add("%" + filters.search() + "%");
		}

		String join = showCompany ? " JOIN companies c ON c.id = e.company_id" : "";
		String companyCol = showCompany ? ", c.company_name" : "";

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM penalties pen JOIN employees e ON e.id = pen.employee_id"
						+ join + " WHERE " + where,
				Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		List<Penalty> rows = this.jdbcTemplate.query(
				"SELECT pen.*, e.company_id" + companyCol + ", "
						+ DISPLAY_NAME + " AS employee_name, " + EMP_CODE + " AS emp_code"
						+ " FROM penalties pen JOIN employees e ON e.id = pen.employee_id" + join
						+ " WHERE " + where
						+ " ORDER BY pen.penalty_date DESC, pen.id DESC"
						+ " LIMIT ? OFFSET ?",
				mapper(showCompany), pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	/** The company a penalty belongs to, through its employee. R-046's lookup. */
	public Long companyOf(long id) {
		if (id <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT e.company_id FROM penalties pen JOIN employees e ON e.id = pen.employee_id"
						+ " WHERE pen.id = ?", Long.class, id);
		return found.isEmpty() ? null : found.get(0);
	}

	public Long employeeCompany(long employeeId) {
		if (employeeId <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT company_id FROM employees WHERE id = ?", Long.class, employeeId);
		return found.isEmpty() ? null : found.get(0);
	}

	/** Whether payroll has already deducted against this row. */
	public Boolean appliedToPayroll(long id) {
		List<Integer> found = this.jdbcTemplate.queryForList(
				"SELECT applied_to_payroll FROM penalties WHERE id = ?", Integer.class, id);
		return found.isEmpty() ? null : found.get(0) == 1;
	}

	public List<LeaveBalance.EmployeeOption> employeeOptions(long companyId) {
		if (companyId > 0) {
			return this.jdbcTemplate.query(
					"SELECT e.id, " + EMP_CODE + " AS emp_code, " + DISPLAY_NAME + " AS employee_name"
							+ " FROM employees e WHERE e.company_id = ? AND e.is_active = 1"
							+ " ORDER BY employee_name",
					(rs, rowNum) -> new LeaveBalance.EmployeeOption(
							rs.getLong("id"), rs.getString("emp_code"),
							rs.getString("employee_name"), null),
					companyId);
		}
		return this.jdbcTemplate.query(
				"SELECT e.id, " + EMP_CODE + " AS emp_code, " + DISPLAY_NAME + " AS employee_name,"
						+ " c.company_name FROM employees e JOIN companies c ON c.id = e.company_id"
						+ " WHERE e.is_active = 1 ORDER BY c.company_name, employee_name LIMIT 500",
				(rs, rowNum) -> new LeaveBalance.EmployeeOption(
						rs.getLong("id"), rs.getString("emp_code"),
						rs.getString("employee_name"), rs.getString("company_name")));
	}

	public long insert(
			long employeeId, String penaltyType, BigDecimal days, String reason, String date) {
		org.springframework.jdbc.support.KeyHolder keys =
				new org.springframework.jdbc.support.GeneratedKeyHolder();
		this.jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					"INSERT INTO penalties (employee_id, penalty_type, penalty_days, reason,"
							+ " penalty_date, applied_to_payroll, created_at)"
							+ " VALUES (?, ?, ?, ?, ?, 0, NOW())",
					java.sql.Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, employeeId);
			statement.setString(2, penaltyType);
			statement.setBigDecimal(3, days);
			statement.setString(4, reason);
			statement.setString(5, date);
			return statement;
		}, keys);
		Number key = keys.getKey();
		return key == null ? 0L : key.longValue();
	}

	public int update(
			long id, long employeeId, String penaltyType, BigDecimal days, String reason,
			String date) {
		return this.jdbcTemplate.update(
				"UPDATE penalties SET employee_id = ?, penalty_type = ?, penalty_days = ?,"
						+ " reason = ?, penalty_date = ? WHERE id = ?",
				employeeId, penaltyType, days, reason, date, id);
	}

	public int markApplied(long id) {
		return this.jdbcTemplate.update(
				"UPDATE penalties SET applied_to_payroll = 1 WHERE id = ?", id);
	}

	/** A hard delete, as {@code dbDelete()} does. */
	public int delete(long id) {
		return this.jdbcTemplate.update("DELETE FROM penalties WHERE id = ?", id);
	}

}
