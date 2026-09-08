package com.workin.backend.platformadmin.hr;

import java.time.LocalDateTime;

/**
 * One employee's request to join a company
 * ({@code home_get_join_requests()}, {@code pages/home/home_service.php}).
 *
 * <p>Not a table of its own: a join request <em>is</em> an {@code employees}
 * row whose {@code join_request_status} is still {@code pending}. Accepting
 * one flips that column and activates the row; rejecting one <b>deletes the
 * employee</b>. That is why {@link #pending()} is load-bearing rather than
 * cosmetic -- it is the only thing standing between the reject button and an
 * active employee's record.
 *
 * @param status one of {@code pending}, {@code accepted}, {@code rejected}.
 *     The third is a valid enum value that nothing in either surface ever
 *     writes, because both reject paths delete the row instead. It is kept
 *     because the column allows it and the filter offers it.
 */
public record JoinRequest(
		long id,
		long companyId,
		String name,
		String phone,
		String status,
		LocalDateTime createdAt) {

	public boolean pending() {
		return "pending".equals(this.status);
	}

	public boolean accepted() {
		return "accepted".equals(this.status);
	}

	/** The name, falling back to the phone, as the page's first column does. */
	public String label() {
		return this.name == null || this.name.isBlank() ? this.phone : this.name;
	}

	/**
	 * The badge legacy prints: {@code approved} rather than {@code accepted},
	 * and {@code pending} for anything unrecognised.
	 */
	public String badge() {
		if (accepted()) {
			return "approved";
		}
		return "rejected".equals(this.status) ? "rejected" : "pending";
	}

}
