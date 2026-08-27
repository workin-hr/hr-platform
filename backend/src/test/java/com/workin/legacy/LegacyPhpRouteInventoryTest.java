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
import com.workin.legacy.wire.LegacyPhpRoutes;

/** Bidirectional literal inventory for every delivered {@code /apis/**} route. */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("phase1-mysql")
class LegacyPhpRouteInventoryTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final List<String> EXPECTED_ROUTES = List.of(
			"/apis/api/advances/approve.php", "/apis/api/advances/create.php",
			"/apis/api/advances/delete.php", "/apis/api/advances/list.php",
			"/apis/api/advances/one.php", "/apis/api/advances/pay.php",
			"/apis/api/advances/reject.php", "/apis/api/advances/update.php",
			"/apis/api/attendance/analyze_excel.php", "/apis/api/attendance/check_in.php",
			"/apis/api/attendance/check_in_qr.php", "/apis/api/attendance/check_out.php",
			"/apis/api/attendance/create.php", "/apis/api/attendance/delete.php",
			"/apis/api/attendance/delete_range.php", "/apis/api/attendance/employee_monthly_attendance.php",
			"/apis/api/attendance/import_excel.php", "/apis/api/attendance/list.php",
			"/apis/api/attendance/one.php", "/apis/api/attendance/stats.php",
			"/apis/api/attendance/update.php",
			"/apis/api/attendance_exception_types/create.php",
			"/apis/api/attendance_exception_types/delete.php",
			"/apis/api/attendance_exception_types/list.php",
			"/apis/api/attendance_exception_types/one.php",
			"/apis/api/attendance_exception_types/update.php",
			"/apis/api/auth/login_employee.php",
			"/apis/api/branches/create.php", "/apis/api/branches/delete.php",
			"/apis/api/branches/generate_qr.php", "/apis/api/branches/list.php",
			"/apis/api/branches/one.php", "/apis/api/branches/update.php",
			"/apis/api/company/update.php", "/apis/api/company/upload_commercial_reg.php",
			"/apis/api/company/upload_logo.php",
			"/apis/api/company_official_holidays/create.php",
			"/apis/api/company_official_holidays/delete.php",
			"/apis/api/company_official_holidays/list.php",
			"/apis/api/company_official_holidays/one.php",
			"/apis/api/company_official_holidays/update.php",
			"/apis/api/departments/create.php", "/apis/api/departments/delete.php",
			"/apis/api/departments/list.php", "/apis/api/departments/one.php",
			"/apis/api/departments/update.php",
			"/apis/api/employees/analyze_excel.php", "/apis/api/employees/create.php",
			"/apis/api/employees/deactivate.php", "/apis/api/employees/delete.php",
			"/apis/api/employees/delete_preview.php", "/apis/api/employees/import_bulk.php",
			"/apis/api/employees/list.php", "/apis/api/employees/my_team.php",
			"/apis/api/employees/one.php", "/apis/api/employees/reactivate.php",
			"/apis/api/employees/stats.php", "/apis/api/employees/template_excel.php",
			"/apis/api/employees/update.php", "/apis/api/employees/upload_photo.php",
			"/apis/api/hr_employees/create.php", "/apis/api/hr_employees/list.php",
			"/apis/api/hr_employees/update_permissions.php",
			"/apis/api/job_titles/create.php", "/apis/api/job_titles/delete.php",
			"/apis/api/job_titles/list.php", "/apis/api/job_titles/one.php",
			"/apis/api/job_titles/update.php",
			"/apis/api/leave_balances/analyze_excel.php", "/apis/api/leave_balances/create.php",
			"/apis/api/leave_balances/delete.php", "/apis/api/leave_balances/generate.php",
			"/apis/api/leave_balances/import_bulk.php", "/apis/api/leave_balances/list.php",
			"/apis/api/leave_balances/one.php", "/apis/api/leave_balances/stats.php",
			"/apis/api/leave_balances/template_excel.php", "/apis/api/leave_balances/update.php",
			"/apis/api/payroll_batches/calculate.php", "/apis/api/payroll_batches/create.php",
			"/apis/api/payroll_batches/delete.php", "/apis/api/payroll_batches/finalize.php",
			"/apis/api/payroll_batches/fiscal_period.php", "/apis/api/payroll_batches/list.php",
			"/apis/api/payroll_batches/one.php", "/apis/api/payroll_batches/reopen.php",
			"/apis/api/payroll_batches/stats.php", "/apis/api/payroll_batches/update.php",
			"/apis/api/payslips/create.php", "/apis/api/payslips/delete.php",
			"/apis/api/payslips/list.php", "/apis/api/payslips/one.php",
			"/apis/api/payslips/update.php",
			"/apis/api/penalties/create.php", "/apis/api/penalties/delete.php",
			"/apis/api/penalties/list.php", "/apis/api/penalties/one.php",
			"/apis/api/penalties/report.php", "/apis/api/penalties/stats.php",
			"/apis/api/penalties/update.php",
			"/apis/api/request_types/create.php", "/apis/api/request_types/delete.php",
			"/apis/api/request_types/list.php", "/apis/api/request_types/one.php",
			"/apis/api/request_types/update.php",
			"/apis/api/requests/approve.php", "/apis/api/requests/create.php",
			"/apis/api/requests/delete.php", "/apis/api/requests/list.php",
			"/apis/api/requests/one.php", "/apis/api/requests/reject.php",
			"/apis/api/requests/update.php",
			"/apis/api/salary_contracts/create.php", "/apis/api/salary_contracts/delete.php",
			"/apis/api/salary_contracts/list.php", "/apis/api/salary_contracts/one.php",
			"/apis/api/salary_contracts/update.php",
			"/apis/api/schedules/assign_employee_schedule.php",
			"/apis/api/schedules/employee_monthly_schedule.php",
			"/apis/api/schedules/generate_employee_schedule.php",
			"/apis/api/shifts/create.php", "/apis/api/shifts/delete.php",
			"/apis/api/shifts/list.php", "/apis/api/shifts/one.php",
			"/apis/api/shifts/update.php");

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
		assertThat(mapped).containsExactlyElementsOf(EXPECTED_ROUTES.stream().sorted().toList());
	}

	@Test
	void deliveredRouteCountIsNowOneHundredTwentyFive() {
		assertThat(EXPECTED_ROUTES).hasSize(125).doesNotHaveDuplicates();
	}

	@Test
	void finalWave12rRoutesAreInTheInventory() {
		assertThat(EXPECTED_ROUTES).contains(
				"/apis/api/departments/list.php", "/apis/api/departments/one.php",
				"/apis/api/departments/create.php", "/apis/api/departments/update.php",
				"/apis/api/departments/delete.php",
				"/apis/api/job_titles/list.php", "/apis/api/job_titles/one.php",
				"/apis/api/job_titles/create.php", "/apis/api/job_titles/update.php",
				"/apis/api/job_titles/delete.php", "/apis/api/auth/login_employee.php");
	}

	/**
	 * Open, not excluded. Both terminate in a streaming helper declared {@code : never}
	 * ({@code data_export_attendance_csv}, {@code api_xlsx_export_send}) instead of returning
	 * PHP's {@code ok()} JSON envelope, which makes them substantial work -- but D-101 records
	 * them as "blocked" and D-106 records {@code payslips/export.php} as "open", and neither is
	 * an owner disposition removing them from the Phase-1 obligation. Legacy serves both to real
	 * clients today. Delete this assertion -- do not amend it -- when they are delivered.
	 */
	@Test
	void theTwoBinaryExportEndpointsAreStillUnmapped() {
		assertThat(EXPECTED_ROUTES).doesNotContain(
				"/apis/api/attendance/export.php",
				"/apis/api/payslips/export.php");
	}

	/**
	 * Not an exclusion. {@code attendance/overall_report.php} ends at
	 * {@code ok(LangKey::OK, $report, 200)} and is an ordinary JSON read endpoint; it was
	 * misclassified as binary because it shares export.php's broad J.2 payroll blocker. It is
	 * unmapped because it is unimplemented, and this assertion must be deleted -- not amended --
	 * when Wave 12.6.6 delivers it. See C9 in the Phase 1 completion plan.
	 */
	@Test
	void theUnimplementedOverallReportEndpointIsStillUnmapped() {
		assertThat(EXPECTED_ROUTES).doesNotContain("/apis/api/attendance/overall_report.php");
	}

	@Test
	void everyDeliveredRouteIsCoveredByTheSecurityBoundary() {
		List<String> entries = List.of(LegacyPhpRoutes.CONTROLLER_GUARDED);
		for (String route : EXPECTED_ROUTES) {
			assertThat(entries).anyMatch(entry -> entry.endsWith("/**")
					? route.startsWith(entry.substring(0, entry.length() - 2)) : route.equals(entry));
		}
	}
}
