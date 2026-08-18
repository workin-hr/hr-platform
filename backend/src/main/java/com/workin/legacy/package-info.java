/**
 * Phase 1's legacy MySQL persistence adapter (ADR-0011).
 *
 * <p>Deliberately outside {@code com.workin.backend}, the application's
 * component-scan root, so these entities are invisible to the running
 * PostgreSQL context until a MySQL profile scans them explicitly. See
 * {@code LegacyAdapterIsolationTest} for why that placement is
 * load-bearing rather than organisational.
 *
 * <p>The tenant filter is declared here rather than on one entity
 * because it applies to every tenant-owned legacy entity, and a shared
 * declaration is what lets the architecture guard check that each one
 * carries it.
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
package com.workin.legacy;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
