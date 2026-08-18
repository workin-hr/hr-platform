package com.workin.legacy.authorization;

/**
 * The seventeen {@code hr_permissions.can_*} columns, one constant per
 * column (punch-list item #11, D-044).
 *
 * <p>Mirrors {@code hr-legacy/apis/config/columns.php}'s
 * {@code Column::CAN_*} constants and {@code hr_permission_keys()} by
 * name, not {@code docs/adr/ADR-0010-authorization-model.md} §7's
 * canonical-permission-key mapping -- D-044 reserves that redesign for
 * Phase 3. There is deliberately no mapping from a key here to more than
 * one legacy column, and no attempt to collapse or rename any of the
 * seventeen: this is a one-to-one port of the raw schema.
 */
public enum LegacyHrPermissionKey {
	CAN_DASHBOARD,
	CAN_RECENT_ACTIVITIES,
	CAN_BRANCHES,
	CAN_DEPARTMENTS,
	CAN_JOB_TITLES,
	CAN_SHIFTS,
	CAN_LEAVE_BALANCES,
	CAN_ASSETS,
	CAN_ADVANCES,
	CAN_WORKFORCE_PLANNING,
	CAN_SALARY_CALCULATOR,
	CAN_COMPANY_SETTINGS,
	CAN_EMPLOYEES,
	CAN_ATTENDANCE,
	CAN_REQUESTS,
	CAN_PAYROLL,
	CAN_PENALTIES
}
