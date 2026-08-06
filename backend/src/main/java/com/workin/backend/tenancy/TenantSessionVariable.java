package com.workin.backend.tenancy;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Component;

/**
 * Applies the RLS tenant scope ({@code SET LOCAL app.current_company_id})
 * to the <em>current transaction's</em> connection -- ADR-0002 Part B /
 * ADR-0010 Dimension 2, step 6. {@code set_config(..., true)} is
 * transaction-local by design: the scope dies with the transaction, so
 * every {@code @Transactional} unit that touches RLS-protected tables
 * must call {@link #apply} itself, first -- a scope established in some
 * earlier transaction (e.g. the authorization interceptor's context
 * validation) does not carry over. Callers must pass a companyId that
 * has already been validated (an {@link AuthorizationContext}'s, or the
 * id of a row created in this same transaction) -- this component
 * applies scope, it does not vouch for it.
 */
@Component
public class TenantSessionVariable {

	private final EntityManager entityManager;

	public TenantSessionVariable(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	public void apply(Long companyId) {
		entityManager
				.createNativeQuery("SELECT set_config('app.current_company_id', :companyId, true)")
				.setParameter("companyId", String.valueOf(companyId))
				.getSingleResult();
	}

}
