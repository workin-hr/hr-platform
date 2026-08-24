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

/** Persistence for {@code apis/api/leave_balances/*.php}. */
@Repository
public class LegacyLeaveBalanceStore {

	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,'')))";
	private static final String ROSTER = "COALESCE(e.join_request_status,'accepted')='accepted'";
	private static final String MANAGER_SCOPE =
			"e.branch_id=(SELECT eb.branch_id FROM employees eb WHERE eb.id=? AND eb.company_id=? LIMIT 1)";

	private final JdbcTemplate jdbc;

	public LegacyLeaveBalanceStore(DataSource legacyDataSource) {
		this.jdbc = new JdbcTemplate(legacyDataSource);
	}

	public record Filter(
			long companyId, Long ownEmployeeId, Long managerEmployeeId, Long employeeId,
			Integer yearFrom, Integer yearTo, Integer year, String search) {
	}

	private String where(Filter filter, List<Object> binds) {
		StringBuilder where = new StringBuilder(" WHERE e.company_id=? AND ").append(ROSTER);
		binds.add(filter.companyId());
		if (filter.ownEmployeeId() != null) {
			where.append(" AND e.id=?");
			binds.add(filter.ownEmployeeId());
		} else if (filter.managerEmployeeId() != null) {
			where.append(" AND ").append(MANAGER_SCOPE);
			binds.add(filter.managerEmployeeId());
			binds.add(filter.companyId());
		} else if (filter.employeeId() != null) {
			where.append(" AND e.id=?");
			binds.add(filter.employeeId());
		}
		if (filter.yearFrom() != null && filter.yearTo() != null) {
			where.append(" AND lb.year BETWEEN ? AND ?");
			binds.add(Math.min(filter.yearFrom(), filter.yearTo()));
			binds.add(Math.max(filter.yearFrom(), filter.yearTo()));
		} else if (filter.year() != null && filter.year() > 0) {
			where.append(" AND lb.year=?");
			binds.add(filter.year());
		}
		if (filter.search() != null) {
			where.append(" AND (").append(DISPLAY_NAME).append(" LIKE ? OR e.employee_code LIKE ?)");
			String like = "%" + filter.search() + "%";
			binds.add(like);
			binds.add(like);
		}
		return where.toString();
	}

	public long count(Filter filter) {
		List<Object> binds = new ArrayList<>();
		Long value = jdbc.queryForObject(
				"SELECT COUNT(*) FROM leave_balance lb JOIN employees e ON e.id=lb.employee_id"
						+ where(filter, binds), Long.class, binds.toArray());
		return value == null ? 0 : value;
	}

	public List<Map<String, Object>> list(Filter filter, LegacyPagination.Params page) {
		List<Object> binds = new ArrayList<>();
		String sql = "SELECT lb.*, (lb.total_days-lb.used_days) AS remaining_days, "
				+ DISPLAY_NAME + " AS employee_name, e.photo_url, e.employee_code "
				+ "FROM leave_balance lb JOIN employees e ON e.id=lb.employee_id"
				+ where(filter, binds)
				+ " ORDER BY lb.year DESC, CASE WHEN e.employee_code REGEXP '^[0-9]+$' "
				+ "THEN CAST(e.employee_code AS UNSIGNED) ELSE NULL END ASC, "
				+ "e.employee_code ASC, lb.id DESC LIMIT ? OFFSET ?";
		binds.add(page.limit());
		binds.add(page.offset());
		return jdbc.query(sql, rowMapper(), binds.toArray());
	}

	public Map<String, Object> byId(long id) {
		return single(jdbc.query("SELECT lb.*, " + DISPLAY_NAME + " AS employee_name, "
				+ "e.employee_code, e.company_id FROM leave_balance lb "
				+ "JOIN employees e ON e.id=lb.employee_id WHERE lb.id=?", rowMapper(), id));
	}

	public Map<String, Object> byEmployeeAndYear(long employeeId, int year) {
		return single(jdbc.query("SELECT * FROM leave_balance WHERE employee_id=? AND year=? LIMIT 1",
				rowMapper(), employeeId, year));
	}

	public long employeeCompanyId(long employeeId) {
		List<Long> rows = jdbc.queryForList("SELECT company_id FROM employees WHERE id=? LIMIT 1", Long.class, employeeId);
		return rows.isEmpty() || rows.getFirst() == null ? 0L : rows.getFirst();
	}

	public long insert(
			long employeeId, int year, Object totalDays, Object usedDays,
			int periodFrom, int periodTo, Object monthlyCap) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO leave_balance (
				 employee_id, year, total_days, used_days,
				 period_from_month, period_to_month, monthly_cap_days
				) VALUES (?, ?, ?, ?, ?, ?, ?)""", PreparedStatement.RETURN_GENERATED_KEYS);
			statement.setLong(1, employeeId);
			statement.setInt(2, year);
			bind(statement, 3, totalDays);
			bind(statement, 4, usedDays);
			statement.setInt(5, periodFrom);
			statement.setInt(6, periodTo);
			bind(statement, 7, monthlyCap);
			return statement;
		}, keys);
		return keys.getKey() == null ? 0 : keys.getKey().longValue();
	}

	public void update(long id, LinkedHashMap<String, Object> fields) {
		StringBuilder sql = new StringBuilder("UPDATE leave_balance SET ");
		int i = 0;
		for (String column : fields.keySet()) {
			if (i++ > 0) {
				sql.append(',');
			}
			sql.append('`').append(column).append("`=?");
		}
		sql.append(" WHERE id=?");
		jdbc.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(sql.toString());
			int parameter = 1;
			for (Object value : fields.values()) {
				bind(statement, parameter++, value);
			}
			statement.setLong(parameter, id);
			return statement;
		});
	}

	public void delete(long id) {
		jdbc.update("DELETE FROM leave_balance WHERE id=?", id);
	}

	public int generate(long companyId, int year, double defaultDays) {
		return jdbc.update("""
			INSERT INTO leave_balance (employee_id, year, total_days, used_days)
			SELECT e.id, ?, ?, 0
			FROM employees e
			LEFT JOIN leave_balance lb ON lb.employee_id=e.id AND lb.year=?
			WHERE e.company_id=?
			  AND COALESCE(e.join_request_status,'accepted')='accepted'
			  AND lb.id IS NULL""", year, defaultDays, year, companyId);
	}

	public double defaultAnnualLeaveDays(long companyId) {
		List<String> values = jdbc.queryForList("""
			SELECT sav.value
			FROM setting_definitions sd
			JOIN company_settings cs ON cs.setting_definition_id=sd.id AND cs.company_id=?
			JOIN company_setting_values csv ON csv.company_setting_id=cs.id
			JOIN setting_allowed_values sav ON sav.id=csv.setting_allowed_value_id
			WHERE sd.setting_key='monthly_leave_accrual'
			ORDER BY sav.sort_order ASC, sav.id ASC""", String.class, companyId);
		for (String value : values) {
			if (value != null && !value.isEmpty()) {
				return LegacyValues.toPhpDecimal(value).doubleValue();
			}
		}
		return 21.0d;
	}

	public Map<String, Object> stats(Filter filter) {
		List<Object> binds = new ArrayList<>();
		String sql = "SELECT COUNT(DISTINCT lb.employee_id) AS employees_count, "
				+ "COALESCE(SUM(lb.total_days),0) AS total_days, "
				+ "COALESCE(SUM(lb.used_days),0) AS used_days, "
				+ "COALESCE(SUM(lb.total_days-lb.used_days),0) AS remaining_days "
				+ "FROM leave_balance lb JOIN employees e ON e.id=lb.employee_id"
				+ where(filter, binds);
		return jdbc.queryForObject(sql, rowMapper(), binds.toArray());
	}

	public List<Map<String, Object>> templateEmployees(long companyId, int year) {
		return jdbc.query("SELECT e.id, e.employee_code, " + DISPLAY_NAME + " AS employee_name, "
				+ "CASE WHEN lb.id IS NULL THEN NULL ELSE (lb.total_days-lb.used_days) END AS remaining_days "
				+ "FROM employees e LEFT JOIN leave_balance lb ON lb.employee_id=e.id AND lb.year=? "
				+ "WHERE e.company_id=? AND e.is_active=1 AND " + ROSTER
				+ " ORDER BY CASE WHEN e.employee_code REGEXP '^[0-9]+$' THEN CAST(e.employee_code AS UNSIGNED) "
				+ "ELSE NULL END ASC, e.employee_code ASC, e.id ASC", rowMapper(), year, companyId);
	}

	public Long employeeIdByCode(long companyId, String code) {
		List<Long> rows = jdbc.queryForList("SELECT id FROM employees WHERE company_id=? AND employee_code=? LIMIT 1",
				Long.class, companyId, code);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	public boolean employeeOwned(long companyId, long employeeId) {
		Long count = jdbc.queryForObject("SELECT COUNT(*) FROM employees WHERE id=? AND company_id=?",
				Long.class, employeeId, companyId);
		return count != null && count > 0;
	}

	public boolean managerCanAccess(long companyId, long managerId, long targetEmployeeId) {
		Long count = jdbc.queryForObject("""
			SELECT COUNT(*) FROM employees e
			WHERE e.id=? AND e.company_id=? AND
			 e.branch_id=(SELECT eb.branch_id FROM employees eb WHERE eb.id=? AND eb.company_id=? LIMIT 1)
			""", Long.class, targetEmployeeId, companyId, managerId, companyId);
		return count != null && count > 0;
	}

	private static RowMapper<Map<String, Object>> rowMapper() {
		return (rs, ignored) -> row(rs);
	}

	private static Map<String, Object> row(ResultSet rs) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			map.put(meta.getColumnLabel(i), LegacyJdbcValues.read(rs, i, meta.getColumnType(i)));
		}
		return map;
	}

	private static Map<String, Object> single(List<Map<String, Object>> rows) {
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
		if (value == null) {
			statement.setNull(index, Types.VARCHAR);
		} else {
			statement.setString(index, LegacyValues.toPhpString(value));
		}
	}
}
