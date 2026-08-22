package com.workin.legacy.workforce;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyValues;

/**
 * {@code shifts}' read and write path, in legacy's own SQL.
 *
 * <h2>Why raw ordered maps</h2>
 * <p>{@code public_row($row)} returns the PDO row itself, so the key set, the
 * key order and the value types are all part of the D-074 contract. Every
 * {@code shifts} query names its eight columns explicitly and in the same
 * order, so a {@link LinkedHashMap} built from {@link ResultSetMetaData}
 * reproduces the response object exactly -- a typed DTO could not.
 *
 * <h2>The projection is deliberately not {@code SELECT *}</h2>
 * <p>Unlike {@code request_types} and {@code company_official_holidays}, every
 * {@code shifts} endpoint lists its columns. The eight are the whole table
 * today, so the two forms happen to agree; they would stop agreeing the moment
 * a column is added, and the explicit list is what PHP has.
 */
@Repository
public class LegacyShiftStore {

	/**
	 * The projection every {@code shifts} endpoint shares, in PHP's column
	 * order. {@code list}, {@code one}, and the re-reads in {@code create} and
	 * {@code update} all select exactly this.
	 */
	private static final String COLUMNS = """
			s.id,
			s.company_id,
			s.name,
			s.start_time,
			s.end_time,
			s.days_off,
			s.is_active,
			s.created_at""";

	private final JdbcTemplate jdbcTemplate;

	public LegacyShiftStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * {@code shifts/list.php}: active shifts of the company, newest first.
	 *
	 * <p>No search, no pagination and no filter parameters -- this endpoint
	 * accepts none, unlike its two sibling modules. The {@code ORDER BY} is
	 * copied literally, including the {@code id DESC} tiebreak that makes it
	 * deterministic for rows sharing a {@code created_at} second.
	 */
	public List<Map<String, Object>> list(long companyId) {
		return jdbcTemplate.query(
				"SELECT " + COLUMNS + """

						FROM shifts AS s
						WHERE s.is_active = 1 AND s.company_id = ?
						ORDER BY s.created_at DESC, s.id DESC""",
				rowMapper(), companyId);
	}

	/**
	 * {@code shifts/one.php}: id, {@code is_active = 1} and company, all three.
	 * An inactive shift is a miss here even though {@code update} and
	 * {@code delete} can still reach it.
	 */
	public Map<String, Object> activeById(long companyId, long id) {
		return single(jdbcTemplate.query(
				"SELECT " + COLUMNS + """

						FROM shifts AS s
						WHERE s.id = ? AND s.is_active = 1 AND s.company_id = ?""",
				rowMapper(), id, companyId));
	}

	/**
	 * The lookup {@code update.php} and {@code delete.php} share: id and
	 * company, and <b>not</b> {@code is_active}. A soft-deleted shift is
	 * therefore still updatable and still deletable, which is legacy's
	 * behaviour and is reproduced deliberately rather than tightened.
	 */
	public Map<String, Object> byIdForCompany(long companyId, long id) {
		return single(jdbcTemplate.query(
				"SELECT " + COLUMNS + """

						FROM shifts AS s
						WHERE s.id = ? AND s.company_id = ?""",
				rowMapper(), id, companyId));
	}

	/** The unscoped re-read {@code create.php} and {@code update.php} both do. */
	public Map<String, Object> byId(long id) {
		return single(jdbcTemplate.query(
				"SELECT " + COLUMNS + """

						FROM shifts AS s
						WHERE s.id = ?""",
				rowMapper(), id));
	}

	/**
	 * {@code shifts/create.php}'s INSERT. The four supplied values are bound as
	 * given -- {@code create.php} casts to {@code (string)} only for the
	 * window assertion and then binds {@code $request_body[...]} itself, so a
	 * non-string that survives validation reaches the driver unchanged.
	 *
	 * @param daysOff SQL NULL when absent, which is {@code ?? null}
	 * @return {@code get_last_inserted_id()}
	 */
	public long insert(long companyId, Object name, Object startTime, Object endTime, Object daysOff) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO shifts (company_id, name, start_time, end_time, days_off) VALUES (?, ?, ?, ?, ?)",
					PreparedStatement.RETURN_GENERATED_KEYS);
			statement.setLong(1, companyId);
			bind(statement, 2, name);
			bind(statement, 3, startTime);
			bind(statement, 4, endTime);
			bind(statement, 5, daysOff);
			return statement;
		}, keys);
		return keys.getKey() == null ? 0L : keys.getKey().longValue();
	}

	/**
	 * {@code shifts/update.php}'s UPDATE, {@code COALESCE} and all.
	 *
	 * <p>{@code SET col = COALESCE(?, col)} means a bound NULL preserves the
	 * stored value. That is why {@code days_off} cannot be cleared through this
	 * endpoint: a JSON {@code null} and an absent key are the same bind, and
	 * both keep whatever is already there. Reproduced, not fixed.
	 *
	 * <p>The statement is scoped by {@code id} alone, exactly as PHP writes it.
	 * That is safe only because the caller has already proved the row belongs
	 * to the company through {@link #byIdForCompany}, and it is kept literal so
	 * the two statements stay recognisably PHP's.
	 */
	public void update(long id, Object name, Object startTime, Object endTime, Object daysOff) {
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement("""
					UPDATE shifts
					SET
						name = COALESCE(?, name),
						start_time = COALESCE(?, start_time),
						end_time = COALESCE(?, end_time),
						days_off = COALESCE(?, days_off)
					WHERE id = ?""");
			bind(statement, 1, name);
			bind(statement, 2, startTime);
			bind(statement, 3, endTime);
			bind(statement, 4, daysOff);
			statement.setLong(5, id);
			return statement;
		});
	}

	/** {@code shifts/delete.php}: a soft delete, scoped by id as PHP writes it. */
	public void softDelete(long id) {
		jdbcTemplate.update("UPDATE shifts SET is_active = 0 WHERE id = ?", id);
	}

	/**
	 * PDO binds a PHP value by whatever its runtime type renders as, and every
	 * column here is a string column. {@link com.workin.legacy.LegacyValues#toPhpString}
	 * is the measured cast -- {@code true} to {@code "1"}, {@code false} and
	 * {@code null} to the empty string, an array to {@code "Array"}, floats at
	 * PHP's 14-digit precision -- and is reused rather than reimplemented, so
	 * this module cannot drift from the one the rest of Phase 1 uses.
	 *
	 * <p>A JSON {@code null} is the exception: it is SQL NULL, not the empty
	 * string, because {@code ?? null} binds a real null and {@code COALESCE}
	 * depends on it being one.
	 */
	private static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
		if (value == null) {
			statement.setNull(index, Types.VARCHAR);
		} else {
			statement.setString(index, LegacyValues.toPhpString(value));
		}
	}

	private static Map<String, Object> single(List<Map<String, Object>> rows) {
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** Column order and column names exactly as the result set declares them. */
	private static RowMapper<Map<String, Object>> rowMapper() {
		return (ResultSet rs, int index) -> {
			ResultSetMetaData meta = rs.getMetaData();
			Map<String, Object> row = new LinkedHashMap<>();
			for (int column = 1; column <= meta.getColumnCount(); column++) {
				row.put(meta.getColumnLabel(column), value(rs, column, meta.getColumnType(column)));
			}
			return row;
		};
	}

	/**
	 * Legacy runs PDO with {@code ATTR_EMULATE_PREPARES => false}
	 * ({@code apis/config/pdo.php:17}), so mysqlnd hands back native types and
	 * {@code json_encode} renders {@code id}, {@code company_id} and
	 * {@code is_active} as JSON numbers. {@code start_time}, {@code end_time},
	 * {@code days_off} and {@code created_at} are strings.
	 */
	private static Object value(ResultSet rs, int column, int sqlType) throws SQLException {
		Object raw = switch (sqlType) {
			// BIT and BOOLEAN are in the list because MariaDB reports
			// `tinyint(1)` as one of them, not as TINYINT -- which is exactly
			// how `is_active` would otherwise reach a client as the string
			// "1" instead of the number mysqlnd gives PHP. Same set as
			// LegacyEmployeeStore's, deliberately.
			case Types.BIT, Types.BOOLEAN, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
				rs.getLong(column);
			default -> rs.getString(column);
		};
		return rs.wasNull() ? null : raw;
	}

}
