package com.workin.legacy.workforce;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyPagination;

/**
 * {@code company_official_holidays}' read and write path.
 *
 * <p>{@code SELECT *} throughout, like {@code request_types} and unlike
 * {@code shifts}. That includes {@code created_at}, a {@code TIMESTAMP}, which
 * MariaDB renders in the session timezone -- so this module is one of the
 * places <b>D-083 stays observable</b>. Nothing here closes it: Phase 1 still
 * does not issue {@code SET time_zone} per connection, and the
 * {@code holiday_date} column is a {@code DATE}, which is not converted, so
 * only {@code created_at} moves with that setting.
 */
@Repository
public class LegacyOfficialHolidayStore {

	private final JdbcTemplate jdbcTemplate;

	public LegacyOfficialHolidayStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * {@code list.php}'s WHERE, in PHP's own order: company, then the two
	 * optional bounds, then the optional search.
	 *
	 * @param dateFrom already {@code !empty()}-filtered by the caller; null
	 *        means the clause is absent
	 */
	private static String where(String dateFrom, String dateTo, String search, List<Object> binds, long companyId) {
		StringBuilder sql = new StringBuilder("WHERE company_id=?");
		binds.add(companyId);
		if (dateFrom != null) {
			sql.append(" AND holiday_date>=?");
			binds.add(dateFrom);
		}
		if (dateTo != null) {
			sql.append(" AND holiday_date<=?");
			binds.add(dateTo);
		}
		if (search != null) {
			sql.append(" AND name LIKE ?");
			binds.add("%" + search + "%");
		}
		return sql.toString();
	}

	public long count(long companyId, String dateFrom, String dateTo, String search) {
		List<Object> binds = new ArrayList<>();
		String sql = "SELECT COUNT(*) FROM company_official_holidays "
				+ where(dateFrom, dateTo, search, binds, companyId);
		Long total = jdbcTemplate.queryForObject(sql, Long.class, binds.toArray());
		return total == null ? 0L : total;
	}

	/**
	 * {@code ORDER BY holiday_date ASC, id ASC} -- the only ascending order in
	 * the wave, and the id tiebreak is what makes a day with two holidays
	 * deterministic.
	 */
	public List<Map<String, Object>> list(
			long companyId, String dateFrom, String dateTo, String search, LegacyPagination.Params pagination) {
		List<Object> binds = new ArrayList<>();
		String sql = "SELECT * FROM company_official_holidays "
				+ where(dateFrom, dateTo, search, binds, companyId)
				+ " ORDER BY holiday_date ASC, id ASC LIMIT ? OFFSET ?";
		binds.add(pagination.limit());
		binds.add(pagination.offset());
		return jdbcTemplate.query(sql, rowMapper(), binds.toArray());
	}

	/**
	 * {@code official_holiday_assert_company_row($company_id, $id)} -- the
	 * second and last helper D-090 admits from
	 * {@code official_holidays_helper.php}.
	 *
	 * <p>The two guards are PHP's own: a non-positive company or id returns
	 * null <em>without</em> querying, so {@code ?id=0} and {@code ?id=abc}
	 * (which casts to 0) never reach the database.
	 */
	public Map<String, Object> assertCompanyRow(long companyId, long id) {
		if (companyId <= 0 || id <= 0) {
			return null;
		}
		return single(jdbcTemplate.query(
				"SELECT * FROM company_official_holidays WHERE id = ? AND company_id = ?",
				rowMapper(), id, companyId));
	}

	/** {@code create.php}'s per-date existence probe on the unique pair. */
	public Long existingIdForDate(long companyId, String holidayDate) {
		List<Long> ids = jdbcTemplate.queryForList(
				"SELECT id FROM company_official_holidays WHERE company_id = ? AND holiday_date = ?",
				Long.class, companyId, holidayDate);
		return ids.isEmpty() ? null : ids.get(0);
	}

	/**
	 * {@code create.php}'s update branch: {@code SET name = ? WHERE id = ?},
	 * scoped by id alone because the row was just found for this company.
	 */
	public void updateNameById(long id, String name) {
		jdbcTemplate.update("UPDATE company_official_holidays SET name = ? WHERE id = ?", name, id);
	}

	public long insert(long companyId, String name, String holidayDate) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO company_official_holidays (company_id, name, holiday_date) VALUES (?, ?, ?)",
					PreparedStatement.RETURN_GENERATED_KEYS);
			statement.setLong(1, companyId);
			statement.setString(2, name);
			statement.setString(3, holidayDate);
			return statement;
		}, keys);
		return keys.getKey() == null ? 0L : keys.getKey().longValue();
	}

	/**
	 * {@code update.php}'s conflict pre-check on the unique pair, excluding the
	 * row being edited. It is a pre-check only: {@code uq_company_holiday_date}
	 * is the real guarantee, and a concurrent writer can still lose to the
	 * constraint after this returns clean.
	 */
	public boolean conflictExists(long companyId, String holidayDate, long excludedId) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM company_official_holidays"
						+ " WHERE company_id = ? AND holiday_date = ? AND id <> ?",
				Long.class, companyId, holidayDate, excludedId);
		return count != null && count > 0;
	}

	public void update(long companyId, long id, String name, String holidayDate) {
		jdbcTemplate.update(
				"UPDATE company_official_holidays SET name = ?, holiday_date = ?"
						+ " WHERE id = ? AND company_id = ?",
				name, holidayDate, id, companyId);
	}

	/** A hard delete, company-scoped. */
	public void delete(long companyId, long id) {
		jdbcTemplate.update(
				"DELETE FROM company_official_holidays WHERE id = ? AND company_id = ?", id, companyId);
	}

	private static Map<String, Object> single(List<Map<String, Object>> rows) {
		return rows.isEmpty() ? null : rows.get(0);
	}

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

	/** The same type set the other two stores use; see {@link LegacyShiftStore}. */
	private static Object value(ResultSet rs, int column, int sqlType) throws SQLException {
		Object raw = switch (sqlType) {
			case Types.BIT, Types.BOOLEAN, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
				rs.getLong(column);
			default -> rs.getString(column);
		};
		return rs.wasNull() ? null : raw;
	}

}
