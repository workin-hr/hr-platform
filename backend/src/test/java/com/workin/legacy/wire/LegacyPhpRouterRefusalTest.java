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
