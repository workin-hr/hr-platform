package com.workin.backend.platformadmin.hr;

/**
 * A row of {@code administrative_decisions} as the dashboard's list and form
 * need it ({@code dashboard/pages/administrative_decisions}).
 *
 * <p>The simplest table on the surface: a company, a title, a body and a flag.
 * It reaches its company by a column rather than a join, and that column is
 * the whole of its tenant ownership -- which is why <b>D-176</b> keeps it out
 * of an edit's updated fields.
 */
public record AdministrativeDecision(
		long id, long companyId, String companyName, String title, String body, boolean active,
		String createdAt) {

	public String createdDate() {
		return this.createdAt == null
				? "" : this.createdAt.substring(0, Math.min(10, this.createdAt.length()));
	}

}
