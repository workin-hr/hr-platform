package com.workin.legacy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.testcontainers.containers.MariaDBContainer;

/**
 * One MariaDB for the whole test JVM, and a fresh database inside it per class.
 *
 * <p><b>Measured.</b> Eighty-three test classes each started their own
 * {@code mariadb:11.8}. Starting a container and waiting for it to accept
 * connections costs <b>3.3 s</b>; creating a database and applying both schema
 * files to it costs <b>0.58 s</b>. So the suite spent about four minutes
 * starting databases it could have started once — on a 2 vCPU runner, out of a
 * fifteen-minute build.
 *
 * <p><b>What is shared and what is not.</b> The container is shared; the
 * database is not. Each caller gets its own, with its own copy of the schema,
 * so no class can see another's rows and nothing about test isolation changes.
 * That is the whole point of doing it this way rather than handing everyone the
 * same database: the classes were written against a private one and some of
 * them count rows.
 *
 * <p><b>What this does not fix.</b> Each class still boots its own Spring
 * context, because the JDBC URL differs per database and Spring's context cache
 * is keyed on the property set. Collapsing those needs the classes to agree on
 * one database, which is the change this deliberately does not make.
 *
 * <p>The container is never stopped: {@code @Testcontainers} would stop it
 * after each class, which is exactly the cost being removed, and Ryuk reaps it
 * at JVM exit.
 */
public final class LegacyMariaDb {

	/**
	 * One container serves every class in the fork, and each class's Spring
	 * context keeps its own connection pool open for as long as Spring's test
	 * context cache holds it. Twenty-five classes' pools plus their fixtures
	 * passed MariaDB's default ceiling of 151 -- "Too many connections", first
	 * seen as a schema apply failing inside a class initialiser. The image's
	 * entrypoint prepends {@code mariadbd} to an argument list that starts with
	 * a dash, so this is the server option and nothing else changes.
	 */
	private static final MariaDBContainer<?> CONTAINER = new MariaDBContainer<>("mariadb:11.8")
			.withCommand("--max-connections=1000");

	private static final AtomicInteger NEXT = new AtomicInteger();

	/** Present in every MariaDB, and the one the root connection can open. */
	private static final String ROOT_DATABASE = "mysql";

	static {
		CONTAINER.start();
	}


	/**
	 * A database of this class's own, with the legacy schema applied.
	 *
	 * <p>The accessors are named as {@link MariaDBContainer}'s are, so a class
	 * that already holds a container in a {@code MARIADB} field keeps every
	 * other line it had.
	 */
	public static final class Handle {

		private final String jdbcUrl;

		private final String username;

		private final String password;

		private Handle(String jdbcUrl, String username, String password) {
			this.jdbcUrl = jdbcUrl;
			this.username = username;
			this.password = password;
		}

		public String getJdbcUrl() {
			return this.jdbcUrl;
		}

		public String getUsername() {
			return this.username;
		}

		public String getPassword() {
			return this.password;
		}

		/** The database this handle names, as {@link MariaDBContainer} reports its own. */
		public String getDatabaseName() {
			String path = this.jdbcUrl.substring(this.jdbcUrl.lastIndexOf('/') + 1);
			int query = path.indexOf('?');
			return query < 0 ? path : path.substring(0, query);
		}

		public Connection connect() throws SQLException {
			return DriverManager.getConnection(this.jdbcUrl, this.username, this.password);
		}
	}

	/**
	 * An empty database, for the handful of classes that create their own
	 * tables rather than using the legacy schema.
	 *
	 * <p>They were starting a bare container and never calling {@code
	 * applySchema}; handing them the schema'"'"'d database would collide with the
	 * tables they create -- {@code Table 'configs' already exists}, which is
	 * exactly what a first attempt produced.
	 */
	public static Handle emptyDatabase() {
		return create(false);
	}

	/** A database with the legacy schema and the Phase 1 extensions applied. */
	public static Handle freshDatabase() {
		return create(true);
	}

	private static Handle create(boolean withSchema) {
		String name = "t" + NEXT.incrementAndGet() + "_" + Long.toString(System.nanoTime(), 36);
		// As root, because the container's own user has rights on its own
		// database and nothing else -- it can neither create one nor be granted
		// on it. Testcontainers sets the root password to the same value.
		try (Connection connection = DriverManager.getConnection(
				urlFor(ROOT_DATABASE), "root", CONTAINER.getPassword());
				Statement statement = connection.createStatement()) {
			// The name is generated here from a counter and a timestamp, never
			// from a caller, so there is nothing to parameterise -- and DDL
			// takes no bind parameters anyway.
			statement.execute("CREATE DATABASE `" + name + "`");
			statement.execute("GRANT ALL PRIVILEGES ON `" + name + "`.* TO '"
					+ CONTAINER.getUsername() + "'@'%'");
		}
		catch (SQLException ex) {
			throw new IllegalStateException("could not create a test database", ex);
		}

		Handle handle = new Handle(urlFor(name), CONTAINER.getUsername(), CONTAINER.getPassword());
		if (withSchema) {
			applySchema(handle, "legacy/mysql_workin.schema.sql");
			applySchema(handle, "db/phase1-mysql/phase1_extensions.sql");
		}
		return handle;
	}

	/** The container's URL with its database segment swapped for another. */
	private static String urlFor(String database) {
		return CONTAINER.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
	}

	/**
	 * Applies one schema file's statements.
	 *
	 * <p>Split on a {@code ;} at end of line, which is sufficient because
	 * neither file contains routines, triggers or views — independently
	 * inventoried as zero of each for the vendored file (ADR-0004), and not
	 * used by the Phase 1 extension file by construction. That is also why no
	 * {@code DELIMITER} handling is needed.
	 *
	 * <p>{@code sql_mode = ''} because production runs non-strict, and the
	 * vendored schema carries zero-date defaults a strict session refuses.
	 */
	private static void applySchema(Handle handle, String resource) {
		String sql;
		try (InputStream stream = LegacyMariaDb.class.getClassLoader().getResourceAsStream(resource)) {
			if (stream == null) {
				throw new IllegalStateException("missing schema resource: " + resource);
			}
			sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (Exception ex) {
			throw new IllegalStateException("could not read " + resource, ex);
		}
		try (Connection connection = handle.connect(); Statement statement = connection.createStatement()) {
			statement.execute("SET SESSION sql_mode = ''");
			for (String piece : sql.split(";\\s*\\R")) {
				if (!piece.isBlank()) {
					statement.execute(piece);
				}
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException("could not apply " + resource, ex);
		}
	}

}
