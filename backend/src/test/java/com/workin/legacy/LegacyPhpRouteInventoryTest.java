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

	/** Wave 12.5's {@code company_official_holidays} slice: five endpoints. */
	private static final List<String> WAVE_125_OFFICIAL_HOLIDAY_ROUTES = List.of(
			"/apis/api/company_official_holidays/create.php",
			"/apis/api/company_official_holidays/delete.php",
			"/apis/api/company_official_holidays/list.php",
			"/apis/api/company_official_holidays/one.php",
			"/apis/api/company_official_holidays/update.php");

	/**
	 * Wave 12.5's three slices together. All three now exist, so this is the
	 * whole wave and {@link #theWave125WaveIsCompleteAtFifteenEndpoints}
	 * asserts the discovery's count against it.
	 */
	private static final List<String> WAVE_125_ROUTES = Stream.of(
					WAVE_125_SHIFT_ROUTES,
					WAVE_125_REQUEST_TYPE_ROUTES,
					WAVE_125_OFFICIAL_HOLIDAY_ROUTES)
			.flatMap(List::stream)
			.toList();

	/**
	 * Wave 12.6's slice 1a-i: the three attendance endpoints whose function
	 * closure reaches neither {@code schedule_helper}, {@code company_settings}
	 * nor {@code payroll_calculation}, so they land ahead of D-091. The wave's
	 * other fifteen endpoints are not listed and get no placeholder.
	 */
	private static final List<String> WAVE_126_ATTENDANCE_1A_I_ROUTES = List.of(
			"/apis/api/attendance/delete.php",
			"/apis/api/attendance/delete_range.php",
			"/apis/api/attendance/one.php");

	/** Wave 12.6 slice 1a-ii: the two mutating attendance endpoints. */
	private static final List<String> WAVE_126_ATTENDANCE_1A_II_ROUTES = List.of(
			"/apis/api/attendance/create.php",
			"/apis/api/attendance/update.php");

	/**
	 * Wave 12.6 slice 1b: the spreadsheet import, and only it.
	 *
	 * <p>{@code analyze_excel.php} is the endpoint next to it in PHP and is
	 * <b>not</b> here: its closure reaches
	 * {@code attendance_import_expected_for_day()}, and through that
	 * {@code company_setting_selected_values()} and
	 * {@code payroll_is_weekly_rest_day()}, which Item 13 and Waves 12.8/12.9
	 * own. The import's own closure reaches none of them, which is the whole
	 * reason the two could be separated at all.
	 */
	private static final List<String> WAVE_126_ATTENDANCE_1B_ROUTES = List.of(
			"/apis/api/attendance/import_excel.php");

	/**
	 * Wave 12.6 slice 2: the schedule assignment, and only it.
	 *
	 * <p>{@code schedules/} holds three endpoints and the other two are Wave
	 * 12.6.5, blocked behind D-091's evidence and the narrow payroll
	 * extraction. This one reaches neither, which is why it could land first.
	 */
	private static final List<String> WAVE_126_SCHEDULES_2_ROUTES = List.of(
			"/apis/api/schedules/assign_employee_schedule.php");

	/**
	 * Wave 12.6 slice 3: the three presence endpoints.
	 *
	 * <p>They share a helper closure with 12.6.4 and 12.6.5 but land first,
	 * because they are the only ones that <b>write</b> attendance from the
	 * server clock and the only ones that reach the geofence.
	 */
	private static final List<String> WAVE_126_ATTENDANCE_3_ROUTES = List.of(
			"/apis/api/attendance/check_in.php",
			"/apis/api/attendance/check_in_qr.php",
			"/apis/api/attendance/check_out.php");

	/**
	 * The pre-Wave-12.7 remainder of Waves 12.6.4 and 12.6.5.
	 *
	 * <p>Only three of those six endpoints are here. {@code list.php},
	 * {@code stats.php} and {@code employee_monthly_attendance.php} reach
	 * {@code attendance_row_worked_minutes()} and through it the {@code requests}
	 * table, so they are ordered after Wave 12.7 alongside
	 * {@code overall_report.php} and {@code export.php}. These three do not.
	 */
	private static final List<String> WAVE_126_PRE_127_ROUTES = List.of(
			"/apis/api/attendance/analyze_excel.php",
			"/apis/api/schedules/employee_monthly_schedule.php",
			"/apis/api/schedules/generate_employee_schedule.php");

	/** Every route the application is expected to map. */
	private static final List<String> EXPECTED_ROUTES = Stream.of(
					WAVE_124_ROUTES,
					WAVE_125_ROUTES,
					WAVE_126_ATTENDANCE_1A_I_ROUTES,
					WAVE_126_ATTENDANCE_1A_II_ROUTES,
					WAVE_126_ATTENDANCE_1B_ROUTES,
					WAVE_126_SCHEDULES_2_ROUTES,
					WAVE_126_ATTENDANCE_3_ROUTES,
					WAVE_126_PRE_127_ROUTES)
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
	void theWave125OfficialHolidaySliceIsItsFullFiveEndpoints() {
		assertThat(WAVE_125_OFFICIAL_HOLIDAY_ROUTES).hasSize(5);
		assertThat(WAVE_125_OFFICIAL_HOLIDAY_ROUTES).allSatisfy(
				route -> assertThat(route).startsWith("/apis/api/company_official_holidays/"));
	}

	@Test
	void theWave125WaveIsCompleteAtFifteenEndpoints() {
		// All three slices are delivered, so the aggregate can finally be
		// checked against the accepted discovery's own number: fifteen
		// endpoints, five per module. Until slice 4 this could only be asserted
		// per slice, because a wave-sized constant holding a third of the wave
		// would have misstated the contract.
		assertThat(WAVE_125_ROUTES).hasSize(15);
		assertThat(WAVE_125_ROUTES).doesNotHaveDuplicates();
	}

	@Test
	void theWave126Slice1aiIsItsThreeEndpoints() {
		// Named for the slice, not the wave: Wave 12.6 is eighteen endpoints
		// and this is three of them, so a constant called WAVE_126_ROUTES
		// would misstate the contract until the wave completes.
		assertThat(WAVE_126_ATTENDANCE_1A_I_ROUTES).hasSize(3);
		assertThat(WAVE_126_ATTENDANCE_1A_I_ROUTES)
				.allSatisfy(route -> assertThat(route).startsWith("/apis/api/attendance/"));
		// create.php and update.php belong to slice 1a-ii, not here.
		assertThat(WAVE_126_ATTENDANCE_1A_I_ROUTES)
				.doesNotContain("/apis/api/attendance/create.php", "/apis/api/attendance/update.php");
	}

	@Test
	void theWave126Slice1aiiIsItsTwoEndpoints() {
		assertThat(WAVE_126_ATTENDANCE_1A_II_ROUTES)
				.containsExactlyInAnyOrder(
						"/apis/api/attendance/create.php", "/apis/api/attendance/update.php");
	}

	@Test
	void theWave126Slice1bIsTheImportEndpointAlone() {
		assertThat(WAVE_126_ATTENDANCE_1B_ROUTES)
				.containsExactly("/apis/api/attendance/import_excel.php");
		// analyze_excel.php was asserted absent while it was blocked. It is now
		// delivered: its D-091 and payroll dependencies were resolved by the
		// Wave 12.6.3 closure, and it does NOT reach the `requests` table that
		// blocks its three siblings.
		assertThat(EXPECTED_ROUTES).contains("/apis/api/attendance/analyze_excel.php");
	}

	@Test
	void wave126HasThirteenOfItsEighteenEndpointsMapped() {
		// The wave is eighteen; thirteen are delivered. The remaining five all
		// depend on Wave 12.7's `requests` table -- see
		// theFiveRequestDependentEndpointsStayUnmapped below.
		assertThat(WAVE_126_ATTENDANCE_1A_I_ROUTES.size()
						+ WAVE_126_ATTENDANCE_1A_II_ROUTES.size()
						+ WAVE_126_ATTENDANCE_1B_ROUTES.size()
						+ WAVE_126_SCHEDULES_2_ROUTES.size()
						+ WAVE_126_ATTENDANCE_3_ROUTES.size()
						+ WAVE_126_PRE_127_ROUTES.size())
				.isEqualTo(13);
	}

	/**
	 * The five endpoints Wave 12.7 unblocks, asserted absent.
	 *
	 * <p>{@code list}, {@code stats} and {@code employee_monthly_attendance}
	 * reach {@code attendance_row_worked_minutes()}, whose first statement is
	 * {@code attendance_approved_timed_request_for_day()} -- a read of the
	 * {@code requests} table. {@code overall_report} and {@code export} reach it
	 * through the payroll helpers. Same dependency, same ordering.
	 */
	@Test
	void theFiveRequestDependentEndpointsStayUnmapped() {
		assertThat(EXPECTED_ROUTES).doesNotContain(
				"/apis/api/attendance/list.php",
				"/apis/api/attendance/stats.php",
				"/apis/api/attendance/employee_monthly_attendance.php",
				"/apis/api/attendance/overall_report.php",
				"/apis/api/attendance/export.php");
	}

	@Test
	void theWave126Slice3IsTheThreePresenceEndpoints() {
		assertThat(WAVE_126_ATTENDANCE_3_ROUTES).hasSize(3);
		assertThat(WAVE_126_ATTENDANCE_3_ROUTES)
				.allSatisfy(route -> assertThat(route).startsWith("/apis/api/attendance/"));
		// The request-dependent five are asserted absent separately.
	}

	@Test
	void theWave126Slice2IsTheScheduleAssignmentAlone() {
		assertThat(WAVE_126_SCHEDULES_2_ROUTES)
				.containsExactly("/apis/api/schedules/assign_employee_schedule.php");
		// The other two schedules endpoints were blocked on D-091's evidence and
		// the payroll extraction; both are closed, so all three are now mapped
		// and the module is complete.
		assertThat(EXPECTED_ROUTES).contains(
				"/apis/api/schedules/employee_monthly_schedule.php",
				"/apis/api/schedules/generate_employee_schedule.php");
	}

	@Test
	void noRouteIsListedTwiceAcrossTheWaves() {
		// A duplicate would hide a missing endpoint behind a matching count.
		assertThat(EXPECTED_ROUTES).doesNotHaveDuplicates();
		assertThat(EXPECTED_ROUTES).hasSize(
				WAVE_124_ROUTES.size() + WAVE_125_ROUTES.size()
						+ WAVE_126_ATTENDANCE_1A_I_ROUTES.size()
						+ WAVE_126_ATTENDANCE_1A_II_ROUTES.size()
						+ WAVE_126_ATTENDANCE_1B_ROUTES.size()
						+ WAVE_126_SCHEDULES_2_ROUTES.size()
						+ WAVE_126_ATTENDANCE_3_ROUTES.size()
						+ WAVE_126_PRE_127_ROUTES.size());
	}

	@Test
	void everyGuardedPrefixCoversAMappedRouteAndNoMore() {
		// LegacyPhpRoutes permits these prefixes past Spring's authorization
		// decision, so a prefix listed there with nothing behind it would be an
		// unauthenticated hole waiting for a future route.
		List<String> prefixes = List.of(com.workin.legacy.wire.LegacyPhpRoutes.CONTROLLER_GUARDED);
		assertThat(prefixes).containsExactly(
				"/apis/api/employees/**", "/apis/api/hr_employees/**", "/apis/api/shifts/**",
				"/apis/api/request_types/**", "/apis/api/company_official_holidays/**",
				"/apis/api/attendance/**", "/apis/api/schedules/**");

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
