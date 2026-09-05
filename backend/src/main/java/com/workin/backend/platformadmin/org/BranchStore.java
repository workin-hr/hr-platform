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

/**
 * {@code org_paginate_branches()} and the writes {@code branches.php} makes.
 *
 * <p>The two subselects for employee and department counts are legacy's own
 * and are per row, which is an N+1 written in SQL rather than in a loop. Left
 * as they are: the page shows ten rows, the columns are correlated counts a
 * join would have to group by, and D-085's rule is that a query is reproduced
 * unless a decision records why it changed.
 */
@Repository
@Profile("phase1-mysql")
public class BranchStore {

	private final JdbcTemplate jdbcTemplate;

	public BranchStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static RowMapper<Branch> mapper(boolean withCompany) {
		return (rs, rowNum) -> new Branch(
				rs.getLong("id"),
				rs.getLong("company_id"),
				withCompany ? rs.getString("company_name") : null,
				rs.getString("name"),
				rs.getString("address"),
				rs.getBigDecimal("latitude"),
				rs.getBigDecimal("longitude"),
				rs.getInt("radius_meters"),
				rs.getInt("is_active") == 1,
				rs.getString("qr_code"),
				rs.getString("expires_at"),
				rs.getString("created_at"),
				rs.getInt("emp_count"),
				rs.getInt("dept_count"));
	}

	/**
	 * The list page's query, clause for clause.
	 *
	 * @param showCompany {@code org_show_company_column()} -- decides both the
	 *                    joined column and the {@code INNER JOIN} that comes
	 *                    with it. The join is not free: it also drops any
	 *                    branch whose {@code company_id} points at a company
	 *                    that no longer exists, which the unfiltered view would
	 *                    otherwise show with an empty name
	 */
	public DashboardPage<Branch> paginate(DashboardListFilters filters, boolean showCompany) {
		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder("1=1");
		if (filters.companyId() > 0) {
			where.append(" AND b.company_id = ?");
			params.add(filters.companyId());
		}
		if (!filters.search().isEmpty()) {
			where.append(" AND (b.name LIKE ? OR COALESCE(b.address, '') LIKE ?)");
			params.add("%" + filters.search() + "%");
			params.add("%" + filters.search() + "%");
		}
		where.append(filters.statusClause("b"));

		String join = showCompany ? " INNER JOIN companies c ON c.id = b.company_id" : "";
		String companyCol = showCompany ? ", c.company_name" : "";

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM branches b" + join + " WHERE " + where,
				Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		List<Branch> rows = this.jdbcTemplate.query(
				"SELECT b.*" + companyCol + ","
						+ " (SELECT COUNT(*) FROM employees e WHERE e.branch_id = b.id AND e.is_active = 1)"
						+ " AS emp_count,"
						+ " (SELECT COUNT(*) FROM department_branches db WHERE db.branch_id = b.id)"
						+ " AS dept_count"
						+ " FROM branches b" + join
						+ " WHERE " + where
						+ " ORDER BY b.created_at DESC, b.id DESC"
						+ " LIMIT ? OFFSET ?",
				mapper(showCompany), pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	/** {@code dbFind('branches', $id)}. */
	public Branch find(long id) {
		List<Branch> rows = this.jdbcTemplate.query(
				"SELECT b.*, 0 AS emp_count, 0 AS dept_count FROM branches b WHERE b.id = ?",
				mapper(false), id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** {@code org_assert_company_row()}: does this row belong to that company? */
	public boolean belongsTo(long id, long companyId) {
		if (id <= 0 || companyId <= 0) {
			return false;
		}
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM branches WHERE id = ? AND company_id = ?",
				Integer.class, id, companyId);
		return count != null && count > 0;
	}

	/** The {@code add} action's insert. A new branch is always active. */
	public long insert(
			long companyId, String name, String address, BigDecimal latitude, BigDecimal longitude,
			int radiusMeters) {
		org.springframework.jdbc.support.KeyHolder keys =
				new org.springframework.jdbc.support.GeneratedKeyHolder();
		this.jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					"INSERT INTO branches (company_id, name, address, latitude, longitude,"
							+ " radius_meters, is_active, created_at)"
							+ " VALUES (?, ?, ?, ?, ?, ?, 1, NOW())",
					java.sql.Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, companyId);
			statement.setString(2, name);
			statement.setString(3, address);
			setNullable(statement, 4, latitude);
			setNullable(statement, 5, longitude);
			statement.setInt(6, radiusMeters);
			return statement;
		}, keys);
		Number key = keys.getKey();
		return key == null ? 0L : key.longValue();
	}

	private static void setNullable(java.sql.PreparedStatement statement, int index, BigDecimal value)
			throws java.sql.SQLException {
		if (value == null) {
			statement.setNull(index, java.sql.Types.DECIMAL);
		} else {
			statement.setBigDecimal(index, value);
		}
	}

	/**
	 * The {@code save_edit} action's update.
	 *
	 * <p>{@code company_id} is not among the columns: the edit form carries it
	 * as a hidden field for the redirect's benefit, and legacy never writes it.
	 * A branch cannot be moved between companies from this page, and this is
	 * where that stays true.
	 */
	public int update(
			long id, String name, String address, BigDecimal latitude, BigDecimal longitude,
			int radiusMeters, boolean active) {
		return this.jdbcTemplate.update(
				"UPDATE branches SET name = ?, address = ?, latitude = ?, longitude = ?,"
						+ " radius_meters = ?, is_active = ? WHERE id = ?",
				name, address, latitude, longitude, radiusMeters, active ? 1 : 0, id);
	}

	/**
	 * {@code dbSoftDelete('branches', $id)}: {@code is_active = 0}, never a
	 * {@code DELETE}.
	 *
	 * <p>Employees, attendance rows and department links all point at the
	 * branch. A hard delete would either fail on the foreign keys or orphan
	 * them, and the dashboard's "delete" has always meant "deactivate".
	 */
	public int softDelete(long id) {
		return this.jdbcTemplate.update("UPDATE branches SET is_active = 0 WHERE id = ?", id);
	}

	/** The {@code generate_qr} action's update. */
	public int storeQr(long id, String code, String expiresAt) {
		return this.jdbcTemplate.update(
				"UPDATE branches SET qr_code = ?, expires_at = ? WHERE id = ?", code, expiresAt, id);
	}

}
