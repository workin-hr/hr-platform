package com.workin.backend.platformadmin.hr;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The queries the join-requests page makes
 * ({@code home_get_join_requests()}, {@code home_accept_join_request()},
 * {@code home_reject_join_request()}).
 *
 * <p>All three work on {@code employees}. There is no join-request table: the
 * request is the employee row, in the state its {@code join_request_status}
 * says it is in.
 */
@Repository
public class JoinRequestStore {

	/**
	 * Legacy's {@code LIMIT 200} on this page, passed as a literal there and
	 * kept as one here. The list is unpaginated in both.
	 */
	public static final int LIMIT = 200;

	/** {@code ROLE_EMPLOYEE}: a join request is only ever an employee's. */
	private static final String ROLE = "employee";

	private final JdbcTemplate jdbcTemplate;

	public JoinRequestStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static final RowMapper<JoinRequest> MAPPER = (rs, rowNum) -> new JoinRequest(
			rs.getLong("id"),
			rs.getLong("company_id"),
			rs.getString("name"),
			rs.getString("phone"),
			rs.getString("join_request_status"),
			rs.getTimestamp("created_at") == null
					? null : rs.getTimestamp("created_at").toLocalDateTime());

	/**
	 * {@code home_get_join_requests()}.
	 *
	 * <p>{@code status} of {@code all} drops the predicate entirely; anything
	 * else is matched exactly. {@code rejected} is accepted and will return
	 * nothing, because rejecting deletes the row rather than setting that
	 * status -- see {@link JoinRequestAdminService#reject}.
	 *
	 * <p>Legacy also selects {@code photo_url} here, behind a runtime
	 * column-existence check, and this page renders no photograph. Not carried
	 * over: a column nothing displays is not parity, it is a wasted read.
	 */
	public List<JoinRequest> list(long companyId, String status) {
		StringBuilder where = new StringBuilder(" WHERE e.role = ?");
		List<Object> params = new ArrayList<>();
		params.add(ROLE);
		if (companyId > 0) {
			where.append(" AND e.company_id = ?");
			params.add(companyId);
		}
		if (status != null && !status.isBlank() && !"all".equals(status)) {
			where.append(" AND e.join_request_status = ?");
			params.add(status);
		}
		return this.jdbcTemplate.query(
				"SELECT e.id, e.company_id, " + EmployeeStore.NAME_SQL + " AS name,"
						+ " e.phone, e.join_request_status, e.created_at"
						+ " FROM employees e" + where
						+ " ORDER BY e.created_at DESC LIMIT " + LIMIT,
				MAPPER, params.toArray());
	}

	/**
	 * {@code dbFind('employees', $id)} narrowed to what the guards read.
	 *
	 * <p>Deliberately unscoped, as legacy's is: the tenant decision is made on
	 * the row that comes back, by
	 * {@link JoinRequestAdminService#visible}, and not by this query. Scoping
	 * here as well would hide which layer is enforcing the rule.
	 */
	public JoinRequest find(long id) {
		List<JoinRequest> rows = this.jdbcTemplate.query(
				"SELECT e.id, e.company_id, " + EmployeeStore.NAME_SQL + " AS name,"
						+ " e.phone, e.join_request_status, e.created_at"
						+ " FROM employees e WHERE e.id = ? AND e.role = ? LIMIT 1",
				MAPPER, id, ROLE);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * {@code home_accept_join_request()}: the status flips and the row is
	 * activated, and nothing else is written -- notably not
	 * {@code company_id}, which D-176 keeps out of every edit.
	 */
	public int accept(long id) {
		return this.jdbcTemplate.update(
				"UPDATE employees SET join_request_status = 'accepted', is_active = 1"
						+ " WHERE id = ?", id);
	}

	/**
	 * {@code home_reject_join_request()}, which is a <b>delete of the employee
	 * row</b> rather than a status change.
	 *
	 * <p>Reproduced because it is what both surfaces do -- the API's
	 * {@code company_join_requests/reject.php} deletes too -- and because the
	 * alternative would leave a row no page can reach. What makes it safe is
	 * the caller's pending check, not anything here.
	 */
	public int reject(long id) {
		return this.jdbcTemplate.update("DELETE FROM employees WHERE id = ?", id);
	}

}
