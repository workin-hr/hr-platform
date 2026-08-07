package com.workin.backend.payroll;

/**
 * Uniform "not found" for every resource lookup in this module that
 * turns out to not exist, belong to another tenant, or (for
 * EMPLOYEE-role self-service) not belong to the caller --
 * docs/architecture/authorization-model.md §8: access-denied responses
 * must not reveal whether an inaccessible cross-tenant resource
 * exists. Same convention as {@code TenantContextException} in
 * {@code com.workin.backend.tenancy}.
 */
public class PayrollNotFoundException extends RuntimeException {

	public PayrollNotFoundException(String message) {
		super(message);
	}

}
