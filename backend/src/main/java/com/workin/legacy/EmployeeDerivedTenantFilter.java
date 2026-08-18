package com.workin.legacy;

/**
 * Names for Phase 1's <b>employee-derived</b> tenancy policy (P-1b) --
 * the second of three named policies (see {@link TenantFilter}'s
 * corrected javadoc for why there are three, not one; F-1 in the Item
 * 12 specification for the discovery).
 *
 * <p>Nine of Item 12's 21 tables carry no {@code company_id} column at
 * all, but reach tenant identity by the same one-hop path: {@code
 * employee_id → employees.company_id}. `advances`, `attendance`,
 * `employee_schedules`, `employee_shift_assignments`, `leave_balance`,
 * `payslips`, `penalties`, `requests`, `salary_contracts`.
 *
 * <p>A genuinely distinct {@code @FilterDef} from {@link TenantFilter},
 * not a parameterisation of it (owner decision, 2026-08-18, D-2): "a
 * distinct policy... enforced centrally by the filter rather than by
 * tenant joins duplicated across repository methods", preserving
 * ADR-0012's one-enforcement-point model. Declared in {@code
 * package-info.java} alongside {@link TenantFilter#NAME} and {@link
 * DepartmentBranchesTenantFilter#NAME}, and applied to each
 * employee-derived legacy entity with {@code @Filter(name =
 * EmployeeDerivedTenantFilter.NAME, condition =
 * EmployeeDerivedTenantFilter.CONDITION)}.
 *
 * <p>Index/query-plan verification for this policy's two highest-volume
 * consumers ({@code attendance}, {@code payslips}) is a merge gate for
 * PR 12.2, not later debt -- see {@code TenantFilterQueryPlanTest} and
 * the PR 12.2 decision-log entry for the actual {@code EXPLAIN}
 * evidence obtained.
 */
public final class EmployeeDerivedTenantFilter {

	/** Hibernate filter name. */
	public static final String NAME = "legacyEmployeeDerivedTenantFilter";

	/** The single parameter: the authenticated company id. */
	public static final String COMPANY_ID_PARAMETER = "companyId";

	/**
	 * The predicate applied to every employee-derived tenant-owned
	 * legacy entity (P-1b).
	 *
	 * <p>A non-correlated {@code IN} subquery against {@code employees}
	 * -- it does not reference the outer (filtered) table at all, which
	 * is exactly the shape MariaDB's semi-join optimizations (enabled by
	 * default) target. Verified, not assumed: see the {@code EXPLAIN}
	 * evidence in the PR 12.2 decision-log entry.
	 *
	 * <p><b>The inner {@code employees} reference is aliased ({@code e})
	 * deliberately, discovered the hard way.</b> Hibernate's {@code
	 * @Filter} condition templating auto-qualifies any bare,
	 * unqualified identifier in the condition string with the
	 * <em>outer</em> (filtered) entity's own SQL alias -- it is a
	 * syntax-level substitution, not a real SQL parser, so it does not
	 * know a bare {@code id}/{@code company_id} two words later is
	 * inside a different table's subquery. Without the alias here, the
	 * emitted SQL was observed to be literally {@code SELECT
	 * <outer_alias>.id FROM employees WHERE <outer_alias>.company_id =
	 * ?} -- a column that does not exist on the outer table, failing
	 * with {@code SQLGrammarException} against real MariaDB (caught by
	 * {@code DerivedTenancyPoliciesFailClosedTest} before this was ever
	 * relied on). Qualifying every reference inside the subquery ({@code
	 * e.id}, {@code e.company_id}) leaves no bare identifier for the
	 * templater to touch, which is Hibernate's own documented pattern
	 * for a filter condition containing a subquery.
	 */
	public static final String CONDITION =
			"employee_id IN (SELECT e.id FROM employees e WHERE e.company_id = :" + COMPANY_ID_PARAMETER + ")";

	/**
	 * The value bound when no tenant scope is established.
	 *
	 * <p>Same property as {@link TenantFilter#NO_TENANT}, proven for
	 * this policy's subquery shape rather than assumed from P-1a's
	 * direct-equality shape (they are not the same kind of predicate):
	 * {@code employees.company_id} is {@code int(10) UNSIGNED}, so no
	 * row ever matches a negative value, so the subquery returns an
	 * empty set, so {@code employee_id IN (<empty set>)} is unconditionally
	 * {@code FALSE} -- zero rows, never every tenant's. {@code
	 * TenantFilterFailClosedTest} proves this against real MariaDB
	 * rather than by this reasoning alone.
	 */
	public static final long NO_TENANT = -1L;

	private EmployeeDerivedTenantFilter() {
	}

}
