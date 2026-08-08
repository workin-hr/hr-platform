package com.workin.backend.schedule;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-employee per-date schedule row (V33). One row per
 * (employee, date) -- the DB UNIQUE constraint is the concurrency
 * backstop behind the service's read-then-write upsert.
 */
@Entity
@Table(name = "employee_schedules")
public class EmployeeSchedule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "employee_id", nullable = false)
	private Long employeeId;

	@Column(name = "schedule_date", nullable = false)
	private LocalDate scheduleDate;

	@Column
	private String name;

	@Column(name = "start_time")
	private LocalTime startTime;

	@Column(name = "end_time")
	private LocalTime endTime;

	@Column(name = "exception_note")
	private String exceptionNote;

	protected EmployeeSchedule() {
	}

	public EmployeeSchedule(Long companyId, Long employeeId, LocalDate scheduleDate) {
		this.companyId = companyId;
		this.employeeId = employeeId;
		this.scheduleDate = scheduleDate;
	}

	/** Legacy ON DUPLICATE KEY UPDATE overwrites all four columns. */
	public void snapshot(String name, LocalTime startTime, LocalTime endTime, String exceptionNote) {
		this.name = name;
		this.startTime = startTime;
		this.endTime = endTime;
		this.exceptionNote = exceptionNote;
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

	public LocalDate getScheduleDate() {
		return scheduleDate;
	}

	public String getName() {
		return name;
	}

	public LocalTime getStartTime() {
		return startTime;
	}

	public LocalTime getEndTime() {
		return endTime;
	}

	public String getExceptionNote() {
		return exceptionNote;
	}

}
