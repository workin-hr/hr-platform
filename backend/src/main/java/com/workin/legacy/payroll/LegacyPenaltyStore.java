package com.workin.legacy.payroll;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyPagination;

@Repository
public class LegacyPenaltyStore {
	private static final RowMapper<Map<String, Object>> ROW = LegacyPenaltyStore::row;
	private final JdbcTemplate jdbc;

	public LegacyPenaltyStore(@Qualifier("legacyDataSource") DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	public long employeeCompanyId(long employeeId) {
		List<Long> rows = jdbc.query("SELECT company_id FROM employees WHERE id=?", (rs, n) -> rs.getLong(1), employeeId);
		return rows.isEmpty() ? 0L : rows.getFirst();
	}

	public long insert(long employeeId, String type, double days, String date, String reason) {
		KeyHolder key = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			var ps = connection.prepareStatement(
					"INSERT INTO penalties (employee_id, penalty_type, penalty_days, penalty_date, reason) VALUES (?, ?, ?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setLong(1, employeeId);
			ps.setString(2, type);
			ps.setDouble(3, days);
			ps.setString(4, date);
			ps.setString(5, reason);
			return ps;
		}, key);
		Number id = key.getKey();
		return id == null ? 0L : id.longValue();
	}

	public Map<String, Object> withEmployee(long id) {
		return single("SELECT p.*, TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,''))) AS employee_name, e.employee_code, e.company_id FROM penalties p JOIN employees e ON e.id=p.employee_id WHERE p.id=?", id);
	}

	public void updateFields(long id, Map<String, Object> values) {
		List<String> fields = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		append(values, fields, args, "penalty_type");
		append(values, fields, args, "penalty_days");
		append(values, fields, args, "reason");
		append(values, fields, args, "penalty_date");
		if (fields.isEmpty()) return;
		args.add(id);
		jdbc.update("UPDATE penalties SET " + String.join(", ", fields) + " WHERE id=?", args.toArray());
	}

	private static void append(Map<String, Object> values, List<String> fields, List<Object> args, String key) {
		if (values.containsKey(key)) {
			fields.add(key + "=?");
			args.add(values.get(key));
		}
	}

	public void deleteById(long id) {
		jdbc.update("DELETE FROM penalties WHERE id=?", id);
	}

	public long count(List<String> predicates, List<Object> args) {
		Long count = jdbc.queryForObject("SELECT COUNT(*) FROM penalties p JOIN employees e ON e.id=p.employee_id " + where(predicates), Long.class, args.toArray());
		return count == null ? 0L : count;
	}

	public List<Map<String, Object>> list(List<String> predicates, List<Object> args, LegacyPagination.Params page) {
		List<Object> params = new ArrayList<>(args);
		params.add(page.limit());
		params.add(page.offset());
		return jdbc.query("SELECT p.*, TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,''))) AS employee_name, e.photo_url, e.employee_code FROM penalties p JOIN employees e ON e.id=p.employee_id " + where(predicates) + " ORDER BY p.penalty_date DESC LIMIT ? OFFSET ?", ROW, params.toArray());
	}

	public long appliedCount(List<String> predicates, List<Object> args) {
		List<String> all = new ArrayList<>(predicates);
		all.add("p.applied_to_payroll=1");
		return count(all, args);
	}

	public double totalPenaltyDays(List<String> predicates, List<Object> args) {
		Double value = jdbc.queryForObject("SELECT IFNULL(SUM(p.penalty_days),0) FROM penalties p JOIN employees e ON e.id=p.employee_id " + where(predicates), Double.class, args.toArray());
		return value == null ? 0.0 : value;
	}

	public List<Map<String, Object>> amountRows(List<String> predicates, List<Object> args) {
		return jdbc.query("SELECT p.penalty_days, p.penalty_date, p.employee_id FROM penalties p JOIN employees e ON e.id=p.employee_id " + where(predicates), ROW, args.toArray());
	}

	public Map<String, Object> salaryContractAt(long employeeId, String date) {
		return single("SELECT * FROM salary_contracts WHERE employee_id=? AND effective_from<=? ORDER BY effective_from DESC LIMIT 1", employeeId, date);
	}

	public List<Map<String, Object>> report(List<String> predicates, List<Object> args) {
		return jdbc.query("SELECT TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,''))) AS employee_name, b.name AS branch_name, p.penalty_type, p.penalty_days, p.reason, p.penalty_date, p.applied_to_payroll FROM penalties p JOIN employees e ON e.id=p.employee_id LEFT JOIN branches b ON b.id=e.branch_id " + where(predicates) + " ORDER BY p.penalty_date DESC", ROW, args.toArray());
	}

	public boolean managerCanAccess(long managerId, long targetId, long companyId) {
		Long count = jdbc.queryForObject("SELECT COUNT(*) FROM employees m JOIN employees t ON t.id=? WHERE m.id=? AND m.company_id=? AND t.company_id=? AND m.branch_id=t.branch_id", Long.class, targetId, managerId, companyId, companyId);
		return count != null && count > 0;
	}

	private static String where(List<String> predicates) {
		return predicates.isEmpty() ? "" : "WHERE " + String.join(" AND ", predicates);
	}

	private Map<String, Object> single(String sql, Object... args) {
		List<Map<String, Object>> rows = jdbc.query(sql, ROW, args);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private static Map<String, Object> row(ResultSet rs, int ignored) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 1; i <= meta.getColumnCount(); i++) result.put(meta.getColumnLabel(i), LegacyJdbcValues.read(rs, i, meta.getColumnType(i)));
		return result;
	}
}
