package com.workin.backend.platformadmin;

public enum PlatformAdminAuditEventType {
	LOGIN,
	LOGIN_FAILED,
	LOGOUT,
	SESSION_REUSE_REVOKED,
	ALL_SESSIONS_REVOKED,

	// --- D-152's operator-assisted TOTP bootstrap (ADR-0015 prerequisites 1
	// and 10). The token is the thing that lets an account bind its second
	// factor, so its whole lifecycle is auditable, not just its use.
	MFA_BOOTSTRAP_TOKEN_ISSUED,
	MFA_BOOTSTRAP_TOKEN_USED,
	MFA_BOOTSTRAP_TOKEN_REVOKED,
	MFA_ENROLLED,
	MFA_RESET,

	// --- ADR-0015 prerequisite 2. The approval is minted and spent as separate
	// events: an approval that was minted and never consumed is a signal in its
	// own right.
	STEP_UP_APPROVED,

	// --- administrative actions on companies (ADR-0009 Option E). Declared
	// ahead of the operations themselves so the audit contract is settled
	// before the first one is written against it; each carries a structured
	// target rather than prose.
	COMPANY_APPROVED,
	COMPANY_REJECTED,
	COMPANY_SUSPENDED,
	COMPANY_UNSUSPENDED
}
