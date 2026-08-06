package com.workin.backend.advances;

/**
 * No PAID state, deliberately: deduction application is the payroll
 * module's finalize side effect on {@code advances.remaining}
 * (docs/legacy/business-rule-extraction.md's batch-finalize rule), not
 * an advances-surface transition.
 */
public enum AdvanceStatus {
	PENDING,
	APPROVED,
	REJECTED
}
