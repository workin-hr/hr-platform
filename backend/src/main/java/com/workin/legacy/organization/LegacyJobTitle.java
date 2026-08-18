package com.workin.legacy.organization;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Filter;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.TenantFilter;

/** The legacy {@code job_titles} row (Wave 12.3c), under direct-company P-1a tenancy. */
@Entity
@Table(name = "job_titles")
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
public class LegacyJobTitle {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	/** Schema-nullable for old data, but create/update require a positive active department. */
	@Column(name = "department_id")
	private Long departmentId;

	@Column(name = "name", nullable = false, length = 255)
	private String name;

	@Column(name = "work_hours", nullable = false, precision = 5, scale = 2)
	private BigDecimal workHours;

	@Column(name = "is_active", nullable = false)
	private Integer isActive;

	/** Database-maintained (D-3); Phase 1 never synthesizes or updates it. */
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	protected LegacyJobTitle() {
	}

	public LegacyJobTitle(Long companyId, Long departmentId, String name, BigDecimal workHours) {
		this.companyId = companyId;
		this.departmentId = departmentId;
		this.name = name;
		this.workHours = workHours;
		this.isActive = LegacyValues.fromBoolean(true);
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

	public boolean active() {
		return LegacyValues.toBoolean(isActive);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setDepartmentId(Long departmentId) {
		this.departmentId = departmentId;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setWorkHours(BigDecimal workHours) {
		this.workHours = workHours;
	}

	public void setActive(boolean active) {
		this.isActive = LegacyValues.fromBoolean(active);
	}

}
