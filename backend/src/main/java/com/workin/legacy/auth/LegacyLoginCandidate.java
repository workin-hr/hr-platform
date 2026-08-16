package com.workin.legacy.auth;

/**
 * One employee row a phone resolves to, reduced to the fields legacy's
 * login decision actually reads.
 *
 * <p>A projection rather than the entity: the decision is pure and
 * testable only if it cannot reach the database, and the fields it needs
 * span two legacy tables ({@code employees} plus its company's
 * {@code status}), which is exactly the join legacy's own query does
 * (`login_employee.php:18-48`).
 *
 * @param joinRequestStatus raw legacy enum text -- 'pending',
 *        'accepted', 'rejected'
 * @param companyStatus raw legacy enum text -- 'active', 'pending',
 *        'rejected', 'suspended'
 * @param passwordHash may be null or empty; legacy requires both to be
 *        false before it will even attempt a verify
 */
public record LegacyLoginCandidate(
		long employeeId,
		long companyId,
		String role,
		String joinRequestStatus,
		boolean employeeActive,
		String companyStatus,
		String passwordHash) {

	static final String ACCEPTED = "accepted";
	static final String PENDING = "pending";
	static final String COMPANY_ACTIVE = "active";

	boolean isAccepted() {
		return ACCEPTED.equals(joinRequestStatus);
	}

	boolean isPending() {
		return PENDING.equals(joinRequestStatus);
	}

	boolean isCompanyActive() {
		return COMPANY_ACTIVE.equals(companyStatus);
	}

	/** Legacy's `$login_ready` predicate, line 68-70. */
	boolean isLoginReady() {
		return isAccepted() && employeeActive && isCompanyActive();
	}

	boolean hasUsableHash() {
		return passwordHash != null && !passwordHash.isEmpty();
	}

}
