package com.workin.backend.organization;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Per-company job title (V29); work_hours (default 8.00) is payroll's fallback input. */
@Entity
@Table(name = "job_titles")
public class JobTitle {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "department_id")
	private Long departmentId;

	@Column(nullable = false)
	private String name;

	@Column(name = "work_hours", nullable = false)
	private BigDecimal workHours = new BigDecimal("8.00");

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	protected JobTitle() {
	}

	public JobTitle(Long companyId) {
		this.companyId = companyId;
	}

	public void apply(UpsertJobTitleRequest request) {
		this.name = request.name();
		this.departmentId = request.departmentId();
		this.workHours = request.workHours() != null ? request.workHours() : new BigDecimal("8.00");
		this.active = request.isActive() == null || request.isActive();
	}

	public Long getId() {
		return id;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public Long getDepartmentId() {
		return departmentId;
	}

	public String getName() {
		return name;
	}

	public BigDecimal getWorkHours() {
		return workHours;
	}

	public boolean isActive() {
		return active;
	}

}
