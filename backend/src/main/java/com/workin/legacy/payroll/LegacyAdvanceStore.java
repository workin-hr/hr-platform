package com.workin.legacy.payroll;

import java.math.BigDecimal;
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

/** JDBC port of the frozen legacy {@code advances} SQL. */
@Repository
public class LegacyAdvanceStore {

	private static final String ADVANCE_WITH_EMPLOYEE = """
			a.*, TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,''))) AS employee_name,
			e.id AS employee_id, e.employee_code AS employee_code, e.photo_url AS photo_url
			""";
	private static final RowMapper<Map<String, Object>> ROW = LegacyAdvanceStore::row;

	private final JdbcTemplate jdbc;

	public LegacyAdvanceStore(@Qualifier("legacyDataSource") DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	public long insert(Map<String, Object> values) {
		KeyHolder key = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO advances
					(employee_id, amount, remaining, reason, deduction_mode, deduction_type,
					 deduction_month_count, deduction_amount_per_month, deduction_payroll_year,
					 deduction_payroll_month, deduction_installments_json, status, request_date)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_DATE)
					""", Statement.RETURN_GENERATED_KEYS);
			int i = 1;
			ps.setObject(i++, values.get("employee_id"));
			ps.setObject(i++, values.get("amount"));
			ps.setObject(i++, values.get("amount"));
			ps.setObject(i++, values.get("reason"));
			ps.setObject(i++, values.get("deduction_mode"));
			ps.setObject(i++, values.get("deduction_type"));
			ps.setObject(i++, values.get("deduction_month_count"));
			ps.setObject(i++, values.get("deduction_amount_per_month"));
			ps.setObject(i++, values.get("deduction_payroll_year"));
			ps.setObject(i++, values.get("deduction_payroll_month"));
			ps.setObject(i++, values.get("deduction_installments_json"));
			ps.setObject(i, values.get("status"));
			return ps;
		}, key);
		Number id = key.getKey();
		return id == null ? 0L : id.longValue();
	}

	/** create/update/action re-reads are intentionally id-only in PHP. */
	public Map<String, Object> withEmployee(long id) {
		return single("SELECT " + ADVANCE_WITH_EMPLOYEE + " FROM advances a JOIN employees e ON e.id=a.employee_id WHERE a.id=?", id);
	}

	/** one.php is company scoped. */
	public Map<String, Object> scopedWithEmployee(long companyId, long id) {
		return single("SELECT " + ADVANCE_WITH_EMPLOYEE + " FROM advances a JOIN employees e ON e.id=a.employee_id WHERE a.id=? AND e.company_id=?", id, companyId);
	}

	/** update.php preflight is company scoped but selects only a.*. */
	public Map<String, Object> scoped(long companyId, long id) {
		return single("SELECT a.* FROM advances a JOIN employees e ON e.id=a.employee_id WHERE a.id=? AND e.company_id=?", id, companyId);
	}

	/**
	 * The frozen PHP {@code advances} endpoints ({@code approve.php}, {@code
	 * reject.php}, {@code pay.php}, {@code delete.php}, and {@code create.php}
	 * for non-employee callers) genuinely have no company scoping at all --
	 * verified against the frozen source, not inferred. That is a real
	 * cross-tenant authorization bypass on a money-handling resource, not a
	 * PHP quirk worth preserving: per the same reasoning already applied in
	 * this codebase to {@code branches/update.php} (D-060, refused to
	 * reproduce a cross-tenant read) and the exception-type slice (D-095,
	 * fails closed on a foreign reference), company scoping is added here
	 * rather than ported faithfully. No legitimate client ever references
	 * another company's advance id, so this changes nothing for real traffic.
	 */
	public long employeeCompanyId(long employeeId) {
		List<Long> rows = jdbc.query("SELECT company_id FROM employees WHERE id=?", (rs, n) -> rs.getLong(1), employeeId);
		return rows.isEmpty() ? 0L : rows.getFirst();
	}

	/** Company-scoped counterpart to {@link #paymentState(long)}; see that method's note. */
	public Map<String, Object> scopedPaymentState(long companyId, long id) {
		return single("SELECT a.amount, a.remaining FROM advances a JOIN employees e ON e.id=a.employee_id"
				+ " WHERE a.id=? AND e.company_id=?", id, companyId);
	}

	/** Company-scoped counterpart to {@link #deleteState(long)}; see that method's note. */
	public Map<String, Object> scopedDeleteState(long companyId, long id) {
		return single("SELECT a.employee_id, a.status FROM advances a JOIN employees e ON e.id=a.employee_id"
				+ " WHERE a.id=? AND e.company_id=?", id, companyId);
	}

	/** pay.php deliberately ignores company ownership in frozen PHP; see {@link #employeeCompanyId(long)}'s note. */
	public Map<String, Object> paymentState(long id) {
		return single("SELECT amount, remaining FROM advances WHERE id=?", id);
	}

	/** delete.php deliberately ignores company ownership in frozen PHP; see {@link #employeeCompanyId(long)}'s note. */
	public Map<String, Object> deleteState(long id) {
		return single("SELECT employee_id, status FROM advances WHERE id=?", id);
	}

	public void updateEmployee(long id, Object amount, Object reason) {
		jdbc.update("UPDATE advances SET amount=?, remaining=?, reason=? WHERE id=?", amount, amount, reason, id);
	}

	public void updateAdministrative(long id, Map<String, Object> values) {
		jdbc.update("""
				UPDATE advances SET amount=?, remaining=?, reason=?, status=?, rejection_reason=?,
				deduction_mode=?, deduction_type=?, deduction_month_count=?, deduction_amount_per_month=?,
				deduction_payroll_year=?, deduction_payroll_month=?, deduction_installments_json=? WHERE id=?
				""",
				values.get("amount"), values.get("remaining"), values.get("reason"), values.get("status"),
				values.get("rejection_reason"), values.get("deduction_mode"), values.get("deduction_type"),
				values.get("deduction_month_count"), values.get("deduction_amount_per_month"),
				values.get("deduction_payroll_year"), values.get("deduction_payroll_month"),
				values.get("deduction_installments_json"), id);
	}

	public void approve(long id) {
		jdbc.update("UPDATE advances SET status=? WHERE id=?", "approved", id);
	}

	public void reject(long id, Object reason) {
		jdbc.update("UPDATE advances SET status=?, rejection_reason=? WHERE id=?", "rejected", reason, id);
	}

	/**
	 * Atomic conditional decrement (PR #120 review): {@code pay.php} previously read {@code
	 * remaining}, computed the new balance in application code, and wrote it back with an
	 * unconditional {@code UPDATE} -- two genuinely concurrent payments against the same
	 * advance could both read the same stale {@code remaining}, both pass the overpayment
	 * check against that stale value, and the second {@code UPDATE} would silently clobber
	 * the first's result, losing one payment. {@code remaining = remaining - ?} computes the
	 * new balance from the row's own live value at write time under InnoDB's row lock, and
	 * {@code AND remaining >= ?} folds the overpayment check into the same atomic statement:
	 * 0 rows changed means a concurrent payment already reduced the balance below what this
	 * payment needs, exactly the condition {@code pay()} must reject as {@code
	 * payment_exceeds_remaining}.
	 *
	 * @return the number of rows updated -- 0 means the payment must be rejected
	 */
	public int payIfSufficientBalance(long id, BigDecimal amount) {
		return jdbc.update("UPDATE advances SET remaining = remaining - ? WHERE id=? AND remaining >= ?",
				amount, id, amount);
	}

	public void delete(long id) {
		jdbc.update("DELETE FROM advances WHERE id=?", id);
	}

	public long count(List<String> predicates, List<Object> args) {
		String where = String.join(" AND ", predicates);
		Long count = jdbc.queryForObject("SELECT COUNT(*) FROM advances a JOIN employees e ON e.id=a.employee_id WHERE " + where,
				Long.class, args.toArray());
		return count == null ? 0L : count;
	}

	public List<Map<String, Object>> list(List<String> predicates, List<Object> args, LegacyPagination.Params page) {
		String where = String.join(" AND ", predicates);
		List<Object> params = new ArrayList<>(args);
		params.add(page.limit());
		params.add(page.offset());
		return jdbc.query("SELECT " + ADVANCE_WITH_EMPLOYEE
				+ " FROM advances a JOIN employees e ON e.id=a.employee_id WHERE " + where
				+ " ORDER BY a.created_at DESC LIMIT ? OFFSET ?", ROW, params.toArray());
	}

	private Map<String, Object> single(String sql, Object... args) {
		List<Map<String, Object>> rows = jdbc.query(sql, ROW, args);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private static Map<String, Object> row(ResultSet rs, int ignored) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			result.put(meta.getColumnLabel(i), LegacyJdbcValues.read(rs, i, meta.getColumnType(i)));
		}
		return result;
	}
}
