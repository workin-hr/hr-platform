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
 *
 * <p>{@code contractDurationMonths} is a {@code Long} and not an {@code Integer}
 * because {@code employees.contract_duration_months} is {@code int(10)
 * unsigned}: its range does not fit a signed {@code int}, so MariaDB
 * Connector/J hands back a {@code Long}. The same reason
 * {@link com.workin.legacy.LegacyJdbcValues} reads every numeric column as one.
 */
public record Employee(
		long id, long companyId, String companyName, String employeeCode, String employeeName,
		String phone, String countryCode, boolean active, String hireDate, String createdAt,
		String photoUrl, Long contractDurationMonths, String branchName, String departmentName,
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

	/**
	 * What the edit form is filled from: every column {@code save_edit} writes,
	 * as stored, and the latest shift assignment. Not the list row, whose name
	 * and code are display values -- a form filled from those saves the joined
	 * name into {@code first_name}. Text reads blank for null, and the save
	 * treats the two alike, so an unchanged blank stays as stored; an absent
	 * org row or shift is zero, the selects' "none".
	 */
	public record Form(
			long id, long companyId, String firstName, String lastName, String employeeCode,
			String phone, String countryCode, String nationalId, String birthDate, String gender,
			String address, String hireDate, long branchId, long departmentId, long jobTitleId,
			Long contractDurationMonths, boolean mobileAttendance, long shiftId,
			String shiftEffectiveFrom) {

		/** {@code employee_contract_form_values()}: whole years are shown as years. */
		public String contractValue() {
			long months = contractMonths();
			if (months <= 0) {
				return "";
			}
			return String.valueOf(months % 12 == 0 ? months / 12 : months);
		}

		public String contractUnit() {
			long months = contractMonths();
			return months > 0 && months % 12 == 0 ? "years" : "months";
		}

		private long contractMonths() {
			return this.contractDurationMonths == null ? 0 : this.contractDurationMonths;
		}

		public String birthDateInput() {
			return inputDate(this.birthDate);
		}

		public String hireDateInput() {
			return inputDate(this.hireDate);
		}

		public String shiftEffectiveFromInput() {
			return inputDate(this.shiftEffectiveFrom);
		}

		/**
		 * Whether a date input can hold the value. A browser submits any other
		 * value -- legacy's {@code 0000-00-00} above all -- as empty, so the save
		 * cannot tell it from a date that was cleared without the stored row.
		 */
		public static boolean isDateInputValue(String value) {
			try {
				return value.length() == 10 && java.time.LocalDate.parse(value).getYear() >= 1;
			}
			catch (java.time.format.DateTimeParseException ex) {
				return false;
			}
		}

		private static String inputDate(String value) {
			return isDateInputValue(value) ? value : "";
		}
	}

}
