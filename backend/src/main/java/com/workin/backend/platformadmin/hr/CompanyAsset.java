package com.workin.backend.platformadmin.hr;

/**
 * A row of {@code assets} as the dashboard's list and form need it
 * ({@code dashboard/pages/assets}).
 *
 * <p>Unlike the other HR tables this one carries its own {@code company_id},
 * so R-046's lookup is a column read rather than a join. The edit writes that
 * column from the chosen employee, which is why the employee is checked on
 * every write and not only on the first.
 *
 * @param returned once true the row is <b>frozen</b> against editing, the same
 *                 shape as a penalty applied to payroll: the asset is back, so
 *                 there is nothing left to correct about the loan
 */
public record CompanyAsset(
		long id, long employeeId, long companyId, String companyName, String employeeCode,
		String employeeName, String assetText, String assetDate, String assetEndDate,
		boolean returned, String returnedAt, String createdAt) {

	public String createdDate() {
		return this.createdAt == null
				? "" : this.createdAt.substring(0, Math.min(10, this.createdAt.length()));
	}

}
