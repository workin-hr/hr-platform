package com.workin.backend.tenancy;

/**
 * A tenant-scoped operation was attempted without an established tenant
 * scope, or the scope was asked to change mid-unit-of-work.
 *
 * <p>Deliberately an error rather than a quiet empty result. Under
 * PostgreSQL RLS an unscoped read simply matched nothing; under Phase
 * 1's application-enforced isolation (ADR-0012 / D-041) there is no
 * database backstop, so the same situation would return every tenant's
 * rows. Failing loudly is what makes that impossible to ship
 * unnoticed.
 *
 * <p>This is an internal invariant failure, not a client error. It must
 * surface as a 5xx and be alerted on -- a 403 would read as "this user
 * lacks access", which is the wrong diagnosis and would get triaged as
 * a permissions question rather than the isolation bug it is.
 */
public class NoTenantScopeException extends RuntimeException {

	public NoTenantScopeException(String message) {
		super(message);
	}

}
