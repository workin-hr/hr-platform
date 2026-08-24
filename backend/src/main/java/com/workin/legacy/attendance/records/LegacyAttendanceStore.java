package com.workin.legacy.attendance.records;

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

import com.workin.legacy.LegacyJdbcValues;

/**
 * `attendance`'s data access for Wave 12.6 slices 1a-i and 1a-ii --
 * `one.php`, `create.php`, `update.php`, `delete.php` and `delete_range.php`.
 *
 * <h2>Tenancy is employee-derived, always</h2>
 * <p>The `attendance` table has <b>no `company_id`</b>
 * (`mysql_workin.schema.sql:121-132`). Every company predicate in this class
 * therefore reaches `employees.company_id` through a join, which is what
 * legacy does. No column is added and no denormalised company is stored.
 *
 * <h2>D-083 stays open here, but not everywhere</h2>
 * <p>`check_in` and `check_out` are `DATETIME`: MariaDB stores and returns
 * them as wall-clock values with no session-timezone conversion, so neither
 * they nor `DATE(check_in)` nor `TIMESTAMPDIFF` between them moves with
 * `time_zone`. `created_at` and `updated_at` are `TIMESTAMP` and <em>are</em>
 * converted on read -- that is this slice's D-083 exposure, and it is not
 * corrected here.
 */
@Repository
public class LegacyAttendanceStore {

	/**
	 * `attendance_record_full($company_id, $attendance_id)`
	 * (`apis/helpers/functions.php:912-941`) -- `a.*` plus six derived
	 * columns, in that order.
	 *
	 * <p>`a.*` means a column added to `attendance` appears in the API with no
	 * code change, so the projection is kept as the wildcard PHP uses rather
	 * than expanded into a column list. The employee display name is
	 * `sql_employee_display_name('e')` verbatim
	 * (`functions.php:169-176`): `TRIM(CONCAT(COALESCE(first_name,''),' ',
	 * COALESCE(last_name,'')))`, which yields `''` for an employee with
	 * neither name, not NULL.
	 */
	private static final String RECORD_FULL = """
			SELECT
				a.*,
				et.name AS exception_type_name,
				TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,''))) AS employee_name,
				TIMESTAMPDIFF(MINUTE, a.check_in, a.check_out) AS duration_minutes,
				br.name AS branch_name,
				s.name AS department_name,
				jt.name AS job_title_name
			FROM attendance AS a
			JOIN employees AS e ON e.id = a.employee_id
			LEFT JOIN exception_types AS et ON et.id = a.exception_type_id
			LEFT JOIN branches AS br ON e.branch_id = br.id
			LEFT JOIN departments AS s ON s.id = e.department_id
			LEFT JOIN job_titles AS jt ON jt.id = e.job_title_id
			WHERE a.id=? AND e.company_id=?""";

	private final JdbcTemplate jdbcTemplate;

	public LegacyAttendanceStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * `attendance_record_full()`. The two non-positive guards are PHP's own
	 * and short-circuit <em>before</em> any query, so `?id=0` never reaches
	 * the database.
	 */
	public Map<String, Object> recordFull(long companyId, long attendanceId) {
		if (companyId <= 0 || attendanceId <= 0) {
			return null;
		}
		return single(jdbcTemplate.query(RECORD_FULL, rowMapper(), attendanceId, companyId));
	}

	/** `delete.php`'s existence probe: the id joined to the caller's company. */
	public boolean existsForCompany(long companyId, long attendanceId) {
		Long count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM attendance AS a
				JOIN employees AS e ON e.id = a.employee_id
				WHERE a.id=? AND e.company_id=?""", Long.class, attendanceId, companyId);
		return count != null && count > 0;
	}

	/**
	 * `delete.php`'s hard delete. Scoped by id alone because
	 * {@link #existsForCompany} has already proved the row belongs to the
	 * caller's company -- PHP's own two-step, kept as two steps.
	 */
	public void deleteById(long id) {
		jdbcTemplate.update("DELETE FROM attendance WHERE id=?", id);
	}

	/** `create.php`'s employee probe -- a count, not a row. */
	public boolean employeeExistsInCompany(long companyId, long employeeId) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE id=? AND company_id=?",
				Long.class, employeeId, companyId);
		return count != null && count > 0;
	}

	/**
	 * D-095's preflight for `update.php`: does this exception type belong to
	 * the caller's company?
	 *
	 * <p>Company-scoped by predicate rather than by reading the foreign row's
	 * `company_id` and comparing in Java -- the foreign value is never fetched,
	 * so it cannot leak into a log, a message or a debugger. **No
	 * `is_active` clause**: `update.php` must keep accepting a same-company
	 * inactive exception type, unlike `create.php`.
	 */
	public boolean exceptionTypeBelongsToCompany(long companyId, long exceptionTypeId) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM exception_types WHERE id = ? AND company_id = ?",
				Long.class, exceptionTypeId, companyId);
		return count != null && count > 0;
	}

	/**
	 * `create.php`'s previous-punch probe: the employee's latest `check_in`,
	 * ordered by `check_in` and **not** by id, so a back-dated row can win.
	 */
	public String latestCheckIn(long employeeId) {
		List<String> rows = jdbcTemplate.queryForList(
				"SELECT check_in FROM attendance WHERE employee_id=? ORDER BY check_in DESC LIMIT 1",
				String.class, employeeId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * `SELECT TIMESTAMPDIFF(MINUTE, ?, ?)` -- evaluated by MariaDB, not in
	 * Java, because the comparison legacy performs is the database's and both
	 * operands may be strings it coerces.
	 *
	 * @return null when either operand does not coerce, which is SQL NULL
	 */
	public Long minutesBetween(String from, String to) {
		return jdbcTemplate.queryForObject("SELECT TIMESTAMPDIFF(MINUTE, ?, ?)", Long.class, from, to);
	}

	/**
	 * `create.php`'s INSERT.
	 *
	 * <p>The raw request strings are bound unchanged and MariaDB coerces them,
	 * exactly as PDO does. Measured under `sql_mode=''`: `'2026-01-15'` stores
	 * `2026-01-15 00:00:00`, while `'now'`, `'1990'`, `'0830'`, `'2026-02-30'`
	 * and `'oops'` all store `0000-00-00 00:00:00`. The parser's opinion of
	 * those strings is a separate matter and must not be substituted here.
	 */
	public long insert(long employeeId, String checkIn, String checkOut, Object method, Long exceptionTypeId) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO attendance (employee_id, check_in, check_out, method, exception_type_id)"
							+ " VALUES (?, ?, ?, ?, ?)",
					PreparedStatement.RETURN_GENERATED_KEYS);
			statement.setLong(1, employeeId);
			statement.setString(2, checkIn);
			bindNullable(statement, 3, checkOut);
			statement.setString(4, com.workin.legacy.LegacyValues.toPhpString(method));
			bindNullableId(statement, 5, exceptionTypeId);
			return statement;
		}, keys);
		return keys.getKey() == null ? 0L : keys.getKey().longValue();
	}

	/** `update.php`'s UPDATE -- id-only, after the scoped read proved ownership. */
	public void update(long id, String checkIn, String checkOut, Long exceptionTypeId) {
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
					"UPDATE attendance SET check_in = ?, check_out = ?, exception_type_id = ? WHERE id = ?");
			statement.setString(1, checkIn);
			bindNullable(statement, 2, checkOut);
			bindNullableId(statement, 3, exceptionTypeId);
			statement.setLong(4, id);
			return statement;
		});
	}

	private static void bindNullable(PreparedStatement statement, int index, String value) throws SQLException {
		if (value == null) {
			statement.setNull(index, Types.VARCHAR);
		} else {
			statement.setString(index, value);
		}
	}

	private static void bindNullableId(PreparedStatement statement, int index, Long value) throws SQLException {
		if (value == null) {
			statement.setNull(index, Types.INTEGER);
		} else {
			statement.setLong(index, value);
		}
	}

	/**
	 * `delete_range.php`'s count, over the same joined predicate the delete
	 * uses.
	 *
	 * <p><b>This predicate is not D-083-sensitive.</b> `check_in` is a
	 * `DATETIME`, which MariaDB stores and returns as a wall-clock value with
	 * no session-timezone conversion, so `DATE(a.check_in)` yields the same
	 * day whatever `time_zone` is set to. Only `TIMESTAMP` columns convert.
	 *
	 * <p>D-083 does still affect this module, through
	 * {@link #recordFull}: `created_at` and `updated_at` are `TIMESTAMP` and
	 * are converted on read, and the attendance paths in later slices write
	 * with the database's own `NOW()`.
	 */
	public long countInRange(long companyId, String from, String to) {
		Long count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM attendance AS a
				JOIN employees AS e ON e.id = a.employee_id
				WHERE e.company_id = ?
				  AND DATE(a.check_in) >= ?
				  AND DATE(a.check_in) <= ?""", Long.class, companyId, from, to);
		return count == null ? 0L : count;
	}

	/**
	 * `delete_range.php`'s joined delete. Deliberately a <b>second</b>
	 * statement rather than a count derived from the delete's row count: PHP
	 * counts first, decides, then deletes, and a concurrent write between the
	 * two is legacy-observable. Collapsing them would hide that.
	 */
	public void deleteInRange(long companyId, String from, String to) {
		jdbcTemplate.update("""
				DELETE a FROM attendance AS a
				JOIN employees AS e ON e.id = a.employee_id
				WHERE e.company_id = ?
				  AND DATE(a.check_in) >= ?
				  AND DATE(a.check_in) <= ?""", companyId, from, to);
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
