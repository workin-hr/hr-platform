package com.workin.backend.platformadmin;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One failed platform-admin authentication attempt (ADR-0015 prerequisite 3).
 *
 * <p>The identifier is a SHA-256 digest, not the phone: an unauthenticated
 * caller chooses this value, so storing it verbatim would let anyone write
 * arbitrary strings into a readable column. Counting is all this row is for.
 */
@Entity
@Table(name = "platform_admin_login_attempts")
public class PlatformAdminLoginAttempt {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "identifier_hash", nullable = false, length = 64)
	private String identifierHash;

	@Column(name = "attempted_at", nullable = false)
	private Instant attemptedAt;

	protected PlatformAdminLoginAttempt() {
	}

	public PlatformAdminLoginAttempt(String identifierHash, Instant attemptedAt) {
		this.identifierHash = identifierHash;
		this.attemptedAt = attemptedAt;
	}

	public Long getId() {
		return this.id;
	}

	public String getIdentifierHash() {
		return this.identifierHash;
	}

	public Instant getAttemptedAt() {
		return this.attemptedAt;
	}

}
