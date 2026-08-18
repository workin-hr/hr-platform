package com.workin.legacy.attendance;

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

/**
 * The legacy {@code exception_types} row (Wave 12.1, item 12 row 7):
 * {@code id}, {@code company_id}, {@code name}, {@code is_active}, plus
 * the two audit columns Java never writes (D-3). Direct {@code
 * company_id} -- P-1a tenancy, the same {@code @Filter} shape {@link
 * com.workin.legacy.employees.LegacyEmployee} already carries.
 */
@Entity
@Table(name = "exception_types")
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
public class LegacyExceptionType {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "name", nullable = false, length = 128)
	private String name;

	@Column(name = "is_active", nullable = false)
	private Integer isActive;

	/** Database-maintained (D-3) -- never written by Java. */
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	/** {@code ON UPDATE current_timestamp()} -- database-maintained (D-3). */
	@Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
	private Instant updatedAt;

	protected LegacyExceptionType() {
	}

	public LegacyExceptionType(Long companyId, String name, boolean active) {
		this.companyId = companyId;
		this.name = name;
		this.isActive = LegacyValues.fromBoolean(active);
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

	public boolean active() {
		return LegacyValues.toBoolean(isActive);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setActive(boolean active) {
		this.isActive = LegacyValues.fromBoolean(active);
	}

}
