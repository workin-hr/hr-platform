package com.workin.legacy.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.workin.backend.BackendApplication;

/**
 * {@code apis/api/index.php}'s behaviour for a path no endpoint serves.
 *
 * <p>Three things were wrong before this, and each is asserted below.
 *
 * <ol>
 * <li><b>Status.</b> An unknown module is a 404 in PHP and was a 404 in Java
 * only when the caller happened to be authenticated.</li>
 * <li><b>Order.</b> PHP resolves the module against the allow-list at the top
 * of {@code index.php}, before any action file -- and therefore before any
 * {@code requireAuth()} -- runs. Java's security chain sits in front of the
 * dispatcher, so an <em>unauthenticated</em> request for an unknown path
 * answered <b>401</b>. {@code time/now} is exactly that path, and the mobile
 * client calls it from its home screen.</li>
 * <li><b>Shape.</b> The body was Spring's
 * {@code {timestamp,status,error,path}}, not the {@code {success,message}}
 * envelope every client here parses (D-074).</li>
 * </ol>
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyPhpRouterRefusalTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	static {
		MARIADB.start();
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private LegacyMessages messages;

	@Autowired
	private tools.jackson.databind.ObjectMapper objectMapper;

	private ResponseEntity<Map<String, Object>> get(String path, String locale) {
		HttpHeaders headers = new HttpHeaders();
		if (locale != null) {
			headers.set("Accept-Language", locale);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.GET, new HttpEntity<>(headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	@Test
	void anUnknownModuleIs404AndNamesTheAllowedListBack() {
		ResponseEntity<Map<String, Object>> response = get("/apis/api/nope/list", "en");

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().get("success")).isEqualTo(false);
		assertThat((String) response.getBody().get("message"))
				.isEqualTo("Module 'nope' not found. Available: " + LegacyPhpModules.ALLOWED_CSV);
	}

	/**
	 * The half a security chain in front of the dispatcher gets wrong: PHP has
	 * already answered before {@code requireAuth()} would have run.
	 */
	@Test
	void anUnknownModuleIs404WithNoCredentialsRatherThan401() {
		assertThat(get("/apis/api/time/now", "en").getStatusCode().value()).isEqualTo(404);
		assertThat(get("/apis/api/time/now.php", "en").getStatusCode().value()).isEqualTo(404);
	}

	/** {@code reports} is allow-listed and has no directory at all (C4). */
	@Test
	void anAllowedModuleWithNoActionFileIs501() {
		ResponseEntity<Map<String, Object>> response = get("/apis/api/reports/summary", "en");

		assertThat(response.getStatusCode().value()).isEqualTo(501);
		assertThat(response.getBody().get("message"))
				.isEqualTo("Module 'reports/summary' not implemented yet");
	}

	/**
	 * PHP strips everything outside {@code [a-z0-9_]} from each segment, so a
	 * request for a non-existent {@code .php} file reports the dot-less name
	 * back. Easy to get wrong, and visible in the response.
	 */
	@Test
	void theDotInANonExistentPhpFileIsStrippedFromTheReportedAction() {
		assertThat(get("/apis/api/configs/nope.php", "en").getBody().get("message"))
				.isEqualTo("Module 'configs/nopephp' not implemented yet");
	}

	@Test
	void theRefusalIsLocalizedLikeEveryOtherLegacyMessage() {
		assertThat(get("/apis/api/reports/summary", "ar").getBody().get("message"))
				.isEqualTo("الوحدة 'reports/summary' غير مُنفَّذة بعد");
	}

	/**
	 * PHP strips {@code [^a-z0-9_]} from the raw segment and lowercases
	 * <em>after</em>, so an uppercase letter is <b>deleted</b>, not folded.
	 * Measured against the running PHP: {@code Configs} reads as
	 * {@code onfigs}, and {@code CONFIGS} as the empty string — which is the
	 * {@code $module ?: 'none'} case.
	 *
	 * <p>An earlier version of {@code phpSegment} lowercased first and so read
	 * both as {@code configs}, which would have served two paths legacy
	 * refuses.
	 */
	@Test
	void anUppercaseSegmentIsStrippedRatherThanLowercased() {
		assertThat(get("/apis/api/CONFIGS/get", "en").getBody().get("message"))
				.isEqualTo("Module 'none' not found. Available: " + LegacyPhpModules.ALLOWED_CSV);
		assertThat(get("/apis/api/Configs/get", "en").getBody().get("message"))
				.isEqualTo("Module 'onfigs' not found. Available: " + LegacyPhpModules.ALLOWED_CSV);
	}

	/**
	 * The other half of the same rule, and the one that matters most: a
	 * separator PHP strips must still reach the endpoint. Both forms below are
	 * <b>200</b> in legacy, so refusing them would break routes legacy serves.
	 */
	@Test
	void aStrippedSeparatorStillResolvesToTheRealRoute() {
		// Both measured at 200 against the running PHP, as is the plain form.
		assertThat(get("/apis/api/phone_count-ries/list", null).getStatusCode().value()).isEqualTo(200);
		assertThat(get("/apis/api/phone_countries/li.st", null).getStatusCode().value()).isEqualTo(200);
	}

	/** An action whose characters are all stripped leaves no action at all. */
	@Test
	void aModuleWithNoUsableActionIsUnknownAction() {
		assertThat(get("/apis/api/configs/GET", "en").getStatusCode().value()).isEqualTo(404);
		assertThat(get("/apis/api/configs/GET", "en").getBody().get("message")).isEqualTo("Unknown action");
		assertThat(get("/apis/api/configs", "en").getBody().get("message")).isEqualTo("Unknown action");
	}

	/**
	 * A malformed percent escape in an <em>optional</em> localization hint must
	 * not decide the status of the response. {@code resolveLocale} reads
	 * {@code ?lang=} through {@code URLDecoder}, which throws on {@code "%"};
	 * letting that escape turned the router's 404 into a 500. PHP's
	 * {@code parse_str} keeps the literal {@code %} and answers its normal 404.
	 *
	 * <p><b>Sent over a raw socket, and that is the whole point.</b> An earlier
	 * version of this test built the URI with {@code new URI(...)}, whose
	 * multi-argument constructor percent-encodes the {@code %} into
	 * {@code %25} -- so {@code URLDecoder} succeeded, nothing threw, and the
	 * test passed whether or not the fix existed. Every HTTP client here
	 * normalizes the request target, so the only way to send a literal
	 * {@code %} is to write the request line ourselves.
	 */
	@Test
	void aMalformedQueryEscapeDoesNotReplaceTheRefusalWithA500() throws IOException {
		String response = rawGet("/apis/api/time/now?lang=%", "en");

		assertThat(response).as("raw request must not 500").startsWith("HTTP/1.1 404");
		assertThat(response).contains("Module 'time' not found");
	}

	/**
	 * The malformed pair is not always the one being read. {@code ?lang=ar&x=%}
	 * is a valid Arabic request with unrelated garbage beside it, and PHP
	 * answers it in Arabic -- measured. Discarding the whole query on any
	 * decode failure answered in English instead, which was a second divergence
	 * introduced by the fix for the first.
	 */
	@Test
	void aValidLangSurvivesAMalformedParameterBesideIt() throws IOException {
		assertThat(rawGet("/apis/api/time/now?lang=ar&x=%", "en"))
				.as("lang=ar must still be honoured when an unrelated pair is malformed")
				.contains("\u0627\u0644\u0648\u062d\u062f\u0629");
	}

	/** Writes the request line directly, so the malformed escape is sent verbatim. */
	private String rawGet(String target, String locale) throws IOException {
		int port = URI.create(restTemplate.getRootUri()).getPort();
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.getOutputStream().write((
					"GET " + target + " HTTP/1.1\r\n"
					+ "Host: localhost\r\n"
					+ "Accept-Language: " + locale + "\r\n"
					+ "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Fail open, not closed. Every refusal this filter makes is decided by a
	 * path's <em>absence</em> from the served-route set, so an empty set would
	 * answer <b>501 for the entire client surface</b> — the single worst
	 * outcome available here, and precisely the D-111 break the filter exists
	 * to prevent. An empty set cannot mean "nothing is served"; it means the
	 * mapping was not readable, and the request must be passed through to the
	 * dispatcher exactly as it was before this filter existed.
	 */
	@Test
	void anUnreadableRouteSetPassesRequestsThroughRatherThanRefusingEverything() throws Exception {
		LegacyPhpRouterFilter failOpen = new LegacyPhpRouterFilter(
				java.util.Set::of, messages, objectMapper);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/apis/api/phone_countries/list");
		request.setRequestURI("/apis/api/phone_countries/list");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		failOpen.doFilter(request, response, chain);

		assertThat(chain.getRequest())
				.as("the request must reach the chain, not be refused")
				.isNotNull();
		assertThat(response.getStatus())
				.as("nothing may be written by the filter itself")
				.isEqualTo(200);
	}

	/**
	 * The guard that keeps this filter honest: a delivered endpoint must still
	 * be dispatched, in both URL forms. Without this a refusal branch that
	 * swallowed everything would still pass every assertion above.
	 */
	@Test
	void aDeliveredEndpointIsStillServedInBothForms() {
		assertThat(get("/apis/api/phone_countries/list", null).getStatusCode().value()).isEqualTo(200);
		assertThat(get("/apis/api/phone_countries/list.php", null).getStatusCode().value()).isEqualTo(200);
	}

}
