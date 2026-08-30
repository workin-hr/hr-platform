package com.workin.legacy.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;

/**
 * {@code workforce_planning} -- planned headcount per
 * (branch, department, job title), with the actual count computed alongside.
 *
 * <h2>The three name joins carry no tenant filter</h2>
 * <p>{@code LEFT JOIN branches b ON b.id = wt.branch_id} and its two siblings
 * match on id alone. That is safe only while every row's
 * {@code branch_id}/{@code department_id}/{@code job_title_id} really does
 * belong to {@code wt.company_id} -- which {@code create.php} enforces and
 * {@code save_target.php} does <b>not</b>. See
 * {@link LegacyWorkforcePlanningService#saveTarget} for what that combination
 * makes possible; it is reproduced deliberately and reported upstream, not
 * quietly repaired here.
 */
@Repository
public class LegacyWorkforcePlanningStore {

	/** The row shape all three read endpoints select, including the correlated actual count. */
	private static final String SELECT_ROW = """
			SELECT wt.*,
			       b.name AS branch_name,
			       s.name AS department_name,
			       jt.name AS job_title_name,
			       (SELECT COUNT(*) FROM employees e
			         WHERE e.company_id = wt.company_id
			           AND e.is_active = 1
			           AND e.branch_id = wt.branch_id
			           AND e.department_id = wt.department_id
			           AND e.job_title_id = wt.job_title_id) AS actual_count
			FROM workforce_planning wt
			LEFT JOIN branches AS b ON b.id = wt.branch_id
			LEFT JOIN departments AS s ON s.id = wt.department_id
			LEFT JOIN job_titles AS jt ON jt.id = wt.job_title_id
			""";

	private final JdbcTemplate jdbcTemplate;

	public LegacyWorkforcePlanningStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** The count query joins the same three tables even though it selects none of their columns. */
	public long count(List<String> predicates, List<Object> binds) {
		Long total = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM workforce_planning wt
				LEFT JOIN branches AS b ON b.id = wt.branch_id
				LEFT JOIN departments AS s ON s.id = wt.department_id
				LEFT JOIN job_titles AS jt ON jt.id = wt.job_title_id
				""" + " WHERE " + String.join(" AND ", predicates),
				Long.class, binds.toArray());
		return total == null ? 0L : total;
	}

	public List<Map<String, Object>> page(
			List<String> predicates, List<Object> binds, long limit, long offset) {
		List<Object> args = new ArrayList<>(binds);
		args.add(limit);
		args.add(offset);
		return jdbcTemplate.query(
				SELECT_ROW + " WHERE " + String.join(" AND ", predicates)
						+ " ORDER BY wt.id DESC LIMIT ? OFFSET ?",
				LegacyJdbcValues.rowMapper(), args.toArray());
	}

	public Map<String, Object> one(long companyId, long id) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				SELECT_ROW + " WHERE wt.id = ? AND wt.company_id = ?",
				LegacyJdbcValues.rowMapper(), id, companyId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** {@code create.php}'s post-insert re-read: by id alone, with no company filter. */
	public Map<String, Object> byId(long id) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				SELECT_ROW + " WHERE wt.id = ?", LegacyJdbcValues.rowMapper(), id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public boolean branchBelongsToCompany(long branchId, long companyId) {
		return exists("SELECT COUNT(*) FROM branches WHERE id=? AND company_id=?", branchId, companyId);
	}

	public boolean departmentBelongsToCompany(long departmentId, long companyId) {
		return exists("SELECT COUNT(*) FROM departments WHERE id=? AND company_id=?",
				departmentId, companyId);
	}

	/** {@code job_title_belongs_to_company()}: also requires {@code is_active = 1}. */
	public boolean jobTitleBelongsToCompany(long jobTitleId, long companyId) {
		return exists("SELECT COUNT(*) FROM job_titles WHERE id = ? AND company_id = ? AND is_active = 1",
				jobTitleId, companyId);
	}

	private boolean exists(String sql, Object... binds) {
		Long count = jdbcTemplate.queryForObject(sql, Long.class, binds);
		return count != null && count > 0;
	}

	public long insert(long companyId, long branchId, long departmentId, long jobTitleId, long planned) {
		return com.workin.legacy.LegacyGeneratedKeys.insert(jdbcTemplate,
				"INSERT INTO workforce_planning (company_id, branch_id, department_id, job_title_id,"
						+ " planned_count) VALUES (?, ?, ?, ?, ?)",
				companyId, branchId, departmentId, jobTitleId, planned);
	}

	/**
	 * {@code save_target.php}'s upsert, which relies on the
	 * {@code uq_workforce_target} unique key over
	 * {@code (company_id, branch_id, department_id, job_title_id)}.
	 */
	public void upsert(long companyId, long branchId, long departmentId, long jobTitleId, long planned) {
		jdbcTemplate.update(
				"INSERT INTO workforce_planning (company_id, branch_id, department_id, job_title_id,"
						+ " planned_count) VALUES (?, ?, ?, ?, ?)"
						+ " ON DUPLICATE KEY UPDATE planned_count = VALUES(planned_count)",
				companyId, branchId, departmentId, jobTitleId, planned);
	}

	public boolean existsForCompany(long companyId, long id) {
		return exists("SELECT COUNT(*) FROM workforce_planning WHERE id=? AND company_id=?", id, companyId);
	}

	public void delete(long companyId, long id) {
		jdbcTemplate.update("DELETE FROM workforce_planning WHERE id=? AND company_id=?", id, companyId);
	}

	public void update(long companyId, long id, List<String> assignments, List<Object> values) {
		List<Object> args = new ArrayList<>(values);
		args.add(id);
		args.add(companyId);
		jdbcTemplate.update(
				"UPDATE workforce_planning SET " + String.join(", ", assignments)
						+ " WHERE id=? AND company_id=?",
				args.toArray());
	}
}
