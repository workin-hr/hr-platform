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

/** {@code requests}' read and write path ({@code apis/api/requests/*.php}). */
@Repository
public class LegacyRequestStore {

	/** {@code sql_employee_display_name('e')} ({@code functions.php:169-176}). */
	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,'')))";

	/** {@code sql_manager_same_branch_scope('e', ...)} ({@code functions.php:673-678}). */
	private static final String MANAGER_SCOPE =
			"e.branch_id = (SELECT eb.branch_id FROM employees eb WHERE eb.id = ? AND eb.company_id = ? LIMIT 1)";

	private final JdbcTemplate jdbcTemplate;

	public LegacyRequestStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * {@code list.php}'s role-driven scope, exactly one of {@code ownEmployeeId}
	 * or {@code companyId} set, matching the PHP {@code if/else}.
	 */
	public record ListFilter(
			Long ownEmployeeId, Long companyId, Long managerEmployeeId, Long filterEmployeeId,
			String status, Long typeId, String dateFrom, String dateTo, String search) {
	}

	private static String where(ListFilter filter, List<Object> binds) {
		StringBuilder sql = new StringBuilder("WHERE ");
		if (filter.ownEmployeeId() != null) {
			sql.append("r.employee_id=?");
			binds.add(filter.ownEmployeeId());
		} else {
			sql.append("e.company_id=?");
			binds.add(filter.companyId());
			if (filter.managerEmployeeId() != null) {
				sql.append(" AND ").append(MANAGER_SCOPE);
				binds.add(filter.managerEmployeeId());
				binds.add(filter.companyId());
			}
			if (filter.filterEmployeeId() != null) {
				sql.append(" AND r.employee_id=?");
				binds.add(filter.filterEmployeeId());
			}
		}
		if (filter.status() != null) {
			sql.append(" AND r.status=?");
			binds.add(filter.status());
		}
		if (filter.typeId() != null) {
			sql.append(" AND r.request_type_id=?");
			binds.add(filter.typeId());
		}
		if (filter.dateFrom() != null) {
			sql.append(" AND r.from_date >= ?");
			binds.add(filter.dateFrom());
		}
		if (filter.dateTo() != null) {
			sql.append(" AND r.from_date <= ?");
			binds.add(filter.dateTo());
		}
		if (filter.search() != null) {
			sql.append(" AND (").append(DISPLAY_NAME).append(" LIKE ? OR e.employee_code LIKE ? OR t.name LIKE ?)");
			String like = "%" + filter.search() + "%";
			binds.add(like);
			binds.add(like);
			binds.add(like);
		}
		return sql.toString();
	}

	public long count(ListFilter filter) {
		List<Object> binds = new ArrayList<>();
		String sql = "SELECT COUNT(*) FROM requests r "
				+ "JOIN employees e ON e.id = r.employee_id "
				+ "JOIN request_types t ON t.id = r.request_type_id "
				+ where(filter, binds);
		Long total = jdbcTemplate.queryForObject(sql, Long.class, binds.toArray());
		return total == null ? 0L : total;
	}

	/** {@code list.php}: employee_name, photo_url, employee_code, request_type_name; newest first. */
	public List<Map<String, Object>> list(ListFilter filter, LegacyPagination.Params pagination) {
		List<Object> binds = new ArrayList<>();
		String sql = "SELECT r.*, " + DISPLAY_NAME + " AS employee_name, "
				+ "e.photo_url AS photo_url, e.employee_code AS employee_code, t.name AS request_type_name "
				+ "FROM requests r "
				+ "JOIN employees e ON e.id = r.employee_id "
				+ "JOIN request_types t ON t.id = r.request_type_id "
				+ where(filter, binds)
				+ " ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?";
		binds.add(pagination.limit());
		binds.add(pagination.offset());
		return jdbcTemplate.query(sql, rowMapper(), binds.toArray());
	}

	/** {@code one.php}: employee_name, employee_code, request_type_name -- no photo_url. */
	public Map<String, Object> byId(long id) {
		return single(jdbcTemplate.query("""
				SELECT r.*, %s AS employee_name, e.employee_code AS employee_code, t.name AS request_type_name
				FROM requests r
				JOIN employees e ON e.id = r.employee_id
				JOIN request_types t ON t.id = r.request_type_id
				WHERE r.id = ?""".formatted(DISPLAY_NAME), rowMapper(), id));
	}

	/** {@code create.php}/{@code update.php}'s re-read: employee_name and request_type_name only. */
	public Map<String, Object> byIdWithTypeAndEmployeeName(long id) {
		return single(jdbcTemplate.query("""
				SELECT r.*, %s AS employee_name, t.name AS request_type_name
				FROM requests r
				JOIN employees e ON e.id = r.employee_id
				JOIN request_types t ON t.id = r.request_type_id
				WHERE r.id = ?""".formatted(DISPLAY_NAME), rowMapper(), id));
	}

	/** {@code update.php}/{@code delete.php}'s ownership lookup: the bare row, no joins. */
	public Map<String, Object> byIdOwnedByEmployee(long id, long employeeId) {
		return single(jdbcTemplate.query(
				"SELECT * FROM requests WHERE id = ? AND employee_id = ?", rowMapper(), id, employeeId));
	}

	/** {@code reject.php}'s fetch: request_type_name only, company-scoped through the employee join. */
	public Map<String, Object> byIdForCompanyWithType(long id, long companyId) {
		return single(jdbcTemplate.query("""
				SELECT r.*, t.name AS request_type_name
				FROM requests r
				JOIN request_types t ON t.id = r.request_type_id
				JOIN employees e ON e.id = r.employee_id
				WHERE r.id = ? AND e.company_id = ?""", rowMapper(), id, companyId));
	}

	public long insert(
			long employeeId, long requestTypeId, String fromDate, String toDate,
			String fromTime, String toTime, String notes) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO requests (
						employee_id, request_type_id, from_date, to_date, from_time, to_time, notes, status
					) VALUES (?, ?, ?, ?, ?, ?, ?, 'pending')""", PreparedStatement.RETURN_GENERATED_KEYS);
			statement.setLong(1, employeeId);
			statement.setLong(2, requestTypeId);
			statement.setString(3, fromDate);
			statement.setString(4, toDate);
			bindNullableString(statement, 5, fromTime);
			bindNullableString(statement, 6, toTime);
			statement.setString(7, notes);
			return statement;
		}, keys);
		return keys.getKey() == null ? 0L : keys.getKey().longValue();
	}

	/** {@code update.php}'s whitelisted UPDATE, scoped by id <b>and</b> employee_id. */
	public void updateFields(long id, long employeeId, List<String> columns, List<Object> values) {
		StringBuilder sql = new StringBuilder("UPDATE requests SET ");
		for (int index = 0; index < columns.size(); index++) {
			if (index > 0) {
				sql.append(", ");
			}
			sql.append('`').append(columns.get(index)).append("`=?");
		}
		sql.append(" WHERE id = ? AND employee_id = ?");

		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(sql.toString());
			int position = 1;
			for (Object value : values) {
				bind(statement, position++, value);
			}
			statement.setLong(position++, id);
			statement.setLong(position, employeeId);
			return statement;
		});
	}

	/**
	 * {@code approve.php}/{@code reject.php}: status, reply, decided_at = NOW().
	 * {@code approverId} is a deliberate addition over legacy -- see
	 * {@link LegacyRequestService#reject}.
	 */
	public void updateStatus(long id, String status, String reply, Long approverId) {
		jdbcTemplate.update(
				"UPDATE requests SET status = ?, reply = ?, approver_id = ?, decided_at = NOW() WHERE id = ?",
				status, reply, approverId, id);
	}

	public void deleteOwnedByEmployee(long id, long employeeId) {
		jdbcTemplate.update("DELETE FROM requests WHERE id = ? AND employee_id = ?", id, employeeId);
	}

	/**
	 * {@code one.php}'s separate {@code db_value()} lookup: the request's owning
	 * employee's company, queried apart from the row itself so the response
	 * envelope never carries it. {@code (int) db_value(...)} on a missing row
	 * is 0, reproduced the same way here.
	 */
	public long employeeCompanyId(long employeeId) {
		List<Long> rows = jdbcTemplate.queryForList(
				"SELECT company_id FROM employees WHERE id = ?", Long.class, employeeId);
		return rows.isEmpty() || rows.get(0) == null ? 0L : rows.get(0);
	}

	private static void bindNullableString(PreparedStatement statement, int index, String value) throws SQLException {
		if (value == null) {
			statement.setNull(index, Types.VARCHAR);
		} else {
			statement.setString(index, value);
		}
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
