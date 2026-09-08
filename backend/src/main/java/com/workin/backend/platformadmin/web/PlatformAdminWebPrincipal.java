package com.workin.backend.platformadmin.web;

import java.io.Serializable;
import java.security.Principal;

/**
 * The authenticated administrator, as held in the session.
 *
 * <p>Carries the id and the identifier. It is deliberately <em>not</em> the
 * authorization source:
 * {@link PlatformAdminSessionRevalidationFilter} reloads the row on every
 * request, so what is stored here is an identifier to reload by, never a cached
 * decision. Serializable because Spring Session JDBC persists it.
 */
public record PlatformAdminWebPrincipal(long platformAdminId, String phone)
		implements Principal, Serializable {

	/**
	 * The administrator's id, as the session index name.
	 *
	 * <p>Spring Session indexes a session by {@code Authentication.getName()},
	 * which for a non-String principal falls back to {@code toString()}. Without
	 * this, sessions would be indexed under a debug string, and "list this
	 * administrator's sessions" -- ADR-0015 prerequisite 13 -- would depend on
	 * the format of a toString(). The id is stable and is what every other table
	 * keys on.
	 */
	@Override
	public String getName() {
		return String.valueOf(this.platformAdminId);
	}

	@Override
	public String toString() {
		// Nothing but the id reaches a log line through an accidental string
		// interpolation of the principal.
		return "PlatformAdminWebPrincipal[id=" + this.platformAdminId + "]";
	}

}
