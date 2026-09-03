package com.workin.backend.platformadmin;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Platform-domain twin of {@code identity.RefreshToken}, deliberately a
 * separate entity over a separate table: a platform session row can
 * never be replayed against the tenant domain because the lookup
 * tables are disjoint -- the same structural separation as the two JWT
 * issuers (docs/architecture/authorization-model.md §8).
 */
@Entity
@Table(name = "platform_admin_refresh_tokens")
public class PlatformAdminRefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "platform_admin_id", nullable = false)
	private Long platformAdminId;

	@Column(name = "family_id", nullable = false)
	private UUID familyId;

	@Column(name = "token_hash", nullable = false, unique = true)
	private String tokenHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PlatformAdminSessionStatus status = PlatformAdminSessionStatus.ACTIVE;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	/**
	 * When this family first authenticated. Copied forward on every rotation so
	 * the absolute cap cannot be reset by rotating, and cannot be lost if
	 * rotated rows are ever pruned (ADR-0015 prerequisite 4).
	 */
	@Column(name = "family_started_at", nullable = false)
	private Instant familyStartedAt;

	protected PlatformAdminRefreshToken() {
	}

	public PlatformAdminRefreshToken(Long platformAdminId, UUID familyId, String tokenHash,
			Instant expiresAt, Instant familyStartedAt) {
		this.platformAdminId = platformAdminId;
		this.familyId = familyId;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
		this.familyStartedAt = familyStartedAt;
	}

	public Instant getFamilyStartedAt() {
		return familyStartedAt;
	}

	public Long getId() {
		return id;
	}

	public Long getPlatformAdminId() {
		return platformAdminId;
	}

	public UUID getFamilyId() {
		return familyId;
	}

	public PlatformAdminSessionStatus getStatus() {
		return status;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

}
