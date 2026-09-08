package com.workin.backend.platformadmin.companies;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.backend.platformadmin.web.DashboardPage;

/**
 * {@code dashboard/pages/companies/page.php}'s list query.
 *
 * <p>Read-only. The lifecycle writes stay on
 * {@code PlatformAdminCompanyDirectory}, behind the step-up ceremony ADR-0015
 * requires -- legacy posts approve, reject and delete straight from this page
 * with no second factor, and that is the one thing the port deliberately does
 * not reproduce.
 *
 * <p>The three lookup tables and the aggregate counts are legacy schema, read
 * where the dashboard reads them.
 */
@Repository
public class CompanyDirectoryStore {

	/**
	 * The three counts as correlated subqueries, exactly as PHP writes them.
	 *
	 * <p>Not joins: a join to three tables at once multiplies the rows and the
	 * counts come out wrong, which is presumably why the PHP is written this
	 * way too. Employees are counted active-only and branches are not, and that
	 * asymmetry is legacy's own -- reproduced rather than tidied, because a
	 * count on this page is a number an operator has been reading for a while.
	 */
	private static final String COUNTS =
			" (SELECT COUNT(*) FROM employees WHERE company_id = c.id AND is_active = 1) AS emp_count,"
			+ " (SELECT COUNT(*) FROM branches WHERE company_id = c.id) AS branch_count,"
			+ " (SELECT COUNT(*) FROM departments d WHERE d.company_id = c.id) AS dept_count";

	private static final RowMapper<CompanyRow> MAPPER = (rs, index) -> new CompanyRow(
			rs.getLong("id"),
			rs.getString("company_name"),
			rs.getString("logo_url"),
			rs.getString("commercial_reg_url"),
			personName(rs.getString("first_name"), rs.getString("last_name")),
			phoneDisplay(rs.getString("country_code"), rs.getString("phone")),
			rs.getString("activity_name"),
			rs.getString("title_name"),
			rs.getString("size_name"),
			rs.getString("status"),
			rs.getLong("emp_count"),
			rs.getLong("branch_count"),
			rs.getLong("dept_count"),
			rs.getString("created_at"));

	private final JdbcTemplate jdbcTemplate;

	public CompanyDirectoryStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** {@code company_person_name()}. */
	private static String personName(String first, String last) {
		String name = ((first == null ? "" : first.trim()) + " "
				+ (last == null ? "" : last.trim())).trim();
		return name.isEmpty() ? "—" : name;
	}

	/** {@code company_phone_display()}: the code and the number, or a dash. */
	private static String phoneDisplay(String countryCode, String phone) {
		String number = phone == null ? "" : phone.trim();
		if (number.isEmpty()) {
			return "—";
		}
		String code = countryCode == null ? "" : countryCode.trim();
		return code.isEmpty() ? number : code + " " + number;
	}

	public DashboardPage<CompanyRow> list(CompanyListFilters filters) {
		StringBuilder where = new StringBuilder("1=1");
		List<Object> params = new ArrayList<>();
		if (!"all".equals(filters.status())) {
			where.append(" AND c.status = ?");
			params.add(filters.status());
		}
		if (!filters.search().isEmpty()) {
			where.append(" AND (c.company_name LIKE ? OR c.phone LIKE ?"
					+ " OR CONCAT(c.first_name, ' ', c.last_name) LIKE ?)");
			String like = "%" + filters.search() + "%";
			params.add(like);
			params.add(like);
			params.add(like);
		}
		if (filters.activityId() > 0) {
			where.append(" AND c.company_activity_id = ?");
			params.add(filters.activityId());
		}
		if (filters.titleId() > 0) {
			where.append(" AND c.company_title_id = ?");
			params.add(filters.titleId());
		}
		if (filters.sizeId() > 0) {
			where.append(" AND c.company_size_id = ?");
			params.add(filters.sizeId());
		}

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM companies c WHERE " + where, Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		List<CompanyRow> rows = this.jdbcTemplate.query(
				"SELECT c.id, c.company_name, c.logo_url, c.commercial_reg_url,"
						+ " c.first_name, c.last_name, c.phone, c.country_code,"
						+ " c.status, c.created_at,"
						+ " ca.name AS activity_name, ct.name AS title_name, cs.name AS size_name,"
						+ COUNTS
						+ " FROM companies c"
						+ " LEFT JOIN company_activities ca ON ca.id = c.company_activity_id"
						+ " LEFT JOIN company_titles ct ON ct.id = c.company_title_id"
						+ " LEFT JOIN company_sizes cs ON cs.id = c.company_size_id"
						+ " WHERE " + where
						+ " ORDER BY c.created_at DESC, c.id DESC"
						+ " LIMIT ? OFFSET ?",
				MAPPER, pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	/** {@code company_lookup_activities()}. */
	public List<CompanyRow.Option> activities() {
		return options("SELECT id, name FROM company_activities ORDER BY name ASC");
	}

	/** {@code company_lookup_titles()}. */
	public List<CompanyRow.Option> titles() {
		return options("SELECT id, name FROM company_titles ORDER BY id ASC");
	}

	/** {@code company_lookup_sizes()}, ordered by the band rather than the name. */
	public List<CompanyRow.Option> sizes() {
		return options("SELECT id, name FROM company_sizes ORDER BY min_employees ASC");
	}

	private List<CompanyRow.Option> options(String sql) {
		return this.jdbcTemplate.query(sql,
				(rs, index) -> new CompanyRow.Option(rs.getLong("id"), rs.getString("name")));
	}

}
