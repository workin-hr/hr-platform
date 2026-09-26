package com.workin.legacy.attendance.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Every attendance row a report reads, for every employee in it, in one
 * statement per {@link LegacyIdBatches batch} (D-292).
 *
 * <p>Legacy reads attendance once per employee, and the port did too -- four
 * times per employee in the overall report, twice in the range calendar -- each
 * a variation on one {@code SELECT ... FROM attendance WHERE employee_id = ? AND
 * DATE(check_in) BETWEEN ? AND ?}. This is that read for a whole roster and the
 * widest window any of those variations asked for; each consumer then takes its
 * own window of it with {@link #between}.
 *
 * <h2>The row order is the per-employee query's</h2>
 * <p>Two consumers depend on it: the range calendar keeps the <em>last</em>
 * row of a date and the period work minutes keep the <em>first</em>. The
 * per-employee queries ordered by {@code check_in} alone and were answered from
 * {@code index_employee_date (employee_id, check_in)}, which carries the primary
 * key as its last column, so two rows with an identical {@code check_in} came
 * back in {@code id} order. This statement says so explicitly --
 * {@code employee_id, check_in, id} -- rather than inheriting it from a plan.
 *
 * <h2>The date filter is on the column, not on {@code DATE()}</h2>
 * <p>{@code DATE(check_in) BETWEEN from AND to} and {@code check_in >= from AND
 * check_in < to + 1 day} select the same rows of a {@code DATETIME NOT NULL}
 * column. MariaDB 11.8 plans both as the same range over
 * {@code index_employee_date}, because since 11.1 it rewrites a {@code DATE()}
 * comparison into the column form itself -- measured in D-292. The column form
 * is written out so the bound on {@code check_in} does not depend on that
 * rewrite: without it, the roster's whole attendance history would be read and
 * filtered.
 */
@Component
public class LegacyAttendanceRangeRows {

	private static final String OWNER = "range_employee_id";

	private static final String ROWS = """
			SELECT a.employee_id AS range_employee_id, a.id, a.check_in, a.check_out, a.exception_type_id,
				et.name AS exception_type_name,
				TIMESTAMPDIFF(MINUTE, a.check_in, a.check_out) AS duration_minutes
			FROM attendance a
			LEFT JOIN exception_types et ON et.id = a.exception_type_id
			WHERE a.employee_id IN (%s)
			  AND a.check_in >= ?
			  AND a.check_in < ?
			ORDER BY a.employee_id ASC, a.check_in ASC, a.id ASC""";

	private final JdbcTemplate jdbcTemplate;

	public LegacyAttendanceRangeRows(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * One attendance row, as every per-employee read of it saw it.
	 *
	 * @param exceptionTypeName null when the row has no exception type, or when
	 *        its type no longer exists -- {@code exception_types.name} is
	 *        {@code NOT NULL}, so null means no matching type
	 * @param durationMinutes {@code TIMESTAMPDIFF} read with {@code getInt}, so
	 *        zero when there is no check-out, exactly as before
	 */
	public record Row(
			long id, String checkIn, String checkOut, Object exceptionTypeId, String exceptionTypeName,
			int durationMinutes) {

		/** The first ten characters of {@code check_in}: {@code DATE(check_in)} for a stored datetime. */
		public String dateKey() {
			return checkIn.length() >= 10 ? checkIn.substring(0, 10) : checkIn;
		}
	}

	/**
	 * Every row whose check-in date falls in {@code [from, to]}, grouped by
	 * employee in the order above. An employee with no rows is absent from the
	 * map, not mapped to an empty list.
	 *
	 * @param from an ISO date, inclusive
	 * @param to   an ISO date, inclusive
	 */
	public Map<Long, List<Row>> byEmployee(Collection<Long> employeeIds, String from, String to) {
		Map<Long, List<Row>> byEmployee = new HashMap<>();
		List<Long> ids = LegacyIdBatches.usable(employeeIds);
		if (ids.isEmpty() || to.compareTo(from) < 0) {
			return byEmployee;
		}
		String endExclusive = LocalDate.parse(to).plusDays(1).toString();
		for (List<Long> batch : LegacyIdBatches.of(ids)) {
			Object[] args = new Object[batch.size() + 2];
			for (int i = 0; i < batch.size(); i++) {
				args[i] = batch.get(i);
			}
			args[batch.size()] = from;
			args[batch.size() + 1] = endExclusive;
			jdbcTemplate.query(ROWS.formatted(LegacyIdBatches.placeholders(batch.size())), rs -> {
				byEmployee.computeIfAbsent(rs.getLong(OWNER), key -> new ArrayList<>()).add(new Row(
						rs.getLong("id"), rs.getString("check_in"), rs.getString("check_out"),
						rs.getObject("exception_type_id"), rs.getString("exception_type_name"),
						rs.getInt("duration_minutes")));
			}, args);
		}
		return byEmployee;
	}

	/** One employee's rows over {@code [from, to]}: {@link #byEmployee} for a roster of one. */
	public List<Row> forEmployee(long employeeId, String from, String to) {
		return byEmployee(List.of(employeeId), from, to).getOrDefault(employeeId, List.of());
	}

	/** The rows whose check-in date is in {@code [from, to]}, in their original order. */
	public static List<Row> between(List<Row> rows, String from, String to) {
		List<Row> window = new ArrayList<>();
		for (Row row : rows) {
			String date = row.dateKey();
			if (date.compareTo(from) >= 0 && date.compareTo(to) <= 0) {
				window.add(row);
			}
		}
		return window;
	}
}
