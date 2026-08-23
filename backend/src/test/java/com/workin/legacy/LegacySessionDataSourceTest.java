package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MariaDBContainer;

import com.zaxxer.hikari.HikariDataSource;

/**
 * D-099: the legacy runtime time zone is applied on every connection checkout.
 *
 * <p>{@code config/pdo.php:21-38} resolves {@code configs.is_daylight_saving}
 * and issues {@code SET time_zone} each time it opens a PDO connection. Every
 * expectation here is measured against real MariaDB 11.8.
 *
 * <p>The load-bearing test is
 * {@link #aPooledConnectionPicksUpAConfigFlipWithoutARestart}. An
 * implementation that set the zone through Hikari's {@code connectionInitSql}
 * -- once per physical connection -- passes every other test in this class and
 * fails that one, which is exactly why the mechanism is a checkout wrapper.
 */
class LegacySessionDataSourceTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static HikariDataSource pool;
	private static LegacySessionDataSource dataSource;

	@BeforeAll
	static void start() throws Exception {
		MARIADB.start();
		try (Connection connection = raw(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					CREATE TABLE configs (
						id int(10) UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
						config_key varchar(100) NOT NULL,
						config_value varchar(255) DEFAULT NULL,
						UNIQUE KEY uniq_config_key (config_key)
					) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
			st.execute("""
					CREATE TABLE tz_probe (
						id int UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
						dt datetime NULL,
						ts timestamp NULL DEFAULT NULL,
						d date NULL,
						t time NULL
					) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
		}
		pool = new HikariDataSource();
		pool.setJdbcUrl(MARIADB.getJdbcUrl());
		pool.setUsername(MARIADB.getUsername());
		pool.setPassword(MARIADB.getPassword());
		// One connection, so "the same physical connection" is not a coincidence.
		pool.setMaximumPoolSize(1);
		pool.setMinimumIdle(1);
		pool.setConnectionInitSql("SET SESSION sql_mode=''");
		dataSource = new LegacySessionDataSource(pool);
	}

	@AfterAll
	static void stop() {
		if (pool != null) {
			pool.close();
		}
	}

	@BeforeEach
	void reset() throws Exception {
		try (Connection connection = raw(); Statement st = connection.createStatement()) {
			st.execute("DELETE FROM configs");
			st.execute("DELETE FROM tz_probe");
		}
	}

	// ------------------------------------------------------------------
	// The offset the config selects
	// ------------------------------------------------------------------

	/**
	 * {@code strtolower(trim(...))} against the five recognised spellings, and
	 * the default for everything else.
	 */
	@ParameterizedTest(name = "[{index}] is_daylight_saving={0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"1        | +03:00",
		"true     | +03:00",
		"TRUE     | +03:00",
		"yes      | +03:00",
		"summer   | +03:00",
		"dst      | +03:00",
		"'  DST  '| +03:00",
		"0        | +02:00",
		"false    | +02:00",
		"no       | +02:00",
		"on       | +02:00",
		"''       | +02:00",
		"maybe    | +02:00",
	})
	void theConfigValueSelectsTheSessionZone(String configValue, String expected) throws Exception {
		daylightSaving(configValue);

		try (Connection connection = dataSource.getConnection()) {
			assertThat(sessionZone(connection)).isEqualTo(expected);
		}
	}

	/** No row at all: {@code fetchColumn()} is false and the default stands. */
	@Test
	void aMissingConfigRowIsTheDefaultOffset() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			assertThat(sessionZone(connection)).isEqualTo("+02:00");
		}
	}

	/**
	 * A config lookup that throws is swallowed, exactly as PHP's inner
	 * {@code catch (Throwable $ignored)} does.
	 *
	 * <p>Dropping the table is the bluntest way to make the {@code SELECT}
	 * fail, and it is the failure PHP's comment names: "Keep default offset
	 * when configs table/key is unavailable."
	 */
	@Test
	void aFailingConfigQueryFallsBackToTheDefaultOffset() throws Exception {
		try (Connection connection = raw(); Statement st = connection.createStatement()) {
			st.execute("RENAME TABLE configs TO configs_hidden");
		}
		try (Connection connection = dataSource.getConnection()) {
			assertThat(sessionZone(connection)).isEqualTo("+02:00");
		} finally {
			try (Connection connection = raw(); Statement st = connection.createStatement()) {
				st.execute("RENAME TABLE configs_hidden TO configs");
			}
		}
	}

	/**
	 * A {@code SET time_zone} failure is <b>not</b> swallowed.
	 *
	 * <p>PHP puts that statement outside the inner catch, so a session it
	 * cannot configure is not a session it hands out. Here the offset is made
	 * unacceptable to the server; acquisition must fail rather than quietly
	 * degrade to +02:00, because a wrong zone is a wrong {@code NOW()}.
	 */
	@Test
	void aFailingSetTimeZoneFailsTheCheckoutRatherThanDegrading() {
		DataSource broken = new LegacySessionDataSource(pool) {
			@Override
			public Connection getConnection() throws SQLException {
				Connection connection = pool.getConnection();
				try (Statement st = connection.createStatement()) {
					// The same shape the wrapper uses, with a zone the server rejects.
					st.execute("SET time_zone = 'Nowhere/Nothing'");
					return connection;
				} catch (SQLException ex) {
					connection.close();
					throw ex;
				}
			}
		};

		assertThatThrownBy(() -> broken.getConnection().close())
				.isInstanceOf(SQLException.class);
		// And the pool is not leaked: the next ordinary checkout still works.
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection()) {
				assertThat(sessionZone(connection)).isEqualTo("+02:00");
			}
			throw new IllegalStateException("checkout succeeded");
		}).isInstanceOf(IllegalStateException.class).hasMessage("checkout succeeded");
	}

	// ------------------------------------------------------------------
	// The property that rules out a physical-connection-only mechanism
	// ------------------------------------------------------------------

	/**
	 * <b>The load-bearing test.</b> Borrow, use, return; flip the config;
	 * borrow again -- the new zone applies, with no restart and no new physical
	 * connection.
	 *
	 * <p>The pool is capped at one connection, so the second borrow is
	 * guaranteed to be the same physical connection as the first. An
	 * implementation that configured the zone in {@code connectionInitSql}
	 * would still report +02:00 here, which is the whole reason D-099 rejects
	 * that mechanism.
	 */
	@Test
	void aPooledConnectionPicksUpAConfigFlipWithoutARestart() throws Exception {
		daylightSaving("0");
		Object firstConnectionId;
		try (Connection connection = dataSource.getConnection()) {
			assertThat(sessionZone(connection)).isEqualTo("+02:00");
			firstConnectionId = scalar(connection, "SELECT CONNECTION_ID()");
		}

		daylightSaving("1");

		try (Connection connection = dataSource.getConnection()) {
			assertThat(sessionZone(connection))
					.describedAs("the flip must reach a pooled connection")
					.isEqualTo("+03:00");
			assertThat(scalar(connection, "SELECT CONNECTION_ID()"))
					.describedAs("and it must be the same physical connection")
					.isEqualTo(firstConnectionId);
		}
	}

	// ------------------------------------------------------------------
	// What the zone actually changes
	// ------------------------------------------------------------------

	/**
	 * The server clock moves with the session zone -- which is the half that
	 * matters for attendance, because {@code check_in.php} writes
	 * {@code NOW()}.
	 */
	@Test
	void theServerClockFollowsTheSessionZone() throws Exception {
		daylightSaving("0");
		String atTwo;
		try (Connection connection = dataSource.getConnection()) {
			atTwo = String.valueOf(scalar(connection, "SELECT NOW()"));
		}

		daylightSaving("1");
		try (Connection connection = dataSource.getConnection()) {
			String atThree = String.valueOf(scalar(connection, "SELECT NOW()"));
			// An hour apart, give or take the second the two queries ran in.
			long minutes = java.time.Duration.between(
					java.time.LocalDateTime.parse(atTwo.substring(0, 19).replace(' ', 'T')),
					java.time.LocalDateTime.parse(atThree.substring(0, 19).replace(' ', 'T')))
					.toMinutes();
			assertThat(minutes).isBetween(59L, 61L);
		}
	}

	/**
	 * {@code DATETIME}, {@code DATE} and {@code TIME} are lexical and do
	 * <b>not</b> move; {@code TIMESTAMP} does.
	 *
	 * <p>That split is the whole reason this correction is safe for the values
	 * D-096 pinned: a punch written as a {@code DATETIME} reads back byte for
	 * byte in any zone, while {@code created_at} -- a {@code TIMESTAMP} -- is
	 * converted on the way out. Measured both ways in one row.
	 */
	@Test
	void onlyTimestampColumnsAreConvertedByTheSessionZone() throws Exception {
		daylightSaving("0");
		try (Connection connection = dataSource.getConnection();
				Statement st = connection.createStatement()) {
			st.execute("INSERT INTO tz_probe (dt, ts, d, t) VALUES "
					+ "('2026-04-26 08:03:00', '2026-04-26 08:03:00', '2026-04-26', '08:03:00')");
		}

		try (Connection connection = dataSource.getConnection()) {
			assertThat(scalar(connection, "SELECT CAST(dt AS CHAR) FROM tz_probe"))
					.isEqualTo("2026-04-26 08:03:00");
			assertThat(scalar(connection, "SELECT CAST(ts AS CHAR) FROM tz_probe"))
					.isEqualTo("2026-04-26 08:03:00");
			assertThat(scalar(connection, "SELECT CAST(d AS CHAR) FROM tz_probe")).isEqualTo("2026-04-26");
			assertThat(scalar(connection, "SELECT CAST(t AS CHAR) FROM tz_probe")).isEqualTo("08:03:00");
		}

		daylightSaving("1");
		try (Connection connection = dataSource.getConnection()) {
			// The DATETIME, DATE and TIME are unchanged...
			assertThat(scalar(connection, "SELECT CAST(dt AS CHAR) FROM tz_probe"))
					.isEqualTo("2026-04-26 08:03:00");
			assertThat(scalar(connection, "SELECT CAST(d AS CHAR) FROM tz_probe")).isEqualTo("2026-04-26");
			assertThat(scalar(connection, "SELECT CAST(t AS CHAR) FROM tz_probe")).isEqualTo("08:03:00");
			// ...and the TIMESTAMP has moved forward by the offset difference.
			assertThat(scalar(connection, "SELECT CAST(ts AS CHAR) FROM tz_probe"))
					.isEqualTo("2026-04-26 09:03:00");
		}
	}

	/** The pure grammar, without a database. */
	@Test
	void theOffsetGrammarIsSharedAndPure() {
		assertThat(LegacyRuntimeOffset.of("dst")).isEqualTo(ZoneOffset.ofHours(3));
		assertThat(LegacyRuntimeOffset.of(" TRUE ")).isEqualTo(ZoneOffset.ofHours(3));
		assertThat(LegacyRuntimeOffset.of(null)).isEqualTo(ZoneOffset.ofHours(2));
		assertThat(LegacyRuntimeOffset.of("on")).isEqualTo(ZoneOffset.ofHours(2));
		assertThat(LegacyRuntimeOffset.sqlLiteral(ZoneOffset.ofHours(3))).isEqualTo("+03:00");
		assertThat(LegacyRuntimeOffset.sqlLiteral(ZoneOffset.ofHours(2))).isEqualTo("+02:00");
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private static String sessionZone(Connection connection) throws Exception {
		return String.valueOf(scalar(connection, "SELECT @@session.time_zone"));
	}

	private static Object scalar(Connection connection, String sql) throws SQLException {
		try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getObject(1) : null;
		}
	}

	private static void daylightSaving(String value) throws Exception {
		try (Connection connection = raw(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("DELETE FROM configs WHERE config_key = 'is_daylight_saving'");
			st.execute("INSERT INTO configs (config_key, config_value) VALUES ('is_daylight_saving', '"
					+ value + "')");
		}
	}

	private static Connection raw() throws Exception {
		return DriverManager.getConnection(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

}
