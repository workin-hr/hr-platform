package com.workin.backend.platformadmin.mfa;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An administrator's TOTP factor. Its absence means the factor is unbound. */
@Entity
@Table(name = "platform_admin_mfa")
public class PlatformAdminMfa {

	@Id
	@Column(name = "platform_admin_id")
	private Long platformAdminId;

	@Column(name = "seed_ciphertext", nullable = false)
	private byte[] seedCiphertext;

	@Column(name = "seed_nonce", nullable = false)
	private byte[] seedNonce;

	@Column(name = "seed_key_version", nullable = false)
	private int seedKeyVersion;

	@Column(name = "enrolled_at", nullable = false)
	private Instant enrolledAt;

	@Column(name = "bound_at")
	private Instant boundAt;

	@Column(name = "last_accepted_time_step")
	private Long lastAcceptedTimeStep;

	protected PlatformAdminMfa() {
	}

	public PlatformAdminMfa(Long platformAdminId, TotpSeedCipher.Encrypted seed, Instant enrolledAt) {
		this.platformAdminId = platformAdminId;
		this.seedCiphertext = seed.ciphertext();
		this.seedNonce = seed.nonce();
		this.seedKeyVersion = seed.keyVersion();
		this.enrolledAt = enrolledAt;
	}

	public TotpSeedCipher.Encrypted seed() {
		return new TotpSeedCipher.Encrypted(this.seedCiphertext, this.seedNonce, this.seedKeyVersion);
	}

	public Long getPlatformAdminId() {
		return this.platformAdminId;
	}

	public Instant getBoundAt() {
		return this.boundAt;
	}

	public boolean isBound() {
		return this.boundAt != null;
	}

	public Long getLastAcceptedTimeStep() {
		return this.lastAcceptedTimeStep;
	}

	/** Records a verified code, binding the factor the first time (D-152 step 5). */
	public void recordAcceptedCode(long timeStep, Instant at) {
		this.lastAcceptedTimeStep = timeStep;
		if (this.boundAt == null) {
			this.boundAt = at;
		}
	}

}
