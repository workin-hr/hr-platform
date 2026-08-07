package com.workin.backend.payroll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.workin.backend.tenancy.TenantRole;

/**
 * Deliberately minimal -- V8__create_employees.sql's own comment: just
 * enough for real FK integrity and RLS company-scoping to unblock the
 * payroll group. The full employee-profile module (self-service
 * endpoints, join-request workflow, every other legacy field) is a
 * separate, later migration wave -- do not add fields here beyond what
 * V8/V15 actually have.
 *
 * <p>{@code identityId} (V15) links this row to the unified
 * identities/tenant_memberships auth system -- resolving "which
 * employees row is the caller" for EMPLOYEE-role self-scoping goes
 * through this column, never through employees' own phone/password
 * columns (those are carried from the legacy schema shape but are not
 * used for authentication in the new system).
 */
@Entity
@Table(name = "employees")
public class Employee {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "identity_id")
	private Long identityId;

	@Column(name = "first_name", nullable = false)
	private String firstName;

	@Column(name = "last_name", nullable = false)
	private String lastName = "";

	@Column
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TenantRole role = TenantRole.EMPLOYEE;

	@Column(nullable = false)
	private boolean active = true;

	protected Employee() {
	}

	public Employee(Long companyId, String firstName, String lastName, TenantRole role) {
		this.companyId = companyId;
		this.firstName = firstName;
		this.lastName = lastName;
		this.role = role;
	}

	public Long getId() {
		return id;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public Long getIdentityId() {
		return identityId;
	}

	public void setIdentityId(Long identityId) {
		this.identityId = identityId;
	}

	public String getFirstName() {
		return firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public TenantRole getRole() {
		return role;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

}
