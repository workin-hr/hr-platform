package com.workin.legacy.configs;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyRuntimeOffset;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code /apis/api/configs/get.php} (Item 13.0) -- the first endpoint of Item
 * 13 and the only one in its module.
 *
 * <h2>Why this one is first</h2>
 * <p>It serves the desktop forced-update/maintenance-mode version gate
 * ({@code hr-platform#21}), which is how a client release is forced. The
 * completion plan's §2.4 records the circularity that makes the ordering
 * matter: the Flutter refresh-token gap ({@code hr-platform#18}) can only be
 * closed by shipping new client builds, and new client builds are forced
 * through the mechanism this endpoint serves. Building it last would mean
 * having no way to tell clients that a cutover had happened.
 *
 * <h2>Unauthenticated, deliberately</h2>
 * <p>The PHP calls no {@code requireAuth()} at all -- it checks the method and
 * goes straight to the query. That is not an oversight to be "hardened" here: a
 * client must be able to read the maintenance flag and the version gate
 * <em>before</em> it can log in, which is the whole point of the endpoint, and
 * D-111 forbids changing what an existing client sees. The table it exposes is
 * global operational configuration with no tenant or personal data in it.
 *
 * <h2>One route, two response shapes</h2>
 * <ul>
 * <li><b>{@code ?config_key=...}</b> -- {@code {config_key, config_value}} for
 *     that one key, and <b>200 even when the key does not exist</b>, with
 *     {@code config_value: null}. There is no 404 branch. The key echoed back
 *     is the one the caller <em>asked for</em>, taken from the query string
 *     rather than from the row, so a request for a missing key still echoes
 *     it.</li>
 * <li><b>no key</b> -- a flat object of every row, <em>plus</em>
 *     {@code server_time} and {@code server_timezone}.</li>
 * </ul>
 *
 * <p><b>An empty {@code config_key} is not a key.</b> PHP guards with
 * {@code $key !== null && $key !== ''}, so {@code ?config_key=} falls all the
 * way through to the all-configs branch and answers with the whole map plus the
 * clock -- not with {@code {config_key: "", config_value: null}}. Only that
 * exact empty string does it: {@code ?config_key=%20} is a one-character key
 * and takes the single-config branch, missing every row.
 *
 * <h2>The clock keys can be shadowed by a row, and the row loses</h2>
 * <p>{@code server_time} and {@code server_timezone} are assigned into the same
 * flat array <em>after</em> the loop, so a {@code configs} row literally keyed
 * {@code server_time} has its value overwritten while keeping its position in
 * the object. That is reachable -- the dashboard's configs editor writes
 * arbitrary keys -- and it is preserved rather than fixed: a client reading
 * {@code server_time} today gets the live clock, and D-058 puts the burden of
 * proof on the change, not on the port.
 */
@RestController
@RequestMapping("/apis/api/configs")
public class LegacyConfigsController {

	/** {@code date('Y-m-d H:i:s')}. */
	private static final DateTimeFormatter PHP_DATETIME =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final LegacyConfigsStore configsStore;
	private final LegacyClock clock;
	private final LegacyMessages messages;

	public LegacyConfigsController(
			LegacyConfigsStore configsStore, LegacyClock clock, LegacyMessages messages) {
		this.configsStore = configsStore;
		this.messages = messages;
		this.clock = clock;
	}

	@RequestMapping("/get.php")
	public LegacyApiResponse get(HttpServletRequest request) {
		if (!"GET".equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}

		Object key = LegacyQueryParameters.parse(request.getQueryString()).value("config_key");
		if (key != null && !"".equals(key)) {
			String requested = String.valueOf(key);
			return LegacyApiResponse.ok(message(request), orderedPair(requested));
		}

		Map<String, Object> configs = new LinkedHashMap<>(configsStore.all());
		configs.put("server_time", clock.now().format(PHP_DATETIME));
		configs.put("server_timezone", LegacyRuntimeOffset.zoneId(clock.offset()));
		return LegacyApiResponse.ok(message(request), configs);
	}

	/**
	 * PHP's literal {@code [Response::CONFIG_KEY => $key, Response::CONFIG_VALUE => ...]}.
	 *
	 * <p>Ordered, because the pair is a JSON object whose key order is part of
	 * the bytes a client receives, and {@code config_key} comes first.
	 */
	private Map<String, Object> orderedPair(String key) {
		Map<String, Object> pair = new LinkedHashMap<>();
		pair.put("config_key", key);
		pair.put("config_value", configsStore.value(key));
		return pair;
	}

	private String message(HttpServletRequest request) {
		return messages.translate(messages.resolveLocale(request), "ok", null);
	}

}
