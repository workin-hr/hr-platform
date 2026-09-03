package com.workin.backend.platformadmin.mfa;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts and decrypts TOTP seeds at the application boundary (ADR-0015
 * prerequisite 1, "seed custody").
 *
 * <p>The seed is the one credential in this system that must be stored in
 * recoverable form -- verifying a code requires it -- so database access alone
 * must not be enough to mint codes. The key comes from configuration, which
 * means the deployment's secret store: a key sitting in the same database as
 * the ciphertext protects nothing.
 *
 * <p>AES-256-GCM, with the administrator's id as additional authenticated data.
 * That binding is the point of using AEAD here rather than plain encryption:
 * without it, a row's ciphertext could be copied onto another administrator's
 * row and would decrypt perfectly, letting an attacker with write access bind
 * their own authenticator to somebody else's account. With it, the decryption
 * fails.
 *
 * <p>Every ciphertext records the key version that produced it, so a rotation
 * has a defined path: add the new key, re-encrypt rows on next use or in a
 * batch, and retire the old version when no row references it. Without the
 * version, rotation means "decrypt everything with the old key at once or lose
 * the seeds".
 */
@Component
public class TotpSeedCipher {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private static final int NONCE_BYTES = 12;

	private static final int TAG_BITS = 128;

	private static final SecureRandom RANDOM = new SecureRandom();

	private final SecretKeySpec key;
	private final int keyVersion;

	public TotpSeedCipher(
			@Value("${app.platform-admin.mfa.encryption-key:}") String base64Key,
			@Value("${app.platform-admin.mfa.encryption-key-version:1}") int keyVersion) {
		this.key = base64Key.isBlank() ? null : new SecretKeySpec(decodeKey(base64Key), "AES");
		this.keyVersion = keyVersion;
	}

	/**
	 * Whether a key is configured at all.
	 *
	 * <p>Unset is not a failure at startup, because most deployments of this
	 * application never touch the platform-admin surface -- the same reasoning
	 * that leaves the WhatsApp gateway optional. It <em>is</em> a failure at the
	 * point of use: enrolment and verification refuse rather than silently
	 * storing a seed in the clear.
	 */
	public boolean isConfigured() {
		return this.key != null;
	}

	public int keyVersion() {
		return this.keyVersion;
	}

	public Encrypted encrypt(byte[] seed, long platformAdminId) {
		requireKey();
		try {
			byte[] nonce = new byte[NONCE_BYTES];
			RANDOM.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(aad(platformAdminId));
			return new Encrypted(cipher.doFinal(seed), nonce, this.keyVersion);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Unable to encrypt a TOTP seed", ex);
		}
	}

	public byte[] decrypt(Encrypted encrypted, long platformAdminId) {
		requireKey();
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, this.key,
					new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
			cipher.updateAAD(aad(platformAdminId));
			return cipher.doFinal(encrypted.ciphertext());
		}
		catch (GeneralSecurityException ex) {
			// Includes the AAD mismatch: a seed moved to another administrator's
			// row, or a tampered ciphertext. Both are the same answer -- no seed.
			throw new IllegalStateException("Unable to decrypt a TOTP seed", ex);
		}
	}

	private void requireKey() {
		if (this.key == null) {
			throw new IllegalStateException(
					"app.platform-admin.mfa.encryption-key is not configured; refusing to handle a TOTP seed");
		}
	}

	private static byte[] aad(long platformAdminId) {
		return java.nio.ByteBuffer.allocate(Long.BYTES).putLong(platformAdminId).array();
	}

	private static byte[] decodeKey(String base64Key) {
		byte[] decoded = Base64.getDecoder().decode(base64Key.strip());
		if (decoded.length != 32) {
			throw new IllegalStateException(
					"app.platform-admin.mfa.encryption-key must decode to 32 bytes (AES-256), got " + decoded.length);
		}
		return decoded;
	}

	/** Ciphertext, its nonce, and the key version that produced it. */
	public record Encrypted(byte[] ciphertext, byte[] nonce, int keyVersion) {
	}

}
