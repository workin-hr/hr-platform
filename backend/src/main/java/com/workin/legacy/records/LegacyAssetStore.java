package com.workin.legacy.records;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;

/**
 * {@code assets} -- employee custody records (العهدة).
 *
 * <p>Every read and every write filters {@code company_id}, with one exception
 * that is legacy's and is preserved: {@code update.php}'s {@code UPDATE} carries
 * only {@code WHERE id=?}. It is not a tenant hole -- the row is read and its
 * company checked immediately before, under the same id -- but the statement is
 * reproduced as written rather than "hardened", because changing it would be a
 * behaviour claim the port has no evidence for.
 */
@Repository
public class LegacyAssetStore {

	/** {@code sql_employee_display_name('e')}. */
	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,'')))";

	private final JdbcTemplate jdbcTemplate;

	public LegacyAssetStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	public long count(List<String> predicates, List<Object> binds) {
		Long total = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM assets c JOIN employees e ON e.id = c.employee_id WHERE "
						+ String.join(" AND ", predicates),
				Long.class, binds.toArray());
		return total == null ? 0L : total;
	}

	/**
	 * {@code list.php}'s page.
	 *
	 * <p>{@code c.*} plus four employee columns, so the asset table's shape is
	 * the wire contract and {@code photo_url} rides along -- {@code one.php}
	 * selects the same set <b>minus</b> {@code photo_url}, which is a real
	 * difference between the two responses rather than an oversight to smooth
	 * over.
	 */
	public List<Map<String, Object>> page(
			List<String> predicates, List<Object> binds, long limit, long offset) {
		List<Object> args = new ArrayList<>(binds);
		args.add(limit);
		args.add(offset);
		return jdbcTemplate.query(
				"SELECT c.*, " + DISPLAY_NAME + " AS employee_name, e.id AS employee_id,"
						+ " e.employee_code AS employee_code, e.photo_url AS photo_url"
						+ " FROM assets c JOIN employees e ON e.id = c.employee_id"
						+ " WHERE " + String.join(" AND ", predicates)
						+ " ORDER BY c.asset_date DESC, c.id DESC LIMIT ? OFFSET ?",
				LegacyJdbcValues.rowMapper(), args.toArray());
	}

	/** {@code one.php}: no {@code photo_url}, unlike the list. */
	public Map<String, Object> one(long companyId, long id) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT c.*, " + DISPLAY_NAME + " AS employee_name, e.id AS employee_id,"
						+ " e.employee_code AS employee_code"
						+ " FROM assets c JOIN employees e ON e.id = c.employee_id"
						+ " WHERE c.id=? AND c.company_id=?",
				LegacyJdbcValues.rowMapper(), id, companyId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** The bare row, as {@code update.php} and {@code delete.php} read it first. */
	public Map<String, Object> row(long companyId, long id) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT * FROM assets WHERE id=? AND company_id=?",
				LegacyJdbcValues.rowMapper(), id, companyId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** {@code create.php}'s employee check: by id only, then the company is compared in PHP. */
	public Long employeeCompanyId(long employeeId) {
		List<Long> rows = jdbcTemplate.queryForList(
				"SELECT company_id FROM employees WHERE id=?", Long.class, employeeId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public long insert(long companyId, long employeeId, String assetDate, String assetText,
			String returnedAt, int isReturned) {
		return com.workin.legacy.LegacyGeneratedKeys.insert(jdbcTemplate,
				"INSERT INTO assets (company_id, employee_id, asset_date, asset_text, returned_at,"
						+ " is_returned) VALUES (?, ?, ?, ?, ?, ?)",
				companyId, employeeId, assetDate, assetText, returnedAt, isReturned);
	}

	public Map<String, Object> byId(long id) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT * FROM assets WHERE id=?", LegacyJdbcValues.rowMapper(), id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** {@code whitelist_update_fields()} applied to the four mutable columns. */
	public void update(long id, List<String> assignments, List<Object> values) {
		List<Object> args = new ArrayList<>(values);
		args.add(id);
		jdbcTemplate.update(
				"UPDATE assets SET " + String.join(", ", assignments) + " WHERE id=?", args.toArray());
	}

	/** The post-update re-read joins the employee but selects no {@code photo_url} or code. */
	public Map<String, Object> afterUpdate(long id) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT c.*, " + DISPLAY_NAME + " AS employee_name"
						+ " FROM assets c JOIN employees e ON e.id = c.employee_id WHERE c.id=?",
				LegacyJdbcValues.rowMapper(), id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public void delete(long companyId, long id) {
		jdbcTemplate.update("DELETE FROM assets WHERE id=? AND company_id=?", id, companyId);
	}
}
