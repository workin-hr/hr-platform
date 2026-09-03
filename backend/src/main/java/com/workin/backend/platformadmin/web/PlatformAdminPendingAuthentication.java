package com.workin.backend.platformadmin.web;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

/**
 * A password step that has passed, waiting on its second factor.
 *
 * <p>Held in the session <em>instead of</em> an {@code Authentication}, which is
 * the important part: until the code verifies there is no security context, so
 * the only thing this session can reach is the challenge page itself. A
 * "partially authenticated" principal in the context would have been one
 * missing {@code authorities} check away from being a full one.
 *
 * <p>It expires on its own. Without that, a password-only session could sit
 * indefinitely waiting for someone to supply a code, which is exactly the
 * window the second factor exists to close.
 */
record PlatformAdminPendingAuthentication(long platformAdminId, String phone, Instant startedAt)
		implements Serializable {

	/** How long the challenge may be answered for. */
	static final Duration TTL = Duration.ofMinutes(5);

	boolean isLiveAt(Instant now) {
		return now.isBefore(this.startedAt.plus(TTL));
	}

}
