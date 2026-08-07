package com.workin.backend.employees;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The employees table's admin-surface mapping. Deliberately does NOT
 * map the legacy-fidelity {@code password_hash} column: credentials
 * belong to identities and are reachable only through auth flows --
 * the structural root fix for hr-legacy#3 (cross-tenant password
 * takeover via employee edit was only possible because the legacy
 * employee-edit surface could write credentials at all). {@code role}
 * and {@code active} are read-only in this slice: lifecycle and role
 * mutation carry open product/escalation questions (hr-legacy#20,
 * authorization-model.md §8) and arrive with their own slices.
 */
@Entity
@Table(name = "employees")
public class Employee {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "first_name", nullable = false)
	private String firstName;

	@Column(name = "last_name", nullable = false)
	private String lastName = "";

	@Column(unique = true)
	private String phone;

	@Column(nullable = false)
	private String role = "EMPLOYEE";

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "branch_id")
	private Long branchId;

	@Column(name = "department_id")
	private Long departmentId;

	@Column(name = "job_title_id")
	private Long jobTitleId;

	protected Employee() {
	}

	public Employee(Long companyId, String firstName, String lastName, String phone) {
		this.companyId = companyId;
		this.firstName = firstName;
		this.lastName = lastName == null ? "" : lastName;
		this.phone = phone;
	}

	public Long getId() {
		return id;
	}

	public Long getCompanyId() {
		return companyId;
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

	public String getRole() {
		return role;
	}

	public boolean isActive() {
		return active;
	}

	public void rename(String firstName, String lastName) {
		this.firstName = firstName;
		this.lastName = lastName == null ? "" : lastName;
	}

	public Long getBranchId() {
		return branchId;
	}

	public Long getDepartmentId() {
		return departmentId;
	}

	public Long getJobTitleId() {
		return jobTitleId;
	}

	/** Lifecycle toggle -- deactivated employees are skipped by payroll calculate. */
	public void deactivate() {
		this.active = false;
	}

	public void activate() {
		this.active = true;
	}

	/** Org attribution (V29); nulls clear. Reference validation lives in EmployeeService. */
	public void place(Long branchId, Long departmentId, Long jobTitleId) {
		this.branchId = branchId;
		this.departmentId = departmentId;
		this.jobTitleId = jobTitleId;
	}

}
