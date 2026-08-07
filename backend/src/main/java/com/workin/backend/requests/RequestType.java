package com.workin.backend.requests;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Company-defined request category (V25) with the three legacy
 * behavior toggles. exception_type_id is only ever set alongside
 * add_attendance_exception (legacy nulls it otherwise);
 * counts_as_paid_leave is stored but has no consumer yet (payroll
 * reads manual AttendanceInput until the derivation slice).
 */
@Entity
@Table(name = "request_types")
public class RequestType {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(nullable = false)
	private String name;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	@Column(name = "deduct_balance", nullable = false)
	private boolean deductBalance = false;

	@Column(name = "counts_as_paid_leave", nullable = false)
	private boolean countsAsPaidLeave = true;

	@Column(name = "add_attendance_exception", nullable = false)
	private boolean addAttendanceException = false;

	@Column(name = "exception_type_id")
	private Long exceptionTypeId;

	protected RequestType() {
	}

	public RequestType(Long companyId, String name, boolean active, boolean deductBalance,
			boolean countsAsPaidLeave, boolean addAttendanceException, Long exceptionTypeId) {
		this.companyId = companyId;
		this.name = name;
		this.active = active;
		this.deductBalance = deductBalance;
		this.countsAsPaidLeave = countsAsPaidLeave;
		this.addAttendanceException = addAttendanceException;
		this.exceptionTypeId = exceptionTypeId;
	}

	public Long getId() {
		return id;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public String getName() {
		return name;
	}

	public boolean isActive() {
		return active;
	}

	public boolean isDeductBalance() {
		return deductBalance;
	}

	public boolean isCountsAsPaidLeave() {
		return countsAsPaidLeave;
	}

	public boolean isAddAttendanceException() {
		return addAttendanceException;
	}

	public Long getExceptionTypeId() {
		return exceptionTypeId;
	}

}
