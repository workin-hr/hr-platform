package com.workin.backend.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;
import com.workin.legacy.LegacyMariaDb;
import com.workin.legacy.attendance.records.AttendanceReportFixture;

/**
 * What the attendance report endpoints cost in statements, per request, through
 * the real application (D-292).
 *
 * <p>{@code list.php?fill_days=1}, {@code overall_report.php} and
 * {@code export.php} (both sheets) used to read per employee and per day, so
 * their statement count was the roster times the range: measured here at
 * {@code 8f7505a0}, the fingerprints export over a month was 1,055 statements
 * for 11 employees and 8,486 for 102, and the overall report 1,615 and 14,101;
 * a week of the overall report for 102 was 3,849. Against the remote
 * database, 106 ms a round trip, the statement count <em>is</em> the response
 * time. The ratchet is on both axes, because either alone can be satisfied by an accident: ten times
 * the employees and four times the days must each leave the count exactly
 * where it was.
 *
 * <p>Measured over HTTP rather than by wiring the services by hand, so the
 * number is what a request costs -- authentication, scoping and the
 * {@code @RequestScope} caches' cold start included -- and so this test runs
 * unchanged against the code before D-292, which is how its failure there was
 * shown. The counter wraps the application's own {@code legacyDataSource}.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(AttendanceReportQueryBudgetTest.CountLegacyStatements.class)
class AttendanceReportQueryBudgetTest {

	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final QueryCounter COUNTER = new QueryCounter();

	/** Branch A1: this many employees plus the company admin. */
	private static final int SMALL = 10;

	/** Branch A2: this many plus the manager, so the whole company is about ten times branch A1. */
	private static final int LARGE = 90;

	private static final String WEEK_FROM = "2026-03-02";
	private static final String WEEK_TO = "2026-03-08";
	private static final String MONTH_FROM = "2026-02-06";
	private static final String MONTH_TO = "2026-03-08";

	/**
	 * The ceiling for any one report request, whatever its roster or range.
	 * Measured at 12 to 15 after D-292, so this leaves room for a statement or
	 * two of legitimate change and none for a per-employee or per-day term.
	 */
	private static final int CEILING = 25;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	static {
		try (Connection connection = MARIADB.connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			for (String sql : AttendanceReportFixture.sized(356L, SMALL, LARGE).statements()) {
				st.execute(sql);
			}
		} catch (Exception ex) {
			throw new IllegalStateException("could not seed the report budget fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	@TestConfiguration
	static class CountLegacyStatements {

		@Bean
		static BeanPostProcessor countLegacyDataSource() {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String beanName) {
					return "legacyDataSource".equals(beanName) && bean instanceof DataSource dataSource
							? COUNTER.wrap(dataSource) : bean;
				}
			};
		}
	}

	@Test
	void theListWithFillDaysCostsTheSameForTenEmployeesAsForAHundred() {
		assertRosterFree("/apis/api/attendance/list.php?fill_days=1&date_from=" + MONTH_FROM + "&date_to=" + MONTH_TO);
	}

	@Test
	void theListWithFillDaysCostsTheSameForAWeekAsForAMonth() {
		assertRangeFree("/apis/api/attendance/list.php?fill_days=1");
	}

	@Test
	void theOverallReportCostsTheSameForTenEmployeesAsForAHundred() {
		assertRosterFree("/apis/api/attendance/overall_report.php?from=" + MONTH_FROM + "&to=" + MONTH_TO);
	}

	@Test
	void theOverallReportCostsTheSameForAWeekAsForAMonth() {
		assertRangeFree("/apis/api/attendance/overall_report.php?x=1");
	}

	@Test
	void theFingerprintsExportCostsTheSameForTenEmployeesAsForAHundred() {
		assertRosterFree("/apis/api/attendance/export.php?type=fingerprints&from=" + MONTH_FROM + "&to=" + MONTH_TO);
	}

	@Test
	void theFingerprintsExportCostsTheSameForAWeekAsForAMonth() {
		assertRangeFree("/apis/api/attendance/export.php?type=fingerprints");
	}

	@Test
	void theOverallExportCostsTheSameForTenEmployeesAsForAHundred() {
		assertRosterFree("/apis/api/attendance/export.php?from=" + MONTH_FROM + "&to=" + MONTH_TO);
	}

	/** stats.php for one employee walks every day of its period; a month must cost what a week does. */
	@Test
	void oneEmployeesStatsCostTheSameForAWeekAsForAMonth() {
		long employee = AttendanceReportFixture.FIRST_A + 1;
		int week = cost("/apis/api/attendance/stats.php?employee_id=" + employee
				+ "&date_from=" + WEEK_FROM + "&date_to=" + WEEK_TO);
		int month = cost("/apis/api/attendance/stats.php?employee_id=" + employee
				+ "&date_from=" + MONTH_FROM + "&date_to=" + MONTH_TO);
		System.out.println("[budget] stats.php one employee: week " + week + ", month " + month);
		assertThat(month).as("a month is four times a week's days").isEqualTo(week);
		assertThat(month).isLessThanOrEqualTo(CEILING);
	}

	private void assertRosterFree(String path) {
		int small = cost(path + "&branch_id=" + AttendanceReportFixture.BRANCH_A1);
		int large = cost(path);
		System.out.println("[budget] " + path + ": " + (SMALL + 1) + " employees " + small + ", "
				+ (SMALL + LARGE + 2) + " employees " + large);
		assertThat(large).as("ten times the employees must not add a statement").isEqualTo(small);
		assertThat(large).isLessThanOrEqualTo(CEILING);
	}

	private void assertRangeFree(String path) {
		String sep = path.contains("?") ? "&" : "?";
		boolean list = path.contains("list.php");
		String week = list ? "date_from=" + WEEK_FROM + "&date_to=" + WEEK_TO : "from=" + WEEK_FROM + "&to=" + WEEK_TO;
		String month = list ? "date_from=" + MONTH_FROM + "&date_to=" + MONTH_TO
				: "from=" + MONTH_FROM + "&to=" + MONTH_TO;
		int weekCost = cost(path + sep + week);
		int monthCost = cost(path + sep + month);
		System.out.println("[budget] " + path + ": week " + weekCost + ", month " + monthCost);
		assertThat(monthCost).as("four times the days must not add a statement").isEqualTo(weekCost);
		assertThat(monthCost).isLessThanOrEqualTo(CEILING);
	}

	/** The statements one GET issues, excluding the login-attempt purge that runs on its own schedule. */
	private int cost(String path) {
		ResponseEntity<byte[]>[] response = new ResponseEntity[1];
		List<String> issued = COUNTER.measure(() -> response[0] = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.GET, new HttpEntity<>(adminHeaders()),
				byte[].class));
		assertThat(response[0].getStatusCode().value()).as(path).isEqualTo(200);
		assertThat(issued).as("the counter must see the request's statements at all").isNotEmpty();
		return (int) issued.stream().filter(sql -> !sql.contains("login_attempts")).count();
	}

	private HttpHeaders adminHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(jwtService.issueAccessToken(AttendanceReportFixture.ADMIN_A,
				AttendanceReportFixture.ADMIN_A, AttendanceReportFixture.COMPANY_A, "test-session",
				Map.of("role", "company_admin", "token_version", 1L)));
		headers.set("Accept-Language", "en");
		return headers;
	}
}
