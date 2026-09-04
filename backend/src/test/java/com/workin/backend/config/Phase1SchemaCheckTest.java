package com.workin.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;

/**
 * Pins {@link Phase1SchemaCheck} to the DDL it reports on, and proves the
 * report itself against a real MariaDB rather than a mocked metadata call
 * -- the collaborator whose behaviour is in question here is the JDBC
 * driver's catalog lookup, so mocking it would test nothing.
 */
class Phase1SchemaCheckTest extends AbstractLegacyMySqlTest {

	private static final Pattern CREATE_TABLE =
			Pattern.compile("(?im)^\\s*CREATE\\s+TABLE\\s+([A-Za-z0-9_]+)");

	/**
	 * The guard that makes the check trustworthy: a table added to
	 * {@code phase1_extensions.sql} without a line in {@code OWNED_TABLES}
	 * would be provisioned but never reported missing, and a name removed
	 * from the DDL would be reported missing forever.
	 */
	@Test
	void theCheckCoversExactlyTheTablesTheShippedDdlCreates() throws IOException {
		assertThat(Phase1SchemaCheck.ownedTables())
				.containsExactlyInAnyOrderElementsOf(tablesDeclaredIn(shippedDdl()));
	}

	@Test
	void everyOwnedTableIsReportedMissingFromADatabaseThatHasNone() throws Exception {
		String database = "phase1_check_empty";
		createDatabase(database);

		List<String> missing = new Phase1SchemaCheck(dataSourceFor(database)).missingTables();

		assertThat(missing).containsExactlyInAnyOrderElementsOf(Phase1SchemaCheck.ownedTables());
	}

	@Test
	void noneIsReportedMissingOnceTheShippedDdlHasBeenApplied() throws Exception {
		String database = "phase1_check_provisioned";
		createDatabase(database);
		applyShippedDdlTo(database);

		List<String> missing = new Phase1SchemaCheck(dataSourceFor(database)).missingTables();

		assertThat(missing).isEmpty();
	}

	/**
	 * The report survives MariaDB folding {@code SPRING_SESSION} to lower
	 * case, which it does whenever {@code lower_case_table_names} is set --
	 * a host-filesystem-dependent setting, so a case-sensitive comparison
	 * would report a phantom missing table on some deployments and not
	 * others.
	 */
	@Test
	void aLowerCasedTableNameStillCounts() throws Exception {
		String database = "phase1_check_folded";
		createDatabase(database);
		applyShippedDdlTo(database);
		try (Connection connection = connectTo(database); Statement st = connection.createStatement()) {
			st.execute("RENAME TABLE SPRING_SESSION TO spring_session_folded");
			st.execute("RENAME TABLE spring_session_folded TO spring_session");
		}

		List<String> missing = new Phase1SchemaCheck(dataSourceFor(database)).missingTables();

		assertThat(missing).isEmpty();
	}

	private static List<String> tablesDeclaredIn(String ddl) {
		Matcher matcher = CREATE_TABLE.matcher(ddl);
		return matcher.results().map(result -> result.group(1)).toList();
	}

	private static String shippedDdl() throws IOException {
		try (InputStream in = Phase1SchemaCheckTest.class.getClassLoader()
				.getResourceAsStream("db/phase1-mysql/phase1_extensions.sql")) {
			assertThat(in).as("the provisioning DDL must ship on the classpath").isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void applyShippedDdlTo(String database) throws Exception {
		try (Connection connection = connectTo(database); Statement st = connection.createStatement()) {
			for (String statement : shippedDdl().split(";\\s*\\R")) {
				if (!statement.isBlank()) {
					st.execute(statement);
				}
			}
		}
	}

	/**
	 * Creates a scratch database and grants the container's ordinary user
	 * access to it. Done as {@code root} because the test user Testcontainers
	 * provisions is scoped to the one database it created.
	 */
	private static void createDatabase(String database) throws Exception {
		String rootUrl = urlFor("mysql");
		try (Connection connection = java.sql.DriverManager.getConnection(rootUrl, "root", MARIADB.getPassword());
				Statement st = connection.createStatement()) {
			st.execute("CREATE DATABASE IF NOT EXISTS " + database);
			st.execute("GRANT ALL ON " + database + ".* TO '" + MARIADB.getUsername() + "'@'%'");
			st.execute("FLUSH PRIVILEGES");
		}
	}

	private static String urlFor(String database) {
		String url = MARIADB.getJdbcUrl();
		int lastSlash = url.lastIndexOf('/');
		int query = url.indexOf('?', lastSlash);
		String parameters = query < 0 ? "" : url.substring(query);
		return url.substring(0, lastSlash + 1) + database + parameters;
	}

	private static Connection connectTo(String database) throws SQLException {
		return java.sql.DriverManager.getConnection(
				urlFor(database), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static DriverManagerDataSource dataSourceFor(String database) {
		return new DriverManagerDataSource(
				urlFor(database), MARIADB.getUsername(), MARIADB.getPassword());
	}

}
