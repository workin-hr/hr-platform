package com.workin.backend.platformadmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes login attempts that have aged out of the throttle window.
 *
 * <p>Without this the table grows without bound, and an <em>unauthenticated</em>
 * caller controls the growth: the throttle caps attempts per identifier, but an
 * attacker can vary the identifier freely and each distinct one leaves a row
 * behind. Rows older than the window can never change a decision, so keeping
 * them buys nothing and hands a stranger a disk-fill lever.
 *
 * <p>Every worker runs this. That is deliberate rather than sloppy: the delete
 * is idempotent and bounded by an index, so redundancy costs a cheap query,
 * whereas electing a single runner would need leader election this application
 * does not otherwise have.
 *
 * <p>This is the application's first scheduled work, so {@code @EnableScheduling}
 * is declared here, next to the only thing that uses it, rather than on the
 * application class where it would silently activate anything annotated later.
 */
@Configuration
@EnableScheduling
public class PlatformAdminLoginAttemptCleanup {

	private static final Logger log = LoggerFactory.getLogger(PlatformAdminLoginAttemptCleanup.class);

	private final PlatformAdminLoginThrottle throttle;

	public PlatformAdminLoginAttemptCleanup(PlatformAdminLoginThrottle throttle) {
		this.throttle = throttle;
	}

	/**
	 * Fixed delay, not a cron: the work is "keep the table from growing", which
	 * has no reason to happen at a wall-clock time, and a fixed delay cannot
	 * pile up if one run is slow.
	 */
	@Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
	public void purgeExpiredAttempts() {
		int deleted = this.throttle.purgeExpired();
		if (deleted > 0) {
			log.debug("Purged {} expired platform-admin login attempts", deleted);
		}
	}

}
