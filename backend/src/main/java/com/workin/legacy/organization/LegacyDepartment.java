package com.workin.legacy.organization;

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

/** The legacy {@code departments} row (Wave 12.3b), under direct-company P-1a tenancy. */
@Entity
@Table(name = "departments")
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
public class LegacyDepartment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "name", nullable = false, length = 255)
	private String name;

	@Column(name = "manager_id")
	private Long managerId;

	@Column(name = "is_active", nullable = false)
	private Integer isActive;

	/** Database-maintained (D-3); Phase 1 never synthesizes or updates it. */
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	protected LegacyDepartment() {
	}

	public LegacyDepartment(Long companyId, String name, Long managerId) {
		this.companyId = companyId;
		this.name = name;
		this.managerId = managerId;
		this.isActive = LegacyValues.fromBoolean(true);
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

	public Long getManagerId() {
		return managerId;
	}

	public boolean active() {
		return LegacyValues.toBoolean(isActive);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setManagerId(Long managerId) {
		this.managerId = managerId;
	}

	public void setActive(boolean active) {
		this.isActive = LegacyValues.fromBoolean(active);
	}

}
