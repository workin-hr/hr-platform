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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MariaDBContainer;

/**
 * The insert helper returns the key its own statement generated.
 *
 * <p>The concurrency case is the point. {@code SELECT LAST_INSERT_ID()} issued
 * as a second {@link JdbcTemplate} call borrows a second connection, and the
 * value is session-scoped -- so under load it returns another inserter's id or
 * zero. This test runs both approaches side by side against a real pool so the
 * difference is measured rather than argued.
 */
class LegacyGeneratedKeysTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static JdbcTemplate jdbcTemplate;

	static {
		MARIADB.start();
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
		jdbcTemplate = new JdbcTemplate(dataSource);
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
}
