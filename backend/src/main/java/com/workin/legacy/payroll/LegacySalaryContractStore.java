package com.workin.legacy.payroll;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyPagination;

@Repository
public class LegacySalaryContractStore {

    private final JdbcTemplate jdbc;

    public LegacySalaryContractStore(DataSource legacyDataSource) {
        this.jdbc = new JdbcTemplate(legacyDataSource);
    }

    public boolean employeeOwned(long companyId, long employeeId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM employees WHERE id=? AND company_id=?",
                Long.class, employeeId, companyId);
        return count != null && count > 0;
    }

    public Map<String, Object> scoped(long companyId, long id) {
        return single(jdbc.query("SELECT c.* FROM salary_contracts c JOIN employees e ON e.id=c.employee_id "
                + "WHERE c.id=? AND e.company_id=?", this::row, id, companyId));
    }

    public long count(long employeeId, String search) {
        List<Object> binds = new ArrayList<>();
        binds.add(employeeId);
        String where = "employee_id=?";
        if (search != null) {
            where += " AND (CAST(basic_salary AS CHAR) LIKE ? OR effective_from LIKE ?)";
            String like = "%" + search + "%";
            binds.add(like);
            binds.add(like);
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM salary_contracts WHERE " + where,
                Long.class, binds.toArray());
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> list(long employeeId, String search, LegacyPagination.Params page) {
        List<Object> binds = new ArrayList<>();
        binds.add(employeeId);
        String where = "employee_id=?";
        if (search != null) {
            where += " AND (CAST(basic_salary AS CHAR) LIKE ? OR effective_from LIKE ?)";
            String like = "%" + search + "%";
            binds.add(like);
            binds.add(like);
        }
        binds.add(page.limit());
        binds.add(page.offset());
        return jdbc.query("SELECT * FROM salary_contracts WHERE " + where
                + " ORDER BY effective_from DESC LIMIT ? OFFSET ?", this::row, binds.toArray());
    }

    public long insert(Map<String, Object> v) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO salary_contracts (
                      employee_id, salary_mode, basic_salary, daily_wage, housing_allowance,
                      transport_allowance, food_allowance, risk_allowance, incentives,
                      insurance_deduction, tax_deduction, advances_deduction, fund_deduction,
                      penalty_deduction, effective_from
                    ) VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            bindValues(ps, v, false);
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? 0L : id.longValue();
    }

    public void update(long id, Map<String, Object> v) {
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    UPDATE salary_contracts SET salary_mode=?, basic_salary=?, daily_wage=?,
                      housing_allowance=0, transport_allowance=?, food_allowance=?, risk_allowance=?,
                      incentives=?, insurance_deduction=?, tax_deduction=?, advances_deduction=?,
                      fund_deduction=?, penalty_deduction=?, effective_from=? WHERE id=?
                    """);
            bindValues(ps, v, true);
            ps.setLong(14, id);
            return ps;
        });
    }

    public Map<String, Object> byId(long id) {
        return single(jdbc.query("SELECT * FROM salary_contracts WHERE id=?", this::row, id));
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM salary_contracts WHERE id=?", id);
    }

    private static void bindValues(PreparedStatement ps, Map<String, Object> v, boolean update) throws SQLException {
        int i = 1;
        if (!update) ps.setLong(i++, ((Number) v.get("employee_id")).longValue());
        ps.setString(i++, String.valueOf(v.get("salary_mode")));
        ps.setDouble(i++, ((Number) v.get("basic_salary")).doubleValue());
        Object daily = v.get("daily_wage");
        if (daily == null) ps.setNull(i++, Types.DOUBLE); else ps.setDouble(i++, ((Number) daily).doubleValue());
        ps.setDouble(i++, ((Number) v.get("transport_allowance")).doubleValue());
        ps.setDouble(i++, ((Number) v.get("food_allowance")).doubleValue());
        ps.setDouble(i++, ((Number) v.get("risk_allowance")).doubleValue());
        ps.setDouble(i++, ((Number) v.get("incentives")).doubleValue());
        ps.setDouble(i++, ((Number) v.get("insurance_deduction")).doubleValue());
        ps.setDouble(i++, ((Number) v.get("tax_deduction")).doubleValue());
        ps.setDouble(i++, ((Number) v.get("advances_deduction")).doubleValue());
        ps.setDouble(i++, ((Number) v.get("fund_deduction")).doubleValue());
        ps.setDouble(i++, ((Number) v.get("penalty_deduction")).doubleValue());
        ps.setObject(i, v.get("effective_from"));
    }

    private Map<String, Object> row(ResultSet rs, int rowNum) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnLabel(i), LegacyJdbcValues.read(rs, i, meta.getColumnType(i)));
        }
        return row;
    }

    private static Map<String, Object> single(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
