package com.workin.legacy;

import jakarta.persistence.EntityManager;

import org.hibernate.Session;
import org.springframework.stereotype.Component;

import com.workin.backend.tenancy.TenantScope;

/**
 * Binds the tenant filter to a persistence context — always, whether or
 * not a scope is established (ADR-0012 / D-041).
 *
 * <p>This is the piece that makes Phase 1's isolation a default rather
 * than a discipline. {@link TenantFilterActivator} is the explicit,
 * loud path for code that means to be tenant-scoped; this is the
 * backstop applied to every transaction, so the case nobody thought
 * about is covered too.
 *
 * <p><b>The unscoped case binds {@link TenantFilter#NO_TENANT}, not
 * nothing.</b> Leaving the filter off would mean an un-enabled
 * Hibernate filter, which restricts nothing and reads every tenant.
 * Binding a value no row can carry makes an unscoped transaction return
 * zero rows — restoring precisely what PostgreSQL RLS did when
 * {@code app.current_company_id} was unset, which is the property Phase
 * 1 otherwise loses.
 *
 * <p>Reads that must legitimately cross tenants disable the filter
 * explicitly and visibly — see
 * {@link TenantFilterActivator#deactivateForPreTenantLookup()}, which
 * login needs because legacy resolves a phone before any tenant is
 * known.
 */
@Component
public class TenantFilterBinder {

	private final TenantScope tenantScope;

	public TenantFilterBinder(TenantScope tenantScope) {
		this.tenantScope = tenantScope;
	}

	/**
	 * Enable the filter on this persistence context, scoped to the
	 * established tenant or to {@link TenantFilter#NO_TENANT}.
	 *
	 * <p>Never raises: it runs on every transaction, including the ones
	 * that have no business being tenant-scoped, and a transaction
	 * manager is the wrong place to start rejecting work. Loudness is
	 * {@link TenantFilterActivator}'s job.
	 */
	public void bind(EntityManager entityManager) {
		long companyId = tenantScope.isEstablished()
				? tenantScope.current()
				: TenantFilter.NO_TENANT;
		entityManager.unwrap(Session.class)
				.enableFilter(TenantFilter.NAME)
				.setParameter(TenantFilter.COMPANY_ID_PARAMETER, companyId);
	}

}
