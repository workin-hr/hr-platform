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

/** Bidirectional literal inventory for every delivered {@code /apis/**} route. */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("phase1-mysql")
class LegacyPhpRouteInventoryTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final List<String> WAVE_124_ROUTES = List.of(
			"/apis/api/employees/analyze_excel.php", "/apis/api/employees/create.php",
			"/apis/api/employees/deactivate.php", "/apis/api/employees/delete.php",
			"/apis/api/employees/delete_preview.php", "/apis/api/employees/import_bulk.php",
			"/apis/api/employees/list.php", "/apis/api/employees/my_team.php",
			"/apis/api/employees/one.php", "/apis/api/employees/reactivate.php",
			"/apis/api/employees/stats.php", "/apis/api/employees/template_excel.php",
			"/apis/api/employees/update.php", "/apis/api/employees/upload_photo.php",
			"/apis/api/hr_employees/create.php", "/apis/api/hr_employees/list.php",
			"/apis/api/hr_employees/update_permissions.php");

	private static final List<String> WAVE_125_ROUTES = List.of(
			"/apis/api/shifts/create.php", "/apis/api/shifts/delete.php", "/apis/api/shifts/list.php",
			"/apis/api/shifts/one.php", "/apis/api/shifts/update.php",
			"/apis/api/request_types/create.php", "/apis/api/request_types/delete.php",
			"/apis/api/request_types/list.php", "/apis/api/request_types/one.php",
			"/apis/api/request_types/update.php",
			"/apis/api/company_official_holidays/create.php", "/apis/api/company_official_holidays/delete.php",
			"/apis/api/company_official_holidays/list.php", "/apis/api/company_official_holidays/one.php",
			"/apis/api/company_official_holidays/update.php");

	/** Thirteen of eighteen Wave-12.6 routes; the request-dependent five stay after Wave 12.7. */
	private static final List<String> WAVE_126_ROUTES = List.of(
			"/apis/api/attendance/delete.php", "/apis/api/attendance/delete_range.php",
			"/apis/api/attendance/one.php", "/apis/api/attendance/create.php",
			"/apis/api/attendance/update.php", "/apis/api/attendance/import_excel.php",
			"/apis/api/schedules/assign_employee_schedule.php",
			"/apis/api/attendance/check_in.php", "/apis/api/attendance/check_in_qr.php",
			"/apis/api/attendance/check_out.php", "/apis/api/attendance/analyze_excel.php",
			"/apis/api/schedules/employee_monthly_schedule.php",
			"/apis/api/schedules/generate_employee_schedule.php");

	private static final List<String> WAVE_127_REQUEST_ROUTES = List.of(
			"/apis/api/requests/create.php", "/apis/api/requests/delete.php",
			"/apis/api/requests/list.php", "/apis/api/requests/one.php",
			"/apis/api/requests/approve.php", "/apis/api/requests/reject.php",
			"/apis/api/requests/update.php");

	private static final List<String> WAVE_127_LEAVE_BALANCE_ROUTES = List.of(
			"/apis/api/leave_balances/analyze_excel.php",
			"/apis/api/leave_balances/create.php",
			"/apis/api/leave_balances/delete.php",
			"/apis/api/leave_balances/generate.php",
			"/apis/api/leave_balances/import_bulk.php",
			"/apis/api/leave_balances/list.php",
			"/apis/api/leave_balances/one.php",
			"/apis/api/leave_balances/stats.php",
			"/apis/api/leave_balances/template_excel.php",
			"/apis/api/leave_balances/update.php");

	/**
	 * Wave 12.6.4b: the three endpoints Wave 12.7 slice 1 unblocked --
	 * {@code list.php}, {@code stats.php} and
	 * {@code employee_monthly_attendance.php} all reach
	 * {@code attendance_row_worked_minutes()} and through it the
	 * {@code requests} table, which now exists.
	 */
	private static final List<String> WAVE_1264B_ROUTES = List.of(
			"/apis/api/attendance/list.php",
			"/apis/api/attendance/stats.php",
			"/apis/api/attendance/employee_monthly_attendance.php");

	private static final List<String> WAVE_128_SALARY_CONTRACT_ROUTES = List.of(
			"/apis/api/salary_contracts/create.php", "/apis/api/salary_contracts/delete.php",
			"/apis/api/salary_contracts/list.php", "/apis/api/salary_contracts/one.php",
			"/apis/api/salary_contracts/update.php");

	private static final List<String> WAVE_128_ADVANCE_ROUTES = List.of(
			"/apis/api/advances/approve.php", "/apis/api/advances/create.php",
			"/apis/api/advances/delete.php", "/apis/api/advances/list.php",
			"/apis/api/advances/one.php", "/apis/api/advances/pay.php",
			"/apis/api/advances/reject.php", "/apis/api/advances/update.php");

	private static final List<String> WAVE_128_PENALTY_ROUTES = List.of(
			"/apis/api/penalties/create.php", "/apis/api/penalties/delete.php",
			"/apis/api/penalties/list.php", "/apis/api/penalties/one.php",
			"/apis/api/penalties/report.php", "/apis/api/penalties/stats.php",
			"/apis/api/penalties/update.php");

	private static final List<String> WAVE_1210_COMPANY_ROUTES = List.of(
			"/apis/api/company/update.php",
			"/apis/api/company/upload_logo.php",
			"/apis/api/company/upload_commercial_reg.php");

	/** All ten {@code payroll_batches} routes: CRUD, fiscal period, and the calculation engine. */
	private static final List<String> WAVE_129_BATCH_ROUTES = List.of(
			"/apis/api/payroll_batches/list.php",
			"/apis/api/payroll_batches/one.php",
			"/apis/api/payroll_batches/create.php",
			"/apis/api/payroll_batches/update.php",
			"/apis/api/payroll_batches/calculate.php",
			"/apis/api/payroll_batches/finalize.php",
			"/apis/api/payroll_batches/reopen.php",
			"/apis/api/payroll_batches/stats.php",
			"/apis/api/payroll_batches/delete.php",
			"/apis/api/payroll_batches/fiscal_period.php");

	/**
	 * Five of {@code payslips}' six routes -- {@code export.php} streams a binary
	 * XLSX response ({@code api_xlsx_export_send()}) and stays unmapped for the
	 * same reason {@code attendance/export.php} does (see
	 * {@link #theTwoPayrollReportEndpointsStayUnmapped()}): D-106.
	 */
	private static final List<String> WAVE_129_PAYSLIP_ROUTES = List.of(
			"/apis/api/payslips/list.php",
			"/apis/api/payslips/one.php",
			"/apis/api/payslips/create.php",
			"/apis/api/payslips/update.php",
			"/apis/api/payslips/delete.php");

	/**
	 * Wave 12.R, D-107: the D-074 retrofit of Wave 12.1's five
	 * {@code attendance_exception_types} routes off {@code /api/legacy/**}
	 * onto their literal {@code /apis/api/**} paths with the PHP envelope.
	 * First of five Wave 12.R module slices (22 endpoints total).
	 */
	private static final List<String> WAVE_12R_EXCEPTION_TYPE_ROUTES = List.of(
			"/apis/api/attendance_exception_types/list.php",
			"/apis/api/attendance_exception_types/one.php",
			"/apis/api/attendance_exception_types/create.php",
			"/apis/api/attendance_exception_types/update.php",
			"/apis/api/attendance_exception_types/delete.php");

	/**
	 * Wave 12.R, D-108: the D-074 retrofit of Wave 12.3a's six {@code branches}
	 * routes off {@code /api/legacy/**} onto their literal {@code /apis/api/**}
	 * paths with the PHP envelope. Second of five Wave 12.R module slices.
	 */
	private static final List<String> WAVE_12R_BRANCH_ROUTES = List.of(
			"/apis/api/branches/list.php",
			"/apis/api/branches/one.php",
			"/apis/api/branches/create.php",
			"/apis/api/branches/update.php",
			"/apis/api/branches/delete.php",
			"/apis/api/branches/generate_qr.php");

	private static final List<String> EXPECTED_ROUTES = Stream.of(
				WAVE_124_ROUTES, WAVE_125_ROUTES, WAVE_126_ROUTES,
				WAVE_127_REQUEST_ROUTES, WAVE_127_LEAVE_BALANCE_ROUTES, WAVE_1264B_ROUTES,
				WAVE_128_SALARY_CONTRACT_ROUTES, WAVE_128_ADVANCE_ROUTES, WAVE_128_PENALTY_ROUTES,
				WAVE_1210_COMPANY_ROUTES, WAVE_129_BATCH_ROUTES, WAVE_129_PAYSLIP_ROUTES,
				WAVE_12R_EXCEPTION_TYPE_ROUTES, WAVE_12R_BRANCH_ROUTES)
			.flatMap(List::stream).sorted().toList();

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	static { MARIADB.start(); }

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
				.distinct().sorted().toList();
		assertThat(mapped).containsExactlyElementsOf(EXPECTED_ROUTES);
	}

	@Test
	void deliveredWaveCountsStayExactAndNonOverlapping() {
		assertThat(WAVE_124_ROUTES).hasSize(17);
		assertThat(WAVE_125_ROUTES).hasSize(15);
		assertThat(WAVE_126_ROUTES).hasSize(13);
		assertThat(WAVE_127_REQUEST_ROUTES).hasSize(7);
		assertThat(WAVE_127_LEAVE_BALANCE_ROUTES).hasSize(10);
		assertThat(WAVE_1264B_ROUTES).hasSize(3);
		assertThat(WAVE_128_SALARY_CONTRACT_ROUTES).hasSize(5);
		assertThat(WAVE_128_ADVANCE_ROUTES).hasSize(8);
		assertThat(WAVE_128_PENALTY_ROUTES).hasSize(7);
		assertThat(WAVE_1210_COMPANY_ROUTES).hasSize(3);
		assertThat(WAVE_129_BATCH_ROUTES).hasSize(10);
		assertThat(WAVE_129_PAYSLIP_ROUTES).hasSize(5);
		assertThat(WAVE_12R_EXCEPTION_TYPE_ROUTES).hasSize(5);
		assertThat(WAVE_12R_BRANCH_ROUTES).hasSize(6);
		assertThat(EXPECTED_ROUTES).hasSize(114).doesNotHaveDuplicates();
	}

	@Test
	void wave127IsCompleteAtSeventeenEndpoints() {
		assertThat(Stream.concat(WAVE_127_REQUEST_ROUTES.stream(), WAVE_127_LEAVE_BALANCE_ROUTES.stream()).toList())
				.hasSize(17).doesNotHaveDuplicates();
	}

	@Test
	void theWave1264bSliceIsItsThreeEndpoints() {
		assertThat(WAVE_1264B_ROUTES).hasSize(3);
		assertThat(WAVE_1264B_ROUTES)
				.containsExactlyInAnyOrder(
						"/apis/api/attendance/list.php", "/apis/api/attendance/stats.php",
						"/apis/api/attendance/employee_monthly_attendance.php");
	}

	@Test
	void wave128FinanceFoundationIsCompleteAtTwentyEndpoints() {
		assertThat(Stream.of(WAVE_128_SALARY_CONTRACT_ROUTES, WAVE_128_ADVANCE_ROUTES, WAVE_128_PENALTY_ROUTES)
				.flatMap(List::stream).toList()).hasSize(20).doesNotHaveDuplicates();
	}

	@Test
	void theWave1210SliceIsItsThreeCompanyEndpoints() {
		assertThat(WAVE_1210_COMPANY_ROUTES).hasSize(3);
		assertThat(WAVE_1210_COMPANY_ROUTES)
				.containsExactlyInAnyOrder(
						"/apis/api/company/update.php", "/apis/api/company/upload_logo.php",
						"/apis/api/company/upload_commercial_reg.php");
	}

	/**
	 * The two payroll-report endpoints Wave 12.7 and Wave 12.6.4b do not
	 * unblock, asserted absent.
	 *
	 * <p>{@code overall_report.php} and {@code export.php} reach
	 * {@code attendance_row_worked_minutes()} through the payroll helpers, not
	 * directly, and still carry the broader D-091 payroll boundary that
	 * {@code list}, {@code stats} and {@code employee_monthly_attendance} do
	 * not.
	 */
	@Test
	void theTwoPayrollReportEndpointsStayUnmapped() {
		assertThat(EXPECTED_ROUTES).doesNotContain(
				"/apis/api/attendance/overall_report.php",
				"/apis/api/attendance/export.php");
	}

	@Test
	void theWave129SliceIsAllTenPayrollBatchesEndpoints() {
		assertThat(WAVE_129_BATCH_ROUTES).hasSize(10).doesNotHaveDuplicates();
		assertThat(WAVE_129_BATCH_ROUTES)
				.allSatisfy(route -> assertThat(route).startsWith("/apis/api/payroll_batches/"));
	}

	@Test
	void theWave129PayslipSliceIsFiveEndpointsWithExportDeferred() {
		assertThat(WAVE_129_PAYSLIP_ROUTES).hasSize(5).doesNotHaveDuplicates();
		assertThat(WAVE_129_PAYSLIP_ROUTES)
				.allSatisfy(route -> assertThat(route).startsWith("/apis/api/payslips/"));
		assertThat(EXPECTED_ROUTES).doesNotContain("/apis/api/payslips/export.php");
	}

	@Test
	void theWave12rExceptionTypeSliceIsAllFiveRoutesRetrofittedFromApiLegacy() {
		assertThat(WAVE_12R_EXCEPTION_TYPE_ROUTES).hasSize(5).doesNotHaveDuplicates();
		assertThat(WAVE_12R_EXCEPTION_TYPE_ROUTES)
				.allSatisfy(route -> assertThat(route).startsWith("/apis/api/attendance_exception_types/"));
		List<String> mapped = handlerMapping.getHandlerMethods().keySet().stream()
				.flatMap(info -> info.getPatternValues().stream())
				.filter(pattern -> pattern.startsWith("/api/legacy/attendance_exception_types"))
				.toList();
		assertThat(mapped).as("D-107: no /api/legacy/** route remains for this retrofitted module").isEmpty();
	}

	@Test
	void theWave12rBranchSliceIsAllSixRoutesRetrofittedFromApiLegacy() {
		assertThat(WAVE_12R_BRANCH_ROUTES).hasSize(6).doesNotHaveDuplicates();
		assertThat(WAVE_12R_BRANCH_ROUTES)
				.allSatisfy(route -> assertThat(route).startsWith("/apis/api/branches/"));
		List<String> mapped = handlerMapping.getHandlerMethods().keySet().stream()
				.flatMap(info -> info.getPatternValues().stream())
				.filter(pattern -> pattern.startsWith("/api/legacy/branches"))
				.toList();
		assertThat(mapped).as("D-108: no /api/legacy/** route remains for this retrofitted module").isEmpty();
	}

	@Test
	void everyGuardedEntryCoversMappedRoutesAndEveryRouteIsGuarded() {
		List<String> entries = List.of(com.workin.legacy.wire.LegacyPhpRoutes.CONTROLLER_GUARDED);
		assertThat(entries).containsExactly(
				"/apis/api/employees/**", "/apis/api/hr_employees/**", "/apis/api/shifts/**",
				"/apis/api/request_types/**", "/apis/api/company_official_holidays/**",
				"/apis/api/attendance/**", "/apis/api/schedules/**",
				"/apis/api/requests/list.php", "/apis/api/requests/one.php",
				"/apis/api/requests/create.php", "/apis/api/requests/update.php",
				"/apis/api/requests/delete.php", "/apis/api/requests/approve.php",
				"/apis/api/requests/reject.php", "/apis/api/leave_balances/**",
				"/apis/api/salary_contracts/**", "/apis/api/advances/**", "/apis/api/penalties/**",
				"/apis/api/company/**",
				"/apis/api/payroll_batches/**", "/apis/api/payslips/**",
				"/apis/api/attendance_exception_types/**", "/apis/api/branches/**");
		for (String entry : entries) {
			if (entry.endsWith("/**")) {
				String prefix = entry.substring(0, entry.length() - 2);
				assertThat(EXPECTED_ROUTES).anyMatch(route -> route.startsWith(prefix));
			} else {
				assertThat(EXPECTED_ROUTES).contains(entry);
			}
		}
		for (String route : EXPECTED_ROUTES) {
			assertThat(entries).anyMatch(entry -> entry.endsWith("/**")
					? route.startsWith(entry.substring(0, entry.length() - 2)) : route.equals(entry));
		}
	}
}
