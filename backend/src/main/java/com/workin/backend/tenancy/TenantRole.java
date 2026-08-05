package com.workin.backend.tenancy;

/**
 * The four fixed tenant role categories --
 * docs/adr/ADR-0010-authorization-model.md Dimension 1. Deliberately
 * fixed, not open-ended: business-level defaults, not the authorization
 * source of truth by themselves (see docs/architecture/authorization-model.md
 * §3 -- permissions and resource scopes are what actually gate access).
 */
public enum TenantRole {
	COMPANY_ADMIN,
	HR,
	MANAGER,
	EMPLOYEE
}
