package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;

/**
 * The rows {@code dashboard/pages/payroll/page.php} renders.
 *
 * <p>The page is two views behind one URL: a list of batches, and — once
 * {@code run_id} is set — one batch's payslips with a totals strip. Both are
 * reproduced here.
 */
public final class PayrollRecord {

	private PayrollRecord() {
	}

	/**
	 * One payroll batch in the list.
	 *
	 * @param employeeCount {@code emp_count}, a correlated subquery over
	 *     {@code payslips} rather than a join, so a batch with no payslips
	 *     still appears with a zero
	 * @param totalNet {@code total_net}, the same shape over {@code net_salary}
	 */
	public record BatchRow(
			long id,
			long companyId,
			String companyName,
			int month,
			int year,
			String periodFrom,
			String periodTo,
			String status,
			String createdAt,
			int employeeCount,
			BigDecimal totalNet) {
	}

	/**
	 * One payslip as the <b>edit form</b> shows it: the stored columns, not the
	 * enriched ones.
	 *
	 * <p>The detail <em>table</em> does not use this record. PHP renders that
	 * table from {@code payroll_enrich_payslip_rows()}'s output, which recomputes
	 * seven of these columns from live attendance and contract data and adds four
	 * derived salary figures, so the port carries those rows as maps straight
	 * from the shared enrichment rather than flattening them into a type that
	 * would imply they came from the table.
	 *
	 * <p>The consequence is worth stating because it looks like a bug and is not:
	 * a {@code net_salary} typed into this form is stored, but the detail table
	 * goes on showing the recomputed figure. The edit surfaces in the batch
	 * totals strip and the list's {@code total_net}, both of which sum the stored
	 * column.
	 */
	public record PayslipRow(
			long id,
			long employeeId,
			String empCode,
			String employeeName,
			BigDecimal daysPresent,
			BigDecimal daysAbsent,
			BigDecimal daysLeave,
			BigDecimal overtimeHours,
			BigDecimal basicSalary,
			BigDecimal allowances,
			BigDecimal overtimePay,
			BigDecimal penaltiesTotal,
			BigDecimal advanceDeduction,
			BigDecimal advancesDeduction,
			BigDecimal otherDeductions,
			BigDecimal netSalary) {
	}

	/** {@code payroll_batch_payslip_totals()}: the strip above the detail table. */
	public record BatchTotals(
			int employeeCount,
			BigDecimal totalEntitlements,
			BigDecimal totalDeductions,
			BigDecimal netSalary) {
	}

	/** The batch currently open, with the company name its heading needs. */
	public record CurrentBatch(
			long id,
			long companyId,
			String companyName,
			int month,
			int year,
			String periodFrom,
			String periodTo,
			String status) {

		public boolean finalized() {
			return "finalized".equals(this.status);
		}
	}

	/** A company the create-run form can pick, for an unscoped administrator. */
	public record CompanyOption(long id, String name) {
	}

}
