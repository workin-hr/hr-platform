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
 * <h2>The three name joins relate to the row's own company</h2>
 * <p>{@code LEFT JOIN branches b ON b.id = wt.branch_id AND b.company_id =
 * wt.company_id} and its two siblings. Matching on id alone would be safe only
 * while every row's {@code branch_id}/{@code department_id}/
 * {@code job_title_id} really does belong to {@code wt.company_id}, and no
 * foreign key makes that true: {@code save_target.php} wrote whatever it was
 * given for the whole life of this surface, and legacy still does against the
 * same database, so rows that cross a tenant boundary can already exist. The
 * write paths can no longer create one ({@link LegacyWorkforcePlanningService});
 * these predicates are what covers the ones already written, which a write-side
 * check cannot reach (D-277).
 *
 * <p>They stay {@code LEFT JOIN}s: such a row <b>remains in its owner's list</b>
 * with the name absent, rather than disappearing, so the tenant keeps sight of
 * a plan it owns while nobody else's name is read. {@code dashboard/stats.php}
 * answers the same shape with an inner join and therefore drops the row; that
 * difference is legacy's, not this port's.
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
			                       AND b.company_id = wt.company_id
			LEFT JOIN departments AS s ON s.id = wt.department_id
			                         AND s.company_id = wt.company_id
			LEFT JOIN job_titles AS jt ON jt.id = wt.job_title_id
			                          AND jt.company_id = wt.company_id
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
				                       AND b.company_id = wt.company_id
				LEFT JOIN departments AS s ON s.id = wt.department_id
				                          AND s.company_id = wt.company_id
				LEFT JOIN job_titles AS jt ON jt.id = wt.job_title_id
				                          AND jt.company_id = wt.company_id
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

	/**
	 * The tenant half of the rule above, without the activity half -- for editing
	 * a row that may already name a title this company has since deactivated
	 * (D-277).
	 */
	public boolean jobTitleOwnedByCompany(long jobTitleId, long companyId) {
		return exists("SELECT COUNT(*) FROM job_titles WHERE id = ? AND company_id = ?",
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
