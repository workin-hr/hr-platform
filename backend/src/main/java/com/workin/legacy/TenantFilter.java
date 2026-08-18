package com.workin.legacy;

/**
 * Names for the Phase 1 tenant filter, and the rule for who may carry
 * it.
 *
 * <p>The filter is declared once in {@code package-info.java} and
 * applied to each tenant-owned legacy entity with
 * {@code @Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)}.
 * Constants rather than string literals so the architecture guard can
 * check the annotation is present without matching on prose, and so a
 * rename cannot leave one entity silently unfiltered.
 *
 * <p>This is the mechanical half of ADR-0012. The decision it implements
 * is that tenant isolation moves from a database guarantee to an
 * application invariant, which is only safe because of the controls
 * around it -- one enforcement point, a context-derived tenant id,
 * fail-closed proven by test, and a build-failing guard that every
 * tenant-owned read is covered.
 */
public final class TenantFilter {

	/** Hibernate filter name. */
	public static final String NAME = "legacyTenantFilter";

	/** The single parameter: the authenticated company id. */
	public static final String COMPANY_ID_PARAMETER = "companyId";

	/**
	 * The predicate applied to every tenant-owned legacy entity.
	 *
	 * <p>Legacy names this column {@code company_id} on every
	 * tenant-owned table, so one condition covers all of them. That
	 * uniformity is a property of the legacy schema being adopted
	 * unchanged, not a convention this project imposed -- and it is what
	 * makes a single filter viable at all.
	 */
	public static final String CONDITION = "company_id = :" + COMPANY_ID_PARAMETER;

	/**
	 * The value bound when no tenant scope is established.
	 *
	 * <p>This is what restores the property PostgreSQL RLS gave for
	 * free. An un-enabled Hibernate filter restricts nothing, so a
	 * transaction that simply forgot to activate would read every tenant
	 * ({@code TenantFilterFailClosedTest} asserts that default
	 * explicitly). Binding a value no row can carry makes the unscoped
	 * case return <em>zero</em> rows instead — exactly what RLS did when
	 * {@code app.current_company_id} was unset.
	 *
	 * <p>Negative because legacy company ids are
	 * {@code int(10) UNSIGNED} and cannot be negative, so this can never
	 * collide with a real tenant.
	 *
	 * <p>It is a backstop, not the mechanism. Code that means to be
	 * tenant-scoped should fail loudly via
	 * {@link com.workin.backend.tenancy.TenantScope#current()} rather
	 * than quietly read nothing — the sentinel exists for the case
	 * nobody thought about.
	 */
	public static final long NO_TENANT = -1L;

	private TenantFilter() {
	}

}
