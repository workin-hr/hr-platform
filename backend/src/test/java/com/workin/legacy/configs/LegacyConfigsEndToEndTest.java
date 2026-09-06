package com.workin.legacy.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * {@code /apis/api/configs/get.php} (Item 13.0) at the request level.
 *
 * <p>Every request here is sent <b>without a token</b>, which is itself an
 * assertion: legacy calls no {@code requireAuth()} in this file, so a 401 from
 * any of these would be a regression a client could not work around -- it must
 * read the maintenance flag and version gate before it can log in.
 *
 * <p>The two response shapes and the empty-key boundary are the substance. They
 * are asserted against raw JSON rather than a parsed map wherever key
 * <em>order</em> or key <em>presence</em> is the point, because both are
 * observable in the bytes and a {@code Map} silently normalises the first.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyConfigsEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String GET = "/apis/api/configs/get.php";

	@Autowired
	private TestRestTemplate restTemplate;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the configs fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	@Test
	@Order(1)
	void withoutAKeyItAnswersEveryRowPlusTheServerClock() {
		Map<String, Object> data = data(json(GET));

		assertThat(data)
				.containsEntry("min_app_version", "3.2.0")
				.containsEntry("maintenance_mode", "0")
				.containsEntry("is_daylight_saving", "0");
		assertThat(data).containsKeys("server_time", "server_timezone");
	}

	/**
	 * {@code date('Y-m-d H:i:s')} -- seconds, a space separator, and no
	 * timezone suffix. An ISO-8601 {@code T} or a trailing offset would be a
	 * different string for every client that parses it.
	 */
	@Test
	@Order(2)
	void theServerTimeIsPhpsDateFormatNotIso8601() {
		Object serverTime = data(json(GET)).get("server_time");

		assertThat(serverTime).asString()
				.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
	}

	/**
	 * {@code Etc/GMT-2} is UTC+2: the POSIX sign is inverted. With
	 * {@code is_daylight_saving = '0'} the seed selects the default profile.
	 */
	@Test
	@Order(3)
	void theServerTimezoneIsTheNamedPosixZoneNotAnOffset() {
		assertThat(data(json(GET)).get("server_timezone")).isEqualTo("Etc/GMT-2");
	}

	/**
	 * The JSON object's key order is the row order the query returned, with the
	 * two clock keys appended.
	 *
	 * <p>PHP has no {@code ORDER BY} and builds an associative array, which
	 * {@code json_encode} emits in insertion order -- so the order a client
	 * receives is whatever the scan produced, and Java must pass that through
	 * rather than impose one. A hash-ordered map serialises the same pairs in a
	 * different sequence, which is a different response body for a client that
	 * renders the map in order.
	 *
	 * <p>Asserted as the exact sequence, not as a pair of relative positions: a
	 * two-key comparison passes by luck under hash ordering roughly half the
	 * time.
	 */
	@Test
	@Order(4)
	void theKeyOrderIsTheRowOrderWithTheClockAppended() {
		assertThat(data(json(GET)).keySet())
				.containsExactly("min_app_version", "maintenance_mode", "is_daylight_saving",
						"server_time", "server_unix", "server_timezone");
	}

	@Test
	@Order(5)
	void asingleKeyAnswersThePairAndNotTheClock() {
		Map<String, Object> data = data(json(GET + "?config_key=min_app_version"));

		assertThat(data).containsExactly(
				Map.entry("config_key", "min_app_version"),
				Map.entry("config_value", "3.2.0"));
		assertThat(data).doesNotContainKeys("server_time", "server_timezone");
	}

	/**
	 * There is no 404 branch: {@code $row[...] ?? null} answers 200 with a null
	 * value, and the key echoed back is the one the <em>caller</em> asked for,
	 * read from the query string rather than from the row that does not exist.
	 */
	@Test
	@Order(6)
	void anUnknownKeyIsTwoHundredWithANullValueAndEchoesTheRequestedKey() {
		ResponseEntity<String> response = raw(GET + "?config_key=no_such_key");

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody()).contains("\"config_key\":\"no_such_key\"", "\"config_value\":null");
	}

	/**
	 * {@code $key !== null && $key !== ''} -- an empty value is not a key, so
	 * this falls all the way through to the all-configs branch instead of
	 * answering a pair with an empty key.
	 */
	@Test
	@Order(7)
	void anEmptyConfigKeyFallsThroughToTheAllConfigsBranch() {
		Map<String, Object> data = data(json(GET + "?config_key="));

		assertThat(data).containsKeys("min_app_version", "server_time", "server_timezone");
		assertThat(data).doesNotContainKey("config_value");
	}

	/**
	 * The guard tests for the exact empty string, not for blankness: a single
	 * space is a one-character key and takes the single-config branch, where it
	 * matches no row.
	 */
	@Test
	@Order(8)
	void aWhitespaceConfigKeyIsAKeyAndMissesEveryRow() {
		ResponseEntity<String> response = raw(GET + "?config_key=%20");

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody()).contains("\"config_key\":\" \"", "\"config_value\":null");
		assertThat(response.getBody()).doesNotContain("server_time");
	}

	/**
	 * The method check precedes everything, and there is no authentication to
	 * reach: a POST with no token is 405, never 401.
	 */
	@Test
	@Order(9)
	void aNonGetMethodIsFourZeroFiveWithoutATokenRatherThanFourZeroOne() {
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + GET), HttpMethod.POST,
				new HttpEntity<>(new HttpHeaders()), new ParameterizedTypeReference<Map<String, Object>>() { });

		assertThat(response.getStatusCode().value()).isEqualTo(405);
		assertThat(response.getBody()).containsEntry("success", false);
	}

	/**
	 * A row literally keyed {@code server_time} is overwritten by the live
	 * clock while keeping its <b>position</b> in the object -- PHP assigns into
	 * an existing key rather than appending. Preserved deliberately (D-058):
	 * the dashboard's configs editor can create that row, and a client reading
	 * {@code server_time} today receives the clock, not the stored value.
	 *
	 * <p>Ordered last because it mutates the fixture.
	 */
	@Test
	@Order(10)
	void aRowShadowingServerTimeLosesItsValueButKeepsItsPosition() {
		execute("INSERT INTO configs (id, config_key, config_value) VALUES"
				+ " (904, 'server_time', 'STORED'), (905, 'zz_after_the_shadow', 'kept')");

		String body = raw(GET).getBody();

		assertThat(body).as("the stored value is replaced by the live clock").doesNotContain("STORED");
		assertThat(body.indexOf("\"server_time\""))
				.as("assignment into an existing key updates it in place, so the shadowed row stays "
						+ "ahead of the row that follows it -- appending would have put it last")
				.isLessThan(body.indexOf("\"zz_after_the_shadow\""));
	}

	/**
	 * The flag {@code LegacyClock} already reads decides this field too, so the
	 * endpoint reports the same profile the rest of Phase 1 computes dates in.
	 *
	 * <p>Ordered last of all: it flips the fixture's timezone profile.
	 */
	@Test
	@Order(11)
	void thedaylightSavingFlagSelectsTheOtherNamedZone() {
		execute("UPDATE configs SET config_value = 'true' WHERE config_key = 'is_daylight_saving'");

		assertThat(data(json(GET)).get("server_timezone")).isEqualTo("Etc/GMT-3");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> data(Map<String, Object> envelope) {
		assertThat(envelope).containsEntry("success", true);
		return (Map<String, Object>) envelope.get("data");
	}

	private Map<String, Object> json(String path) {
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.GET,
				new HttpEntity<>(new HttpHeaders()), new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		return response.getBody();
	}

	private ResponseEntity<String> raw(String path) {
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.GET,
				new HttpEntity<>(new HttpHeaders()), String.class);
	}

	private static void execute(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute(sql);
		} catch (Exception ex) {
			throw new IllegalStateException("could not update the configs fixture", ex);
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			// Inserted in this order so the shadowing test can prove position is kept.
			st.execute("INSERT INTO configs (id, config_key, config_value) VALUES"
					+ " (901, 'min_app_version', '3.2.0'),"
					+ " (902, 'maintenance_mode', '0'),"
					+ " (903, 'is_daylight_saving', '0')");
		}
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
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream in = LegacyConfigsEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
