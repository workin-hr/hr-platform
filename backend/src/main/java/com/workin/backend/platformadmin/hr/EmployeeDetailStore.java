package com.workin.backend.platformadmin.hr;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The eight queries {@code detail.php} makes for one employee
 * ({@code dashboard/pages/employees/detail.php:21-28}).
 *
 * <p>Each is scoped by {@code employee_id} alone, which is correct <i>once the
 * employee has been established as this session's to read</i>. Legacy never
 * established that for an HR session (<b>R-057</b>); the port resolves the
 * employee through {@link EmployeeStore} and the shared {@code canOpenRow} rule
 * before any of these run.
 */
@Repository
@Profile("phase1-mysql")
public class EmployeeDetailStore {

	private final JdbcTemplate jdbcTemplate;

	public EmployeeDetailStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** Latest by effective date. {@code total} is a generated column. */
	public EmployeeDetail.Salary salary(long employeeId) {
		List<EmployeeDetail.Salary> rows = this.jdbcTemplate.query(
				"SELECT basic_salary, total, effective_from FROM salary_contracts"
						+ " WHERE employee_id = ? ORDER BY effective_from DESC LIMIT 1",
				(rs, rowNum) -> new EmployeeDetail.Salary(
						rs.getBigDecimal("basic_salary"), rs.getBigDecimal("total"),
						rs.getString("effective_from")),
				employeeId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** One row per year, or none. {@code remaining_days} is generated. */
	public EmployeeDetail.Leave leave(long employeeId, int year) {
		List<EmployeeDetail.Leave> rows = this.jdbcTemplate.query(
				"SELECT total_days, used_days, remaining_days FROM leave_balance"
						+ " WHERE employee_id = ? AND year = ?",
				(rs, rowNum) -> new EmployeeDetail.Leave(
						rs.getBigDecimal("total_days"), rs.getBigDecimal("used_days"),
						rs.getBigDecimal("remaining_days")),
				employeeId, year);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * Attendance for the chosen month, with hours rounded per row the way
	 * legacy rounds them -- to one decimal, in SQL, before they are summed.
	 * An open shift has a null {@code check_out} and therefore null hours.
	 */
	public List<EmployeeDetail.AttendanceDay> attendance(long employeeId, int month, int year) {
		return this.jdbcTemplate.query(
				"SELECT DATE(check_in) d, check_in, check_out, method,"
						+ " ROUND(TIMESTAMPDIFF(MINUTE, check_in, check_out) / 60, 1) hours"
						+ " FROM attendance WHERE employee_id = ? AND MONTH(check_in) = ?"
						+ " AND YEAR(check_in) = ? ORDER BY check_in",
				(rs, rowNum) -> new EmployeeDetail.AttendanceDay(
						rs.getString("d"), rs.getString("check_in"), rs.getString("check_out"),
						rs.getString("method"), rs.getBigDecimal("hours")),
				employeeId, month, year);
	}

	/** The ten most recent, across all time -- not filtered by the month. */
	public List<EmployeeDetail.Request> requests(long employeeId) {
		return this.jdbcTemplate.query(
				"SELECT r.from_date, r.to_date, r.status, t.name request_type_name"
						+ " FROM requests r INNER JOIN request_types t ON t.id = r.request_type_id"
						+ " WHERE r.employee_id = ? ORDER BY r.created_at DESC LIMIT 10",
				(rs, rowNum) -> new EmployeeDetail.Request(
						rs.getString("request_type_name"), rs.getString("from_date"),
						rs.getString("to_date"), rs.getString("status")),
				employeeId);
	}

	public List<EmployeeDetail.Penalty> penalties(long employeeId) {
		return this.jdbcTemplate.query(
				"SELECT penalty_date, penalty_type, penalty_days, applied_to_payroll"
						+ " FROM penalties WHERE employee_id = ? ORDER BY penalty_date DESC"
						+ " LIMIT 10",
				(rs, rowNum) -> new EmployeeDetail.Penalty(
						rs.getString("penalty_date"), rs.getString("penalty_type"),
						rs.getBigDecimal("penalty_days"),
						rs.getInt("applied_to_payroll") == 1),
				employeeId);
	}

	/** Every advance, with no limit -- the one list legacy does not cap. */
	public List<EmployeeDetail.Advance> advances(long employeeId) {
		return this.jdbcTemplate.query(
				"SELECT amount, remaining, status FROM advances WHERE employee_id = ?"
						+ " ORDER BY created_at DESC",
				(rs, rowNum) -> new EmployeeDetail.Advance(
						rs.getBigDecimal("amount"), rs.getBigDecimal("remaining"),
						rs.getString("status")),
				employeeId);
	}

	/** The payslip for the chosen month, if the batch for it has been run. */
	public EmployeeDetail.Payslip payslip(long employeeId, int month, int year) {
		List<EmployeeDetail.Payslip> rows = this.jdbcTemplate.query(
				"SELECT pd.basic_salary, pd.allowances, pd.overtime_pay, pd.penalties_total,"
						+ " pd.advance_deduction, pd.advances_deduction, pd.net_salary,"
						+ " pr.month, pr.year"
						+ " FROM payslips pd INNER JOIN payroll_batches pr ON pr.id = pd.batch_id"
						+ " WHERE pd.employee_id = ? AND pr.month = ? AND pr.year = ?",
				(rs, rowNum) -> new EmployeeDetail.Payslip(
						rs.getInt("month"), rs.getInt("year"), rs.getBigDecimal("basic_salary"),
						rs.getBigDecimal("allowances"), rs.getBigDecimal("overtime_pay"),
						rs.getBigDecimal("penalties_total"),
						rs.getBigDecimal("advance_deduction"),
						rs.getBigDecimal("advances_deduction"), rs.getBigDecimal("net_salary")),
				employeeId, month, year);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public List<EmployeeDetail.Document> documents(long employeeId) {
		return this.jdbcTemplate.query(
				"SELECT doc_type, file_url, uploaded_at FROM employee_docs"
						+ " WHERE employee_id = ? ORDER BY uploaded_at DESC",
				(rs, rowNum) -> new EmployeeDetail.Document(
						rs.getString("doc_type"), rs.getString("file_url"),
						rs.getString("uploaded_at")),
				employeeId);
	}

	/** Assembles the page once the employee is known to be readable. */
	public EmployeeDetail of(Employee employee, int month, int year) {
		long id = employee.id();
		return new EmployeeDetail(
				employee, month, year, salary(id), leave(id, year),
				attendance(id, month, year), requests(id), penalties(id), advances(id),
				payslip(id, month, year), documents(id));
	}

}
