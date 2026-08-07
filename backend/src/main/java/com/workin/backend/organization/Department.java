package com.workin.backend.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Org unit (V29). manager_id is data only -- no authorization
 * behavior attaches to it until F-16's manager-scoping decision. Its
 * branch set lives in department_branches, owned by this aggregate
 * and managed through DepartmentService's replace-set semantics.
 */
@Entity
@Table(name = "departments")
public class Department {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(nullable = false)
	private String name;

	@Column(name = "manager_id")
	private Long managerId;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	protected Department() {
	}

	public Department(Long companyId) {
		this.companyId = companyId;
	}

	public void apply(String name, Long managerId, Boolean isActive) {
		this.name = name;
		this.managerId = managerId;
		this.active = isActive == null || isActive;
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

	public boolean isActive() {
		return active;
	}

}
