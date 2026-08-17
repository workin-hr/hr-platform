package com.workin.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.testcontainers.containers.MariaDBContainer;

/**
 * A real MariaDB running the real legacy schema — the Phase 1 substrate
 * shared by every test that needs it.
 *
 * <p>MariaDB and not MySQL because production is MariaDB 11.8.8,
 * verified read-only against the live host (D-037). The schema applied
 * is the vendored copy of {@code hr-legacy}'s, proven byte-identical by
 * {@code scripts/check_legacy_schema_drift.py}, so these tests exercise
 * the production storage contract rather than an approximation of it.
 *
 * <p>Singleton container, started once and never stopped, for the same
 * reason {@code AbstractIntegrationTest} uses one: {@code @Testcontainers}
 * stops the container after each test <em>class</em>, and Ryuk reaps it
 * at JVM exit anyway. Two suites sharing one MariaDB is also the
 * difference between one container start and two in a build that already
 * spends minutes on containers.
 *
 * <p>Subclasses seed their own rows and must use distinct ids: the
 * schema is applied once and the database is shared, exactly like the
 * PostgreSQL suite.
 */
public abstract class AbstractLegacyMySqlTest {

	protected static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
		} catch (Exception ex) {
			throw new IllegalStateException("could not apply the legacy schema", ex);
		}
	}

	/**
	 * Applies one schema file's statements. Called once for the vendored,
	 * drift-checked legacy contract and once for
	 * {@code phase1_extensions.schema.sql} -- new Phase 1 infrastructure
	 * that is not part of that contract and must never be folded into the
	 * vendored file, or {@code check_legacy_schema_drift.py} would start
	 * comparing tables hr-legacy was never asked about.
	 */
	private static void applySchema(String resourceName) throws Exception {
		String schema = readResource(resourceName);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			// One statement per `;` at end of line. Splitting on that is
			// sufficient because neither schema file contains routines,
			// triggers or views -- independently inventoried as zero of
			// each for the vendored file (ADR-0004), and not used by the
			// Phase 1 extension file by construction -- which is also why
			// no DELIMITER handling is needed.
			for (String statement : schema.split(";\\s*\\R")) {
				if (!statement.isBlank()) {
					st.execute(statement);
				}
			}
		}
	}

	protected static Connection connect() throws Exception {
		return DriverManager.getConnection(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	/**
	 * Runs statements with MySQL's strict mode off.
	 *
	 * <p>Legacy production runs non-strict -- which is precisely how its
	 * 24 zero-date rows came to exist -- so a fixture that inserts
	 * legacy-shaped data must reproduce that mode. Seeding under strict
	 * mode would prove the adapter against data that cannot occur.
	 */
	protected static void seedAsLegacyWould(String... statements) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			for (String statement : statements) {
				st.execute(statement);
			}
		}
	}

	protected static String readResource(String name) throws IOException {
		try (InputStream in = AbstractLegacyMySqlTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** Reads one column of one employee row, as raw text. */
	protected static String employeeColumn(long employeeId, String columnName) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT " + columnName + " FROM employees WHERE id = " + employeeId)) {
			rs.next();
			return rs.getString(1);
		}
	}

}
