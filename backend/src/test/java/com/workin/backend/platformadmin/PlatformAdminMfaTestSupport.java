package com.workin.backend.platformadmin;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;

import com.workin.backend.platformadmin.mfa.PlatformAdminMfaService;
import com.workin.backend.platformadmin.mfa.Totp;

/**
 * Shared helpers for tests that authenticate against the bearer surface.
 *
 * <p>ADR-0015 prerequisite 8 made the second factor mandatory there, so a test
 * that used to post a phone and a password now has to enrol its fixture
 * administrator first. That is the point of the change and not something to
 * work around -- these helpers make doing it correctly the short path, rather
 * than tempting each test to weaken something.
 */
public final class PlatformAdminMfaTestSupport {

	private PlatformAdminMfaTestSupport() {
	}

	/** A fresh encryption key per run; never a literal, which a secret scanner cannot tell from a real one. */
	public static void registerEncryptionKey(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("app.platform-admin.mfa.encryption-key",
				() -> Base64.getEncoder().encodeToString(key));
	}

	/** Completes D-152's ceremony for an existing administrator and returns their seed. */
	public static String enrol(PlatformAdminMfaService mfaService, long platformAdminId) {
		String token = mfaService.issueBootstrapToken(platformAdminId, platformAdminId);
		String seed = mfaService.beginEnrolment(platformAdminId, token).orElseThrow();
		if (!mfaService.confirmEnrolment(platformAdminId, codeAt(seed, 0))) {
			throw new IllegalStateException("enrolment did not confirm");
		}
		return seed;
	}

	/**
	 * A code the administrator has not spent yet.
	 *
	 * <p>Enrolment consumes the current time step, and prerequisite 12 refuses a
	 * code at or below the last accepted one, so a login immediately afterwards
	 * needs the next step. It is inside the accepted skew -- this is the code the
	 * authenticator shows a moment later, not a loosened rule.
	 */
	public static String freshCode(String seed) {
		return codeAt(seed, 1);
	}

	/**
	 * Stands in for the next 30-second window passing.
	 *
	 * <p>Prerequisite 12 records the last accepted time step and refuses
	 * anything at or below it, and the accepted skew is one step, so exactly one
	 * code is usable per window. A test that legitimately needs to authenticate
	 * twice would otherwise have to sleep. Clearing the marker is what the
	 * passage of time does; it is not a relaxation of the rule, which has its
	 * own test.
	 */
	public static void allowAnotherCode(org.springframework.jdbc.core.JdbcTemplate jdbc, long platformAdminId) {
		jdbc.update("UPDATE platform_admin_mfa SET last_accepted_time_step = NULL "
				+ "WHERE platform_admin_id = ?", platformAdminId);
	}

	private static String codeAt(String base32Seed, long stepOffset) {
		return Totp.codeAt(fromBase32(base32Seed), Totp.timeStepAt(Instant.now()) + stepOffset);
	}

	private static byte[] fromBase32(String encoded) {
		final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int buffer = 0;
		int bitsLeft = 0;
		for (char c : encoded.toCharArray()) {
			buffer = (buffer << 5) | alphabet.indexOf(c);
			bitsLeft += 5;
			if (bitsLeft >= 8) {
				out.write((buffer >> (bitsLeft - 8)) & 0xFF);
				bitsLeft -= 8;
			}
		}
		return out.toByteArray();
	}

}
