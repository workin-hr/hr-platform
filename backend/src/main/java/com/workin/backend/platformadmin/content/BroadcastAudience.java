package com.workin.backend.platformadmin.content;

/**
 * Who a notification goes to, mirroring the {@code $audience} values
 * {@code dashboard_notification_dispatch()} switches on.
 *
 * <p>Only the audiences a <b>platform administrator</b> can address are
 * here. The dashboard's remaining ones ({@code company_inbox},
 * {@code one_employee}, and the branch and department variants) belong to a
 * company session, which this surface does not yet have (ADR-0016), and
 * adding them before that exists would mean a platform admin writing into
 * one tenant with no company context established -- exactly the
 * cross-tenant path R-044 is about.
 */
public enum BroadcastAudience {

	/** Every active employee in every company. */
	ALL_EMPLOYEES("all_employees", "notif_audience_all_employees"),

	/** Every active employee of one company. */
	COMPANY_EMPLOYEES("company_employees", "notif_audience_company_employees");

	private final String submitted;

	private final String labelKey;

	BroadcastAudience(String submitted, String labelKey) {
		this.submitted = submitted;
		this.labelKey = labelKey;
	}

	public String submitted() {
		return this.submitted;
	}

	public String labelKey() {
		return this.labelKey;
	}

	/** @return the audience, or null when the value is not one this surface offers */
	public static BroadcastAudience of(String value) {
		for (BroadcastAudience audience : values()) {
			if (audience.submitted.equals(value)) {
				return audience;
			}
		}
		return null;
	}

	/**
	 * Whether sending needs the operator to have ticked the confirmation.
	 *
	 * <p>The dashboard requires it for every audience that reaches more than
	 * one person, and so does this. The reach is real: 2,838 active employees
	 * in the reference snapshot, and it grows.
	 */
	public boolean requiresConfirmation() {
		return true;
	}

}
