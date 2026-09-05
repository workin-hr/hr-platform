package com.workin.backend.platformadmin.org;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.backend.platformadmin.web.DashboardListFilters;
import com.workin.backend.platformadmin.web.DashboardPage;

/**
 * {@code org_paginate_departments()} and the writes {@code departments.php}
 * makes, including the {@code department_branches} link table.
 */
@Repository
@Profile("phase1-mysql")
public class DepartmentStore {

	private final JdbcTemplate jdbcTemplate;

	public DepartmentStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static RowMapper<Department> mapper(boolean withCompany) {
		return (rs, rowNum) -> new Department(
				rs.getLong("id"),
				rs.getLong("company_id"),
				withCompany ? rs.getString("company_name") : null,
				rs.getString("name"),
				rs.getInt("is_active") == 1,
				rs.getString("branch_names"),
				rs.getString("created_at"),
				rs.getInt("emp_count"),
				rs.getInt("job_count"),
				List.of());
	}

	public DashboardPage<Department> paginate(DashboardListFilters filters, boolean showCompany) {
		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder("1=1");
		if (filters.companyId() > 0) {
			where.append(" AND d.company_id = ?");
			params.add(filters.companyId());
		}
		if (!filters.search().isEmpty()) {
			// Name only -- a department has no address to search, unlike a branch.
			where.append(" AND d.name LIKE ?");
			params.add("%" + filters.search() + "%");
		}
		where.append(filters.statusClause("d"));
		if (filters.filterBranch() > 0) {
			where.append(" AND EXISTS (SELECT 1 FROM department_branches db"
					+ " WHERE db.department_id = d.id AND db.branch_id = ?)");
			params.add(filters.filterBranch());
		}

		String join = showCompany ? " INNER JOIN companies c ON c.id = d.company_id" : "";
		String companyCol = showCompany ? ", c.company_name" : "";

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM departments d" + join + " WHERE " + where,
				Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		List<Department> rows = this.jdbcTemplate.query(
				"SELECT d.*" + companyCol + ","
						+ " (SELECT COUNT(*) FROM employees e WHERE e.department_id = d.id"
						+ " AND e.is_active = 1) AS emp_count,"
						+ " (SELECT COUNT(*) FROM job_titles jt WHERE jt.department_id = d.id"
						+ " AND jt.is_active = 1) AS job_count,"
						+ " (SELECT GROUP_CONCAT(b.name ORDER BY b.name SEPARATOR ', ')"
						+ " FROM department_branches db INNER JOIN branches b ON b.id = db.branch_id"
						+ " WHERE db.department_id = d.id) AS branch_names"
						+ " FROM departments d" + join
						+ " WHERE " + where
						+ " ORDER BY d.created_at DESC, d.id DESC"
						+ " LIMIT ? OFFSET ?",
				mapper(showCompany), pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	/** {@code dbFind()} plus {@code org_department_branch_ids()}, which the edit form needs. */
	public Department find(long id) {
		List<Department> rows = this.jdbcTemplate.query(
				"SELECT d.*, NULL AS branch_names, 0 AS emp_count, 0 AS job_count"
						+ " FROM departments d WHERE d.id = ?",
				mapper(false), id);
		if (rows.isEmpty()) {
			return null;
		}
		Department row = rows.get(0);
		return new Department(row.id(), row.companyId(), row.companyName(), row.name(), row.active(),
				row.branchNames(), row.createdAt(), row.employeeCount(), row.jobTitleCount(),
				branchIds(id));
	}

	/** {@code org_department_branch_ids()}. */
	public List<Long> branchIds(long departmentId) {
		if (departmentId <= 0) {
			return List.of();
		}
		return this.jdbcTemplate.queryForList(
				"SELECT branch_id FROM department_branches WHERE department_id = ? ORDER BY branch_id",
				Long.class, departmentId);
	}

	/**
	 * The company that owns this row -- authoritative for an edit, because the
	 * posted {@code company_id} is the operator's and can name any company.
	 */
	public Long companyOf(long id) {
		if (id <= 0) {
			return null;
		}
		java.util.List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT company_id FROM departments WHERE id = ?", Long.class, id);
		return found.isEmpty() ? null : found.get(0);
	}

	public boolean belongsTo(long id, long companyId) {
		if (id <= 0 || companyId <= 0) {
			return false;
		}
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM departments WHERE id = ? AND company_id = ?",
				Integer.class, id, companyId);
		return count != null && count > 0;
	}

	/** {@code org_branch_belongs_to_company()}, for the branch-picker validation. */
	public boolean branchBelongsTo(long branchId, long companyId) {
		if (branchId <= 0 || companyId <= 0) {
			return false;
		}
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM branches WHERE id = ? AND company_id = ?",
				Integer.class, branchId, companyId);
		return count != null && count > 0;
	}

	/**
	 * {@code org_branches_for_company()}: the picker's options.
	 *
	 * <p>With no company chosen this is every active branch on the platform,
	 * grouped by company name -- which is only reachable by an administrator,
	 * and is what makes "add a department before picking a company" a
	 * recoverable state rather than an empty form.
	 */
	public List<Department.BranchOption> branchOptions(long companyId) {
		if (companyId > 0) {
			return this.jdbcTemplate.query(
					"SELECT id, name FROM branches WHERE company_id = ? AND is_active = 1 ORDER BY name",
					(rs, rowNum) -> new Department.BranchOption(
							rs.getLong("id"), rs.getString("name"), null),
					companyId);
		}
		return this.jdbcTemplate.query(
				"SELECT b.id, b.name, c.company_name FROM branches b"
						+ " INNER JOIN companies c ON c.id = b.company_id"
						+ " WHERE b.is_active = 1 ORDER BY c.company_name, b.name",
				(rs, rowNum) -> new Department.BranchOption(
						rs.getLong("id"), rs.getString("name"), rs.getString("company_name")));
	}

	public long insert(long companyId, String name) {
		org.springframework.jdbc.support.KeyHolder keys =
				new org.springframework.jdbc.support.GeneratedKeyHolder();
		this.jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					"INSERT INTO departments (company_id, name, is_active, created_at)"
							+ " VALUES (?, ?, 1, NOW())",
					java.sql.Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, companyId);
			statement.setString(2, name);
			return statement;
		}, keys);
		Number key = keys.getKey();
		return key == null ? 0L : key.longValue();
	}

	public int update(long id, String name, boolean active) {
		return this.jdbcTemplate.update(
				"UPDATE departments SET name = ?, is_active = ? WHERE id = ?",
				name, active ? 1 : 0, id);
	}

	public int softDelete(long id) {
		return this.jdbcTemplate.update("UPDATE departments SET is_active = 0 WHERE id = ?", id);
	}

	/**
	 * {@code org_department_sync_branches()}: delete every link, then insert
	 * the posted set.
	 *
	 * <p>Not a diff. The rows carry nothing but the pair, so replacing them is
	 * the same end state with one fewer thing to get wrong -- and the caller
	 * has already refused an empty set, so this cannot leave a department
	 * attached to nothing.
	 */
	public void syncBranches(long departmentId, List<Long> branchIds) {
		this.jdbcTemplate.update(
				"DELETE FROM department_branches WHERE department_id = ?", departmentId);
		for (Long branchId : branchIds) {
			this.jdbcTemplate.update(
					"INSERT INTO department_branches (department_id, branch_id) VALUES (?, ?)",
					departmentId, branchId);
		}
	}

}
