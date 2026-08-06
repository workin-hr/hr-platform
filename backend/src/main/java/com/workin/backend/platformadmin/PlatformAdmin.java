package com.workin.backend.platformadmin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An individual platform administrator -- docs/migration/consolidated-task-matrix.md
 * F-26, replacing hr-legacy's single shared admin password
 * (dashboard/includes/auth.php's doAdminLogin(), hr-legacy#11).
 * Deliberately its own table, not a row in identities/tenant_memberships
 * -- the platform domain and tenant domain are structurally separate
 * (docs/architecture/authorization-model.md).
 */
@Entity
@Table(name = "platform_admins")
public class PlatformAdmin {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String phone;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(nullable = false)
	private boolean active = true;

	protected PlatformAdmin() {
	}

	public PlatformAdmin(String phone, String passwordHash) {
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
