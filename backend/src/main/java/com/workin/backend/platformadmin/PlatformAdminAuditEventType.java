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
	COMPANY_UNSUSPENDED,

	// --- platform content the mobile and desktop clients read but cannot
	// write: dial codes, FAQs, banners, broadcast notifications (ADR-0016).
	// One triple for all of them, with the table in the audit row's target
	// type, rather than a pair of constants per table -- the interesting
	// question of any such row is which record changed, and the target
	// already answers it.
	//
	// Deliberately NOT behind step-up, unlike the company actions above.
	// Step-up exists for irreversible lifecycle changes to a tenant; a TOTP
	// prompt per FAQ edit would make the page unusable and push an operator
	// back to editing MySQL by hand, which is the outcome with no audit trail
	// at all. They stay behind the surface flag, a bound second factor on the
	// session, and this record.
	CONTENT_CREATED,
	CONTENT_UPDATED,
	CONTENT_DELETED
}
