package com.workin.backend.platformadmin.companies;

/**
 * One row of the dashboard's company directory
 * ({@code dashboard/pages/companies/page.php}).
 *
 * <p>Distinct from {@code PlatformAdminCompanyDirectory.CompanyView}, which
 * ADR-0015 keeps deliberately narrow because it is the interface the
 * <em>lifecycle actions</em> run through. This record is the listing: ten more
 * columns and three aggregate counts that only the page needs, kept out of the
 * interface the actions depend on.
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

	/**
	 * {@code company_logo_src()} ({@code company_helper.php:173}): the name the fallback avatar is
	 * drawn from, {@code C} when there is none.
	 *
	 * <p>{@code trim($name) ?: 'C'}, and PHP's {@code ?:} treats {@code "0"} as empty too.
	 */
	public String avatarName() {
		return avatarName(this.name);
	}

	/** {@link #avatarName()} for a company's stored name; the detail page draws the same fallback. */
	public static String avatarName(String name) {
		String trimmed = name == null ? "" : name.trim();
		return trimmed.isEmpty() || "0".equals(trimmed) ? "C" : trimmed;
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

	/**
	 * The logo this row loads, or null to draw the initials instead. The detail page's card reads
	 * the same column through the same rule; see {@code PlatformAdminCompanyDirectory.Profile}.
	 */
	public String logoSrc() {
		return com.workin.backend.platformadmin.hr.StoredUrl.href(this.logoUrl);
	}

	public boolean hasLogo() {
		return logoSrc() != null;
	}

	/**
	 * The commercial registration's link, or null for none. Legacy's list prefixes any stored value
	 * that is not http or https with its own host ({@code dashboard_media_url()}), so it never links
	 * another scheme either -- though it still draws the anchor, where this draws none (D-264).
	 * {@code page.php:218} gates it on {@code !empty()}, so a stored {@code "0"} draws none.
	 */
	public String commercialRegHref() {
		return "0".equals(this.commercialRegUrl) ? null
				: com.workin.backend.platformadmin.hr.StoredUrl.href(this.commercialRegUrl);
	}

	/** {@code company_lookup_activities()} and its two siblings. */
	public record Option(long id, String name) {
	}

}
