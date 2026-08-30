package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.containers.MariaDBContainer;

/**
 * The insert helper returns the key its own statement generated.
 *
 * <p>The concurrency case is the point. {@code SELECT LAST_INSERT_ID()} issued
 * as a second {@link JdbcTemplate} call borrows a second connection, and the
 * value is session-scoped -- so under load it returns another inserter's id or
 * zero.
 *
 * <p>Both approaches run against the <b>same real Hikari pool</b>, so the
 * difference is measured rather than argued: the helper's case asserts every
 * caller reads back its own row, and the two-call case asserts that the old
 * implementation does not.
 */
class LegacyGeneratedKeysTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static JdbcTemplate jdbcTemplate;

	static {
		MARIADB.start();
		// A REAL pool, deliberately. DriverManagerDataSource opens a fresh
		// connection per operation, so the old two-call form would always meet a
		// virgin session and return 0 -- the failure would show, but for the
		// wrong reason, and it would not demonstrate the misrouting that happens
		// in production. A small Hikari pool makes connections genuinely
		// reusable, so a second statement can land on one another thread just
		// inserted through.
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(MARIADB.getJdbcUrl());
		config.setUsername(MARIADB.getUsername());
		config.setPassword(MARIADB.getPassword());
		config.setMaximumPoolSize(4);
		jdbcTemplate = new JdbcTemplate(new HikariDataSource(config));
		try (Connection connection = DriverManager.getConnection(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
				Statement st = connection.createStatement()) {
			st.execute("CREATE TABLE keyed (id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,"
					+ " tag VARCHAR(64) NOT NULL)");
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Test
	void theKeyComesBackFromTheInsertItself() {
		long first = LegacyGeneratedKeys.insert(jdbcTemplate,
				"INSERT INTO keyed (tag) VALUES (?)", "a");
		long second = LegacyGeneratedKeys.insert(jdbcTemplate,
				"INSERT INTO keyed (tag) VALUES (?)", "b");

		assertThat(first).isPositive();
		assertThat(second).isGreaterThan(first);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT tag FROM keyed WHERE id = ?", String.class, first)).isEqualTo("a");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT tag FROM keyed WHERE id = ?", String.class, second)).isEqualTo("b");
	}

	/**
	 * Every concurrent caller must get back the row it actually inserted.
	 *
	 * <p>Each thread tags its row uniquely and then reads the tag back by the
	 * returned id. A misrouted key shows up as a tag belonging to a different
	 * thread -- which, in the endpoints this helper serves, is another tenant's
	 * row being returned as the caller's own.
	 */
	@Test
	void concurrentInsertsEachReceiveTheirOwnKey() throws Exception {
		int threads = 12;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Callable<String>> work = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				String tag = "concurrent-" + i;
				work.add(() -> {
					long id = LegacyGeneratedKeys.insert(jdbcTemplate,
							"INSERT INTO keyed (tag) VALUES (?)", tag);
					String readBack = jdbcTemplate.queryForObject(
							"SELECT tag FROM keyed WHERE id = ?", String.class, id);
					return tag.equals(readBack) ? "ok" : tag + " read back as " + readBack;
				});
			}
			List<String> results = new ArrayList<>();
			for (Future<String> future : pool.invokeAll(work)) {
				results.add(future.get());
			}
			assertThat(results).as("every caller must read back its own row").containsOnly("ok");
		} finally {
			pool.shutdownNow();
		}
	}

	/**
	 * The implementation this helper replaced, shown failing <b>deterministically</b>.
	 *
	 * <p>An earlier version of this case raced twelve threads and asserted the
	 * results were not all {@code ok}. That was unsound in the failing
	 * direction: if the scheduler lets every worker reacquire the same
	 * connection for its two adjacent calls -- legal and common -- all results
	 * are {@code ok} and the assertion fails while
	 * {@link LegacyGeneratedKeys} is perfectly correct. A test that can fail
	 * for the absence of a race proves nothing about the presence of one.
	 *
	 * <p>A <b>single-connection pool</b> removes the scheduling entirely. Two
	 * inserts run in sequence, each borrowing and returning the one connection;
	 * a following {@code SELECT LAST_INSERT_ID()} borrows that same connection
	 * and reports the <em>second</em> insert's id. No concurrency, no timing,
	 * and exactly the misrouting the helper exists to prevent: a caller
	 * receiving an id that describes somebody else's row.
	 */
	@Test
	void theTwoCallFormReportsAnotherInsertsKey() throws Exception {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(MARIADB.getJdbcUrl());
		config.setUsername(MARIADB.getUsername());
		config.setPassword(MARIADB.getPassword());
		config.setMaximumPoolSize(1);
		try (HikariDataSource single = new HikariDataSource(config)) {
			JdbcTemplate shared = new JdbcTemplate(single);

			shared.update("INSERT INTO keyed (tag) VALUES (?)", "first-caller");
			shared.update("INSERT INTO keyed (tag) VALUES (?)", "second-caller");

			Long reported = shared.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
			String describes = shared.queryForObject(
					"SELECT tag FROM keyed WHERE id = ?", String.class, reported);

			assertThat(describes)
					.as("the second call reports the connection's last insert, not the caller's")
					.isEqualTo("second-caller");

			// And the helper, on the same pool, hands back the caller's own row.
			long own = LegacyGeneratedKeys.insert(shared,
					"INSERT INTO keyed (tag) VALUES (?)", "third-caller");
			assertThat(shared.queryForObject("SELECT tag FROM keyed WHERE id = ?", String.class, own))
					.isEqualTo("third-caller");
		}
	}
}
