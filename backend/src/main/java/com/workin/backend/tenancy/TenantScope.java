package com.workin.backend.tenancy;

import org.springframework.stereotype.Component;

/**
 * The tenant scope for the current unit of work — Phase 1's replacement
 * for {@code SET LOCAL app.current_company_id} (ADR-0012 / D-041).
 *
 * <p><b>Why this fails closed by raising, not by returning empty.</b>
 * PostgreSQL RLS failed closed for free: with no session variable set,
 * an unscoped read matched zero rows ({@code RlsFailClosedTest}). A
 * Hibernate filter behaves the opposite way — a filter that was never
 * enabled restricts nothing, so the same query returns every tenant's
 * rows. Fail-open is the entire risk of the mechanism Phase 1 adopts,
 * and MySQL has no backstop behind it.
 *
 * <p>So {@link #current()} raises when no scope is established. Silently
 * returning empty would be safe but would hide the bug until someone
 * wondered why a list was short; returning rows would be a cross-tenant
 * breach. Raising is the only outcome that is both safe and loud.
 *
 * <p><b>Why thread-local, and why {@link #exit()} is mandatory.</b>
 * {@code SET LOCAL} died with its transaction automatically. Nothing
 * here does: the application serves concurrent requests from a pool, so
 * a scope left behind is the next request on that thread reading the
 * previous tenant's data. {@code exit()} in a {@code finally} is not
 * tidiness, it is the control.
 *
 * <p>This holds scope; it does not vouch for it. Callers must pass a
 * company id already validated against the authenticated principal —
 * ADR-0012's second mandatory part, that the authenticated context is
 * the only source of a tenant id.
 */
@Component
public class TenantScope {

	private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

	/**
	 * Establish the scope for this unit of work.
	 *
	 * <p>Re-entering the same tenant is a no-op, so nested
	 * {@code @Transactional} paths that each scope defensively do not
	 * fight each other. Entering a <em>different</em> tenant without
	 * exiting is rejected: mid-unit-of-work tenant changes are either a
	 * leaked scope or an attempt to widen reach, and Phase 1 has no
	 * database backstop to distinguish them. The existing scope is left
	 * intact when a widening is rejected — dropping it would leave the
	 * thread unscoped and fail the next legitimate read.
	 */
	public void enter(Long companyId) {
		if (companyId == null) {
			throw new NoTenantScopeException(
					"Refusing to establish a null tenant scope: a tenant id must be derived from "
							+ "the authenticated context, never defaulted.");
		}
		Long established = CURRENT.get();
		if (established != null && !established.equals(companyId)) {
			throw new NoTenantScopeException(
					"Refusing to change tenant scope from " + established + " to " + companyId
							+ " inside one unit of work. Start a new unit of work instead.");
		}
		CURRENT.set(companyId);
	}

	/**
	 * Release the scope. Safe to call when none was established, so it
	 * can sit unconditionally in a {@code finally}.
	 */
	public void exit() {
		CURRENT.remove();
	}

	/**
	 * @return the established tenant id
	 * @throws NoTenantScopeException when none is established — see the
	 *         class javadoc for why this is not an empty result
	 */
	public long current() {
		Long established = CURRENT.get();
		if (established == null) {
			throw new NoTenantScopeException(
					"Refusing to run a tenant-scoped query with no tenant scope established. "
							+ "Phase 1 has no row-level security behind this check (ADR-0012), so an "
							+ "unscoped query would read every tenant.");
		}
		return established;
	}

	/** Whether a scope is established, without raising. */
	public boolean isEstablished() {
		return CURRENT.get() != null;
	}

}
