package com.workin.backend.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A global principal, independent of any tenant --
 * docs/adr/ADR-0010-authorization-model.md Dimension 1: roles,
 * permissions, and resource scopes belong to the membership, not
 * globally to the identity. This entity intentionally carries no
 * company_id, no role.
 */
@Entity
@Table(name = "identities")
public class Identity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String phone;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(nullable = false)
	private boolean active = true;

	protected Identity() {
	}

	public Identity(String phone, String passwordHash) {
		this.phone = phone;
		this.passwordHash = passwordHash;
	}

	public Long getId() {
		return id;
	}

	public String getPhone() {
		return phone;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public boolean isActive() {
		return active;
	}

}
