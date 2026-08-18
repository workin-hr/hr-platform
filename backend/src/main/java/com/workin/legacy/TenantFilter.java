package com.workin.legacy;

/**
 * Names for Phase 1's <b>direct</b> tenancy policy (P-1a), and the rule
 * for who may carry it.
 *
 * <p><b>Corrected 2026-08-18 (PR 12.2, U-1, F-1):</b> this javadoc
 * previously claimed "legacy names this column {@code company_id} on
 * every tenant-owned table, so one condition covers all of them" and
 * that this uniformity "is what makes a single filter viable at all".
 * That claim was disproven: verified against the vendored schema, 10 of
 * Item 12's 21 in-scope tables carry no {@code company_id} column at
 * all (`advances`, `attendance`, `department_branches`,
 * `employee_schedules`, `employee_shift_assignments`, `leave_balance`,
 * `payslips`, `penalties`, `requests`, `salary_contracts`). This class
 * is <b>one of three</b> named tenancy policies, not "the" filter:
 * {@link EmployeeDerivedTenantFilter} (P-1b) covers the nine tables
 * reachable one-hop via {@code employee_id → employees.company_id};
 * {@link DepartmentBranchesTenantFilter} (P-1c) covers the one table
 * reachable via {@code department_id → departments.company_id}. Each is
 * a genuinely distinct {@code @FilterDef} (owner decision, 2026-08-18,
 * D-2 in the Item 12 specification: "a distinct policy, not a
 * parameterisation of P-1a"), not a variant of this one -- deliberately,
 * so a table can never be forced through a condition that does not
 * match its columns. {@link TenantFilterCoverageTest} enforces that
 * every tenant-owned legacy entity declares exactly one of the three,
 * matching its actual columns.
 *
 * <p>The filter is declared once in {@code package-info.java} and
 * applied to each direct-{@code company_id} legacy entity with
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
	 * The predicate applied to every direct-{@code company_id}
	 * tenant-owned legacy entity (P-1a) -- 10 of Item 12's 21 tables,
	 * not all of them. See {@link EmployeeDerivedTenantFilter} (P-1b)
	 * and {@link DepartmentBranchesTenantFilter} (P-1c) for the other
	 * two policies this schema actually needs.
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
