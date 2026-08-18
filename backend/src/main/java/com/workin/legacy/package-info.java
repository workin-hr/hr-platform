/**
 * Phase 1's legacy MySQL persistence adapter (ADR-0011).
 *
 * <p>Deliberately outside {@code com.workin.backend}, the application's
 * component-scan root, so these entities are invisible to the running
 * PostgreSQL context until a MySQL profile scans them explicitly. See
 * {@code LegacyAdapterIsolationTest} for why that placement is
 * load-bearing rather than organisational.
 *
 * <p>Three tenancy filters are declared here rather than on one entity
 * because each applies to a class of tenant-owned legacy entities, and a
 * shared declaration is what lets the architecture guard check that each
 * entity carries exactly one of them (P-4, {@code
 * TenantFilterCoverageTest}). {@link TenantFilter} (P-1a, direct {@code
 * company_id}), {@link EmployeeDerivedTenantFilter} (P-1b, the
 * nine-table {@code employee_id} one-hop) and {@link
 * DepartmentBranchesTenantFilter} (P-1c, {@code department_branches}
 * alone) are three genuinely distinct policies (owner decision,
 * 2026-08-18, D-2 -- "a distinct policy, not a parameterisation of
 * P-1a"), not one filter with three conditions -- see {@link
 * TenantFilter}'s corrected javadoc (U-1, F-1) for why a single
 * condition never covered every tenant-owned table.
 */
@FilterDef(
		name = TenantFilter.NAME,
		parameters = @ParamDef(name = TenantFilter.COMPANY_ID_PARAMETER, type = Long.class),
		// Applied only when explicitly enabled. Hibernate's default for
		// an un-enabled filter is to apply nothing at all -- which is
		// why TenantScope raises rather than returning empty, and why
		// TenantFilterActivator is the only sanctioned way to turn this
		// on (ADR-0012 / D-041).
		autoEnabled = false)
@FilterDef(
		name = EmployeeDerivedTenantFilter.NAME,
		parameters = @ParamDef(name = EmployeeDerivedTenantFilter.COMPANY_ID_PARAMETER, type = Long.class),
		autoEnabled = false)
@FilterDef(
		name = DepartmentBranchesTenantFilter.NAME,
		parameters = @ParamDef(name = DepartmentBranchesTenantFilter.COMPANY_ID_PARAMETER, type = Long.class),
		autoEnabled = false)
package com.workin.legacy;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
