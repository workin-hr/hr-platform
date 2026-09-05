package com.workin.backend.platformadmin.hr;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The recent-activity feed's query ({@code activities_fetch_list()}).
 *
 * <p>A {@code UNION ALL} of two unrelated tables, each contributing the
 * columns the other cannot fill.
 */
@Repository
@Profile("phase1-mysql")
public class ActivityStore {

	/**
	 * {@code db_sql_unicode()}.
	 *
	 * <p><b>Not decoration.</b> {@code employees}, {@code attendance} and
	 * {@code requests} do not all carry the same collation, and MariaDB refuses
	 * a {@code UNION} whose corresponding columns disagree -- "Illegal mix of
	 * collations", an error rather than a degraded result. Every text column on
	 * both sides is cast to one collation, and so is every {@code NULL}
	 * placeholder, because an untyped NULL takes the connection's collation and
	 * is just as capable of not matching.
	 */
	private static String unicode(String expression) {
		return "CAST((" + expression + ") AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci";
	}

	/** {@code db_sql_unicode_null()}. */
	private static final String UNICODE_NULL =
			"CAST(NULL AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci";

	private final JdbcTemplate jdbcTemplate;

	public ActivityStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static final RowMapper<Activity> MAPPER = (rs, rowNum) -> new Activity(
			rs.getString("kind"),
			rs.getLong("row_id"),
			rs.getString("emp_code"),
			rs.getString("employee_name"),
			local(rs.getTimestamp("at")),
			local(rs.getTimestamp("check_in")),
			local(rs.getTimestamp("check_out")),
			rs.getString("status"),
			rs.getString("request_type_name"));

	private static java.time.LocalDateTime local(java.sql.Timestamp value) {
		return value == null ? null : value.toLocalDateTime();
	}

	/**
	 * One branch of the union, or nothing when the caller may not see that kind.
	 *
	 * <p>Legacy asks {@code HrAccess::can(PERM_ATTENDANCE) ||
	 * HrAccess::hasFullAccess()} per branch, so a session with one permission
	 * and not the other sees half the feed rather than an error.
	 */
	private record Branch(String sql, List<Object> params) {
	}

	private Branch attendanceBranch(long companyId, String from, String to) {
		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder(companyWhere(companyId, params));
		if (from != null && !from.isBlank()) {
			where.append(" AND DATE(COALESCE(a.check_out, a.check_in)) >= ?");
			params.add(from);
		}
		if (to != null && !to.isBlank()) {
			where.append(" AND DATE(COALESCE(a.check_out, a.check_in)) <= ?");
			params.add(to);
		}
		return new Branch(
				"SELECT " + unicode("'attendance'") + " AS kind, a.id AS row_id,"
						+ " " + unicode(EmployeeStore.CODE_SQL) + " AS emp_code,"
						+ " " + unicode(EmployeeStore.NAME_SQL) + " AS employee_name,"
						+ " COALESCE(a.check_out, a.check_in) AS at, a.check_in, a.check_out,"
						+ " " + UNICODE_NULL + " AS status,"
						+ " " + UNICODE_NULL + " AS request_type_name"
						+ " FROM attendance a JOIN employees e ON e.id = a.employee_id"
						+ " WHERE " + where,
				params);
	}

	private Branch requestBranch(long companyId, String from, String to) {
		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder(companyWhere(companyId, params));
		if (from != null && !from.isBlank()) {
			where.append(" AND DATE(r.created_at) >= ?");
			params.add(from);
		}
		if (to != null && !to.isBlank()) {
			where.append(" AND DATE(r.created_at) <= ?");
			params.add(to);
		}
		return new Branch(
				"SELECT " + unicode("'request'") + " AS kind, r.id AS row_id,"
						+ " " + unicode(EmployeeStore.CODE_SQL) + " AS emp_code,"
						+ " " + unicode(EmployeeStore.NAME_SQL) + " AS employee_name,"
						+ " r.created_at AS at, NULL AS check_in, NULL AS check_out,"
						+ " " + unicode("r.status") + " AS status,"
						+ " " + unicode("COALESCE(t.name, '')") + " AS request_type_name"
						+ " FROM requests r JOIN employees e ON e.id = r.employee_id"
						+ " LEFT JOIN request_types t ON t.id = r.request_type_id"
						+ " WHERE " + where,
				params);
	}

	/** {@code home_company_where('e', ...)}, for an administrator's filter. */
	private static String companyWhere(long companyId, List<Object> params) {
		if (companyId > 0) {
			params.add(companyId);
			return "e.company_id = ?";
		}
		return "1=1";
	}

	/**
	 * {@code activities_fetch_list()}.
	 *
	 * @param canSeeAttendance whether this session may see the attendance half
	 * @param canSeeRequests whether it may see the request half
	 * @return the rows and the unpaginated total; both empty when neither
	 *     branch is permitted, which legacy returns rather than failing
	 */
	public Result list(long companyId, String kind, String from, String to,
			int limit, int offset, boolean canSeeAttendance, boolean canSeeRequests) {

		List<Branch> branches = new ArrayList<>();
		if (!"request".equals(kind) && canSeeAttendance) {
			branches.add(attendanceBranch(companyId, from, to));
		}
		if (!"attendance".equals(kind) && canSeeRequests) {
			branches.add(requestBranch(companyId, from, to));
		}
		if (branches.isEmpty()) {
			return new Result(List.of(), 0);
		}

		List<String> pieces = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		for (Branch branch : branches) {
			pieces.add(branch.sql());
			params.addAll(branch.params());
		}
		String union = "(" + String.join(") UNION ALL (", pieces) + ")";

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM (" + union + ") AS activity_union",
				Integer.class, params.toArray());

		// Legacy clamps again here, after the page has already clamped: the
		// tighter of the two wins, and reproducing both keeps the bound in the
		// same place it is in the source.
		int bounded = Math.max(1, Math.min(200, limit));
		List<Activity> rows = this.jdbcTemplate.query(
				"SELECT * FROM (" + union + ") AS activity_union"
						+ " ORDER BY at DESC LIMIT " + bounded + " OFFSET " + Math.max(0, offset),
				MAPPER, params.toArray());
		return new Result(rows, total == null ? 0 : total);
	}

	public record Result(List<Activity> rows, int total) {
	}

}
