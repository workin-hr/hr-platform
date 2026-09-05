package com.workin.backend.platformadmin.org;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.backend.platformadmin.web.DashboardListFilters;
import com.workin.backend.platformadmin.web.DashboardPage;

/** {@code org_paginate_shifts()} and the writes {@code shifts.php} makes. */
@Repository
@Profile("phase1-mysql")
public class ShiftStore {

	private final JdbcTemplate jdbcTemplate;

	public ShiftStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static RowMapper<Shift> mapper(boolean withCompany) {
		return (rs, rowNum) -> new Shift(
				rs.getLong("id"),
				rs.getLong("company_id"),
				withCompany ? rs.getString("company_name") : null,
				rs.getString("name"),
				rs.getString("start_time"),
				rs.getString("end_time"),
				rs.getInt("is_active") == 1,
				rs.getString("created_at"),
				rs.getInt("emp_count"));
	}

	public DashboardPage<Shift> paginate(DashboardListFilters filters, boolean showCompany) {
		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder("1=1");
		if (filters.companyId() > 0) {
			where.append(" AND s.company_id = ?");
			params.add(filters.companyId());
		}
		if (!filters.search().isEmpty()) {
			where.append(" AND s.name LIKE ?");
			params.add("%" + filters.search() + "%");
		}
		where.append(filters.statusClause("s"));

		String join = showCompany ? " INNER JOIN companies c ON c.id = s.company_id" : "";
		String companyCol = showCompany ? ", c.company_name" : "";

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM shifts s" + join + " WHERE " + where,
				Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		List<Shift> rows = this.jdbcTemplate.query(
				"SELECT s.*" + companyCol + ","
						+ " (SELECT COUNT(DISTINCT employee_id) FROM employee_shift_assignments esa"
						+ " WHERE esa.shift_id = s.id) AS emp_count"
						+ " FROM shifts s" + join
						+ " WHERE " + where
						+ " ORDER BY s.created_at DESC, s.id DESC"
						+ " LIMIT ? OFFSET ?",
				mapper(showCompany), pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	public Shift find(long id) {
		List<Shift> rows = this.jdbcTemplate.query(
				"SELECT s.*, 0 AS emp_count FROM shifts s WHERE s.id = ?", mapper(false), id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public boolean belongsTo(long id, long companyId) {
		if (id <= 0 || companyId <= 0) {
			return false;
		}
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM shifts WHERE id = ? AND company_id = ?",
				Integer.class, id, companyId);
		return count != null && count > 0;
	}

	public long insert(long companyId, String name, String startTime, String endTime) {
		org.springframework.jdbc.support.KeyHolder keys =
				new org.springframework.jdbc.support.GeneratedKeyHolder();
		this.jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					"INSERT INTO shifts (company_id, name, start_time, end_time, is_active, created_at)"
							+ " VALUES (?, ?, ?, ?, 1, NOW())",
					java.sql.Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, companyId);
			statement.setString(2, name);
			statement.setString(3, startTime);
			statement.setString(4, endTime);
			return statement;
		}, keys);
		Number key = keys.getKey();
		return key == null ? 0L : key.longValue();
	}

	public int update(long id, String name, String startTime, String endTime, boolean active) {
		return this.jdbcTemplate.update(
				"UPDATE shifts SET name = ?, start_time = ?, end_time = ?, is_active = ? WHERE id = ?",
				name, startTime, endTime, active ? 1 : 0, id);
	}

	public int softDelete(long id) {
		return this.jdbcTemplate.update("UPDATE shifts SET is_active = 0 WHERE id = ?", id);
	}

}
