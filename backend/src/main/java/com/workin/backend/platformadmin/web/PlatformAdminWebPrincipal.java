package com.workin.backend.platformadmin.web;

import java.io.Serializable;

/**
 * The authenticated administrator, as held in the session.
 *
 * <p>Carries the id and the phone only. It is deliberately <em>not</em> the
 * authorization source: {@link PlatformAdminSessionRevalidationFilter} reloads
 * the row on every request, so what is stored here is an identifier to reload
 * by, never a cached decision. Serializable because Spring Session JDBC
 * persists it.
 */
public record PlatformAdminWebPrincipal(long platformAdminId, String phone) implements Serializable {

	@Override
	public String toString() {
		// Never let the phone reach a log line through an accidental
		// string interpolation of the principal.
		return "PlatformAdminWebPrincipal[id=" + this.platformAdminId + "]";
	}

}
