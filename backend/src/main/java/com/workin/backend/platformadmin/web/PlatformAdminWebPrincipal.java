package com.workin.backend.platformadmin.web;

import java.io.Serializable;

/**
 * The authenticated administrator, as held in the session.
 *
 * <p>Carries the id, the phone, and whether a second factor is bound. It is
 * deliberately <em>not</em> the authorization source:
 * {@link PlatformAdminSessionRevalidationFilter} reloads the row on every
 * request, so what is stored here is an identifier to reload by, never a cached
 * decision. Serializable because Spring Session JDBC persists it.
 *
 * <p>{@code factorBound} is false only for an administrator who has not
 * completed D-152's enrolment ceremony. Such a session exists so they can
 * enrol; ADR-0015 prerequisite 1 requires that it cannot perform a destructive
 * operation until the factor is bound.
 */
public record PlatformAdminWebPrincipal(long platformAdminId, String phone, boolean factorBound)
		implements Serializable {

	@Override
	public String toString() {
		// Never let the phone reach a log line through an accidental
		// string interpolation of the principal.
		return "PlatformAdminWebPrincipal[id=" + this.platformAdminId + ", factorBound=" + this.factorBound + "]";
	}

}
