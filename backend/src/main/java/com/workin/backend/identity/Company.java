package com.workin.backend.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "companies")
public class Company {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, unique = true)
	private String phone;

	@Column(nullable = false)
	private boolean active = true;

	/**
	 * The lifecycle state legacy actually models (D-035): pending, active,
	 * rejected, suspended -- not the boolean above, which predates it and is
	 * kept because other code still reads it.
	 *
	 * <p>Mapped now because the platform-admin surface acts on it. The column
	 * has existed since {@code common/V41}.
	 */
	@Column(nullable = false, length = 16)
	private String status = "active";

	/**
	 * Why the company was rejected, when it was. Legacy has always had this
	 * column; PostgreSQL gained it in {@code common/V52} so the same
	 * administrative action records the same thing on both databases.
	 */
	@Column(name = "rejection_reason")
	private String rejectionReason;

	public String getStatus() {
		return this.status;
	}

	public String getRejectionReason() {
		return this.rejectionReason;
	}

	public void setRejectionReason(String rejectionReason) {
		this.rejectionReason = rejectionReason;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	protected Company() {
	}

	public Company(String name, String phone) {
		this.name = name;
		this.phone = phone;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getPhone() {
		return phone;
	}

	public boolean isActive() {
		return active;
	}

}
