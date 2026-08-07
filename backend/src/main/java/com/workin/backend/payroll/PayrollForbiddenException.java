package com.workin.backend.payroll;

/**
 * Raised when the caller's role is not permitted to call an endpoint
 * at all (e.g. an EMPLOYEE calling a COMPANY_ADMIN/HR-only batch
 * endpoint). Distinct from {@link PayrollNotFoundException}: this is
 * ordinary role-based access control, not a specific resource's
 * existence being hidden, so a real 403 is the correct response.
 */
public class PayrollForbiddenException extends RuntimeException {

	public PayrollForbiddenException(String message) {
		super(message);
	}

}
