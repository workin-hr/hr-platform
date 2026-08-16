package com.workin.legacy;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Applies the tenant filter to every transaction, at the moment the
 * persistence context is created — Phase 1's automatic call site
 * (ADR-0012 / D-041, "one enforcement point").
 *
 * <p>Without this, the filter is only ever as good as whoever remembered
 * to switch it on, which is the convention ADR-0012 explicitly refuses
 * to rely on. Binding at transaction start means a query cannot be
 * issued through Spring-managed JPA <em>before</em> the filter exists on
 * its session.
 *
 * <p>Deliberately in {@code doBegin} rather than a
 * {@code TransactionSynchronization}: synchronizations run after the
 * transaction is already usable, which would leave a window in which an
 * unfiltered query is possible. There is no such window here.
 *
 * <p>Only a newly created persistence context is bound. When a
 * transaction joins an existing one, the filter is already on that
 * session from the outer {@code doBegin}, and re-binding would let an
 * inner transaction silently re-scope the outer one's session — the
 * same widening {@link com.workin.backend.tenancy.TenantScope#enter}
 * refuses.
 *
 * <p>Phase 2 restores row-level security. This should stay: two
 * independent controls is the end state, not one replacing the other.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

	private final transient TenantFilterBinder tenantFilterBinder;

	public TenantAwareJpaTransactionManager(
			EntityManagerFactory entityManagerFactory, TenantFilterBinder tenantFilterBinder) {
		super(entityManagerFactory);
		this.tenantFilterBinder = tenantFilterBinder;
	}

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
		boolean joiningExisting = TransactionSynchronizationManager
				.hasResource(obtainEntityManagerFactory());

		super.doBegin(transaction, definition);

		if (joiningExisting) {
			return;
		}
		Object resource = TransactionSynchronizationManager
				.getResource(obtainEntityManagerFactory());
		if (resource instanceof EntityManagerHolder holder) {
			tenantFilterBinder.bind(holder.getEntityManager());
		}
	}

}
