package com.workin.legacy;

import jakarta.persistence.EntityManager;

import org.hibernate.Session;
import org.springframework.stereotype.Component;

import com.workin.backend.tenancy.NoTenantScopeException;
import com.workin.backend.tenancy.TenantScope;

/**
 * Applies all three tenancy filters (P-1a/P-1b/P-1c) to the current
 * persistence context — Phase 1's counterpart to {@code
 * TenantSessionVariable.apply}, and deliberately the same shape
 * (ADR-0012 / D-041).
 *
 * <p>Under PostgreSQL, {@code SET LOCAL app.current_company_id} scoped
 * the transaction and RLS did the rest. Here the scope comes from
 * {@link TenantScope} and Hibernate does the filtering. The contract is
 * identical in the way that matters: <b>this applies scope, it does not
 * vouch for it.</b> The company id must already have been validated
 * against the authenticated principal — ADR-0012's second mandatory
 * part, that the authenticated context is the only source of a tenant
 * id. A request-supplied company id must never reach here.
 *
 * <p>There is one deliberate asymmetry with the PostgreSQL version.
 * {@code SET LOCAL} was transaction-scoped and expired on its own; a
 * Hibernate filter is enabled on the session and an un-enabled filter
 * restricts <em>nothing</em>. So this cannot be optional or
 * best-effort: {@link #activate} reads {@link TenantScope#current()}
 * once, which raises when no scope is established, and that raise is
 * the fail-closed behaviour -- for all three filters together, not one
 * check per policy. Calling {@code activate} on an unscoped session is a
 * bug, and it is loud.
 */
@Component
public class TenantFilterActivator {

	private final EntityManager entityManager;
	private final TenantScope tenantScope;

	public TenantFilterActivator(EntityManager entityManager, TenantScope tenantScope) {
		this.entityManager = entityManager;
		this.tenantScope = tenantScope;
	}

	/**
	 * Enable all three tenancy filters on the current session, scoped to
	 * the established tenant.
	 *
	 * @throws NoTenantScopeException when no scope is established —
	 *         never silently unfiltered, which would return every
	 *         tenant's rows. Raised before any filter is touched, so a
	 *         refused activation leaves all three off, never some
	 *         enabled and some not.
	 */
	public void activate() {
		long companyId = tenantScope.current();
		Session session = entityManager.unwrap(Session.class);
		session.enableFilter(TenantFilter.NAME)
				.setParameter(TenantFilter.COMPANY_ID_PARAMETER, companyId);
		session.enableFilter(EmployeeDerivedTenantFilter.NAME)
				.setParameter(EmployeeDerivedTenantFilter.COMPANY_ID_PARAMETER, companyId);
		session.enableFilter(DepartmentBranchesTenantFilter.NAME)
				.setParameter(DepartmentBranchesTenantFilter.COMPANY_ID_PARAMETER, companyId);
	}

	/**
	 * Disable all three filters on the current session.
	 *
	 * <p>Exists for the deliberately pre-tenant lookups the legacy
	 * contract requires — login resolves a phone before any tenant is
	 * known ({@code login_employee.php:18-48}). Naming it explicitly
	 * means such a read is a visible, reviewable choice rather than the
	 * accident of having forgotten to scope. Disabling all three, not
	 * just P-1a, keeps the escape hatch's meaning uniform: "no tenant
	 * filtering applies in this window," not "P-1a specifically doesn't."
	 */
	public void deactivateForPreTenantLookup() {
		Session session = entityManager.unwrap(Session.class);
		session.disableFilter(TenantFilter.NAME);
		session.disableFilter(EmployeeDerivedTenantFilter.NAME);
		session.disableFilter(DepartmentBranchesTenantFilter.NAME);
	}

}
