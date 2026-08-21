package com.workin.legacy.employees;

import java.sql.PreparedStatement;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

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
	private final TransactionTemplate transactions;

	public LegacyEmployeeStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(legacyDataSource));
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
	 * {@code employees_has_column($column)} ({@code functions.php:1002-1014}).
	 *
	 * <p>PHP caches this in a function-local {@code static}, i.e. for one
	 * request. Callers here resolve it once per endpoint invocation and pass the
	 * answer down rather than caching it on this singleton, which would give it
	 * a JVM lifetime PHP never had.
	 */
	public boolean employeesHasColumn(String column) {
		Long count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
				WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employees' AND COLUMN_NAME = ?""",
				Long.class, column);
		return count != null && count > 0;
	}

	/** {@code create.php}'s inline branch check: the id must exist <em>in this company</em>. */
	public boolean branchExistsInCompany(long branchId, long companyId) {
		return count("SELECT COUNT(*) FROM branches WHERE id=? AND company_id=?", branchId, companyId) > 0;
	}

	/**
	 * {@code company_default_active_branch_id()} ({@code functions.php:957-970}):
	 * the lowest-id active branch, used when the request omits one because
	 * {@code employees.branch_id} is NOT NULL.
	 */
	public Long companyDefaultActiveBranchId(long companyId) {
		if (companyId <= 0) {
			return null;
		}
		List<Long> ids = jdbcTemplate.queryForList(
				"SELECT id FROM branches WHERE company_id = ? AND is_active = 1 ORDER BY id ASC LIMIT 1",
				Long.class, companyId);
		return ids.isEmpty() || ids.get(0) == null || ids.get(0) <= 0 ? null : ids.get(0);
	}

	/**
	 * {@code department_belongs_to_branch()} ({@code org_hierarchy.php:14-21}).
	 * Note what is <em>not</em> here: no company predicate and no active check --
	 * the junction row alone decides. D-075 adds the company check separately, in
	 * the service, so the divergence stays visible.
	 */
	public boolean departmentBelongsToBranch(long departmentId, long branchId) {
		return count(
				"SELECT COUNT(*) FROM department_branches WHERE department_id = ? AND branch_id = ?",
				departmentId, branchId) > 0;
	}

	/** {@code department_belongs_to_company()} ({@code org_hierarchy.php:4-12}): company <em>and</em> active. */
	public boolean departmentBelongsToCompany(long departmentId, long companyId) {
		return count(
				"SELECT COUNT(*) FROM departments WHERE id = ? AND company_id = ? AND is_active = 1",
				departmentId, companyId) > 0;
	}

	/**
	 * {@code job_title_belongs_to_department()} ({@code org_hierarchy.php:33-41}):
	 * active, and in that department -- again with no company predicate of its
	 * own (D-075).
	 */
	public boolean jobTitleBelongsToDepartment(long jobTitleId, long departmentId) {
		return count(
				"SELECT COUNT(*) FROM job_titles WHERE id = ? AND department_id = ? AND is_active = 1",
				jobTitleId, departmentId) > 0;
	}

	/**
	 * D-075's fail-closed test, and nothing more: does this id belong to a
	 * <em>different</em> company? A missing row answers false, because PHP's own
	 * check already rejects it -- this exists only to close the cross-company
	 * hole PHP's company-predicate-free lookups leave open.
	 */
	public boolean departmentExistsInOtherCompany(long departmentId, long companyId) {
		return count(
				"SELECT COUNT(*) FROM departments WHERE id = ? AND company_id <> ?", departmentId, companyId) > 0;
	}

	/** The same fail-closed test for a job title (D-075). */
	public boolean jobTitleExistsInOtherCompany(long jobTitleId, long companyId) {
		return count(
				"SELECT COUNT(*) FROM job_titles WHERE id = ? AND company_id <> ?", jobTitleId, companyId) > 0;
	}

	/** {@code shift_belongs_to_company()} ({@code org_hierarchy.php:43-50}): company-scoped, active or not. */
	public boolean shiftBelongsToCompany(long shiftId, long companyId) {
		return count("SELECT COUNT(*) FROM shifts s WHERE s.id = ? AND s.company_id = ?", shiftId, companyId) > 0;
	}

	/**
	 * {@code create.php}'s employee INSERT: a fixed column list, plus
	 * {@code can_check_in_any_branch} only when the column exists.
	 *
	 * @return {@code get_last_inserted_id()}
	 */
	public long insertEmployee(Map<String, Object> columns) {
		List<String> names = new ArrayList<>(columns.keySet());
		String placeholders = String.join(", ", java.util.Collections.nCopies(names.size(), "?"));
		String sql = "INSERT INTO employees (" + String.join(", ", names) + ") VALUES (" + placeholders + ")";
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
			int index = 1;
			for (String name : names) {
				statement.setObject(index++, columns.get(name));
			}
			return statement;
		}, keys);
		return keys.getKey() == null ? 0L : keys.getKey().longValue();
	}

	/**
	 * {@code update.php}'s dynamic {@code UPDATE employees SET ... WHERE id=? AND company_id=?}.
	 *
	 * <p>Only the columns the request actually carried are written -- the
	 * allowed-column loop builds the set -- and the company predicate is what
	 * keeps a native statement tenant-safe.
	 */
	public void updateEmployeeColumns(long employeeId, long companyId, Map<String, Object> columns) {
		if (columns.isEmpty()) {
			return;
		}
		List<String> assignments = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		for (Map.Entry<String, Object> column : columns.entrySet()) {
			assignments.add(column.getKey() + "=?");
			params.add(column.getValue());
		}
		params.add(employeeId);
		params.add(companyId);
		jdbcTemplate.update(
				"UPDATE employees SET " + String.join(", ", assignments) + " WHERE id=? AND company_id=?",
				params.toArray());
	}

	/** {@code update.php}'s salary guard: a contract is inserted only when the employee has none. */
	public long countSalaryContracts(long employeeId) {
		return count("SELECT COUNT(*) FROM salary_contracts WHERE employee_id = ?", employeeId);
	}

	/**
	 * {@code create.php}'s salary INSERT. Housing is hard-coded to 0 there even
	 * though the request may carry one, and {@code effective_from} is the
	 * employee's hire date, not today.
	 */
	public void insertSalaryContract(long employeeId, Map<String, Object> amounts, String effectiveFrom) {
		jdbcTemplate.update(
				"""
				INSERT INTO salary_contracts (
					employee_id, basic_salary, housing_allowance, transport_allowance, food_allowance,
					risk_allowance, incentives, insurance_deduction, tax_deduction, advances_deduction,
					fund_deduction, penalty_deduction, effective_from
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				employeeId,
				amounts.get("basic_salary"), 0, amounts.get("transport_allowance"), amounts.get("food_allowance"),
				amounts.get("risk_allowance"), amounts.get("incentives"), amounts.get("insurance_deduction"),
				amounts.get("tax_deduction"), amounts.get("advances_deduction"), amounts.get("fund_deduction"),
				amounts.get("penalty_deduction"), effectiveFrom);
	}

	/** {@code create.php}'s {@code SELECT id FROM leave_balance WHERE employee_id=? AND year=?}. */
	public boolean leaveBalanceExists(long employeeId, long year) {
		return count("SELECT COUNT(*) FROM leave_balance WHERE employee_id=? AND year=?", employeeId, year) > 0;
	}

	/** The update branch of {@code create.php}'s leave-balance upsert -- {@code used_days} untouched. */
	public void updateLeaveBalance(
			long employeeId, long year, Object totalDays, long fromMonth, long toMonth, Object monthlyCap) {
		jdbcTemplate.update(
				"""
				UPDATE leave_balance SET total_days=?, period_from_month=?, period_to_month=?, monthly_cap_days=?
				WHERE employee_id=? AND year=?""",
				totalDays, fromMonth, toMonth, monthlyCap, employeeId, year);
	}

	/** The insert branch, which seeds {@code used_days} at 0. */
	public void insertLeaveBalance(
			long employeeId, long year, Object totalDays, long fromMonth, long toMonth, Object monthlyCap) {
		jdbcTemplate.update(
				"""
				INSERT INTO leave_balance (
					employee_id, year, total_days, used_days, period_from_month, period_to_month, monthly_cap_days
				) VALUES (?, ?, ?, 0, ?, ?, ?)""",
				employeeId, year, totalDays, fromMonth, toMonth, monthlyCap);
	}

	/** {@code create.php}'s shift-assignment INSERT, appended rather than replacing anything. */
	public void insertShiftAssignment(long employeeId, long shiftId, String effectiveFrom) {
		jdbcTemplate.update(
				"INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from) VALUES (?, ?, ?)",
				employeeId, shiftId, effectiveFrom);
	}

	/**
	 * {@code create.php}'s post-commit re-read. Deliberately <b>id-only</b>: PHP
	 * has no company predicate here, because the row was just inserted under the
	 * authenticated company and there is nothing to scope against.
	 */
	public Map<String, Object> findByIdWithOrgLabels(long employeeId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"""
				SELECT
					e.*,
					b.name AS branch_name,
					s.name AS department_name,
					jt.name AS job_title_name
				FROM employees AS e
				LEFT JOIN branches AS b ON b.id = e.branch_id
				LEFT JOIN departments AS s ON s.id = e.department_id
				LEFT JOIN job_titles AS jt ON jt.id = e.job_title_id
				WHERE e.id = ?""",
				ROW_MAPPER, employeeId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * {@code $pdo->beginTransaction() ... commit()} with
	 * {@code catch (Throwable $e) { rollBack(); }}.
	 *
	 * <p>A {@code DataSourceTransactionManager} over the same {@code DataSource}
	 * the {@link JdbcTemplate} uses, so every statement inside the callback joins
	 * one real database transaction. It is built here rather than exposed as a
	 * bean on purpose: the application's transaction manager is JPA's, and adding
	 * a second one to the context would change what an unqualified
	 * {@code @Transactional} means everywhere else.
	 */
	public <T> T inTransaction(java.util.function.Supplier<T> work) {
		return transactions.execute(status -> work.get());
	}

	private long count(String sql, Object... params) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, params);
		return value == null ? 0L : value;
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
