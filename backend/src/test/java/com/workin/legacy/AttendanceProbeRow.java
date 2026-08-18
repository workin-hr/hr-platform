package com.workin.legacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Filter;

/**
 * Test-only, minimal read mapping of {@code attendance} — proves P-1b
 * (employee-derived tenancy) against a real employee-derived table,
 * before the real {@code attendance} adapter/module exists (out of PR
 * 12.2's scope: "no attendance/payroll/business endpoint implementation
 * yet"). Lives under {@code src/test}, mirroring {@code
 * LegacyIsolationProbeController}'s precedent exactly, so it never ships
 * in the application jar and cannot be mistaken for the real module.
 *
 * <p>Deliberately maps only {@code id} and {@code employee_id} — enough
 * to prove the filter, nothing that would make this look like the start
 * of a real adapter. Rows are seeded and read via raw JDBC/JPQL in
 * fixtures, never written through this entity.
 */
@Entity
@Table(name = "attendance")
@Filter(name = EmployeeDerivedTenantFilter.NAME, condition = EmployeeDerivedTenantFilter.CONDITION)
class AttendanceProbeRow {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "employee_id", nullable = false)
	private Long employeeId;

	protected AttendanceProbeRow() {
	}

	Long getId() {
		return id;
	}

	Long getEmployeeId() {
		return employeeId;
	}

}
