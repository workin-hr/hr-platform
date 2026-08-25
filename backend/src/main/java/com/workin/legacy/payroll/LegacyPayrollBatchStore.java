package com.workin.legacy.payroll;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
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

/** {@code payroll_batches/*.php}'s row access (Wave 12.9). */
@Repository
public class LegacyPayrollBatchStore {

	/**
	 * {@code sql_payroll_batch_select_with_stats()}/{@code get_payroll_batch_with_stats()}
	 * ({@code payroll_calculation.php:11-33}): every batch column plus two
	 * payslip aggregates over a {@code LEFT JOIN}, so a batch with no
	 * payslips yet still returns a row -- {@code employees_count = 0},
	 * {@code total_net_salary = 0} via {@code COALESCE}, never {@code NULL}.
	 */
	private static final String WITH_STATS_SELECT = """
			SELECT b.*,
			  COUNT(p.employee_id) AS employees_count,
			  COALESCE(SUM(p.net_salary), 0) AS total_net_salary
			FROM payroll_batches b
			LEFT JOIN payslips p ON p.batch_id = b.id""";

	private final JdbcTemplate jdbc;

	public LegacyPayrollBatchStore(DataSource legacyDataSource) {
		this.jdbc = new JdbcTemplate(legacyDataSource);
	}

	public Map<String, Object> withStats(long batchId, long companyId) {
		return single(jdbc.query(
				WITH_STATS_SELECT + " WHERE b.id=? AND b.company_id=? GROUP BY b.id",
				this::row, batchId, companyId));
	}

	/** {@code byId} without the company scope -- side-effect helpers already hold the batch row. */
	public Map<String, Object> byId(long batchId) {
		return single(jdbc.query("SELECT * FROM payroll_batches WHERE id=?", this::row, batchId));
	}

	public Map<String, Object> scoped(long batchId, long companyId) {
		return single(jdbc.query(
				"SELECT * FROM payroll_batches WHERE id=? AND company_id=?", this::row, batchId, companyId));
	}

	/** {@code create.php}'s pre-insert uniqueness check. */
	public boolean existsForPeriod(long companyId, int month, int year) {
		Long count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM payroll_batches WHERE company_id=? AND month=? AND year=?",
				Long.class, companyId, month, year);
		return count != null && count > 0;
	}

	public long insert(long companyId, int month, int year, String periodFrom, String periodTo, String status) {
		KeyHolder key = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement(
					"INSERT INTO payroll_batches (company_id, month, year, period_from, period_to, status) "
							+ "VALUES (?, ?, ?, ?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setLong(1, companyId);
			ps.setInt(2, month);
			ps.setInt(3, year);
			ps.setString(4, periodFrom);
			ps.setString(5, periodTo);
			ps.setString(6, status);
			return ps;
		}, key);
		Number id = key.getKey();
		return id == null ? 0L : id.longValue();
	}

	/** {@code update.php}: month/year/period_from/period_to together, never a partial set. */
	public void updatePeriod(long batchId, int month, int year, String periodFrom, String periodTo) {
		jdbc.update("UPDATE payroll_batches SET month=?, year=?, period_from=?, period_to=? WHERE id=?",
				month, year, periodFrom, periodTo, batchId);
	}

	public void updateStatus(long batchId, String status) {
		jdbc.update("UPDATE payroll_batches SET status=? WHERE id=?", status, batchId);
	}

	/** {@code finalize.php} also rewrites the fiscal period at the same time as the status. */
	public void finalizeBatch(long batchId, String status, String periodFrom, String periodTo) {
		jdbc.update("UPDATE payroll_batches SET status=?, period_from=?, period_to=? WHERE id=?",
				status, periodFrom, periodTo, batchId);
	}

	/** {@code delete.php}: payslips first, batch second -- no FK cascade in this schema. */
	public void deleteWithPayslips(long batchId) {
		jdbc.update("DELETE FROM payslips WHERE batch_id=?", batchId);
		jdbc.update("DELETE FROM payroll_batches WHERE id=?", batchId);
	}

	public long countForList(long companyId, String status, Integer year, String search) {
		Filter filter = new Filter(companyId, status, year, search);
		return jdbc.queryForObject("SELECT COUNT(*) FROM payroll_batches b WHERE " + filter.whereSql(),
				Long.class, filter.params());
	}

	/** {@code list.php}: filtered, joined with stats, newest year/month first. */
	public List<Map<String, Object>> list(
			long companyId, String status, Integer year, String search, LegacyPagination.Params page) {
		Filter filter = new Filter(companyId, status, year, search);
		List<Object> params = new ArrayList<>(List.of(filter.params()));
		params.add(page.limit());
		params.add(page.offset());
		return jdbc.query(
				WITH_STATS_SELECT + " WHERE " + filter.whereSql()
						+ " GROUP BY b.id ORDER BY b.year DESC, b.month DESC LIMIT ? OFFSET ?",
				this::row, params.toArray());
	}

	/** {@code list.php}'s three optional filters, applied in PHP's own order. */
	private record Filter(long companyId, String status, Integer year, String search) {

		String whereSql() {
			StringBuilder sql = new StringBuilder("b.company_id=?");
			if (status != null) {
				sql.append(" AND b.status=?");
			}
			if (year != null && year > 0) {
				sql.append(" AND b.year=?");
			}
			if (search != null) {
				sql.append(" AND (CAST(b.month AS CHAR) LIKE ? OR CAST(b.year AS CHAR) LIKE ?)");
			}
			return sql.toString();
		}

		Object[] params() {
			List<Object> params = new ArrayList<>();
			params.add(companyId);
			if (status != null) {
				params.add(status);
			}
			if (year != null && year > 0) {
				params.add(year);
			}
			if (search != null) {
				String like = "%" + search + "%";
				params.add(like);
				params.add(like);
			}
			return params.toArray();
		}
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
