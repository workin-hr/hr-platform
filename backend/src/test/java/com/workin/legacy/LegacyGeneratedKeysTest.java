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
	 * The implementation this helper replaced, run against the same pool.
	 *
	 * <p>{@code jdbcTemplate.update(...)} followed by
	 * {@code queryForObject("SELECT LAST_INSERT_ID()")} borrows two connections.
	 * The value is session-scoped, so the second returns whatever that
	 * connection last inserted -- another thread's row, or {@code 0} on one that
	 * has inserted nothing.
	 *
	 * <p>Asserting that failure directly is what makes the fix's claim
	 * measurable. Without it the suite would only show that the new code works,
	 * never that the old code did not.
	 */
	@Test
	void theTwoCallFormMisroutesKeysUnderAPool() throws Exception {
		int threads = 12;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Callable<String>> work = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				String tag = "two-call-" + i;
				work.add(() -> {
					jdbcTemplate.update("INSERT INTO keyed (tag) VALUES (?)", tag);
					Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
					if (id == null || id == 0L) {
						return "lost";
					}
					String readBack = jdbcTemplate.queryForObject(
							"SELECT tag FROM keyed WHERE id = ?", String.class, id);
					return tag.equals(readBack) ? "ok" : "misrouted";
				});
			}
			List<String> results = new ArrayList<>();
			for (Future<String> future : pool.invokeAll(work)) {
				results.add(future.get());
			}
			assertThat(results.stream().allMatch("ok"::equals))
					.as("the two-call form must NOT be reliable -- if this ever comes back all-ok "
							+ "the pool is not being shared and the comparison above proves nothing "
							+ "(results: %s)", results)
					.isFalse();
		} finally {
			pool.shutdownNow();
		}
	}
}
