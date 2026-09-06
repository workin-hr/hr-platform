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
 * The queries {@code administrative_decisions.php} makes.
 *
 * <p>Its list is written inline in the page rather than in a helper, and it is
 * the only HR list with a {@code LEFT JOIN} to companies -- a decision whose
 * company row has gone still appears, with an empty name.
 */
@Repository
@Profile("phase1-mysql")
public class AdministrativeDecisionStore {

	private final JdbcTemplate jdbcTemplate;

	public AdministrativeDecisionStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static final RowMapper<AdministrativeDecision> MAPPER =
			(rs, rowNum) -> new AdministrativeDecision(
					rs.getLong("id"),
					rs.getLong("company_id"),
					rs.getString("company_name"),
					rs.getString("title"),
					rs.getString("body"),
					rs.getInt("is_active") == 1,
					rs.getString("created_at"));

	public DashboardPage<AdministrativeDecision> paginate(DashboardListFilters filters) {
		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder("1=1");
		// One branch in PHP, two conditions with the same effect: a scoped
		// session is pinned to its own company and an administrator to the
		// filter, with no filter meaning every company.
		if (filters.companyId() > 0) {
			where.append(" AND ad.company_id = ?");
			params.add(filters.companyId());
		}
		if (!filters.search().isEmpty()) {
			where.append(" AND (ad.title LIKE ? OR ad.body LIKE ?)");
			params.add("%" + filters.search() + "%");
			params.add("%" + filters.search() + "%");
		}

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM administrative_decisions ad WHERE " + where,
				Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		List<AdministrativeDecision> rows = this.jdbcTemplate.query(
				"SELECT ad.*, c.company_name FROM administrative_decisions ad"
						+ " LEFT JOIN companies c ON c.id = ad.company_id"
						+ " WHERE " + where
						+ " ORDER BY ad.id DESC"
						+ " LIMIT ? OFFSET ?",
				MAPPER, pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	/** The company that owns a decision -- a column, not a join. */
	public Long companyOf(long id) {
		if (id <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT company_id FROM administrative_decisions WHERE id = ?", Long.class, id);
		return found.isEmpty() ? null : found.get(0);
	}

	public AdministrativeDecision find(long id) {
		List<AdministrativeDecision> rows = this.jdbcTemplate.query(
				"SELECT ad.*, c.company_name FROM administrative_decisions ad"
						+ " LEFT JOIN companies c ON c.id = ad.company_id WHERE ad.id = ?",
				MAPPER, id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public long insert(long companyId, String title, String body, boolean active) {
		org.springframework.jdbc.support.KeyHolder keys =
				new org.springframework.jdbc.support.GeneratedKeyHolder();
		this.jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					"INSERT INTO administrative_decisions (company_id, title, body, is_active,"
							+ " created_at) VALUES (?, ?, ?, ?, NOW())",
					java.sql.Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, companyId);
			statement.setString(2, title);
			statement.setString(3, body);
			statement.setInt(4, active ? 1 : 0);
			return statement;
		}, keys);
		Number key = keys.getKey();
		return key == null ? 0L : key.longValue();
	}

	/**
	 * <b>D-176</b>: {@code company_id} is deliberately absent from this
	 * statement.
	 *
	 * <p>Legacy has it here, taken from the posted value, which is how an
	 * administrator with no company filter could transfer a decision from one
	 * company to another by editing it (<b>R-047</b>). A row's tenant is not
	 * something a form should be able to write.
	 */
	public int update(long id, String title, String body, boolean active) {
		return this.jdbcTemplate.update(
				"UPDATE administrative_decisions SET title = ?, body = ?, is_active = ?"
						+ " WHERE id = ?",
				title, body, active ? 1 : 0, id);
	}

	public int delete(long id) {
		return this.jdbcTemplate.update(
				"DELETE FROM administrative_decisions WHERE id = ?", id);
	}

}
