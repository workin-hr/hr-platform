package com.workin.backend.platformadmin.hr;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.backend.platformadmin.web.DashboardListFilters;
import com.workin.backend.platformadmin.web.DashboardPage;

/**
 * The queries {@code workforce_planning} makes
 * ({@code hr_paginate_workforce()}, {@code hr_list_helper.php:381-424}).
 */
@Repository
@Profile("phase1-mysql")
public class WorkforcePlanStore {

	private final JdbcTemplate jdbcTemplate;

	public WorkforcePlanStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static final RowMapper<WorkforcePlan> MAPPER = (rs, rowNum) -> new WorkforcePlan(
			rs.getLong("id"),
			rs.getLong("company_id"),
			rs.getLong("branch_id"),
			rs.getString("branch_name"),
			rs.getLong("department_id"),
			rs.getString("department_name"),
			rs.getLong("job_title_id"),
			rs.getString("job_title_name"),
			rs.getInt("planned_count"),
			rs.getInt("actual_count"));

	/**
	 * The actual headcount, counted per row rather than joined.
	 *
	 * <p>Matching on {@code e.department_id = wt.department_id} means a plan
	 * with no department counts only employees whose department is likewise
	 * zero -- not every employee in the branch. That is legacy's arithmetic and
	 * it is reproduced, not corrected.
	 */
	private static final String ACTUAL_COUNT =
			" (SELECT COUNT(*) FROM employees e"
					+ " WHERE e.company_id = wt.company_id AND e.is_active = 1"
					+ " AND e.branch_id = wt.branch_id"
					+ " AND e.department_id = wt.department_id"
					+ " AND e.job_title_id = wt.job_title_id) AS actual_count";

	private static final String FROM =
			" FROM workforce_planning wt"
					+ " LEFT JOIN branches b ON b.id = wt.branch_id"
					+ " LEFT JOIN departments d ON d.id = wt.department_id"
					+ " LEFT JOIN job_titles jt ON jt.id = wt.job_title_id";

	public DashboardPage<WorkforcePlan> paginate(DashboardListFilters filters) {
		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder("1=1");
		if (filters.companyId() > 0) {
			where.append(" AND wt.company_id = ?");
			params.add(filters.companyId());
		}
		// The search matches the job title only -- not the branch or the
		// department, though both are joined and displayed.
		if (!filters.search().isEmpty()) {
			where.append(" AND jt.name LIKE ?");
			params.add("%" + filters.search() + "%");
		}

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*)" + FROM + " WHERE " + where, Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		List<WorkforcePlan> rows = this.jdbcTemplate.query(
				"SELECT wt.*, b.name AS branch_name, d.name AS department_name,"
						+ " jt.name AS job_title_name," + ACTUAL_COUNT + FROM
						+ " WHERE " + where
						+ " ORDER BY jt.name, wt.id DESC"
						+ " LIMIT ? OFFSET ?",
				MAPPER, pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	/**
	 * The form's three selects, scoped to one company.
	 *
	 * <p><b>R-051</b>: legacy builds these from five queries whose only
	 * predicate is {@code is_active = 1}, JSON-encodes every company's rows
	 * into {@code data-} attributes and lets the browser pick the group for the
	 * chosen company. Measured against the production copy that is 3,671 rows
	 * across 283 companies handed to any company-scoped session that opens the
	 * page. Filtering client-side is not filtering; the predicate belongs here.
	 *
	 * <p>A {@code companyId} of zero is the administrator with no filter, whose
	 * reach genuinely is every company -- so that case returns everything, with
	 * the company name appended the way {@code org_option_label()} does when no
	 * company is chosen.
	 */
	public List<WorkforcePlan.Option> branchOptions(long companyId) {
		return options("branches", companyId);
	}

	public List<WorkforcePlan.Option> departmentOptions(long companyId) {
		return options("departments", companyId);
	}

	public List<WorkforcePlan.Option> jobTitleOptions(long companyId) {
		return options("job_titles", companyId);
	}

	private List<WorkforcePlan.Option> options(String table, long companyId) {
		if (companyId > 0) {
			return this.jdbcTemplate.query(
					"SELECT id, name FROM " + table + " WHERE company_id = ? AND is_active = 1"
							+ " ORDER BY name",
					(rs, rowNum) -> new WorkforcePlan.Option(
							rs.getLong("id"), rs.getString("name"), null),
					companyId);
		}
		return this.jdbcTemplate.query(
				"SELECT t.id, t.name, c.company_name FROM " + table + " t"
						+ " INNER JOIN companies c ON c.id = t.company_id"
						+ " WHERE t.is_active = 1 ORDER BY c.company_name, t.name",
				(rs, rowNum) -> new WorkforcePlan.Option(
						rs.getLong("id"), rs.getString("name"), rs.getString("company_name")));
	}

	/** The company that owns a plan -- a column on the row itself. */
	public Long companyOf(long id) {
		if (id <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT company_id FROM workforce_planning WHERE id = ?", Long.class, id);
		return found.isEmpty() ? null : found.get(0);
	}

	public WorkforcePlan find(long id) {
		List<WorkforcePlan> rows = this.jdbcTemplate.query(
				"SELECT wt.*, b.name AS branch_name, d.name AS department_name,"
						+ " jt.name AS job_title_name," + ACTUAL_COUNT + FROM
						+ " WHERE wt.id = ?",
				MAPPER, id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * {@code org_branch_belongs_to_company()} ({@code org_helper.php:266-275}).
	 * Note the {@code is_active = 1}: an archived branch is not a valid target
	 * even for the company that owns it.
	 */
	public boolean branchInCompany(long branchId, long companyId) {
		return exists("branches", branchId, companyId);
	}

	/**
	 * {@code org_department_belongs_to_company()}
	 * ({@code org_helper.php:470-482}). A department id of zero is
	 * <b>accepted</b>: the column is optional and legacy returns true early
	 * for it.
	 */
	public boolean departmentInCompany(long departmentId, long companyId) {
		if (departmentId <= 0) {
			return true;
		}
		return exists("departments", departmentId, companyId);
	}

	/** The page checks this one inline rather than through an org helper. */
	public boolean jobTitleInCompany(long jobTitleId, long companyId) {
		return exists("job_titles", jobTitleId, companyId);
	}

	private boolean exists(String table, long id, long companyId) {
		if (id <= 0 || companyId <= 0) {
			return false;
		}
		Integer found = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM " + table + " WHERE id = ? AND company_id = ? AND"
						+ " is_active = 1",
				Integer.class, id, companyId);
		return found != null && found > 0;
	}

	/**
	 * The id already planning this exact target, or null. Backs the
	 * <b>R-050</b> check; the table's {@code uq_workforce_target} unique key
	 * remains the actual guarantee.
	 */
	public Long findTarget(long companyId, long branchId, long departmentId, long jobTitleId) {
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT id FROM workforce_planning WHERE company_id = ? AND branch_id = ?"
						+ " AND department_id = ? AND job_title_id = ?",
				Long.class, companyId, branchId, departmentId, jobTitleId);
		return found.isEmpty() ? null : found.get(0);
	}

	public long insert(
			long companyId, long branchId, long departmentId, long jobTitleId, int plannedCount) {
		org.springframework.jdbc.support.KeyHolder keys =
				new org.springframework.jdbc.support.GeneratedKeyHolder();
		this.jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					"INSERT INTO workforce_planning (company_id, branch_id, department_id,"
							+ " job_title_id, planned_count, created_at)"
							+ " VALUES (?, ?, ?, ?, ?, NOW())",
					java.sql.Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, companyId);
			statement.setLong(2, branchId);
			statement.setLong(3, departmentId);
			statement.setLong(4, jobTitleId);
			statement.setInt(5, plannedCount);
			return statement;
		}, keys);
		Number key = keys.getKey();
		return key == null ? 0L : key.longValue();
	}

	/**
	 * <b>D-176</b>: {@code company_id} is deliberately absent from this
	 * statement.
	 *
	 * <p>Legacy writes it here from {@code $resolveWpCompanyId()}, which for an
	 * administrator with no company filter is the <i>posted</i> value -- and it
	 * validates all three foreign keys against that same posted company, so the
	 * row and everything it points at move to another tenant together and
	 * arrive self-consistent (<b>R-047</b>). The three keys stay writable here
	 * because a plan may legitimately be re-pointed within its own company;
	 * {@link WorkforcePlanAdminService} is what holds them to it.
	 */
	public int update(long id, long branchId, long departmentId, long jobTitleId, int plannedCount) {
		return this.jdbcTemplate.update(
				"UPDATE workforce_planning SET branch_id = ?, department_id = ?,"
						+ " job_title_id = ?, planned_count = ? WHERE id = ?",
				branchId, departmentId, jobTitleId, plannedCount, id);
	}

	public int delete(long id) {
		return this.jdbcTemplate.update("DELETE FROM workforce_planning WHERE id = ?", id);
	}

}
