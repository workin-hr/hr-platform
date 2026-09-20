package com.workin.backend.platformadmin.hr;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.backend.platformadmin.web.DashboardListFilters;
import com.workin.backend.platformadmin.web.DashboardPage;

/** {@code hr_paginate_advances()} and the writes {@code advances.php} makes. */
@Repository
public class AdvanceStore {

	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,'')))";

	private static final String EMP_CODE =
			"COALESCE(NULLIF(TRIM(e.employee_code), ''), CAST(e.id AS CHAR))";

	private final JdbcTemplate jdbcTemplate;

	public AdvanceStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static RowMapper<Advance> mapper(boolean withCompany) {
		return (rs, rowNum) -> new Advance(
				rs.getLong("id"),
				rs.getLong("employee_id"),
				rs.getLong("company_id"),
				withCompany ? rs.getString("company_name") : null,
				rs.getString("emp_code"),
				rs.getString("employee_name"),
				rs.getBigDecimal("amount"),
				rs.getBigDecimal("remaining"),
				rs.getString("reason"),
				rs.getString("rejection_reason"),
				rs.getString("status"),
				rs.getString("request_date"),
				rs.getString("created_at"));
	}

	public DashboardPage<Advance> paginate(
			DashboardListFilters filters, String status, String dateFrom, String dateTo,
			boolean showCompany) {
		List<Object> params = new ArrayList<>();
		String where = where(filters, status, dateFrom, dateTo, params);

		String join = showCompany ? " JOIN companies c ON c.id = e.company_id" : "";
		String companyCol = showCompany ? ", c.company_name" : "";

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM advances a JOIN employees e ON e.id = a.employee_id"
						+ join + " WHERE " + where,
				Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		// advances has no company_id of its own: the row belongs to whichever
		// company its employee is in.
		List<Advance> rows = this.jdbcTemplate.query(
				"SELECT a.*, e.company_id" + companyCol + ", "
						+ DISPLAY_NAME + " AS employee_name, " + EMP_CODE + " AS emp_code"
						+ " FROM advances a JOIN employees e ON e.id = a.employee_id" + join
						+ " WHERE " + where
						+ " ORDER BY a.request_date DESC, a.id DESC"
						+ " LIMIT ? OFFSET ?",
				mapper(showCompany), pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	/**
	 * The one {@code WHERE} of this page ({@code hr_paginate_advances()} /
	 * {@code hr_export_advances_csv()}, both {@code hr_list_helper.php:331-374, 1110-1152}),
	 * appending its bound values to {@code params}.
	 *
	 * <p>Shared by {@link #paginate} and {@link #exportRows} so the export
	 * cannot narrow differently from the table above it (D-269's shape for
	 * {@code LeaveBalanceStore}).
	 */
	private static String where(
			DashboardListFilters filters, String status, String dateFrom, String dateTo,
			List<Object> params) {
		StringBuilder where = new StringBuilder("1=1");
		if (filters.companyId() > 0) {
			where.append(" AND e.company_id = ?");
			params.add(filters.companyId());
		}
		if (!"all".equals(status)) {
			where.append(" AND a.status = ?");
			params.add(status);
		}
		if (dateFrom != null && !dateFrom.isBlank()) {
			where.append(" AND a.request_date >= ?");
			params.add(dateFrom.trim());
		}
		if (dateTo != null && !dateTo.isBlank()) {
			where.append(" AND a.request_date <= ?");
			params.add(dateTo.trim());
		}
		if (!filters.search().isEmpty()) {
			where.append(" AND (").append(DISPLAY_NAME).append(" LIKE ? OR ")
					.append(EMP_CODE).append(" LIKE ?)");
			params.add("%" + filters.search() + "%");
			params.add("%" + filters.search() + "%");
		}
		return where.toString();
	}

	/**
	 * {@code hr_export_advances_csv()} ({@code hr_list_helper.php:1110-1152}): every row the
	 * list would show for this filter, as eight strings a spreadsheet cell can hold, unpaginated
	 * and in legacy's own export order -- {@code a.created_at DESC, a.id DESC} -- rather than the
	 * {@code request_date}-first order the table above it uses.
	 *
	 * <p>The values are read as the database renders them, which is what PDO hands legacy:
	 * {@code decimal(10,2)} as {@code "1000.00"}.
	 */
	public List<List<String>> exportRows(
			DashboardListFilters filters, String status, String dateFrom, String dateTo) {
		List<Object> params = new ArrayList<>();
		String where = where(filters, status, dateFrom, dateTo, params);

		return this.jdbcTemplate.query(
				"SELECT " + EMP_CODE + " AS emp_code, " + DISPLAY_NAME + " AS employee_name,"
						+ " a.amount, a.remaining, a.request_date, a.reason, a.rejection_reason,"
						+ " a.status"
						+ " FROM advances a JOIN employees e ON e.id = a.employee_id"
						+ " WHERE " + where
						+ " ORDER BY a.created_at DESC, a.id DESC",
				(rs, rowNum) -> List.of(
						blankIfNull(rs.getString("emp_code")),
						blankIfNull(rs.getString("employee_name")),
						blankIfNull(rs.getString("amount")),
						blankIfNull(rs.getString("remaining")),
						blankIfNull(rs.getString("request_date")),
						blankIfNull(rs.getString("reason")),
						blankIfNull(rs.getString("rejection_reason")),
						blankIfNull(rs.getString("status"))),
				params.toArray());
	}

	private static String blankIfNull(String value) {
		return value == null ? "" : value;
	}

	/** R-046's lookup: the row's company, through its employee. */
	public Long companyOf(long id) {
		if (id <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT e.company_id FROM advances a JOIN employees e ON e.id = a.employee_id"
						+ " WHERE a.id = ?", Long.class, id);
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

	/** The status, amount and balance an edit has to reason from. */
	public Advance byId(long id) {
		List<Advance> rows = this.jdbcTemplate.query(
				"SELECT a.*, e.company_id, " + DISPLAY_NAME + " AS employee_name, "
						+ EMP_CODE + " AS emp_code FROM advances a"
						+ " JOIN employees e ON e.id = a.employee_id WHERE a.id = ?",
				mapper(false), id);
		return rows.isEmpty() ? null : rows.get(0);
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
						+ " WHERE e.is_active = 1 ORDER BY c.company_name, employee_name",
				(rs, rowNum) -> new LeaveBalance.EmployeeOption(
						rs.getLong("id"), rs.getString("emp_code"),
						rs.getString("employee_name"), rs.getString("company_name")));
	}

	public long insert(
			long employeeId, java.math.BigDecimal amount, String reason, String requestDate) {
		org.springframework.jdbc.support.KeyHolder keys =
				new org.springframework.jdbc.support.GeneratedKeyHolder();
		this.jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					// status is 'approved', not 'pending'. An advance created here is
					// one HR is recording, not one an employee is asking for --
					// 'pending' belongs to the mobile app's request flow, and the
					// approve/reject buttons on this page are for those.
					"INSERT INTO advances (employee_id, amount, remaining, reason, status,"
							+ " request_date, created_at) VALUES (?, ?, ?, ?, 'approved', ?, NOW())",
					java.sql.Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, employeeId);
			statement.setBigDecimal(2, amount);
			// A new advance is owed in full.
			statement.setBigDecimal(3, amount);
			statement.setString(4, reason);
			statement.setString(5, requestDate);
			return statement;
		}, keys);
		Number key = keys.getKey();
		return key == null ? 0L : key.longValue();
	}

	public int update(
			long id, long employeeId, java.math.BigDecimal amount, java.math.BigDecimal remaining,
			String reason, String requestDate) {
		return this.jdbcTemplate.update(
				"UPDATE advances SET employee_id = ?, amount = ?, remaining = ?, reason = ?,"
						+ " request_date = ? WHERE id = ?",
				employeeId, amount, remaining, reason, requestDate, id);
	}

	public int approve(long id) {
		return this.jdbcTemplate.update("UPDATE advances SET status = 'approved' WHERE id = ?", id);
	}

	public int reject(long id, String rejectionReason) {
		return this.jdbcTemplate.update(
				"UPDATE advances SET status = 'rejected', rejection_reason = ? WHERE id = ?",
				rejectionReason, id);
	}

	/** {@code mark_paid}: approved with nothing left outstanding. */
	public int markPaid(long id) {
		return this.jdbcTemplate.update(
				"UPDATE advances SET status = 'approved', remaining = 0 WHERE id = ?", id);
	}

	public int delete(long id) {
		return this.jdbcTemplate.update("DELETE FROM advances WHERE id = ?", id);
	}

}
