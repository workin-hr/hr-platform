package com.workin.legacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Filter;

/**
 * Test-only, minimal read mapping of {@code payslips} — the other
 * high-volume P-1b consumer PR 12.2's query-plan verification (D-2)
 * specifically names. See {@link AttendanceProbeRow}'s javadoc for why
 * this is test-scoped and deliberately minimal.
 */
@Entity
@Table(name = "payslips")
@Filter(name = EmployeeDerivedTenantFilter.NAME, condition = EmployeeDerivedTenantFilter.CONDITION)
class PayslipProbeRow {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "employee_id", nullable = false)
	private Long employeeId;

	protected PayslipProbeRow() {
	}

	Long getId() {
		return id;
	}

	Long getEmployeeId() {
		return employeeId;
	}

}
