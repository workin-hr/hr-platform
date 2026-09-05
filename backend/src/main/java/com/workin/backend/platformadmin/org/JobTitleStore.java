package com.workin.backend.platformadmin.org;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.backend.platformadmin.web.DashboardListFilters;
import com.workin.backend.platformadmin.web.DashboardPage;

/** {@code org_paginate_job_titles()} and the writes {@code job_titles.php} makes. */
@Repository
@Profile("phase1-mysql")
public class JobTitleStore {

	private final JdbcTemplate jdbcTemplate;

	public JobTitleStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static RowMapper<JobTitle> mapper(boolean withCompany) {
		return (rs, rowNum) -> {
			long departmentId = rs.getLong("department_id");
			return new JobTitle(
					rs.getLong("id"),
					rs.getLong("company_id"),
					withCompany ? rs.getString("company_name") : null,
					rs.wasNull() ? null : departmentId,
					rs.getString("department_name"),
					rs.getString("name"),
					rs.getBigDecimal("work_hours"),
					rs.getInt("is_active") == 1,
					rs.getString("created_at"),
					rs.getInt("emp_count"));
		};
	}

	public DashboardPage<JobTitle> paginate(DashboardListFilters filters, boolean showCompany) {
		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder("1=1");
		if (filters.companyId() > 0) {
			where.append(" AND jt.company_id = ?");
			params.add(filters.companyId());
		}
		if (!filters.search().isEmpty()) {
			where.append(" AND jt.name LIKE ?");
			params.add("%" + filters.search() + "%");
		}
		where.append(filters.statusClause("jt"));
		if (filters.filterDepartment() > 0) {
			where.append(" AND jt.department_id = ?");
			params.add(filters.filterDepartment());
		}

		// The count query joins companies but NOT departments, which is
		// legacy's own asymmetry and is correct: the department join is a LEFT
		// JOIN and cannot change the count.
		String join = showCompany ? " INNER JOIN companies c ON c.id = jt.company_id" : "";
		String companyCol = showCompany ? ", c.company_name" : "";

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM job_titles jt" + join + " WHERE " + where,
				Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		List<JobTitle> rows = this.jdbcTemplate.query(
				"SELECT jt.*" + companyCol + ", d.name AS department_name,"
						+ " (SELECT COUNT(*) FROM employees e WHERE e.job_title_id = jt.id"
						+ " AND e.is_active = 1) AS emp_count"
						+ " FROM job_titles jt"
						+ " LEFT JOIN departments d ON d.id = jt.department_id" + join
						+ " WHERE " + where
						+ " ORDER BY jt.created_at DESC, jt.id DESC"
						+ " LIMIT ? OFFSET ?",
				mapper(showCompany), pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	public JobTitle find(long id) {
		List<JobTitle> rows = this.jdbcTemplate.query(
				"SELECT jt.*, d.name AS department_name, 0 AS emp_count FROM job_titles jt"
						+ " LEFT JOIN departments d ON d.id = jt.department_id WHERE jt.id = ?",
				mapper(false), id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public boolean belongsTo(long id, long companyId) {
		if (id <= 0 || companyId <= 0) {
			return false;
		}
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM job_titles WHERE id = ? AND company_id = ?",
				Integer.class, id, companyId);
		return count != null && count > 0;
	}

	/** {@code org_department_belongs_to_company()}. */
	public boolean departmentBelongsTo(long departmentId, long companyId) {
		if (departmentId <= 0 || companyId <= 0) {
			return false;
		}
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM departments WHERE id = ? AND company_id = ?",
				Integer.class, departmentId, companyId);
		return count != null && count > 0;
	}

	/** {@code org_departments_for_company()}: the picker's options. */
	public List<JobTitle.DepartmentOption> departmentOptions(long companyId) {
		if (companyId > 0) {
			return this.jdbcTemplate.query(
					"SELECT id, name FROM departments WHERE company_id = ? AND is_active = 1"
							+ " ORDER BY name",
					(rs, rowNum) -> new JobTitle.DepartmentOption(
							rs.getLong("id"), rs.getString("name"), null),
					companyId);
		}
		return this.jdbcTemplate.query(
				"SELECT d.id, d.name, c.company_name FROM departments d"
						+ " INNER JOIN companies c ON c.id = d.company_id"
						+ " WHERE d.is_active = 1 ORDER BY c.company_name, d.name",
				(rs, rowNum) -> new JobTitle.DepartmentOption(
						rs.getLong("id"), rs.getString("name"), rs.getString("company_name")));
	}

	public long insert(long companyId, Long departmentId, String name, BigDecimal workHours) {
		org.springframework.jdbc.support.KeyHolder keys =
				new org.springframework.jdbc.support.GeneratedKeyHolder();
		this.jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					"INSERT INTO job_titles (company_id, department_id, name, work_hours,"
							+ " is_active, created_at) VALUES (?, ?, ?, ?, 1, NOW())",
					java.sql.Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, companyId);
			if (departmentId == null) {
				statement.setNull(2, java.sql.Types.BIGINT);
			} else {
				statement.setLong(2, departmentId);
			}
			statement.setString(3, name);
			statement.setBigDecimal(4, workHours);
			return statement;
		}, keys);
		Number key = keys.getKey();
		return key == null ? 0L : key.longValue();
	}

	public int update(long id, Long departmentId, String name, BigDecimal workHours, boolean active) {
		return this.jdbcTemplate.update(
				"UPDATE job_titles SET department_id = ?, name = ?, work_hours = ?, is_active = ?"
						+ " WHERE id = ?",
				departmentId, name, workHours, active ? 1 : 0, id);
	}

	public int softDelete(long id) {
		return this.jdbcTemplate.update("UPDATE job_titles SET is_active = 0 WHERE id = ?", id);
	}

}
