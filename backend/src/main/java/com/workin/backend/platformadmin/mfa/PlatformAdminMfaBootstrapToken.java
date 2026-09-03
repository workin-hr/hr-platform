package com.workin.backend.platformadmin.mfa;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * D-152's operator-assisted enrolment token.
 *
 * <p>It exists to close the window in which a password alone is sufficient: the
 * population is bootstrap-provisioned with no self-registration, so whoever
 * reaches the login first with a stolen password would otherwise bind the second
 * factor to their own device and lock the real administrator out.
 */
@Entity
@Table(name = "platform_admin_mfa_bootstrap_tokens")
public class PlatformAdminMfaBootstrapToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "platform_admin_id", nullable = false)
	private Long platformAdminId;

	@Column(name = "token_hash", nullable = false, unique = true)
	private String tokenHash;

	@Column(name = "issued_at", nullable = false)
	private Instant issuedAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	protected PlatformAdminMfaBootstrapToken() {
	}

	public PlatformAdminMfaBootstrapToken(Long platformAdminId, String tokenHash,
			Instant issuedAt, Instant expiresAt) {
		this.platformAdminId = platformAdminId;
		this.tokenHash = tokenHash;
		this.issuedAt = issuedAt;
		this.expiresAt = expiresAt;
	}

	public Long getId() {
		return this.id;
	}

	public Long getPlatformAdminId() {
		return this.platformAdminId;
	}

	public Instant getExpiresAt() {
		return this.expiresAt;
	}

	public Instant getUsedAt() {
		return this.usedAt;
	}

	public Instant getRevokedAt() {
		return this.revokedAt;
	}

	/** Live means: not spent, not revoked, not expired. All three, at this instant. */
	public boolean isLiveAt(Instant now) {
		return this.usedAt == null && this.revokedAt == null && now.isBefore(this.expiresAt);
	}

	public void markUsed(Instant at) {
		this.usedAt = at;
	}

	public void markRevoked(Instant at) {
		this.revokedAt = at;
	}

}
