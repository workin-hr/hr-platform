package com.workin.legacy.authorization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.workin.legacy.LegacyValues;

/**
 * The legacy {@code hr_permissions} row, mapped exactly as MySQL holds
 * it: seventeen {@code tinyint(1)} flags keyed by {@code employee_id}
 * (punch-list item #11, D-044).
 *
 * <p>Phase 1 treats the legacy schema as an external contract
 * (ADR-0011): this ports {@code hr-legacy/apis/helpers/hr_permissions.php}'s
 * raw-column check, not {@code docs/adr/ADR-0010-authorization-model.md}
 * §7's canonical-permission redesign, which stays Phase 3 target-schema
 * work (D-044).
 *
 * <h2>Why this carries no {@code @Filter}</h2>
 * <p>Unlike every other tenant-owned legacy entity in this package
 * ({@code LegacyEmployee}), {@code hr_permissions} has no
 * {@code company_id} column at all -- only {@code employee_id}, unique,
 * with a foreign key to {@code employees} (confirmed directly against
 * {@code mysql_workin.schema.sql}). {@code TenantFilterCoverageTest}'s
 * {@code isTenantOwned()} decides tenant ownership by the presence of a
 * mapped {@code company_id} column, so this entity is correctly not
 * flagged -- and adding {@code @Filter(TenantFilter.NAME, ...)} here
 * would be a genuine SQL error, not extra safety, since Hibernate would
 * append a {@code company_id = :companyId} predicate against a table
 * that has no such column.
 *
 * <p>Tenant safety instead comes from the caller: {@link LegacyHrPermissionEnforcer}
 * only ever looks up a row by the <em>authenticated</em> employee id
 * (the JWT's re-derivation-backed {@code identityId}, never a
 * request-supplied one), never an arbitrary one. That is the same
 * "self-scoped by construction, named explicitly rather than left
 * implicit" pattern {@code TenantFilterActivator#deactivateForPreTenantLookup()}
 * already documents for the pre-tenant phone lookup.
 */
@Entity
@Table(name = "hr_permissions")
public class LegacyHrPermissions {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "employee_id", nullable = false)
	private Long employeeId;

	@Column(name = "can_dashboard", nullable = false)
	private Integer canDashboard;

	@Column(name = "can_recent_activities", nullable = false)
	private Integer canRecentActivities;

	@Column(name = "can_branches", nullable = false)
	private Integer canBranches;

	@Column(name = "can_departments", nullable = false)
	private Integer canDepartments;

	@Column(name = "can_job_titles", nullable = false)
	private Integer canJobTitles;

	@Column(name = "can_shifts", nullable = false)
	private Integer canShifts;

	@Column(name = "can_leave_balances", nullable = false)
	private Integer canLeaveBalances;

	@Column(name = "can_assets", nullable = false)
	private Integer canAssets;

	@Column(name = "can_advances", nullable = false)
	private Integer canAdvances;

	@Column(name = "can_workforce_planning", nullable = false)
	private Integer canWorkforcePlanning;

	@Column(name = "can_salary_calculator", nullable = false)
	private Integer canSalaryCalculator;

	@Column(name = "can_company_settings", nullable = false)
	private Integer canCompanySettings;

	@Column(name = "can_employees", nullable = false)
	private Integer canEmployees;

	@Column(name = "can_attendance", nullable = false)
	private Integer canAttendance;

	@Column(name = "can_requests", nullable = false)
	private Integer canRequests;

	@Column(name = "can_payroll", nullable = false)
	private Integer canPayroll;

	@Column(name = "can_penalties", nullable = false)
	private Integer canPenalties;

	public Long getId() {
		return id;
	}

	public Long getEmployeeId() {
		return employeeId;
	}

	/**
	 * Reads a single flag by key. A {@code switch} over the enum rather
	 * than reflection or a map, so a key added to
	 * {@link LegacyHrPermissionKey} without a matching branch here fails
	 * the build instead of silently reading as denied.
	 */
	public boolean has(LegacyHrPermissionKey key) {
		Integer flag = switch (key) {
			case CAN_DASHBOARD -> canDashboard;
			case CAN_RECENT_ACTIVITIES -> canRecentActivities;
			case CAN_BRANCHES -> canBranches;
			case CAN_DEPARTMENTS -> canDepartments;
			case CAN_JOB_TITLES -> canJobTitles;
			case CAN_SHIFTS -> canShifts;
			case CAN_LEAVE_BALANCES -> canLeaveBalances;
			case CAN_ASSETS -> canAssets;
			case CAN_ADVANCES -> canAdvances;
			case CAN_WORKFORCE_PLANNING -> canWorkforcePlanning;
			case CAN_SALARY_CALCULATOR -> canSalaryCalculator;
			case CAN_COMPANY_SETTINGS -> canCompanySettings;
			case CAN_EMPLOYEES -> canEmployees;
			case CAN_ATTENDANCE -> canAttendance;
			case CAN_REQUESTS -> canRequests;
			case CAN_PAYROLL -> canPayroll;
			case CAN_PENALTIES -> canPenalties;
		};
		return LegacyValues.toBoolean(flag);
	}

}
