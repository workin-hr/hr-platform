package com.workin.backend.schedule;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Date-effective shift assignment (V33). Append-only by design: legacy
 * never updates or deletes an assignment row, only inserts new ones --
 * so no mutators and no repository delete/update methods exist.
 */
@Entity
@Table(name = "employee_shift_assignments")
public class EmployeeShiftAssignment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "employee_id", nullable = false)
	private Long employeeId;

	@Column(name = "shift_id", nullable = false)
	private Long shiftId;

	@Column(name = "effective_from", nullable = false)
	private LocalDate effectiveFrom;

	protected EmployeeShiftAssignment() {
	}

	public EmployeeShiftAssignment(Long companyId, Long employeeId, Long shiftId, LocalDate effectiveFrom) {
		this.companyId = companyId;
		this.employeeId = employeeId;
		this.shiftId = shiftId;
		this.effectiveFrom = effectiveFrom;
	}

	public Long getId() {
		return id;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public Long getEmployeeId() {
		return employeeId;
	}

	public Long getShiftId() {
		return shiftId;
	}

	public LocalDate getEffectiveFrom() {
		return effectiveFrom;
	}

}
