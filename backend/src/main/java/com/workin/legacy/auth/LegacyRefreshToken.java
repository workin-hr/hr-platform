package com.workin.legacy.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One rotation link in a legacy refresh-token family, mirroring
 * {@code com.workin.backend.identity.RefreshToken}. The family
 * ({@code family_id}) is the session; rotation adds a new ACTIVE link
 * and retires the previous one as ROTATED. Presenting a retired link
 * again revokes the whole family, same as the PostgreSQL domain.
 *
 * <p>Not a {@code company_id}-mapped entity by design (see
 * {@code phase1_extensions.schema.sql}'s header), so
 * {@code TenantFilterCoverageTest} does not -- and should not -- require
 * a tenant filter here. {@code family_id} is a {@link String}, not a
 * {@code UUID} column type: the vendored legacy schema uses no UUID
 * columns anywhere, so a portable {@code VARCHAR(36)} avoids introducing
 * a Hibernate UUID-to-MySQL type mapping this codebase has no other use
 * for.
 */
@Entity
@Table(name = "legacy_refresh_tokens")
public class LegacyRefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "employee_id", nullable = false)
	private Long employeeId;

	@Column(name = "family_id", nullable = false, length = 36)
	private String familyId;

	@Column(name = "token_hash", nullable = false, unique = true, length = 64)
	private String tokenHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private LegacyRefreshTokenStatus status = LegacyRefreshTokenStatus.ACTIVE;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	protected LegacyRefreshToken() {
	}

	public LegacyRefreshToken(Long employeeId, String familyId, String tokenHash, Instant expiresAt) {
		this.employeeId = employeeId;
		this.familyId = familyId;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
	}

	public Long getId() {
		return id;
	}

	public Long getEmployeeId() {
		return employeeId;
	}

	public String getFamilyId() {
		return familyId;
	}

	public LegacyRefreshTokenStatus getStatus() {
		return status;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

}
