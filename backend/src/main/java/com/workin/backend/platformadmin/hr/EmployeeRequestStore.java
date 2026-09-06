package com.workin.backend.platformadmin.hr;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.backend.platformadmin.web.DashboardListFilters;
import com.workin.backend.platformadmin.web.DashboardPage;

/**
 * {@code hr_paginate_requests()} and the writes {@code requests.php} makes,
 * plus the reads {@code dashboard_request_approve()} needs.
 */
@Repository
@Profile("phase1-mysql")
public class EmployeeRequestStore {

	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,'')))";

	private static final String EMP_CODE =
			"COALESCE(NULLIF(TRIM(e.employee_code), ''), CAST(e.id AS CHAR))";

	private final JdbcTemplate jdbcTemplate;

	public EmployeeRequestStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static RowMapper<EmployeeRequest> mapper(boolean withCompany) {
		return (rs, rowNum) -> {
			long exceptionType = rs.getLong("exception_type_id");
			return new EmployeeRequest(
					rs.getLong("id"),
					rs.getLong("employee_id"),
					rs.getLong("company_id"),
					withCompany ? rs.getString("company_name") : null,
					rs.getString("emp_code"),
					rs.getString("employee_name"),
					rs.getString("request_type_name"),
					rs.getInt("deduct_balance") == 1,
					rs.getInt("add_attendance_exception") == 1,
					rs.wasNull() || exceptionType <= 0 ? null : exceptionType,
					rs.getString("status"),
					rs.getString("from_date"),
					rs.getString("to_date"),
					rs.getString("notes"),
					rs.getString("reply"),
					rs.getString("decided_at"),
					rs.getString("created_at"));
		};
	}

	/**
	 * @param status {@code all} shows every state; anything else is matched
	 *               literally, and the page defaults to {@code pending} rather
	 *               than to {@code all}
	 */
	public DashboardPage<EmployeeRequest> paginate(
			DashboardListFilters filters, String status, long typeId, String dateFrom,
			String dateTo, boolean showCompany) {
		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder("1=1");
		if (!"all".equals(status)) {
			where.append(" AND r.status = ?");
			params.add(status);
		}
		if (filters.companyId() > 0) {
			where.append(" AND e.company_id = ?");
			params.add(filters.companyId());
		}
		if (typeId > 0) {
			where.append(" AND r.request_type_id = ?");
			params.add(typeId);
		}
		if (!filters.search().isEmpty()) {
			where.append(" AND (").append(DISPLAY_NAME).append(" LIKE ? OR ")
					.append(EMP_CODE).append(" LIKE ?)");
			params.add("%" + filters.search() + "%");
			params.add("%" + filters.search() + "%");
		}
		// Both bounds compare against from_date, so a request that starts
		// inside the window and ends outside it is in; one that starts before
		// and runs into the window is not. Legacy's choice, kept.
		if (dateFrom != null && !dateFrom.isBlank()) {
			where.append(" AND r.from_date >= ?");
			params.add(dateFrom.trim());
		}
		if (dateTo != null && !dateTo.isBlank()) {
			where.append(" AND r.from_date <= ?");
			params.add(dateTo.trim());
		}

		String companyJoin = showCompany ? " JOIN companies c ON c.id = e.company_id" : "";
		String companyCol = showCompany ? ", c.company_name" : "";

		// The count query joins employees but NOT request_types, while the list
		// joins both. A request whose type row is gone is therefore counted and
		// not listed. Reproduced rather than corrected: the total is what the
		// pager and the heading show, and changing it would change both.
		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM requests r JOIN employees e ON e.id = r.employee_id"
						+ companyJoin + " WHERE " + where,
				Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		List<EmployeeRequest> rows = this.jdbcTemplate.query(
				"SELECT r.*, e.company_id" + companyCol + ", "
						+ DISPLAY_NAME + " AS employee_name, " + EMP_CODE + " AS emp_code,"
						+ " t.name AS request_type_name, t.deduct_balance,"
						+ " t.add_attendance_exception, t.exception_type_id"
						+ " FROM requests r"
						+ " JOIN employees e ON e.id = r.employee_id"
						+ " JOIN request_types t ON t.id = r.request_type_id" + companyJoin
						+ " WHERE " + where
						+ " ORDER BY r.created_at DESC, r.id DESC"
						+ " LIMIT ? OFFSET ?",
				mapper(showCompany), pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	/**
	 * {@code dashboard_request_fetch_for_approval()}: the request with its
	 * type's flags, scoped to a company.
	 *
	 * <p>The company is in the {@code WHERE}, not checked afterwards -- so a
	 * request belonging to another company is simply not found, which is the
	 * shape the caller already handles.
	 */
	public EmployeeRequest forApproval(long id, long companyId) {
		List<EmployeeRequest> rows = this.jdbcTemplate.query(
				"SELECT r.*, e.company_id, " + DISPLAY_NAME + " AS employee_name, "
						+ EMP_CODE + " AS emp_code, t.name AS request_type_name, t.deduct_balance,"
						+ " t.add_attendance_exception, t.exception_type_id"
						+ " FROM requests r"
						+ " JOIN request_types t ON t.id = r.request_type_id"
						+ " JOIN employees e ON e.id = r.employee_id"
						+ " WHERE r.id = ? AND e.company_id = ? LIMIT 1",
				mapper(false), id, companyId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** The company a request belongs to, through its employee. R-046's lookup. */
	public Long companyOf(long id) {
		if (id <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT e.company_id FROM requests r JOIN employees e ON e.id = r.employee_id"
						+ " WHERE r.id = ?", Long.class, id);
		return found.isEmpty() ? null : found.get(0);
	}

	public List<EmployeeRequest.TypeOption> typeOptions(long companyId) {
		if (companyId > 0) {
			return this.jdbcTemplate.query(
					"SELECT id, name FROM request_types WHERE company_id = ? AND is_active = 1"
							+ " ORDER BY name",
					(rs, rowNum) -> new EmployeeRequest.TypeOption(
							rs.getLong("id"), rs.getString("name")),
					companyId);
		}
		return this.jdbcTemplate.query(
				"SELECT DISTINCT name, MIN(id) AS id FROM request_types WHERE is_active = 1"
						+ " GROUP BY name ORDER BY name",
				(rs, rowNum) -> new EmployeeRequest.TypeOption(
						rs.getLong("id"), rs.getString("name")));
	}

	public int decide(long id, String status, String reply, String decidedAt) {
		return this.jdbcTemplate.update(
				"UPDATE requests SET status = ?, reply = ?, decided_at = ? WHERE id = ?",
				status, reply, decidedAt, id);
	}

	public int delete(long id) {
		return this.jdbcTemplate.update("DELETE FROM requests WHERE id = ?", id);
	}

	// ---- the approval's side effects ----

	/** {@code dashboard_request_available_leave_days()}, via its two callers. */
	public boolean leaveBalanceExists(long employeeId, int year) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM leave_balance WHERE employee_id = ? AND year = ?",
				Integer.class, employeeId, year);
		return count != null && count > 0;
	}

	public double availableLeaveDays(long employeeId, int year) {
		List<Double> found = this.jdbcTemplate.queryForList(
				"SELECT GREATEST(0, total_days - used_days) FROM leave_balance"
						+ " WHERE employee_id = ? AND year = ?",
				Double.class, employeeId, year);
		return found.isEmpty() || found.get(0) == null ? 0d : found.get(0);
	}

	/**
	 * {@code dashboard_request_apply_leave_deduction()}: add to the existing
	 * year's used days, or create the year with a 15-day total already spent
	 * down by this request.
	 */
	public void applyLeaveDeduction(long employeeId, int days, int year) {
		if (leaveBalanceExists(employeeId, year)) {
			this.jdbcTemplate.update(
					"UPDATE leave_balance SET used_days = used_days + ? WHERE employee_id = ? AND year = ?",
					days, employeeId, year);
			return;
		}
		this.jdbcTemplate.update(
				"INSERT INTO leave_balance (employee_id, year, total_days, used_days)"
						+ " VALUES (?, ?, 15, ?)",
				employeeId, year, days);
	}

	/**
	 * {@code dashboard_request_resolve_exception_type_id()}: the requested type
	 * when it is this company's and active, else the company's lowest active
	 * type, else 0.
	 */
	public long resolveExceptionType(long companyId, Long requested) {
		if (requested != null && requested > 0) {
			Integer count = this.jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM exception_types WHERE id = ? AND company_id = ?"
							+ " AND is_active = 1",
					Integer.class, requested, companyId);
			if (count != null && count > 0) {
				return requested;
			}
		}
		List<Long> fallback = this.jdbcTemplate.queryForList(
				"SELECT id FROM exception_types WHERE company_id = ? AND is_active = 1"
						+ " ORDER BY id ASC LIMIT 1",
				Long.class, companyId);
		return fallback.isEmpty() ? 0L : fallback.get(0);
	}

	public boolean attendanceExistsOn(long employeeId, String date) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM attendance WHERE employee_id = ? AND DATE(check_in) = ?",
				Integer.class, employeeId, date);
		return count != null && count > 0;
	}

	/** {@code method} is the literal {@code 'app'}, as legacy writes it. */
	public void insertAttendanceException(long employeeId, String date, long exceptionTypeId) {
		this.jdbcTemplate.update(
				"INSERT INTO attendance (employee_id, check_in, method, exception_type_id)"
						+ " VALUES (?, ?, 'app', ?)",
				employeeId, date + " 00:00:00", exceptionTypeId);
	}

}
