package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * D-099 at the wire: the runtime time zone reaches HTTP responses.
 *
 * <p>{@code LegacySessionDataSourceTest} proves the mechanism against a
 * datasource. This proves the consequence a client can see, on an
 * already-delivered endpoint, for both of the value families the session zone
 * touches:
 *
 * <ul>
 *   <li>a {@code TIMESTAMP} column ({@code created_at}) written and read under
 *       the configured offset;</li>
 *   <li>a {@code NOW()}-derived {@code DATETIME} ({@code check_in}), which is
 *       written from the server clock and therefore moves with the offset even
 *       though the column type itself never converts.</li>
 * </ul>
 *
 * <p>Deliberately narrow. Wave 12.R owns the complete literal wire retrofit of
 * the delivered surface; this is the representative regression D-099 requires,
 * not an audit of all 38 endpoints.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyRuntimeTimeZoneEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String CREATE = "/apis/api/attendance/create.php";

	private static final long COMPANY = 21101L;
	private static final long ADMIN = 211011L;
	private static final long EMPLOYEE = 211012L;
	private static final long BRANCH = 21111L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the timezone fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	/**
	 * The same request under both offsets: {@code created_at} moves by exactly
	 * an hour, and the supplied {@code DATETIME} does not move at all.
	 *
	 * <p>Before D-099 the application's connections ran in the container's own
	 * zone, so the rendered {@code created_at} was neither of these values. The
	 * reference point is UTC rather than a literal, because the container's
	 * clock is the one thing the test cannot fix.
	 */
	@Test
	void theConfiguredOffsetReachesTimestampColumnsOnTheWire() {
		daylightSaving("0");
		execute("DELETE FROM attendance");
		LocalDateTime beforeStandard = utcNow();
		LocalDateTime standard = returnedCreatedAt(create("2026-04-26 08:03:00"));

		daylightSaving("1");
		execute("DELETE FROM attendance");
		LocalDateTime beforeSummer = utcNow();
		LocalDateTime summer = returnedCreatedAt(create("2026-04-26 08:03:00"));

		assertThat(offsetHoursFromUtc(beforeStandard, standard))
				.describedAs("created_at as the API renders it under +02:00").isEqualTo(2L);
		assertThat(offsetHoursFromUtc(beforeSummer, summer))
				.describedAs("created_at as the API renders it under +03:00").isEqualTo(3L);
	}

	/**
	 * A {@code DATETIME} the caller supplied is lexical in both zones.
	 *
	 * <p>This is what makes the correction safe for the values D-096 pinned: a
	 * punch time round-trips byte for byte whatever the session zone is, so
	 * only server-clock and {@code TIMESTAMP} values changed.
	 */
	@Test
	void aSuppliedDateTimeIsUnchangedByTheOffset() {
		daylightSaving("0");
		execute("DELETE FROM attendance");
		Map<String, Object> standard = create("2026-04-26 08:03:00");

		daylightSaving("1");
		execute("DELETE FROM attendance");
		Map<String, Object> summer = create("2026-04-26 08:03:00");

		assertThat(dataOf(standard).get("check_in")).isEqualTo("2026-04-26 08:03:00");
		assertThat(dataOf(summer).get("check_in")).isEqualTo("2026-04-26 08:03:00");
	}

	/**
	 * The flip reaches a live application without a restart.
	 *
	 * <p>The two requests above already share a pool; this states the property
	 * explicitly at the HTTP layer, because it is the one a
	 * {@code connectionInitSql} implementation would fail.
	 */
	@Test
	void flippingTheConfigChangesTheNextRequestWithoutARestart() {
		daylightSaving("0");
		execute("DELETE FROM attendance");
		LocalDateTime standard = returnedCreatedAt(create("2026-04-26 08:03:00"));

		daylightSaving("1");
		execute("DELETE FROM attendance");
		LocalDateTime summer = returnedCreatedAt(create("2026-04-26 08:03:00"));

		assertThat(java.time.Duration.between(standard, summer).toMinutes())
				.describedAs("the second request must be an hour ahead of the first")
				.isBetween(59L, 61L);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Map<String, Object> create(String checkIn) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(jwtService.issueAccessToken(
				ADMIN, ADMIN, COMPANY, "test-session", Map.of("role", "company_admin", "token_version", 1L)));
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.APPLICATION_JSON);
		String json = "{\"employee_id\":" + EMPLOYEE + ",\"check_in\":\"" + checkIn + "\"}";
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + CREATE), HttpMethod.POST,
				new HttpEntity<>(json, headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
		return response.getBody();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
	}

	/**
	 * {@code created_at} as the <b>application</b> rendered it.
	 *
	 * <p>Read through the API rather than out of the table on purpose. A
	 * {@code TIMESTAMP} is stored as a UTC instant and converted to the session
	 * zone on the way out, so a raw fixture connection would show the same UTC
	 * value whatever the application's zone is. What D-099 changes is what the
	 * application returns, and that is what a client sees.
	 */
	private static LocalDateTime returnedCreatedAt(Map<String, Object> body) {
		Object value = dataOf(body).get("created_at");
		assertThat(value).describedAs("create.php returns the inserted row").isNotNull();
		return LocalDateTime.parse(String.valueOf(value),
				DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	}

	/** The container's UTC clock, read through a connection this wrapper does not touch. */
	private static LocalDateTime utcNow() {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET time_zone = '+00:00'");
			try (ResultSet rs = st.executeQuery("SELECT CAST(NOW() AS CHAR)")) {
				rs.next();
				return LocalDateTime.parse(rs.getString(1),
						DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
			}
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Whole hours between a UTC reference taken just before the write and the stored value. */
	private static long offsetHoursFromUtc(LocalDateTime utcReference, LocalDateTime stored) {
		return Math.round(java.time.Duration.between(utcReference, stored).toMinutes() / 60.0d);
	}

	private static void daylightSaving(String value) {
		execute("DELETE FROM configs WHERE config_key = 'is_daylight_saving'");
		execute("INSERT INTO configs (config_key, config_value) VALUES ('is_daylight_saving', '"
				+ value + "')");
	}

	private static void execute(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute(sql);
		} catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static List<Map<String, Object>> query(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			List<Map<String, Object>> rows = new ArrayList<>();
			while (rs.next()) {
				Map<String, Object> row = new LinkedHashMap<>();
				for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
					row.put(rs.getMetaData().getColumnLabel(column), rs.getObject(column));
				}
				rows.add(row);
			}
			return rows;
		} catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES ("
					+ COMPANY + ", 'TZ Co', '+201000021101', 'active', '2025-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES ("
					+ BRANCH + ", " + COMPANY + ", 'Main', 1, '2025-03-01 10:00:00')");
			employee(st, ADMIN, "company_admin", "+201000211011", "Adam", "Admin");
			employee(st, EMPLOYEE, "employee", "+201000211012", "Ellie", "One");
		}
	}

	private static void employee(Statement st, long id, String role, String phone, String first,
			String last) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, created_at) VALUES (" + id + ", " + COMPANY
				+ ", " + BRANCH + ", " + id + ", '" + first + "', '" + last + "', '" + phone
				+ "', '" + role + "', 1, '2025-04-01 08:00:00')");
	}

	private static void applySchema(String resourceName) throws Exception {
		String schema = readResource(resourceName);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			for (String statement : schema.split(";\\s*\\R")) {
				if (!statement.isBlank()) {
					st.execute(statement);
				}
			}
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream = LegacyRuntimeTimeZoneEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
