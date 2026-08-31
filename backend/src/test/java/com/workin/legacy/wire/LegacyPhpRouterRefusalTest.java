package com.workin.legacy.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
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

	private ResponseEntity<Map<String, Object>> get(String path, String locale) {
		HttpHeaders headers = new HttpHeaders();
		if (locale != null) {
			headers.set("Accept-Language", locale);
		}
		// Not URI.create: one of these paths carries a deliberately malformed
		// percent escape, and URI.create would reject it in the test rather
		// than letting the server answer it.
		return restTemplate.exchange(
				java.net.URI.create(restTemplate.getRootUri()).resolve(rawPath(path)),
				HttpMethod.GET, new HttpEntity<>(headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	/** Builds a URI without validating escapes, so a malformed one reaches the server. */
	private static URI rawPath(String path) {
		try {
			return new URI(null, null, null, -1, path.split("\\?")[0],
					path.contains("?") ? path.substring(path.indexOf('?') + 1) : null, null);
		} catch (java.net.URISyntaxException ex) {
			throw new IllegalStateException(ex);
		}
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
	 */
	@Test
	void aMalformedQueryEscapeDoesNotReplaceTheRefusalWithA500() {
		ResponseEntity<Map<String, Object>> response = get("/apis/api/time/now?lang=%", "en");

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat((String) response.getBody().get("message")).startsWith("Module 'time' not found");
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
