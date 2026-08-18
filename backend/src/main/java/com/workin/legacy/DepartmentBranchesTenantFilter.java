package com.workin.legacy;

/**
 * Names for Phase 1's <b>{@code department_branches}</b> tenancy policy
 * (P-1c) -- the third of three named policies (see {@link
 * TenantFilter}'s corrected javadoc; F-1 in the Item 12 specification).
 *
 * <p>Reaches tenancy via {@code department_id → departments.company_id},
 * not {@code employee_id} -- the one Item 12 table that differs from
 * {@link EmployeeDerivedTenantFilter}'s nine. Owner decision,
 * 2026-08-18: "identify it separately; do not force it through a
 * generic filter." Named for the one table it covers rather than
 * generically ("department-derived"), because it is not known to
 * generalise to any other table the way P-1b's one-hop path does --
 * `job_titles`/`departments` themselves carry a direct {@code
 * company_id} and are P-1a, not P-1c, even though they also reference
 * {@code department_id}/{@code company_id}.
 *
 * <p>Declared in {@code package-info.java} alongside {@link
 * TenantFilter#NAME} and {@link EmployeeDerivedTenantFilter#NAME}, and
 * applied with {@code @Filter(name = DepartmentBranchesTenantFilter.NAME,
 * condition = DepartmentBranchesTenantFilter.CONDITION)}.
 */
public final class DepartmentBranchesTenantFilter {

	/** Hibernate filter name. */
	public static final String NAME = "legacyDepartmentBranchesTenantFilter";

	/** The single parameter: the authenticated company id. */
	public static final String COMPANY_ID_PARAMETER = "companyId";

	/**
	 * The predicate applied to {@code department_branches}.
	 *
	 * <p>A non-correlated {@code IN} subquery against {@code
	 * departments}, the same shape as {@link
	 * EmployeeDerivedTenantFilter#CONDITION} one hop earlier in the
	 * chain -- {@code departments.company_id} is indexed
	 * (`fk_sections_company`) and {@code department_branches}'s
	 * composite primary key leads with {@code department_id}, so this
	 * reaches an index on both sides of the join without needing a new
	 * one (verified against the vendored schema, not added).
	 *
	 * <p>The inner {@code departments} reference is aliased ({@code d})
	 * for the same reason {@link EmployeeDerivedTenantFilter#CONDITION}
	 * aliases {@code employees} — see that constant's javadoc for the
	 * real {@code SQLGrammarException} this avoids.
	 */
	public static final String CONDITION =
			"department_id IN (SELECT d.id FROM departments d WHERE d.company_id = :" + COMPANY_ID_PARAMETER + ")";

	/**
	 * The value bound when no tenant scope is established. Same
	 * reasoning as {@link EmployeeDerivedTenantFilter#NO_TENANT}:
	 * {@code departments.company_id} is unsigned, so a negative value
	 * makes the subquery empty and the {@code IN} unconditionally
	 * {@code FALSE}. Proven against real MariaDB by {@code
	 * TenantFilterFailClosedTest}, not assumed.
	 */
	public static final long NO_TENANT = -1L;

	private DepartmentBranchesTenantFilter() {
	}

}
