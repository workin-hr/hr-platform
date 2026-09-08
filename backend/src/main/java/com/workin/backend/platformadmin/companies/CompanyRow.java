package com.workin.backend.platformadmin.companies;

/**
 * One row of the dashboard's company directory
 * ({@code dashboard/pages/companies/page.php}).
 *
 * <p>Distinct from {@code PlatformAdminCompanyDirectory.CompanyView}, which
 * ADR-0015 keeps deliberately narrow because it is the interface the
 * <em>lifecycle actions</em> run through and it has to be satisfiable over
 * both databases. This record is the listing, it exists only under
 * {@code phase1-mysql}, and widening the shared interface to carry it would
 * have made the PostgreSQL profile owe ten columns and three aggregate counts
 * that its schema has no answer for.
 *
 * @param accountHolder  {@code company_person_name()}: first and last name
 *                       joined, a dash when both are blank
 * @param phone          {@code company_phone_display()}: country code then
 *                       number, already joined
 * @param employeeCount  active employees only, as legacy counts them
 * @param branchCount    every branch, active or not -- legacy does not filter
 *                       this one, and the two counts disagreeing is legacy's
 *                       behaviour rather than an oversight here
 */
public record CompanyRow(
		long id, String name, String logoUrl, String commercialRegUrl, String accountHolder,
		String phone, String activityName, String titleName, String sizeName, String status,
		long employeeCount, long branchCount, long departmentCount, String createdAt) {

	/** {@code substr((string) $c['created_at'], 0, 10)}. */
	public String createdDate() {
		return this.createdAt == null || this.createdAt.isBlank()
				? "—" : this.createdAt.substring(0, Math.min(10, this.createdAt.length()));
	}

	public String nameLabel() {
		// D-035: a company that has signed up but not completed its profile has
		// no name yet, and the row still has to list.
		return this.name == null || this.name.isBlank() ? "—" : this.name;
	}

	public String activityLabel() {
		return this.activityName == null || this.activityName.isBlank() ? "—" : this.activityName;
	}

	public String titleLabel() {
		return this.titleName == null || this.titleName.isBlank() ? "—" : this.titleName;
	}

	public String sizeLabel() {
		return this.sizeName == null || this.sizeName.isBlank() ? "—" : this.sizeName;
	}

	public boolean hasLogo() {
		return this.logoUrl != null && !this.logoUrl.isBlank();
	}

	public boolean hasCommercialReg() {
		return this.commercialRegUrl != null && !this.commercialRegUrl.isBlank();
	}

	/** {@code company_lookup_activities()} and its two siblings. */
	public record Option(long id, String name) {
	}

}
