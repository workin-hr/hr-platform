package com.workin.legacy;

import java.sql.PreparedStatement;
import java.sql.Statement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * {@code get_last_inserted_id()} without the race that a naive port has.
 *
 * <h2>Why {@code SELECT LAST_INSERT_ID()} is not a translation of it</h2>
 * <p>PHP's {@code PDO::lastInsertId()} reads the id from <b>the connection that
 * performed the insert</b>, and PHP holds one connection for the whole request.
 * A {@link JdbcTemplate} does not: each call borrows a connection from the pool
 * and returns it, so {@code jdbcTemplate.update(insert)} followed by
 * {@code jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()")} can run the
 * two statements on <b>different</b> connections.
 *
 * <p>{@code LAST_INSERT_ID()} is session-scoped, so the second call then
 * returns whatever the borrowed connection last inserted -- another request's
 * row under concurrency, or {@code 0} on a connection that has inserted
 * nothing. The caller goes on to re-read "its" row by that id, which is how a
 * plain lost id turns into a response carrying <b>another tenant's</b> data.
 *
 * <p>It is invisible in a single-threaded test and in any transactional path,
 * because a transaction pins one connection for its duration -- which is
 * exactly why it survives review until concurrency finds it.
 *
 * <p>This asks JDBC for the key the insert itself generated, so the value comes
 * from the same statement that produced it and no second round trip exists to
 * be misrouted.
 */
public final class LegacyGeneratedKeys {

	private LegacyGeneratedKeys() {
	}

	/**
	 * Executes {@code sql} with {@code binds} and returns the auto-increment key
	 * the statement generated, or {@code 0} when the driver reports none.
	 */
	public static long insert(JdbcTemplate jdbcTemplate, String sql, Object... binds) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement =
					connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			for (int i = 0; i < binds.length; i++) {
				statement.setObject(i + 1, binds[i]);
			}
			return statement;
		}, keyHolder);
		Number key = keyHolder.getKey();
		return key == null ? 0L : key.longValue();
	}
}
