package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import com.workin.legacy.LegacyValues;

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

	/**
	 * {@code dashboard_employee_display_name($emp)} ({@code detail.php:36}): the name the title,
	 * the header and the initials circle all use, an em dash when the stored name is blank.
	 */
	public String displayName() {
		return EmployeeDisplay.displayName(this.employee.employeeName(), "—");
	}

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

	/**
	 * {@code round(array_sum(...), 1)} echoed: PHP prints a whole float without its decimal, so
	 * eight hours read {@code 8} and a month with none reads {@code 0}. The per-row hours are
	 * one-decimal values, so the exact sum is the float PHP rounds to.
	 */
	public String hoursWorkedLabel() {
		return LegacyValues.toPhpString(hoursWorked().doubleValue());
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
		return this.salary == null ? "—" : PayrollDisplay.money(this.salary.total());
	}

	/** {@code number_format($payslip['net_salary'], 0)}: whole pounds, grouped in threes. */
	public String netSalaryLabel() {
		return this.payslip == null ? "—" : PayrollDisplay.money(this.payslip.netSalary());
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

		/** {@code substr($a['check_in'], 11, 5)}: the time, to the minute. */
		public String checkInTime() {
			return time(this.checkIn);
		}

		/** Legacy's badge replaces the time while the shift is open. */
		public boolean open() {
			return this.checkOut == null || this.checkOut.isEmpty();
		}

		public String checkOutTime() {
			return time(this.checkOut);
		}

		/** {@code $a['hours'] ?? '—'}: an open shift has no hours. */
		public String hoursLabel() {
			return this.hours == null ? "—" : this.hours.toPlainString();
		}

		private static String time(String stored) {
			return stored == null || stored.length() <= 11
					? "" : stored.substring(11, Math.min(16, stored.length()));
		}
	}

	public record Request(String typeName, String fromDate, String toDate, String status) {
	}

	public record Penalty(
			String penaltyDate, String penaltyType, BigDecimal penaltyDays,
			boolean appliedToPayroll) {
	}

	public record Advance(BigDecimal amount, BigDecimal remaining, String status) {

		/** {@code number_format($a['amount'], 0)} (detail.php:109). */
		public String amountDisplay() {
			return PayrollDisplay.money(this.amount);
		}

		public String remainingDisplay() {
			return PayrollDisplay.money(this.remaining);
		}

		/** {@code $a['remaining'] > 0}: red while any of it is owed, green once none is. */
		public boolean stillOwed() {
			return this.remaining != null && this.remaining.signum() > 0;
		}
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

		/**
		 * The link legacy opens, unless the stored value names a scheme other than http or https.
		 * The upload endpoint stores its own absolute URL, so this refuses nothing it wrote; it
		 * keeps a {@code javascript:} value written some other way from becoming a link an
		 * administrator clicks.
		 */
		public String href() {
			if (this.fileUrl == null || this.fileUrl.isBlank()) {
				return null;
			}
			String url = this.fileUrl.strip();
			int colon = url.indexOf(':');
			int path = -1;
			for (char separator : new char[] {'/', '?', '#'}) {
				int at = url.indexOf(separator);
				if (at >= 0 && (path < 0 || at < path)) {
					path = at;
				}
			}
			if (colon < 0 || (path >= 0 && path < colon)) {
				return url;
			}
			String scheme = url.substring(0, colon).toLowerCase(Locale.ROOT);
			return scheme.equals("http") || scheme.equals("https") ? url : null;
		}
	}

}
