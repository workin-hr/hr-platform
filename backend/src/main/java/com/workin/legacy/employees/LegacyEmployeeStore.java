package com.workin.legacy.employees;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.legacy.phone.LegacyPhoneNumbers;

/**
 * The employee read path, in legacy's own SQL and legacy's own value types.
 *
 * <h2>Why native SQL and a raw map, not the JPA adapter</h2>
 * <p>{@code public_row($row)} returns the PDO row itself ({@code helpers/public_row.php}):
 * every column of {@code e.*} minus {@code password_hash}/{@code token_version},
 * in column order, followed by whatever aliases the query added. A typed DTO
 * cannot reproduce that -- key order, key set and value types are all part of
 * the contract D-074 makes authoritative.
 *
 * <h2>Value types are measured, not assumed</h2>
 * <p>Legacy runs PDO with {@code ATTR_EMULATE_PREPARES => false}
 * ({@code apis/config/pdo.php:17}), so mysqlnd returns native types and
 * {@code json_encode} renders them accordingly. A PHP 8.3 + MariaDB 11.8 probe
 * over this exact query and the vendored schema produced:
 *
 * <ul>
 * <li>{@code INT}/{@code TINYINT} columns as JSON <b>numbers</b> -- {@code id},
 *     {@code company_id}, {@code branch_id}, {@code department_id},
 *     {@code job_title_id}, {@code contract_duration_months}, {@code is_active},
 *     {@code is_mobile_attendance_enabled}, {@code can_check_in_any_branch},
 *     {@code assigned_shift_id};</li>
 * <li>{@code DECIMAL} columns as JSON <b>strings</b> -- {@code expected_daily_hours}
 *     {@code "8.00"}, {@code basic_salary} {@code "12000.50"},
 *     {@code gross_salary} {@code "12250.75"};</li>
 * <li>{@code DATE}/{@code TIMESTAMP} as strings, {@code '0000-00-00'} included;</li>
 * <li>{@code VARCHAR}/{@code ENUM} as strings.</li>
 * </ul>
 *
 * <p>{@link #ROW_MAPPER} reproduces exactly that mapping from JDBC metadata.
 * Reading a {@code DECIMAL} as a number, or a date as a temporal type, would
 * change the bytes on the wire.
 *
 * <h2>Tenancy</h2>
 * <p>Hibernate's {@code @Filter} does not touch native SQL, so every statement
 * here carries its own {@code e.company_id = ?} predicate bound from the
 * re-derived {@link com.workin.legacy.auth.LegacyRequestContext#companyId()},
 * never a claim -- the defence-in-depth rule the Wave 12.4 discovery's §H
 * records for native reads.
 */
@Repository
public class LegacyEmployeeStore {

	/** {@code sensitive_response_keys()} ({@code helpers/public_row.php:10}). */
	private static final List<String> SENSITIVE_KEYS = List.of("password_hash", "token_version");

	/** {@code sql_employee_roster_join_clause('e')} ({@code functions.php:684-688}). */
	private static final String ROSTER_CLAUSE = "COALESCE(e.join_request_status, 'accepted') = 'accepted'";

	/** {@code sql_employee_display_name('e')} ({@code functions.php:169-176}). */
	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,'')))";

	/** {@code sql_manager_same_branch_scope('e', ...)} ({@code functions.php:673-678}). */
	private static final String MANAGER_SCOPE =
			"e.branch_id = (SELECT eb.branch_id FROM employees eb WHERE eb.id = ? AND eb.company_id = ? LIMIT 1)";

	private static final String LIST_SELECT = """
			SELECT
				e.*,
				b.name AS branch_name,
				s.name AS department_name,
				jt.name AS job_title_name,
				(
					SELECT sh.name
					FROM employee_shift_assignments esa2
					INNER JOIN shifts sh ON sh.id = esa2.shift_id
					WHERE esa2.employee_id = e.id
					ORDER BY esa2.effective_from DESC, esa2.id DESC
					LIMIT 1
				) AS assigned_shift_name,
				(
					SELECT esa3.shift_id
					FROM employee_shift_assignments esa3
					WHERE esa3.employee_id = e.id
					ORDER BY esa3.effective_from DESC, esa3.id DESC
					LIMIT 1
				) AS assigned_shift_id,
				(
					SELECT esa4.effective_from
					FROM employee_shift_assignments esa4
					WHERE esa4.employee_id = e.id
					ORDER BY esa4.effective_from DESC, esa4.id DESC
					LIMIT 1
				) AS assigned_shift_effective_from,
				(
					SELECT sc2.basic_salary
					FROM salary_contracts sc2
					WHERE sc2.employee_id = e.id
					ORDER BY sc2.effective_from DESC, sc2.id DESC
					LIMIT 1
				) AS basic_salary,
				(
					SELECT sc2.total
					FROM salary_contracts sc2
					WHERE sc2.employee_id = e.id
					ORDER BY sc2.effective_from DESC, sc2.id DESC
					LIMIT 1
				) AS gross_salary
			FROM employees AS e
			LEFT JOIN branches AS b ON b.id = e.branch_id
			LEFT JOIN departments AS s ON s.id = e.department_id
			LEFT JOIN job_titles AS jt ON jt.id = e.job_title_id
			WHERE %s
			ORDER BY %s
			LIMIT ? OFFSET ?""";

	private static final String ONE_SELECT = """
			SELECT
				e.*,
				b.name AS branch_name,
				s.name AS department_name,
				jt.name AS job_title_name
			FROM employees AS e
			LEFT JOIN branches AS b ON b.id = e.branch_id
			LEFT JOIN departments AS s ON s.id = e.department_id
			LEFT JOIN job_titles AS jt ON jt.id = e.job_title_id
			WHERE e.id=?
			AND e.company_id=?""";

	private final JdbcTemplate jdbcTemplate;

	public LegacyEmployeeStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** {@code list.php}'s {@code db_value("SELECT COUNT(*) ...")}. */
	public long count(String whereSql, List<Object> params) {
		Long total = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM employees AS e WHERE " + whereSql, Long.class, params.toArray());
		return total == null ? 0L : total;
	}

	/** {@code list.php}'s {@code get_all(...)}, already stripped by {@code public_rows()}. */
	public List<Map<String, Object>> list(String whereSql, String orderSql, List<Object> params, long limit, long offset) {
		List<Object> bound = new ArrayList<>(params);
		bound.add(limit);
		bound.add(offset);
		return jdbcTemplate.query(LIST_SELECT.formatted(whereSql, orderSql), ROW_MAPPER, bound.toArray());
	}

	/** {@code one.php}'s {@code get_one(...)}: scoped by id <em>and</em> company, null when neither matches. */
	public Map<String, Object> findOne(long employeeId, long companyId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(ONE_SELECT, ROW_MAPPER, employeeId, companyId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * {@code employee_row_attach_latest_salary_contract()} ({@code functions.php:809-827}):
	 * appends {@code basic_salary} and {@code gross_salary} <em>only</em> when a
	 * contract exists, so an employee without one has neither key -- unlike
	 * {@code list.php}, where both are always-present nullable aliases.
	 */
	public void attachLatestSalaryContract(Map<String, Object> employee) {
		long employeeId = idOf(employee);
		if (employeeId <= 0) {
			return;
		}
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"""
				SELECT basic_salary, total
				FROM salary_contracts
				WHERE employee_id = ?
				ORDER BY effective_from DESC, id DESC
				LIMIT 1""",
				ROW_MAPPER, employeeId);
		if (rows.isEmpty()) {
			return;
		}
		employee.put("basic_salary", rows.get(0).get("basic_salary"));
		employee.put("gross_salary", rows.get(0).get("total"));
	}

	/**
	 * {@code employee_row_attach_latest_shift_assignment()} ({@code functions.php:834-859}).
	 * Note the casts PHP applies here and nowhere else: the shift id is
	 * {@code (int)}, the name is {@code (string)} with an empty-string fallback,
	 * and the effective date is passed through raw.
	 */
	public void attachLatestShiftAssignment(Map<String, Object> employee) {
		long employeeId = idOf(employee);
		if (employeeId <= 0) {
			return;
		}
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"""
				SELECT
					esa.shift_id,
					sh.name AS shift_name,
					esa.effective_from AS shift_effective_from
				FROM employee_shift_assignments esa
				INNER JOIN shifts sh ON sh.id = esa.shift_id
				WHERE esa.employee_id = ?
				ORDER BY esa.effective_from DESC, esa.id DESC
				LIMIT 1""",
				ROW_MAPPER, employeeId);
		if (rows.isEmpty()) {
			return;
		}
		Map<String, Object> assignment = rows.get(0);
		employee.put("assigned_shift_id", assignment.get("shift_id"));
		Object name = assignment.get("shift_name");
		employee.put("assigned_shift_name", name == null ? "" : name.toString());
		employee.put("assigned_shift_effective_from", assignment.get("shift_effective_from"));
	}

	/**
	 * {@code employee_phone_exists_globally($phone, $exclude)}
	 * ({@code functions.php:99-117}).
	 *
	 * <p>Three properties of this query are contract, not detail. It is
	 * <b>global</b>: no {@code company_id} predicate, because
	 * {@code employees.phone} is a login identifier with a database-wide unique
	 * index. It matches through {@code phone_sql_match_clause()}, so a number
	 * stored in any of {@link LegacyPhoneNumbers#lookupVariants}' spellings --
	 * or with {@code + - ( )} formatting still in the column -- counts as taken.
	 * And it ignores rows whose {@code join_request_status} is
	 * {@code 'rejected'} (NULL counting as {@code 'accepted'}), so a rejected
	 * join request never blocks a real hire.
	 */
	public boolean phoneExistsGlobally(String phone, Long excludeEmployeeId) {
		String digits = LegacyPhoneNumbers.digitsOnly(phone == null ? "" : phone.trim());
		if (digits.isEmpty()) {
			return false;
		}
		List<String> variants = LegacyPhoneNumbers.lookupVariants(digits);
		if (variants.isEmpty()) {
			return false;
		}
		String placeholders = String.join(", ", java.util.Collections.nCopies(variants.size(), "?"));
		StringBuilder sql = new StringBuilder()
				.append("SELECT COUNT(*) FROM employees WHERE (")
				.append(LegacyPhoneNumbers.digitsSqlExpression("phone"))
				.append(" IN (").append(placeholders).append("))")
				.append(" AND COALESCE(join_request_status, 'accepted') <> 'rejected'");
		List<Object> params = new ArrayList<>(variants);
		if (excludeEmployeeId != null && excludeEmployeeId > 0) {
			sql.append(" AND id <> ?");
			params.add(excludeEmployeeId);
		}
		Long matches = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
		return matches != null && matches > 0;
	}

	/**
	 * {@code employee_code_exists_in_company($company_id, $code, $exclude)}
	 * ({@code functions.php:150-167}): company-scoped, exact match on the
	 * already-normalized code.
	 */
	public boolean employeeCodeExistsInCompany(long companyId, String employeeCode, Long excludeEmployeeId) {
		if (employeeCode == null || employeeCode.isEmpty()) {
			return false;
		}
		StringBuilder sql = new StringBuilder(
				"SELECT COUNT(*) FROM employees WHERE company_id = ? AND employee_code = ?");
		List<Object> params = new ArrayList<>(List.of(companyId, employeeCode));
		if (excludeEmployeeId != null && excludeEmployeeId > 0) {
			sql.append(" AND id <> ?");
			params.add(excludeEmployeeId);
		}
		Long matches = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
		return matches != null && matches > 0;
	}

	/**
	 * {@code fetch_employee_with_org_labels($id, $company_id)}
	 * ({@code functions.php:866-890}). The lifecycle endpoints re-read through
	 * this, not through {@code one.php}'s query: same three left joins, no
	 * correlated salary or shift columns.
	 */
	public Map<String, Object> findWithOrgLabels(long employeeId, long companyId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(ONE_SELECT, ROW_MAPPER, employeeId, companyId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * {@code deactivate.php}/{@code reactivate.php}'s scoped
	 * {@code UPDATE employees SET is_active=? WHERE id=? AND company_id=?}.
	 *
	 * <p>PHP runs this <em>before</em> checking that the employee exists, so a
	 * missing or foreign id updates zero rows and only then 404s. The row count
	 * is deliberately ignored for that reason, and the company predicate is what
	 * makes the write tenant-safe -- a native statement Hibernate's filters
	 * never see.
	 */
	public void setActive(long employeeId, long companyId, int active) {
		jdbcTemplate.update(
				"UPDATE employees SET is_active=? WHERE id=? AND company_id=?", active, employeeId, companyId);
	}

	/**
	 * {@code manager_can_access_employee_branch()} ({@code functions.php:790-802}).
	 * The join compares branches with {@code <=>}, so two employees whose
	 * {@code branch_id} is NULL count as the same branch -- not reachable through
	 * the schema's NOT NULL column today, but reproduced rather than tightened.
	 */
	public boolean managerCanAccessEmployeeBranch(long managerEmployeeId, long targetEmployeeId, long companyId) {
		Long matches = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*) FROM employees ep
				 INNER JOIN employees mgr ON mgr.id = ?
				   AND mgr.company_id = ?
				   AND ep.company_id = mgr.company_id
				   AND ep.branch_id <=> mgr.branch_id
				 WHERE ep.id = ?""",
				Long.class, managerEmployeeId, companyId, targetEmployeeId);
		return matches != null && matches > 0;
	}

	public String rosterClause() {
		return ROSTER_CLAUSE;
	}

	public String displayNameExpression() {
		return DISPLAY_NAME;
	}

	public String managerScopeClause() {
		return MANAGER_SCOPE;
	}

	private static long idOf(Map<String, Object> employee) {
		Object id = employee.get("id");
		return id instanceof Number number ? number.longValue() : 0L;
	}

	/**
	 * PDO's native-type fetch, reproduced from JDBC metadata. Integers become
	 * {@code Long} (JSON numbers), everything else -- decimals, dates,
	 * timestamps, enums, text -- stays the raw string MariaDB sent, which is
	 * also what keeps {@code '0000-00-00'} readable.
	 */
	static final RowMapper<Map<String, Object>> ROW_MAPPER = (ResultSet rs, int rowNumber) -> {
		ResultSetMetaData metaData = rs.getMetaData();
		Map<String, Object> row = new LinkedHashMap<>();
		for (int column = 1; column <= metaData.getColumnCount(); column++) {
			String label = metaData.getColumnLabel(column);
			if (SENSITIVE_KEYS.contains(label)) {
				continue;
			}
			row.put(label, readValue(rs, metaData.getColumnType(column), column));
		}
		return row;
	};

	private static Object readValue(ResultSet rs, int sqlType, int column) throws SQLException {
		switch (sqlType) {
			case Types.BIT, Types.BOOLEAN, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
				long value = rs.getLong(column);
				return rs.wasNull() ? null : value;
			}
			default -> {
				return rs.getString(column);
			}
		}
	}

}
