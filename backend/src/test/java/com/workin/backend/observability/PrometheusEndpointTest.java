package com.workin.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyMariaDb;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;

/**
 * The meters this application maintains have to be readable by something.
 *
 * <p>Actuator was on the classpath with no Prometheus registry and only
 * {@code health} exposed, so every counter and summary in the codebase was
 * being incremented and then discarded -- the cost of maintaining them paid,
 * none of the benefit taken. This asserts the endpoint exists AND that an
 * application meter reaches it, because a registry that scrapes empty is the
 * same failure wearing a dependency.
 */
@SpringBootTest(classes = BackendApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "management.endpoints.web.exposure.include=health,prometheus")
@AutoConfigureTestRestTemplate
class PrometheusEndpointTest {

	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

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
	private MeterRegistry meters;

	@Test
	void theScrapeEndpointServesJvmAndPoolFiguresWithoutAnyApplicationCode() {
		String body = scrape();

		assertThat(body)
				.as("heap and GC come free with the registry")
				.contains("jvm_memory_used_bytes")
				.contains("jvm_gc_");
		assertThat(body)
				.as("the connection pool is the first thing to look at under load")
				.contains("hikaricp_connections");
	}

	@Test
	void anApplicationMeterReachesTheScrape() {
		meters.counter("workin.observability.probe", "surface", "test").increment();

		assertThat(scrape())
				.as("a registry that scrapes without the application's own meters "
						+ "is the same failure wearing a dependency")
				.contains("workin_observability_probe_total");
	}

	private String scrape() {
		ResponseEntity<String> response =
				restTemplate.getForEntity("/actuator/prometheus", String.class);
		assertThat(response.getStatusCode().is2xxSuccessful())
				.as("the scrape endpoint must be exposed in this profile (got %s)",
						response.getStatusCode())
				.isTrue();
		return response.getBody();
	}
}
