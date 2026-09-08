package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything {@code dashboard/pages/employees/detail.php} puts on one page.
 *
 * <p>A read-only view, and the one behind <b>R-057</b>: legacy guarded it with
 * {@code requireLogin()} alone -- no section permission, and a tenant predicate
 * keyed on {@code isCompany()}, which an HR session is not. The port reaches it
 * through the same {@code canOpenRow} rule as every other detail view here.
 *
 * <p>The month and year are free parameters that select the attendance rows and
 * the payslip; everything else is the employee's whole history, capped where
 * legacy caps it.
 */
public record EmployeeDetail(
		Employee employee, int month, int year, Salary salary, Leave leave,
		List<AttendanceDay> attendance, List<Request> requests, List<Penalty> penalties,
		List<Advance> advances, Payslip payslip, List<Document> documents) {

	/** Days with a check-in in the selected month. */
	public int daysPresent() {
		return this.attendance.size();
	}

	/** Legacy sums the per-row hours and rounds once, to one decimal. */
	public BigDecimal hoursWorked() {
		BigDecimal total = BigDecimal.ZERO;
		for (AttendanceDay day : this.attendance) {
			if (day.hours() != null) {
				total = total.add(day.hours());
			}
		}
		return total.setScale(1, java.math.RoundingMode.HALF_UP);
	}

	public int penaltyCount() {
		return this.penalties.size();
	}

	/**
	 * The latest contract's {@code total}, a generated column: basic plus
	 * allowances less deductions.
	 *
	 * <p>Legacy's stat card labels this {@code basic_salary} while showing the
	 * computed total. The label is wrong and is reproduced -- it is what the
	 * page has always shown, and relabelling it would change a figure people
	 * read against their own records.
	 */
	public String contractTotalLabel() {
		return this.salary == null ? "—" : this.salary.total().setScale(0,
				java.math.RoundingMode.HALF_UP).toPlainString();
	}

	public String netSalaryLabel() {
		return this.payslip == null ? "—" : this.payslip.netSalary().setScale(0,
				java.math.RoundingMode.HALF_UP).toPlainString();
	}

	public String remainingLeaveLabel() {
		return this.leave == null ? "—" : this.leave.remainingDays().toPlainString();
	}

	public record Salary(BigDecimal basicSalary, BigDecimal total, String effectiveFrom) {
	}

	public record Leave(BigDecimal totalDays, BigDecimal usedDays, BigDecimal remainingDays) {
	}

	public record AttendanceDay(
			String day, String checkIn, String checkOut, String method, BigDecimal hours) {

		/** Legacy prints a dash for an open shift rather than an empty cell. */
		public String checkOutLabel() {
			return this.checkOut == null || this.checkOut.isEmpty() ? "—" : this.checkOut;
		}
	}

	public record Request(String typeName, String fromDate, String toDate, String status) {
	}

	public record Penalty(
			String penaltyDate, String penaltyType, BigDecimal penaltyDays,
			boolean appliedToPayroll) {
	}

	public record Advance(BigDecimal amount, BigDecimal remaining, String status) {
	}

	/**
	 * The page reads both {@code advance_deduction} and
	 * {@code advances_deduction}. Both columns exist; carrying both is legacy's
	 * duplication, reproduced rather than tidied.
	 */
	public record Payslip(
			int month, int year, BigDecimal basicSalary, BigDecimal allowances,
			BigDecimal overtimePay, BigDecimal penaltiesTotal, BigDecimal advanceDeduction,
			BigDecimal advancesDeduction, BigDecimal netSalary) {
	}

	public record Document(String docType, String fileUrl, String uploadedAt) {
	}

}
