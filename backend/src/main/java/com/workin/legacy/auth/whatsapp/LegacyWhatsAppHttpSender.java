package com.workin.legacy.auth.whatsapp;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code sendWhatsAppText()} and the two functions under it
 * ({@code helpers/whatsapp_helper.php:20-215}) -- the Whats360 gateway call
 * behind every OTP.
 *
 * <h2>Configuration decides everything</h2>
 * <p>{@code whatsapp_is_configured()} requires a token that is neither empty
 * nor the committed placeholder, plus at least one instance id that is neither
 * empty nor its placeholder. When that fails, legacy returns <b>false</b> in
 * production, and the caller turns that into 503 {@code otp_delivery_failed}.
 * So a deployment with no WhatsApp credentials cannot issue an OTP, and every
 * registration, password reset and phone verification fails. That is the
 * behaviour here.
 *
 * <p><b>Legacy's dev escape hatch is deliberately not ported.</b> PHP's
 * unconfigured branch returns <em>true</em> when {@code AppConfig::DEBUG} is
 * on -- it logs the message and pretends it was delivered. Production's
 * {@code DEBUG} was confirmed {@code false} on 2026-08-05, so parity with the
 * running system is the false branch, and PMR-05 / {@code hr-legacy#4} require
 * the rewrite to carry no {@code DEBUG}-gated exception at all. A flag that
 * silently marks undelivered OTPs as sent is exactly the kind this repository
 * has already decided not to have.
 *
 * <h2>Instance ordering and the skip cache</h2>
 * <p>An instance that answers "not connected" is remembered for fifteen
 * minutes and sorted to the back, so the fallback is tried first while the
 * primary is down. PHP keeps that map in a JSON file under the system temp
 * directory because each request is a fresh process; a JVM shares memory
 * across requests, so the map lives here instead. Same scope -- per node,
 * fifteen minutes -- by a mechanism that fits the runtime.
 *
 * <h2>Failures never throw</h2>
 * <p>Every transport and parse failure is logged and turned into false, which
 * is what PHP does. Throwing would turn legacy's 503 into a 500.
 */
@Component
public class LegacyWhatsAppHttpSender implements LegacyWhatsAppSender {

	private static final Logger LOG = LoggerFactory.getLogger(LegacyWhatsAppHttpSender.class);
	private static final ObjectMapper JSON = new ObjectMapper();

	/** {@code CURLOPT_TIMEOUT => 15}. */
	private static final Duration TIMEOUT = Duration.ofSeconds(15);

	/** "Skip disconnected primary for 15 minutes so fallback is used first." */
	private static final Duration SKIP_FOR = Duration.ofMinutes(15);

	/** The committed placeholders {@code whatsapp_is_configured()} rejects. */
	private static final String TOKEN_PLACEHOLDER = "YOUR_WHATSAPP_TOKEN_HERE";
	private static final String INSTANCE_PLACEHOLDER = "YOUR_WHATSAPP_INSTANCE_ID";

	/** Never logged: it is a credential, and it travels in the request URL. */
	private final HttpClient httpClient;
	private final Map<String, Instant> skipUntil = new ConcurrentHashMap<>();
	private final String apiBase;
	private final String apiToken;
	private final String instanceId;
	private final String fallbackInstanceId;

	public LegacyWhatsAppHttpSender(
			@Value("${app.legacy-whatsapp.api-base:https://pro.whats360.live/api/v1/send-text}") String apiBase,
			@Value("${app.legacy-whatsapp.api-token:}") String apiToken,
			@Value("${app.legacy-whatsapp.instance-id:}") String instanceId,
			@Value("${app.legacy-whatsapp.instance-id-fallback:}") String fallbackInstanceId) {
		this.apiBase = apiBase;
		this.apiToken = apiToken == null ? "" : apiToken.trim();
		this.instanceId = instanceId == null ? "" : instanceId.trim();
		this.fallbackInstanceId = fallbackInstanceId == null ? "" : fallbackInstanceId.trim();
		this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	}

	/** {@code whatsapp_instance_ids()}: primary then fallback, placeholders and duplicates dropped. */
	List<String> instanceIds() {
		List<String> ids = new ArrayList<>();
		if (!instanceId.isEmpty() && !INSTANCE_PLACEHOLDER.equals(instanceId)) {
			ids.add(instanceId);
		}
		if (!fallbackInstanceId.isEmpty() && !INSTANCE_PLACEHOLDER.equals(fallbackInstanceId)
				&& !ids.contains(fallbackInstanceId)) {
			ids.add(fallbackInstanceId);
		}
		return ids;
	}

	/** {@code whatsapp_is_configured()}. */
	public boolean isConfigured() {
		return !apiToken.isEmpty() && !TOKEN_PLACEHOLDER.equals(apiToken) && !instanceIds().isEmpty();
	}

	@Override
	public boolean sendText(String localPhone, String message, String countryCode) {
		if (!isConfigured()) {
			// PHP's non-DEBUG branch. See the class javadoc for why the DEBUG
			// branch above it is not ported.
			LOG.error("WhatsApp is not configured; OTP delivery will fail with otp_delivery_failed");
			return false;
		}

		String jid = LegacyWhatsAppJid.of(localPhone, countryCode);
		List<String> instances = instanceIdsForSend();
		boolean sawNoLid = false;

		for (int index = 0; index < instances.size(); index++) {
			String instance = instances.get(index);
			Attempt attempt = sendVia(instance, jid, message);
			if (attempt.ok()) {
				if (index > 0) {
					LOG.warn("WhatsApp fallback succeeded (instance={})", instance);
				}
				return true;
			}
			if (attempt.noLid()) {
				sawNoLid = true;
			}
			if (index < instances.size() - 1 && !attempt.noLid()) {
				LOG.warn("WhatsApp primary failed (instance={}); trying fallback", instance);
			}
		}

		if (sawNoLid) {
			LOG.error("WhatsApp delivery failed (number not on WhatsApp / no LID)");
		} else {
			LOG.error("WhatsApp delivery failed on every instance");
		}
		return false;
	}

	/** {@code whatsapp_instance_ids_for_send()}: ready first, recently-disconnected last. */
	private List<String> instanceIdsForSend() {
		List<String> ready = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		Instant now = Instant.now();
		for (String id : instanceIds()) {
			Instant until = skipUntil.get(id);
			if (until != null && until.isAfter(now)) {
				skipped.add(id);
			} else {
				ready.add(id);
			}
		}
		ready.addAll(skipped);
		return ready;
	}

	private record Attempt(boolean ok, boolean disconnected, boolean noLid) {
	}

	/** {@code sendWhatsAppTextViaInstance()}, including its error classification. */
	private Attempt sendVia(String instance, String jid, String message) {
		String url = apiBase + "?token=" + encode(apiToken)
				+ "&instance_id=" + encode(instance)
				+ "&jid=" + encode(jid)
				+ "&msg=" + encode(message);
		try {
			HttpResponse<String> response = httpClient.send(
					HttpRequest.newBuilder(URI.create(url)).GET().timeout(TIMEOUT).build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			return classify(instance, response.statusCode(), response.body());
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			LOG.error("WhatsApp send interrupted (instance={})", instance);
			return new Attempt(false, false, false);
		} catch (Exception ex) {
			// curl_exec() === false -- logged, never surfaced to the client.
			//
			// The exception's *message* is deliberately not logged. The request
			// URL carries the API token as a query parameter (legacy's own
			// shape), and several failures here embed that URL in their message
			// -- URI.create() on a malformed api-base is the clearest, but a
			// driver or proxy failure can do it too. Logging the class name
			// alone keeps the credential out of the log while still saying what
			// kind of failure occurred.
			LOG.error("WhatsApp transport error (instance={}): {}", instance, ex.getClass().getName());
			return new Attempt(false, false, false);
		}
	}

	/**
	 * Success is {@code httpCode < 400} <b>and</b> a JSON object with a truthy
	 * {@code success} -- a 200 carrying {@code {"success": false}} is a
	 * failure, which is how this gateway reports most errors.
	 */
	private Attempt classify(String instance, int status, String body) {
		JsonNode decoded = null;
		try {
			JsonNode parsed = JSON.readTree(body == null ? "" : body);
			decoded = parsed.isObject() ? parsed : null;
		} catch (Exception ignored) {
			// !is_array($decoded) -- an unparseable body is simply not an object.
			decoded = null;
		}

		boolean success = decoded != null && truthy(decoded.get("success"));
		if (status < 400 && success) {
			return new Attempt(true, false, false);
		}

		String apiError = decoded != null && decoded.hasNonNull("error")
				? decoded.get("error").asText() : String.valueOf(body);
		String lower = apiError.toLowerCase(Locale.ROOT);
		boolean disconnected = lower.contains("not connected") || lower.contains("instance not connected");
		boolean noLid = lower.contains("no lid found");

		if (disconnected) {
			skipUntil.put(instance, Instant.now().plus(SKIP_FOR));
			LOG.warn("WhatsApp instance disconnected (instance={}); will prefer fallback briefly", instance);
		} else if (noLid) {
			LOG.warn("WhatsApp number unreachable (instance={}): no LID found", instance);
		} else {
			LOG.error("WhatsApp API error ({}) instance={}", status, instance);
		}
		return new Attempt(false, disconnected, noLid);
	}

	/** {@code !empty($decoded['success'])} -- PHP emptiness, not JSON truth. */
	private static boolean truthy(JsonNode node) {
		if (node == null || node.isNull()) {
			return false;
		}
		if (node.isBoolean()) {
			return node.booleanValue();
		}
		if (node.isNumber()) {
			return node.asDouble() != 0;
		}
		String text = node.asText();
		return !text.isEmpty() && !"0".equals(text);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
