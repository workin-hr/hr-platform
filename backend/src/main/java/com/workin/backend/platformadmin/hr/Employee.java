package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;

/**
 * A row of {@code employees} as the dashboard's list needs it
 * ({@code dashboard/pages/employees}).
 *
 * <p>The page legacy leaves entirely unguarded on write (<b>R-053</b>): all
 * four of its actions take the row id from the request, one of them can set
 * {@code password_hash}, and one of them deletes a row fourteen tables cascade
 * from.
 *
 * <p>Neither the shift nor the salary is a column here. Both are the latest
 * row of a history table, picked per employee by a correlated subquery, so an
 * employee with no assignment or no contract shows nothing rather than a zero.
 */
public record Employee(
		long id, long companyId, String companyName, String employeeCode, String employeeName,
		String phone, String countryCode, boolean active, String hireDate, String createdAt,
		String photoUrl, Integer contractDurationMonths, String branchName, String departmentName,
		String jobTitleName, String shiftName, BigDecimal basicSalary) {

	public String branchLabel() {
		return label(this.branchName);
	}

	public String departmentLabel() {
		return label(this.departmentName);
	}

	public String jobTitleLabel() {
		return label(this.jobTitleName);
	}

	public String shiftLabel() {
		return label(this.shiftName);
	}

	/** Legacy prints the raw value with no currency and no rounding. */
	public String salaryLabel() {
		return this.basicSalary == null
				? "—" : this.basicSalary.stripTrailingZeros().toPlainString();
	}

	/**
	 * The stored phone is the local part; the country code is a separate
	 * column and either may be absent.
	 */
	public String phoneLabel() {
		if (this.phone == null || this.phone.isEmpty()) {
			return "—";
		}
		return this.countryCode == null || this.countryCode.isEmpty()
				? this.phone : this.countryCode + " " + this.phone;
	}

	public String hireDateLabel() {
		String value = this.hireDate == null || this.hireDate.isEmpty()
				? this.createdAt : this.hireDate;
		return value == null ? "" : value.substring(0, Math.min(10, value.length()));
	}

	private static String label(String name) {
		return name == null || name.isEmpty() ? "—" : name;
	}

	/** One entry of a branch, department, job-title or shift select. */
	public record Option(long id, String name, String companyName) {

		public String label() {
			return this.companyName == null || this.companyName.isEmpty()
					? this.name : this.name + " — " + this.companyName;
		}
	}

}
