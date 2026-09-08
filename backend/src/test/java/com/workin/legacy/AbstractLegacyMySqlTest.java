package com.workin.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

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

	/**
	 * One database for this whole hierarchy, inside the JVM's single MariaDB.
	 *
	 * <p>Shared by every subclass, which is this class's long-standing contract
	 * -- see the note above about distinct ids. What changed is only that the
	 * <em>container</em> is now shared with the rest of the suite as well
	 * ({@link LegacyMariaDb}), instead of this class starting a second one.
	 */
	protected static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

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
