package com.workin.backend.platformadmin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication throttling for the platform-admin surface (ADR-0015
 * prerequisite 3).
 *
 * <p>Two properties matter more than the numbers:
 *
 * <ul>
 * <li><b>Shared and restart-surviving.</b> The budget lives in the database, so
 * spreading attempts across workers or waiting for a deploy does not reset it.
 * An in-memory counter would be defeated by either.</li>
 * <li><b>A miss costs the same as a hit.</b> Attempts against an identifier
 * that is not an administrator consume the same budget. Without that, the
 * unknown-identifier path is an unlimited oracle, and the throttle only ever
 * protects accounts an attacker has already identified.</li>
 * </ul>
 *
 * <p>The window and budget are legacy's floor -- 8 attempts per 15 minutes --
 * not a ceiling. ADR-0015 calls that "the floor", so they are named constants
 * here rather than tuneable configuration: a property that can widen the budget
 * is a property that can remove it.
 *
 * <p>Recording is {@code REQUIRES_NEW} for the same reason the audit write is:
 * the caller throws immediately after a failed attempt, and a failure the
 * rollback erases is a failure that never counted.
 */
@Service
public class PlatformAdminLoginThrottle {

	/** Legacy's floor (dashboard/includes/auth.php): 8 attempts per 15 minutes. */
	public static final int MAX_ATTEMPTS = 8;

	public static final Duration WINDOW = Duration.ofMinutes(15);

	private final PlatformAdminLoginAttemptRepository attemptRepository;
	private final Clock clock;

	@Autowired
	public PlatformAdminLoginThrottle(PlatformAdminLoginAttemptRepository attemptRepository) {
		this(attemptRepository, Clock.systemUTC());
	}

	/** Package-private, so a test can advance the window without waiting it out. */
	PlatformAdminLoginThrottle(PlatformAdminLoginAttemptRepository attemptRepository, Clock clock) {
		this.attemptRepository = attemptRepository;
		this.clock = clock;
	}

	/** Whether this identifier has spent its budget for the current window. */
	@Transactional(readOnly = true)
	public boolean isExhausted(String identifier) {
		return this.attemptRepository.countByIdentifierHashAndAttemptedAtAfter(
				hash(identifier), this.clock.instant().minus(WINDOW)) >= MAX_ATTEMPTS;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordFailure(String identifier) {
		this.attemptRepository.save(
				new PlatformAdminLoginAttempt(hash(identifier), this.clock.instant()));
	}

	/**
	 * Clears the budget after a successful authentication.
	 *
	 * <p>Deliberate: the budget exists to bound guessing, and a caller who has
	 * proven the password is not guessing. The alternative -- leaving failures
	 * to age out -- locks an administrator out of their own account for the rest
	 * of the window after a few typos, which is how throttling gets turned off.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void clear(String identifier) {
		this.attemptRepository.deleteByIdentifierHash(hash(identifier));
	}

	/** Rows older than the window can never affect a decision. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int purgeExpired() {
		return this.attemptRepository.deleteOlderThan(this.clock.instant().minus(WINDOW));
	}

	/**
	 * The stored key. Never the raw identifier: an unauthenticated caller
	 * chooses it, so the column would otherwise be attacker-written text.
	 */
	private static String hash(String identifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(identifier.strip().toLowerCase().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 is mandated by the platform; its absence is not a
			// condition to degrade gracefully through.
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

}
