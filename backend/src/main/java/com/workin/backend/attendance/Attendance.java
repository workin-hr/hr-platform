package com.workin.backend.attendance;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One attendance row is either real punches (check_in/check_out,
 * method, optional GPS) or a category-only exception day
 * (exception_type_id set, check_in at that day's UTC midnight,
 * everything else null) -- never both. V21's CHECK enforces the
 * DB-half; {@link AttendanceService} enforces the full shape rules,
 * so this entity only offers the two shape-consistent mutators.
 */
@Entity
@Table(name = "attendance")
public class Attendance {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "employee_id", nullable = false)
	private Long employeeId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "check_in", nullable = false)
	private Instant checkIn;

	@Column(name = "check_out")
	private Instant checkOut;

	@Column
	private String method;

	@Column
	private BigDecimal latitude;

	@Column
	private BigDecimal longitude;

	@Column(name = "exception_type_id")
	private Long exceptionTypeId;

	protected Attendance() {
	}

	public Attendance(Long employeeId, Long companyId) {
		this.employeeId = employeeId;
		this.companyId = companyId;
	}

	public void applyPunch(Instant checkIn, Instant checkOut, String method, BigDecimal latitude, BigDecimal longitude) {
		this.checkIn = checkIn;
		this.checkOut = checkOut;
		this.method = method;
		this.latitude = latitude;
		this.longitude = longitude;
		this.exceptionTypeId = null;
	}

	public void applyException(Instant midnightCheckIn, Long exceptionTypeId) {
		this.checkIn = midnightCheckIn;
		this.checkOut = null;
		this.method = null;
		this.latitude = null;
		this.longitude = null;
		this.exceptionTypeId = exceptionTypeId;
	}

	public Long getId() {
		return id;
	}

	public Long getEmployeeId() {
		return employeeId;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public Instant getCheckIn() {
		return checkIn;
	}

	public Instant getCheckOut() {
		return checkOut;
	}

	public String getMethod() {
		return method;
	}

	public BigDecimal getLatitude() {
		return latitude;
	}

	public BigDecimal getLongitude() {
		return longitude;
	}

	public Long getExceptionTypeId() {
		return exceptionTypeId;
	}

}
