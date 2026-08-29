package com.workin.legacy.records;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;

/** {@code administrative_decisions} -- company-wide notices. */
@Repository
public class LegacyAdministrativeDecisionStore {

	private final JdbcTemplate jdbcTemplate;

	public LegacyAdministrativeDecisionStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	public long count(List<String> predicates, List<Object> binds) {
		Long total = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM administrative_decisions WHERE "
						+ String.join(" AND ", predicates),
				Long.class, binds.toArray());
		return total == null ? 0L : total;
	}

	public List<Map<String, Object>> page(
			List<String> predicates, List<Object> binds, long limit, long offset) {
		List<Object> args = new ArrayList<>(binds);
		args.add(limit);
		args.add(offset);
		return jdbcTemplate.query(
				"SELECT * FROM administrative_decisions WHERE " + String.join(" AND ", predicates)
						+ " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
				LegacyJdbcValues.rowMapper(), args.toArray());
	}

	/**
	 * {@code administrative_decision_assert_company_row()}.
	 *
	 * <p>Returns null for a non-positive id or company <em>before</em> querying,
	 * which is what makes {@code ?id=0} a 404 rather than a lookup.
	 */
	public Map<String, Object> assertCompanyRow(long companyId, long id) {
		if (id <= 0 || companyId <= 0) {
			return null;
		}
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT * FROM administrative_decisions WHERE id = ? AND company_id = ?",
				LegacyJdbcValues.rowMapper(), id, companyId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public long insert(long companyId, String title, String body) {
		jdbcTemplate.update(
				"INSERT INTO administrative_decisions (company_id, title, body, is_active)"
						+ " VALUES (?, ?, ?, 1)",
				companyId, title, body);
		Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		return id == null ? 0L : id;
	}

	public void update(long companyId, long id, String title, String body, int isActive) {
		jdbcTemplate.update(
				"UPDATE administrative_decisions SET title = ?, body = ?, is_active = ?"
						+ " WHERE id = ? AND company_id = ?",
				title, body, isActive, id, companyId);
	}

	public void delete(long companyId, long id) {
		jdbcTemplate.update(
				"DELETE FROM administrative_decisions WHERE id = ? AND company_id = ?", id, companyId);
	}
}
