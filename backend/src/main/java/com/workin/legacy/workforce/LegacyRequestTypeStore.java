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

import com.workin.legacy.LegacyJdbcValues;

import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyValues;

/**
 * {@code request_types}' read and write path.
 *
 * <h2>{@code SELECT *}, not a projection</h2>
 * <p>Unlike {@code shifts}, every {@code request_types} query is
 * {@code SELECT *}, so the response carries the table's columns in schema
 * order and a column added to the table would appear in the API without any
 * code change. Reproduced literally: naming the nine columns would be a
 * different contract the day the schema moves.
 */
@Repository
public class LegacyRequestTypeStore {

	private final JdbcTemplate jdbcTemplate;

	public LegacyRequestTypeStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * {@code request_types/list.php}'s WHERE clause, built in PHP's own order:
	 * company, then {@code is_active}, then the optional search.
	 *
	 * @param isActive already coerced by the caller; null means the key was
	 *        absent and the default {@code is_active = 1} applies
	 */
	private static String where(Long isActive, String search, List<Object> binds, long companyId) {
		StringBuilder sql = new StringBuilder("WHERE company_id=?");
		binds.add(companyId);
		if (isActive == null) {
			sql.append(" AND is_active=1");
		} else {
			sql.append(" AND is_active=?");
			binds.add(isActive);
		}
		if (search != null) {
			sql.append(" AND name LIKE ?");
			binds.add("%" + search + "%");
		}
		return sql.toString();
	}

	public long count(long companyId, Long isActive, String search) {
		List<Object> binds = new ArrayList<>();
		String sql = "SELECT COUNT(*) FROM request_types " + where(isActive, search, binds, companyId);
		Long total = jdbcTemplate.queryForObject(sql, Long.class, binds.toArray());
		return total == null ? 0L : total;
	}

	/** {@code ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?}. */
	public List<Map<String, Object>> list(
			long companyId, Long isActive, String search, LegacyPagination.Params pagination) {
		List<Object> binds = new ArrayList<>();
		String sql = "SELECT * FROM request_types " + where(isActive, search, binds, companyId)
				+ " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
		binds.add(pagination.limit());
		binds.add(pagination.offset());
		return jdbcTemplate.query(sql, rowMapper(), binds.toArray());
	}

	/** The company-scoped single row {@code one.php} and {@code delete.php} share. */
	public Map<String, Object> byIdForCompany(long companyId, long id) {
		return single(jdbcTemplate.query(
				"SELECT * FROM request_types WHERE id = ? AND company_id = ?", rowMapper(), id, companyId));
	}

	/** The re-read after an INSERT, by id alone -- the id came from this connection. */
	public Map<String, Object> byId(long id) {
		return single(jdbcTemplate.query("SELECT * FROM request_types WHERE id=?", rowMapper(), id));
	}

	public long insert(
			long companyId, Object name, long isActive, int deductBalance, int countsAsPaidLeave,
			int addAttendanceException, Long exceptionTypeId) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO request_types (
						company_id, name, is_active, deduct_balance,
						counts_as_paid_leave, add_attendance_exception, exception_type_id
					) VALUES (?, ?, ?, ?, ?, ?, ?)""", PreparedStatement.RETURN_GENERATED_KEYS);
			statement.setLong(1, companyId);
			statement.setString(2, LegacyValues.toPhpString(name));
			statement.setLong(3, isActive);
			statement.setInt(4, deductBalance);
			statement.setInt(5, countsAsPaidLeave);
			statement.setInt(6, addAttendanceException);
			if (exceptionTypeId == null) {
				statement.setNull(7, Types.INTEGER);
			} else {
				statement.setLong(7, exceptionTypeId);
			}
			return statement;
		}, keys);
		return keys.getKey() == null ? 0L : keys.getKey().longValue();
	}

	/**
	 * {@code update.php}'s whitelisted UPDATE, scoped by id <b>and</b>
	 * company_id exactly as PHP writes it. A foreign id therefore matches zero
	 * rows and writes nothing; the caller then discovers that through the
	 * company-scoped re-read (D-088), not through a row count.
	 */
	public void update(long companyId, long id, List<String> columns, List<Object> values) {
		StringBuilder sql = new StringBuilder("UPDATE request_types SET ");
		for (int index = 0; index < columns.size(); index++) {
			if (index > 0) {
				sql.append(", ");
			}
			sql.append('`').append(columns.get(index)).append("`=?");
		}
		sql.append(" WHERE id = ? AND company_id = ?");

		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(sql.toString());
			int position = 1;
			for (Object value : values) {
				bind(statement, position++, value);
			}
			statement.setLong(position++, id);
			statement.setLong(position, companyId);
			return statement;
		});
	}

	/**
	 * {@code delete.php}'s in-use check. The join through {@code employees} is
	 * what scopes it to the caller's company, so a request row belonging to
	 * <em>another</em> company's employee is invisible here and does not raise
	 * {@code request_type_in_use}.
	 *
	 * <p>That is <b>not</b> the same as saying it does not block the delete.
	 * {@code fk_request_request_type} ({@code mysql_workin.schema.sql:1688})
	 * has no {@code ON DELETE} clause, so the database restricts while any
	 * request row references the type, whoever owns it. A foreign-only
	 * reference therefore passes this check and is then refused by the
	 * constraint, and the uncaught exception becomes D-084's deterministic
	 * 500. Reproduced deliberately: broadening this query, or catching the
	 * constraint error, would each be a new divergence.
	 */
	public long inUseCount(long companyId, long requestTypeId) {
		Long count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM requests AS r
				INNER JOIN employees AS e ON e.id = r.employee_id
				WHERE r.request_type_id = ? AND e.company_id = ?""",
				Long.class, requestTypeId, companyId);
		return count == null ? 0L : count;
	}

	/** A hard delete, company-scoped. */
	public void delete(long companyId, long id) {
		jdbcTemplate.update("DELETE FROM request_types WHERE id = ? AND company_id = ?", id, companyId);
	}

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

	private static RowMapper<Map<String, Object>> rowMapper() {
		return (ResultSet rs, int index) -> {
			ResultSetMetaData meta = rs.getMetaData();
			Map<String, Object> row = new LinkedHashMap<>();
			for (int column = 1; column <= meta.getColumnCount(); column++) {
				row.put(meta.getColumnLabel(column), LegacyJdbcValues.read(rs, column, meta.getColumnType(column)));
			}
			return row;
		};
	}

}
