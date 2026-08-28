package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.legacy.wire.LegacyApiResponse;
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
			"/apis/api/attendance/export.php",
			"/apis/api/attendance/import_excel.php", "/apis/api/attendance/list.php",
			"/apis/api/attendance/one.php", "/apis/api/attendance/overall_report.php",
			"/apis/api/attendance/stats.php",
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
	void deliveredRouteCountIsNowOneHundredTwentySeven() {
		assertThat(EXPECTED_ROUTES).hasSize(127).doesNotHaveDuplicates();
	}

	/**
	 * The completion plan's §5 G3 partitions the delivered routes by response
	 * shape -- 122 envelope-only, 2 download-only, 1 conditional -- and that
	 * table has already been corrected twice for missing a live download route.
	 * These two assertions are its drift test: the classification is derived
	 * from the live handler mappings, so a new non-envelope handler, or one
	 * converted back to the envelope, fails here instead of silently staling a
	 * completion-gate measurement.
	 *
	 * <p><b>Type-level only.</b> This catches a <em>new</em> non-envelope route,
	 * or one converted back to the envelope. It cannot catch
	 * {@code penalties/report.php} losing its {@code format=csv} download branch
	 * while still declaring {@code ResponseEntity<?>};
	 * {@code LegacyPenaltyReportBranchesEndToEndTest} exercises both of that
	 * route's responses to cover exactly that.
	 *
	 * <p>The rule reads the declared return type: a handler answers with
	 * D-074's envelope iff it returns {@link LegacyApiResponse} or
	 * {@code ResponseEntity<LegacyApiResponse>} -- the latter being how a
	 * handler that needs a non-200 status still answers in the envelope.
	 * Everything else ({@code void}, {@code ResponseEntity<byte[]>}, the
	 * wildcard {@code ResponseEntity<?>}) has to be inventoried below with its
	 * PHP terminator. The wildcard is not incidental: it is what lets
	 * {@code penalties/report.php} return either shape from one handler.
	 */
	private static final List<String> DOWNLOAD_ONLY_ROUTES = List.of(
			// stream_employee_template_xlsx() -- writes to output and exits.
			"/apis/api/employees/template_excel.php",
			// leave_balance_excel_stream_template() -- same shape.
			"/apis/api/leave_balances/template_excel.php",
			// api_xlsx_export_send(), via data_export_attendance_csv() or
			// data_export_fingerprints_sheet() -- Wave 12.6.6d.
			"/apis/api/attendance/export.php");

	/**
	 * {@code penalties/report.php} picks its wire contract from {@code format}:
	 * {@code ?format=csv} reaches the file's own local {@code streamCSV()}
	 * ({@code penalties/report.php:24}, which shadows the global one in
	 * {@code functions.php:398} and rewrites the {@code .csv} name it is handed
	 * to {@code .xlsx}); anything else falls through to {@code ok()}.
	 */
	private static final List<String> CONDITIONAL_ROUTES = List.of("/apis/api/penalties/report.php");

	@Test
	void everyRouteAnsweringOutsideTheD074EnvelopeIsInventoried() {
		List<String> nonEnvelope = handlerMapping.getHandlerMethods().entrySet().stream()
				.filter(entry -> !answersWithTheD074Envelope(entry.getValue().getMethod()))
				.flatMap(entry -> entry.getKey().getPatternValues().stream())
				.filter(pattern -> pattern.startsWith("/apis/"))
				.distinct().sorted().toList();

		assertThat(nonEnvelope).containsExactlyElementsOf(
				Stream.concat(DOWNLOAD_ONLY_ROUTES.stream(), CONDITIONAL_ROUTES.stream()).sorted().toList());
	}

	private static boolean answersWithTheD074Envelope(Method method) {
		Class<?> raw = method.getReturnType();
		if (LegacyApiResponse.class.equals(raw)) {
			return true;
		}
		if (!ResponseEntity.class.equals(raw)) {
			return false;
		}
		return method.getGenericReturnType() instanceof ParameterizedType parameterized
				&& LegacyApiResponse.class.equals(parameterized.getActualTypeArguments()[0]);
	}

	@Test
	void theResponseShapePartitionMatchesTheCompletionPlan() {
		assertThat(DOWNLOAD_ONLY_ROUTES).hasSize(3).doesNotHaveDuplicates();
		assertThat(CONDITIONAL_ROUTES).hasSize(1);
		assertThat(EXPECTED_ROUTES).containsAll(DOWNLOAD_ONLY_ROUTES).containsAll(CONDITIONAL_ROUTES);
		assertThat(EXPECTED_ROUTES.size() - DOWNLOAD_ONLY_ROUTES.size() - CONDITIONAL_ROUTES.size())
				.as("envelope-only routes, per the completion plan's 123/3/1 partition")
				.isEqualTo(123);
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
	 * {@code attendance/export.php} was delivered by Wave 12.6.6d and has left
	 * this assertion, exactly as its predecessor's javadoc required -- deleted
	 * from the list, not inverted. Only {@code payslips/export.php} remains,
	 * owed by Wave 12.9.
	 *
	 * <p>It emits an XLSX workbook through the {@code : never} terminator
	 * {@code api_xlsx_export_send()} ({@code xlsx_writer.php:318}) despite its
	 * {@code _csv}-named row builder. Per D-085 the port owes the same
	 * reader-observable workbook, not the same archive bytes. Delete this
	 * assertion -- do not amend it -- when Wave 12.9 delivers the last one.
	 */
	@Test
	void theLastBinaryExportEndpointIsStillUnmapped() {
		assertThat(EXPECTED_ROUTES).contains("/apis/api/attendance/export.php");
		assertThat(EXPECTED_ROUTES).doesNotContain("/apis/api/payslips/export.php");
	}

	/**
	 * Deleted by Wave 12.6.6c, exactly as its predecessor's javadoc required:
	 * {@code attendance/overall_report.php} is delivered and is now inside
	 * {@link #EXPECTED_ROUTES}, so the assertion that it stays unmapped is gone
	 * rather than inverted. Only {@code attendance/export.php} and
	 * {@code payslips/export.php} remain.
	 */
	@Test
	void theRemainingUnmappedRoutesAreOnlyTheTwoExports() {
		assertThat(EXPECTED_ROUTES).contains(
				"/apis/api/attendance/overall_report.php", "/apis/api/attendance/export.php");
		assertThat(EXPECTED_ROUTES).doesNotContain("/apis/api/payslips/export.php");
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
