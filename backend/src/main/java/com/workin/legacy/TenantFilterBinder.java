package com.workin.legacy;

import jakarta.persistence.EntityManager;

import org.hibernate.Session;
import org.springframework.stereotype.Component;

import com.workin.backend.tenancy.TenantScope;

/**
 * Binds all three tenancy filters (P-1a/P-1b/P-1c) to a persistence
 * context — always, whether or not a scope is established (ADR-0012 /
 * D-041).
 *
 * <p>This is the piece that makes Phase 1's isolation a default rather
 * than a discipline. {@link TenantFilterActivator} is the explicit,
 * loud path for code that means to be tenant-scoped; this is the
 * backstop applied to every transaction, so the case nobody thought
 * about is covered too.
 *
 * <p><b>The unscoped case binds each policy's {@code NO_TENANT}
 * sentinel, not nothing.</b> Leaving a filter off would mean an
 * un-enabled Hibernate filter, which restricts nothing and reads every
 * tenant. Binding a value no row can carry makes an unscoped transaction
 * return zero rows — restoring precisely what PostgreSQL RLS did when
 * {@code app.current_company_id} was unset, which is the property Phase
 * 1 otherwise loses. All three policies happen to share {@code -1L} as
 * that sentinel (each unsigned-key table it applies to cannot carry a
 * negative id, directly or via its one-hop subquery), but each is bound
 * through its own named constant rather than reused across policies —
 * three distinct policies, not one value three call sites happen to
 * agree on.
 *
 * <p>All three are enabled unconditionally on every persistence context,
 * regardless of which filters the entities actually mapped through it
 * will use — an entity that carries none of the three simply never
 * references any of them, so binding all three is inert for it, not a
 * behaviour change. This mirrors the single-filter binder's original
 * shape exactly, just for three names instead of one; {@code
 * TenantBindingEndToEndTest} proves this holds for a real Spring-managed
 * transaction.
 *
 * <p>Reads that must legitimately cross tenants disable all three
 * filters explicitly and visibly — see
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
	 * Enable all three tenancy filters on this persistence context,
	 * scoped to the established tenant or to each policy's own {@code
	 * NO_TENANT} sentinel.
	 *
	 * <p>Never raises: it runs on every transaction, including the ones
	 * that have no business being tenant-scoped, and a transaction
	 * manager is the wrong place to start rejecting work. Loudness is
	 * {@link TenantFilterActivator}'s job.
	 */
	public void bind(EntityManager entityManager) {
		boolean established = tenantScope.isEstablished();
		long scopedCompanyId = established ? tenantScope.current() : 0L;
		Session session = entityManager.unwrap(Session.class);
		session.enableFilter(TenantFilter.NAME)
				.setParameter(TenantFilter.COMPANY_ID_PARAMETER,
						established ? scopedCompanyId : TenantFilter.NO_TENANT);
		session.enableFilter(EmployeeDerivedTenantFilter.NAME)
				.setParameter(EmployeeDerivedTenantFilter.COMPANY_ID_PARAMETER,
						established ? scopedCompanyId : EmployeeDerivedTenantFilter.NO_TENANT);
		session.enableFilter(DepartmentBranchesTenantFilter.NAME)
				.setParameter(DepartmentBranchesTenantFilter.COMPANY_ID_PARAMETER,
						established ? scopedCompanyId : DepartmentBranchesTenantFilter.NO_TENANT);
	}

}
