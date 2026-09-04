package com.workin.legacy.time;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.LegacyRuntimeOffset;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code /apis/api/time/now.php} -- the authoritative clock the mobile
 * attendance screen trusts instead of the handset's own.
 *
 * <p>Added to legacy after this port's first sweep; the mobile client reads
 * {@code server_unix} from it. Caught by
 * {@code scripts/check_legacy_route_drift.py}, not by a person: it was the
 * fourth of four new routes and the one a manual reading of the diff had
 * missed.
 *
 * <p><b>{@code server_unix} is not derived from {@code server_time}.</b> PHP
 * returns {@code time()}, which is real epoch seconds and has no timezone in
 * it, while {@code server_time} is {@code date('Y-m-d H:i:s')} under the
 * configured offset. Computing the epoch by treating the offset-shifted local
 * time as UTC would answer two or three hours wrong, and the client uses it to
 * decide whether a check-in is late.
 */
@RestController
@RequestMapping("/apis/api/time")
public class LegacyTimeController {

	/** {@code date('Y-m-d H:i:s')}. */
	private static final DateTimeFormatter PHP_DATETIME =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final LegacyClock clock;

	private final LegacyMessages messages;

	private final LegacyRequestGuard requestGuard;

	public LegacyTimeController(
			LegacyClock clock, LegacyMessages messages, LegacyRequestGuard requestGuard) {
		this.clock = clock;
		this.messages = messages;
		this.requestGuard = requestGuard;
	}

	@AuthenticatedUseCase(reason = "The server clock the mobile attendance UI trusts over the "
			+ "handset's. PHP calls requireAuth() with no role check: any authenticated user.")
	@RequestMapping("/now.php")
	public LegacyApiResponse now(HttpServletRequest request) {
		if (!"GET".equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
		// After the method check and with no role list, exactly as PHP orders
		// it: an unauthenticated POST here answers 405, not 401.
		this.requestGuard.requireAuth();

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("server_time", this.clock.now().format(PHP_DATETIME));
		// time(): the true instant, taken by pairing the offset-local time with
		// the offset it was read in -- not by re-reading the system clock,
		// which could land on the other side of a second.
		body.put("server_unix", this.clock.now().toEpochSecond(this.clock.offset()));
		body.put("timezone", LegacyRuntimeOffset.zoneId(this.clock.offset()));
		return LegacyApiResponse.ok(
				this.messages.translate(this.messages.resolveLocale(request), "ok", null), body);
	}

}
