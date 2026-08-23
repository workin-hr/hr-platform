package com.workin.legacy.schedules;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;

/**
 * {@code schedules}' data access for Wave 12.6 slice 2 --
 * {@code assign_employee_schedule.php}.
 *
 * <h2>Tenancy is employee-derived, and the writes carry no company at all</h2>
 * <p>{@code employee_schedules} has no {@code company_id}
 * ({@code mysql_workin.schema.sql:477-486}). Both lookups below are the
 * company predicate, and the upsert then writes by {@code employee_id} alone --
 * exactly as PHP does. The tenancy guarantee is therefore entirely in the
 * order: the employee is proven to belong to the company <em>before</em> any
 * row is written, and no request value ever reaches a company predicate.
 *
 * <h2>The date is not parsed anywhere</h2>
 * <p>{@code schedule_upsert_employee_day()} binds the caller's raw string
 * straight into a {@code DATE NOT NULL} column. There is no PHP date parsing on
 * this path at all, so MariaDB's own coercion is the specification -- see
 * {@link LegacyScheduleService} for the measured table.
 */
@Repository
public class LegacyScheduleStore {

	/**
	 * {@code schedule_upsert_employee_day()}
	 * ({@code helpers/schedule_helper.php:116-140}), verbatim.
	 *
	 * <p>The {@code ON DUPLICATE KEY} arm is what makes re-assigning a date
	 * idempotent: {@code uniq_employee_schedule_date (employee_id,
	 * schedule_date)} catches the second write and replaces all four payload
	 * columns, keeping the original {@code id} and {@code created_at}.
	 * {@code exception_note} is in that list, so re-assigning a shift over a
	 * day that carried an exception note <b>clears the note</b> -- this path
	 * always passes null for it.
	 */
	private static final String UPSERT = """
			INSERT INTO employee_schedules (
				employee_id, schedule_date, name, start_time, end_time, exception_note
			) VALUES (?, ?, ?, ?, ?, ?)
			ON DUPLICATE KEY UPDATE
				name = VALUES(name),
				start_time = VALUES(start_time),
				end_time = VALUES(end_time),
				exception_note = VALUES(exception_note)""";

	private final JdbcTemplate jdbcTemplate;

	public LegacyScheduleStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** {@code SELECT id FROM employees WHERE id=? AND company_id=?}. */
	public boolean employeeExistsInCompany(long companyId, long employeeId) {
		return !jdbcTemplate.queryForList(
				"SELECT id FROM employees WHERE id=? AND company_id=?",
				Long.class, employeeId, companyId).isEmpty();
	}

	/**
	 * {@code SELECT * FROM shifts WHERE id=? AND company_id=?}.
	 *
	 * <p>The wildcard is PHP's and is kept: the endpoint reads {@code name},
	 * {@code start_time} and {@code end_time} from the row, and a column added
	 * to {@code shifts} would reach the same code with no change here. Values
	 * come back through {@link LegacyJdbcValues} so a {@code TIME} column stays
	 * the lexical {@code HH:MM:SS} string PDO returns rather than becoming a
	 * temporal object (D-096).
	 *
	 * @return the row, or null when no shift of that id belongs to the company
	 */
	public Map<String, Object> shiftForCompany(long companyId, long shiftId) {
		try {
			return jdbcTemplate.queryForObject(
					"SELECT * FROM shifts WHERE id=? AND company_id=?", rowMapper(), shiftId, companyId);
		} catch (EmptyResultDataAccessException ex) {
			return null;
		}
	}

	/** One day of the loop. Not transactional -- PHP has no transaction here. */
	public void upsertDay(long employeeId, String date, String name, String startTime, String endTime) {
		jdbcTemplate.update(UPSERT, employeeId, date, name, startTime, endTime, null);
	}

	private static RowMapper<Map<String, Object>> rowMapper() {
		return (ResultSet rs, int index) -> {
			ResultSetMetaData meta = rs.getMetaData();
			Map<String, Object> row = new LinkedHashMap<>();
			for (int column = 1; column <= meta.getColumnCount(); column++) {
				row.put(meta.getColumnLabel(column),
						LegacyJdbcValues.read(rs, column, meta.getColumnType(column)));
			}
			return row;
		};
	}

}
