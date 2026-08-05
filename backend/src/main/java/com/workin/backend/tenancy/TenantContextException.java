package com.workin.backend.tenancy;

/**
 * Raised by every fail-closed condition in
 * docs/adr/ADR-0010-authorization-model.md Dimension 2: no tenant
 * selected, membership does not exist, membership disabled,
 * identity/membership mismatch. Always translated to a uniform 404 by
 * callers -- docs/architecture/authorization-model.md §8: "all
 * access-denied responses must avoid revealing whether an inaccessible
 * cross-tenant resource exists." The exception message is for logs, not
 * the HTTP response body.
 */
public class TenantContextException extends RuntimeException {

	public TenantContextException(String message) {
		super(message);
	}

}
