package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

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
 * The literal inventory of every {@code /apis/**} route Phase 1 maps.
 *
 * <h2>Why a hardcoded list rather than an enumeration</h2>
 * <p>Enumerating whatever the application happens to map and asserting things
 * about it can only catch a route that misbehaves -- never one that was never
 * written. Each wave's accepted discovery fixes an endpoint count, and this
 * test names every endpoint in it, so a missing one fails the build instead of
 * passing unnoticed. That is exactly how {@code stats.php} and
 * {@code my_team.php} escaped the first pass of Wave 12.4.
 *
 * <p>The comparison runs both ways: every expected route must be mapped, and
 * every mapped {@code /apis/**} route must be expected -- so a route added
 * without being recorded here fails too. That second direction is why this
 * class is not scoped to one wave: it asserts the whole legacy surface, so it
 * necessarily grows as each wave lands. It was named
 * {@code LegacyWave124RouteInventoryTest} while Wave 12.4 was the only wave
 * with routes; the name is now the surface it actually guards.
 *
 * <p>The lists below stay separate on purpose, one per delivered slice. A
 * single flat list would still catch a missing route, but it would not say
 * which discovery's count had drifted, and the count assertions are the part
 * that maps back to an accepted document. Slice-sized lists also keep every
 * intermediate commit honest: a wave that lands over three slices never has a
 * constant claiming to be the whole wave while holding a third of it.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("phase1-mysql")
class LegacyPhpRouteInventoryTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	/**
	 * The seventeen endpoints Wave 12.4 covers: fourteen {@code employees/*} and
	 * three {@code hr_employees/*}, exactly as its accepted discovery lists them.
	 */
	private static final List<String> WAVE_124_ROUTES = List.of(
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

	/**
	 * Wave 12.5's {@code shifts} slice: five endpoints.
	 *
	 * <p>Named for the slice rather than the wave on purpose. Wave 12.5's
	 * accepted discovery defines <b>fifteen</b> endpoints -- five each for
	 * {@code shifts}, {@code request_types} and
	 * {@code company_official_holidays} -- so a five-route constant called
	 * {@code WAVE_125_ROUTES} would misstate the contract in every commit
	 * between the first slice and the last. One list per slice keeps each
	 * intermediate commit truthful, and the wave is complete when all three
	 * exist.
	 */
	private static final List<String> WAVE_125_SHIFT_ROUTES = List.of(
			"/apis/api/shifts/create.php",
			"/apis/api/shifts/delete.php",
			"/apis/api/shifts/list.php",
			"/apis/api/shifts/one.php",
			"/apis/api/shifts/update.php");

	/** Wave 12.5's {@code request_types} slice: five endpoints. */
	private static final List<String> WAVE_125_REQUEST_TYPE_ROUTES = List.of(
			"/apis/api/request_types/create.php",
			"/apis/api/request_types/delete.php",
			"/apis/api/request_types/list.php",
			"/apis/api/request_types/one.php",
			"/apis/api/request_types/update.php");

	/**
	 * Every route the application is expected to map, composed from the slice
	 * lists above.
	 *
	 * <p>Wave 12.5 adds one more group as it lands:
	 * {@code WAVE_125_OFFICIAL_HOLIDAY_ROUTES} (5) in slice 4. It is named here
	 * rather than stubbed empty, because an empty list would satisfy the
	 * inventory while claiming coverage that does not exist.
	 */
	private static final List<String> EXPECTED_ROUTES = Stream.of(
					WAVE_124_ROUTES,
					WAVE_125_SHIFT_ROUTES,
					WAVE_125_REQUEST_TYPE_ROUTES)
			.flatMap(List::stream)
			.sorted()
			.toList();

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
	void everyMappedRouteIsExpectedAndEveryExpectedRouteIsMapped() {
		List<String> mapped = handlerMapping.getHandlerMethods().keySet().stream()
				.flatMap(info -> info.getPatternValues().stream())
				.filter(pattern -> pattern.startsWith("/apis/"))
				.distinct()
				.sorted()
				.toList();

		assertThat(mapped)
				.as("every mapped /apis/** route across all delivered waves")
				.containsExactlyElementsOf(EXPECTED_ROUTES);
	}

	@Test
	void theWave124InventoryIsItsDiscoverysCount() {
		assertThat(WAVE_124_ROUTES).hasSize(17);
		// startsWith on the full prefix, so the two counts cannot be confused
		// by a module whose name ends in the other's. (The original
		// contains("/employees/") was in fact exact -- the character before
		// "employees/" in an hr_employees route is an underscore -- but that
		// is a coincidence of naming, not a property worth relying on.)
		assertThat(WAVE_124_ROUTES.stream()
				.filter(route -> route.startsWith("/apis/api/employees/")).toList())
				.hasSize(14);
		assertThat(WAVE_124_ROUTES.stream()
				.filter(route -> route.startsWith("/apis/api/hr_employees/")).toList())
				.hasSize(3);
	}

	@Test
	void theWave125ShiftSliceIsItsFullFiveEndpoints() {
		// The shifts slice is complete at five; the wave is not complete until
		// request_types and company_official_holidays add five each. This
		// asserts the slice, not the wave, so it stays true as the wave grows.
		assertThat(WAVE_125_SHIFT_ROUTES).hasSize(5);
		assertThat(WAVE_125_SHIFT_ROUTES)
				.allSatisfy(route -> assertThat(route).startsWith("/apis/api/shifts/"));
	}

	@Test
	void theWave125RequestTypeSliceIsItsFullFiveEndpoints() {
		assertThat(WAVE_125_REQUEST_TYPE_ROUTES).hasSize(5);
		assertThat(WAVE_125_REQUEST_TYPE_ROUTES)
				.allSatisfy(route -> assertThat(route).startsWith("/apis/api/request_types/"));
	}

	@Test
	void noRouteIsListedTwiceAcrossTheWaves() {
		// A duplicate would hide a missing endpoint behind a matching count.
		assertThat(EXPECTED_ROUTES).doesNotHaveDuplicates();
		assertThat(EXPECTED_ROUTES)
				.hasSize(WAVE_124_ROUTES.size() + WAVE_125_SHIFT_ROUTES.size()
						+ WAVE_125_REQUEST_TYPE_ROUTES.size());
	}

	@Test
	void everyGuardedPrefixCoversAMappedRouteAndNoMore() {
		// LegacyPhpRoutes permits these prefixes past Spring's authorization
		// decision, so a prefix listed there with nothing behind it would be an
		// unauthenticated hole waiting for a future route.
		List<String> prefixes = List.of(com.workin.legacy.wire.LegacyPhpRoutes.CONTROLLER_GUARDED);
		assertThat(prefixes).containsExactly(
				"/apis/api/employees/**", "/apis/api/hr_employees/**", "/apis/api/shifts/**",
				"/apis/api/request_types/**");

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
