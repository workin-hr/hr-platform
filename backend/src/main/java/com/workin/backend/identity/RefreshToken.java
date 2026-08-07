package com.workin.backend.identity;

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
 * One rotation link in a refresh-token family. The family
 * ({@code family_id}) is the session; rotation adds a new ACTIVE link
 * and retires the previous one as ROTATED. Presenting a retired link
 * again is the accepted design's compromise signal and revokes the
 * whole family (docs/security/authentication-remediation-design.md,
 * Target Design item 2).
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "identity_id", nullable = false)
	private Long identityId;

	@Column(name = "membership_id", nullable = false)
	private Long membershipId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "family_id", nullable = false)
	private UUID familyId;

	@Column(name = "token_hash", nullable = false, unique = true)
	private String tokenHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private RefreshTokenStatus status = RefreshTokenStatus.ACTIVE;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	protected RefreshToken() {
	}

	public RefreshToken(Long identityId, Long membershipId, Long companyId, UUID familyId, String tokenHash,
			Instant expiresAt) {
		this.identityId = identityId;
		this.membershipId = membershipId;
		this.companyId = companyId;
		this.familyId = familyId;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
	}

	public Long getId() {
		return id;
	}

	public Long getIdentityId() {
		return identityId;
	}

	public Long getMembershipId() {
		return membershipId;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public UUID getFamilyId() {
		return familyId;
	}

	public RefreshTokenStatus getStatus() {
		return status;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

}
