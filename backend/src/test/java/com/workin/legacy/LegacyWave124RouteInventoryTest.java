package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;

/**
 * The Wave 12.4 route inventory, written out literally.
 *
 * <h2>Why a hardcoded list rather than an enumeration</h2>
 * <p>Enumerating whatever the application happens to map and asserting things
 * about it can only catch a route that misbehaves -- never one that was never
 * written. The accepted discovery defines seventeen endpoints, and this test
 * names all seventeen, so a missing endpoint fails the build instead of passing
 * unnoticed. That is exactly how {@code stats.php} and {@code my_team.php}
 * escaped the first pass of this wave.
 *
 * <p>The comparison runs both ways: every expected route must be mapped, and
 * every mapped {@code /apis/**} route must be expected -- so a route added
 * without being recorded here fails too.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("phase1-mysql")
class LegacyWave124RouteInventoryTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	/**
	 * The seventeen endpoints Wave 12.4 covers: fourteen {@code employees/*} and
	 * three {@code hr_employees/*}, exactly as the accepted discovery lists them.
	 */
	private static final List<String> EXPECTED_ROUTES = List.of(
			"/apis/api/employees/analyze_excel.php",
			"/apis/api/employees/create.php",
			"/apis/api/employees/deactivate.php",
			"/apis/api/employees/delete.php",
			"/apis/api/employees/delete_preview.php",
			"/apis/api/employees/import_bulk.php",
			"/apis/api/employees/list.php",
			"/apis/api/employees/my_team.php",
			"/apis/api/employees/one.php",
			"/apis/api/employees/reactivate.php",
			"/apis/api/employees/stats.php",
			"/apis/api/employees/template_excel.php",
			"/apis/api/employees/update.php",
			"/apis/api/employees/upload_photo.php",
			"/apis/api/hr_employees/create.php",
			"/apis/api/hr_employees/list.php",
			"/apis/api/hr_employees/update_permissions.php");

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

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

	@Test
	void allSeventeenWaveRoutesAreMappedAndNothingElseIs() {
		List<String> mapped = handlerMapping.getHandlerMethods().keySet().stream()
				.flatMap(info -> info.getPatternValues().stream())
				.filter(pattern -> pattern.startsWith("/apis/"))
				.distinct()
				.sorted()
				.toList();

		assertThat(mapped)
				.as("the wave's mapped /apis/** routes")
				.containsExactlyElementsOf(EXPECTED_ROUTES);
	}

	@Test
	void theInventoryIsTheDiscoverysCount() {
		assertThat(EXPECTED_ROUTES).hasSize(17);
		assertThat(EXPECTED_ROUTES.stream().filter(route -> route.contains("/employees/")).toList())
				.hasSize(14);
		assertThat(EXPECTED_ROUTES.stream().filter(route -> route.contains("/hr_employees/")).toList())
				.hasSize(3);
		// No duplicates hiding a missing endpoint behind a matching count.
		assertThat(EXPECTED_ROUTES).doesNotHaveDuplicates();
	}

	@Test
	void everyGuardedPrefixCoversAMappedRouteAndNoMore() {
		// LegacyPhpRoutes permits these prefixes past Spring's authorization
		// decision, so a prefix listed there with nothing behind it would be an
		// unauthenticated hole waiting for a future route.
		List<String> prefixes = List.of(com.workin.legacy.wire.LegacyPhpRoutes.CONTROLLER_GUARDED);
		assertThat(prefixes).containsExactly(
				"/apis/api/employees/**", "/apis/api/hr_employees/**");

		for (String prefix : prefixes) {
			String base = prefix.substring(0, prefix.length() - "**".length());
			assertThat(EXPECTED_ROUTES.stream().filter(route -> route.startsWith(base)).toList())
					.as("routes behind %s", prefix)
					.isNotEmpty();
		}
		// And every route sits behind one of the permitted prefixes.
		for (String route : EXPECTED_ROUTES) {
			assertThat(prefixes.stream()
					.anyMatch(prefix -> route.startsWith(prefix.substring(0, prefix.length() - 2))))
					.as("%s is behind a guarded prefix", route)
					.isTrue();
		}
	}

}
