package com.workin.legacy.attendance.records;

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
import org.springframework.stereotype.Repository;

/**
 * `attendance`'s read and delete path for Wave 12.6 slice 1a-i --
 * `one.php`, `delete.php` and `delete_range.php`.
 *
 * <h2>Tenancy is employee-derived, always</h2>
 * <p>The `attendance` table has <b>no `company_id`</b>
 * (`mysql_workin.schema.sql:121-132`). Every company predicate in this class
 * therefore reaches `employees.company_id` through a join, which is what
 * legacy does. No column is added and no denormalised company is stored.
 *
 * <h2>D-083 stays open here</h2>
 * <p>`check_in` and `check_out` are `datetime`, `created_at` and
 * `updated_at` are `timestamp`, and `attendance_record_full()` computes
 * `TIMESTAMPDIFF(MINUTE, check_in, check_out)` in the database. None of that
 * is timezone-corrected by this slice.
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

	/**
	 * `delete_range.php`'s count, over the same joined predicate the delete
	 * uses. `DATE(a.check_in)` is evaluated by MariaDB on the session
	 * timezone -- another D-083 surface.
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
				row.put(meta.getColumnLabel(column), value(rs, column, meta.getColumnType(column)));
			}
			return row;
		};
	}

	/**
	 * The same type set the Wave 12.4/12.5 stores use. Note what is
	 * deliberately absent: `DECIMAL` is not in the numeric branch, so
	 * `latitude` and `longitude` -- `decimal(10,7)` -- fall to `getString`.
	 * That is correct: mysqlnd hands PHP a string for a DECIMAL and
	 * `json_encode` renders it as a JSON string, so reading them as numbers
	 * would change the wire type.
	 */
	private static Object value(ResultSet rs, int column, int sqlType) throws SQLException {
		Object raw = switch (sqlType) {
			case Types.BIT, Types.BOOLEAN, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
				rs.getLong(column);
			default -> rs.getString(column);
		};
		return rs.wasNull() ? null : raw;
	}

}
