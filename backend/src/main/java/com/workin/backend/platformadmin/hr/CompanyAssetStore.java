package com.workin.backend.platformadmin.hr;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.backend.platformadmin.web.DashboardListFilters;
import com.workin.backend.platformadmin.web.DashboardPage;

/** {@code hr_paginate_assets()} and the writes {@code assets.php} makes. */
@Repository
@Profile("phase1-mysql")
public class CompanyAssetStore {

	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,'')))";

	private static final String EMP_CODE =
			"COALESCE(NULLIF(TRIM(e.employee_code), ''), CAST(e.id AS CHAR))";

	private final JdbcTemplate jdbcTemplate;

	public CompanyAssetStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static RowMapper<CompanyAsset> mapper(boolean withCompany) {
		return (rs, rowNum) -> new CompanyAsset(
				rs.getLong("id"),
				rs.getLong("employee_id"),
				rs.getLong("company_id"),
				withCompany ? rs.getString("company_name") : null,
				rs.getString("emp_code"),
				rs.getString("employee_name"),
				rs.getString("asset_text"),
				rs.getString("asset_date"),
				rs.getString("asset_end_date"),
				rs.getInt("is_returned") == 1,
				rs.getString("returned_at"),
				rs.getString("created_at"));
	}

	public DashboardPage<CompanyAsset> paginate(
			DashboardListFilters filters, String returned, String dateFrom, String dateTo,
			boolean showCompany) {
		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder("1=1");
		if (filters.companyId() > 0) {
			where.append(" AND e.company_id = ?");
			params.add(filters.companyId());
		}
		if ("0".equals(returned)) {
			where.append(" AND a.is_returned = 0");
		} else if ("1".equals(returned)) {
			where.append(" AND a.is_returned = 1");
		}
		if (dateFrom != null && !dateFrom.isBlank()) {
			where.append(" AND a.asset_date >= ?");
			params.add(dateFrom.trim());
		}
		if (dateTo != null && !dateTo.isBlank()) {
			where.append(" AND a.asset_date <= ?");
			params.add(dateTo.trim());
		}
		if (!filters.search().isEmpty()) {
			where.append(" AND (").append(DISPLAY_NAME).append(" LIKE ? OR ")
					.append(EMP_CODE).append(" LIKE ? OR a.asset_text LIKE ?)");
			params.add("%" + filters.search() + "%");
			params.add("%" + filters.search() + "%");
			params.add("%" + filters.search() + "%");
		}

		String join = showCompany ? " JOIN companies c ON c.id = e.company_id" : "";
		String companyCol = showCompany ? ", c.company_name" : "";

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM assets a JOIN employees e ON e.id = a.employee_id"
						+ join + " WHERE " + where,
				Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		// e.company_id, not a.company_id: the filter and the display both follow
		// the employee, which is what keeps a row visible after its employee is
		// moved between companies.
		List<CompanyAsset> rows = this.jdbcTemplate.query(
				"SELECT a.*, e.company_id" + companyCol + ", "
						+ DISPLAY_NAME + " AS employee_name, " + EMP_CODE + " AS emp_code"
						+ " FROM assets a JOIN employees e ON e.id = a.employee_id" + join
						+ " WHERE " + where
						+ " ORDER BY a.asset_date DESC, a.id DESC"
						+ " LIMIT ? OFFSET ?",
				mapper(showCompany), pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	/**
	 * R-046's lookup. Read through the <em>employee</em> rather than from the
	 * row's own {@code company_id}, so the check agrees with what the list
	 * showed -- the two can differ if an employee has moved.
	 */
	public Long companyOf(long id) {
		if (id <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT e.company_id FROM assets a JOIN employees e ON e.id = a.employee_id"
						+ " WHERE a.id = ?", Long.class, id);
		return found.isEmpty() ? null : found.get(0);
	}

	public Long employeeCompany(long employeeId) {
		if (employeeId <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT company_id FROM employees WHERE id = ?", Long.class, employeeId);
		return found.isEmpty() ? null : found.get(0);
	}

	public Boolean isReturned(long id) {
		List<Integer> found = this.jdbcTemplate.queryForList(
				"SELECT is_returned FROM assets WHERE id = ?", Integer.class, id);
		return found.isEmpty() ? null : found.get(0) == 1;
	}

	public List<LeaveBalance.EmployeeOption> employeeOptions(long companyId) {
		if (companyId > 0) {
			return this.jdbcTemplate.query(
					"SELECT e.id, " + EMP_CODE + " AS emp_code, " + DISPLAY_NAME + " AS employee_name"
							+ " FROM employees e WHERE e.company_id = ? AND e.is_active = 1"
							+ " ORDER BY employee_name",
					(rs, rowNum) -> new LeaveBalance.EmployeeOption(
							rs.getLong("id"), rs.getString("emp_code"),
							rs.getString("employee_name"), null),
					companyId);
		}
		return this.jdbcTemplate.query(
				"SELECT e.id, " + EMP_CODE + " AS emp_code, " + DISPLAY_NAME + " AS employee_name,"
						+ " c.company_name FROM employees e JOIN companies c ON c.id = e.company_id"
						+ " WHERE e.is_active = 1 ORDER BY c.company_name, employee_name LIMIT 500",
				(rs, rowNum) -> new LeaveBalance.EmployeeOption(
						rs.getLong("id"), rs.getString("emp_code"),
						rs.getString("employee_name"), rs.getString("company_name")));
	}

	public long insert(
			long companyId, long employeeId, String assetDate, String assetEndDate,
			String assetText) {
		org.springframework.jdbc.support.KeyHolder keys =
				new org.springframework.jdbc.support.GeneratedKeyHolder();
		this.jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					"INSERT INTO assets (company_id, employee_id, asset_date, asset_end_date,"
							+ " asset_text, is_returned, created_at)"
							+ " VALUES (?, ?, ?, ?, ?, 0, NOW())",
					java.sql.Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, companyId);
			statement.setLong(2, employeeId);
			statement.setString(3, assetDate);
			statement.setString(4, assetEndDate);
			statement.setString(5, assetText);
			return statement;
		}, keys);
		Number key = keys.getKey();
		return key == null ? 0L : key.longValue();
	}

	/** {@code company_id} is among the updated columns, taken from the employee. */
	public int update(
			long id, long companyId, long employeeId, String assetDate, String assetEndDate,
			String assetText) {
		return this.jdbcTemplate.update(
				"UPDATE assets SET company_id = ?, employee_id = ?, asset_date = ?,"
						+ " asset_end_date = ?, asset_text = ? WHERE id = ?",
				companyId, employeeId, assetDate, assetEndDate, assetText, id);
	}

	public int markReturned(long id, String returnedAt) {
		return this.jdbcTemplate.update(
				"UPDATE assets SET is_returned = 1, returned_at = ? WHERE id = ?", returnedAt, id);
	}

	public int delete(long id) {
		return this.jdbcTemplate.update("DELETE FROM assets WHERE id = ?", id);
	}

}
