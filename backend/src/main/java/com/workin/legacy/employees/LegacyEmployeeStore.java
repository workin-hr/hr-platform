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

import com.workin.legacy.LegacyJdbcValues;
import org.springframework.transaction.support.TransactionTemplate;

import com.workin.legacy.LegacyValues;
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

	/**
	 * {@code employee_related_records_summary()}'s definition list: response
	 * key, label message key, table. Order is the response's order.
	 */
	private static final List<String[]> RELATED_RECORD_DEFINITIONS = List.of(
			new String[] {"attendance", "employee_related_attendance", "attendance"},
			new String[] {"schedules", "employee_related_schedules", "employee_schedules"},
			new String[] {"shift_assignments", "employee_related_shift_assignments", "employee_shift_assignments"},
			new String[] {"salary_contracts", "employee_related_salary_contracts", "salary_contracts"},
			new String[] {"payslips", "employee_related_payslips", "payslips"},
			new String[] {"penalties", "employee_related_penalties", "penalties"},
			new String[] {"requests", "employee_related_requests", "requests"},
			new String[] {"advances", "employee_related_advances", "advances"},
			new String[] {"leave_balance", "employee_related_leave_balance", "leave_balance"},
			new String[] {"documents", "employee_related_documents", "employee_docs"},
			new String[] {"complaints", "employee_related_complaints", "complaints"},
			new String[] {"notifications", "employee_related_notifications", "notifications"},
			new String[] {"push_tokens", "employee_related_push_tokens", "push_tokens"},
			new String[] {"hr_permissions", "employee_related_hr_permissions", "hr_permissions"});

	/**
	 * The cascade's delete order after {@code notifications}, exactly as the
	 * helper lists it. Not derived from foreign keys: D-078 makes the PHP list
	 * the contract.
	 */
	private static final List<String> CASCADE_TABLES = List.of(
			"push_tokens", "payslips", "penalties", "requests", "advances", "leave_balance", "attendance",
			"employee_schedules", "employee_shift_assignments", "salary_contracts", "employee_docs",
			"complaints", "hr_permissions");

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
	 * {@code employee_related_records_summary()}
	 * ({@code employee_delete_helper.php:11-46}): fourteen counts, in this
	 * order, with the zero ones dropped by the caller.
	 *
	 * <p>The order is part of the response, so it is a list rather than a map
	 * built from a set. {@code notifications} is the one query with a different
	 * shape -- it counts <em>rows</em> where the employee is on either side, not
	 * each side separately, so a row that is both from and to the same employee
	 * counts once.
	 */
	public List<RelatedRecordCount> relatedRecordCounts(long employeeId) {
		List<RelatedRecordCount> counts = new ArrayList<>();
		for (String[] definition : RELATED_RECORD_DEFINITIONS) {
			String key = definition[0];
			String labelKey = definition[1];
			String table = definition[2];
			long count = "notifications".equals(table)
					? count("SELECT COUNT(*) FROM notifications WHERE to_employee_id = ? OR from_employee_id = ?",
							employeeId, employeeId)
					: count("SELECT COUNT(*) FROM " + table + " WHERE employee_id = ?", employeeId);
			counts.add(new RelatedRecordCount(key, labelKey, count));
		}
		return counts;
	}

	/** {@code delete_preview.php}'s existence check: id and company, nothing else selected. */
	public boolean employeeExistsInCompany(long employeeId, long companyId) {
		return count("SELECT COUNT(*) FROM employees WHERE id = ? AND company_id = ?", employeeId, companyId) > 0;
	}

	/**
	 * {@code delete.php}'s direct path: a single scoped delete, <b>not</b> inside
	 * a transaction and with no manager cleanup (D-077).
	 */
	public void deleteEmployeeUnscopedOfAnyTransaction(long employeeId, long companyId) {
		deleteDeviceIdentity(employeeId, companyId);
		jdbcTemplate.update("DELETE FROM employees WHERE id = ? AND company_id = ?", employeeId, companyId);
	}

	/**
	 * {@code upload_photo.php}'s scoped {@code UPDATE employees SET photo_url=?}.
	 * Its row count is ignored, exactly as PHP ignores it -- a missing or
	 * foreign target updates nothing and the endpoint carries on to the re-read.
	 */
	public void updatePhotoUrl(long employeeId, long companyId, String photoUrl) {
		jdbcTemplate.update(
				"UPDATE employees SET photo_url=? WHERE id=? AND company_id=?", photoUrl, employeeId, companyId);
	}

	/** The cascade's first statement: notifications on either side of the employee. */
	public void deleteNotificationsFor(long employeeId) {
		jdbcTemplate.update(
				"DELETE FROM notifications WHERE to_employee_id = ? OR from_employee_id = ?",
				employeeId, employeeId);
	}

	/** One of the cascade's thirteen {@code DELETE FROM <table> WHERE employee_id = ?} statements. */
	/**
	 * {@code employee_excel_build_lookups()}
	 * ({@code employee_excel_helper.php}): four name-to-id maps, keyed by
	 * {@code mb_strtolower(trim($name))}.
	 *
	 * <p>Three of the four filter on {@code is_active = 1}; shifts do not, which
	 * is legacy's asymmetry and not a transcription slip. None of them carries an
	 * {@code ORDER BY}, so the iteration order is whatever the server returns --
	 * which matters because the template's example values are the <em>first</em>
	 * key of each map. J2 is still unresolved on the duplicate-name behaviour that
	 * follows from the same keying, and no ordering is invented here to paper over
	 * it: the query is the query legacy runs.
	 *
	 * <p>Two rows whose names differ only in case or surrounding whitespace
	 * collapse onto one key, and the later row's id wins while the earlier row's
	 * position is kept -- PHP array assignment, which {@link LinkedHashMap#put}
	 * reproduces exactly.
	 */
	public LinkedHashMap<String, Long> spreadsheetLookup(String table, long companyId, boolean activeOnly) {
		if (!LOOKUP_TABLES.contains(table)) {
			// A table name reaches SQL by concatenation, so it may only ever be
			// one the helper itself lists.
			throw new IllegalArgumentException("not a lookup table: " + table);
		}
		String sql = "SELECT id, name FROM " + table + " WHERE company_id=?" + (activeOnly ? " AND is_active=1" : "");
		LinkedHashMap<String, Long> lookup = new LinkedHashMap<>();
		jdbcTemplate.query(sql, rs -> {
			String name = rs.getString("name");
			lookup.put(LegacyValues.mbStrToLower(LegacyValues.phpTrim(name == null ? "" : name)), rs.getLong("id"));
		}, companyId);
		return lookup;
	}

	/** The tables {@code employee_excel_build_lookups()} reads, and the only names {@link #spreadsheetLookup} accepts. */
	private static final java.util.Set<String> LOOKUP_TABLES =
			java.util.Set.of("branches", "departments", "job_titles", "shifts");

	/**
	 * {@code stats.php}'s average-tenure query, which is the only one of its five
	 * that is not a {@code COUNT(*)}.
	 *
	 * <p>{@code AVG(...)} over an empty set is SQL NULL, which PHP turns into
	 * {@code 0.0} rather than omitting the key -- so the null has to survive the
	 * read to be distinguished from a real zero.
	 *
	 * <p>{@code CURDATE()} is evaluated by the database, on the connection's own
	 * timezone. D-083 is the open blocker for that, and running this query does
	 * not close it.
	 */
	public Double averageTenureMonths(String whereSql, List<Object> params) {
		return jdbcTemplate.queryForObject(
				"""
				SELECT AVG(
					TIMESTAMPDIFF(
						MONTH,
						COALESCE(e.hire_date, DATE(e.created_at)),
						CURDATE()
					)
				)
				FROM employees e
				WHERE %s
				AND COALESCE(e.hire_date, e.created_at) IS NOT NULL""".formatted(whereSql),
				Double.class, params.toArray());
	}

	/**
	 * {@code my_team.php}'s query: the manager's own branch, active only, self
	 * excluded, with the three org labels and a correlated
	 * {@code checked_in_today}.
	 *
	 * <p>No roster predicate -- {@code my_team.php} has none, unlike
	 * {@code list.php}. A pending join request is on the team.
	 *
	 * <p>The subselect returns {@code DATE(check_in)} rather than a flag, so the
	 * column is a date string when the employee checked in today and null
	 * otherwise. {@code CURRENT_DATE} is the database's, which is D-083's
	 * territory again.
	 */
	public List<Map<String, Object>> myTeam(long companyId, long managerEmployeeId) {
		return jdbcTemplate.query(
				"""
				SELECT
					e.*,
					b.name AS branch_name,
					s.name AS department_name,
					jt.name AS job_title_name,
					(
						SELECT DATE(check_in)
						FROM attendance
						WHERE employee_id = e.id AND DATE(check_in) = CURRENT_DATE
						LIMIT 1
					) AS checked_in_today
				FROM employees AS e
				LEFT JOIN branches AS b ON b.id = e.branch_id
				LEFT JOIN departments AS s ON s.id = e.department_id
				LEFT JOIN job_titles AS jt ON jt.id = e.job_title_id
				WHERE
					e.company_id = ?
					AND e.is_active = 1
					AND e.id <> ?
					AND e.branch_id = (
						SELECT mb.branch_id FROM employees mb
						WHERE mb.id = ? AND mb.company_id = ? LIMIT 1
					)
				ORDER BY e.first_name ASC, e.last_name ASC""",
				ROW_MAPPER, companyId, managerEmployeeId, managerEmployeeId, companyId);
	}

	/**
	 * {@code hr_permission_keys()}: the seventeen {@code hr_permissions.can_*}
	 * columns <em>in source order</em>.
	 *
	 * <p>The order is part of the contract twice over -- it is the column order
	 * of the upsert, and it is the key order of the {@code permissions} object
	 * every HR response carries. Kept here rather than taken from
	 * {@link com.workin.legacy.authorization.LegacyHrPermissionKey}, whose
	 * declaration order differs and which exists for gating, where order does not
	 * matter.
	 */
	public static final List<String> HR_PERMISSION_KEYS = List.of(
			"can_dashboard", "can_recent_activities", "can_branches", "can_departments", "can_job_titles",
			"can_shifts", "can_employees", "can_requests", "can_leave_balances", "can_penalties",
			"can_assets", "can_advances", "can_workforce_planning", "can_salary_calculator",
			"can_attendance", "can_payroll", "can_company_settings");

	/** {@code hr_permissions_sql_select_prefixed('p')}. */
	private static final String HR_PERMISSION_SELECT = HR_PERMISSION_KEYS.stream()
			.map(key -> "p." + key).collect(java.util.stream.Collectors.joining(", "));

	/**
	 * {@code hr_employees/list.php}'s query: every column of the employee plus
	 * the joined permission columns, newest first.
	 *
	 * <p>{@code ORDER BY e.id DESC} is the whole ordering -- no created_at
	 * tiebreak and no name ordering, unlike {@code employees/list.php}.
	 */
	public List<Map<String, Object>> hrEmployeeList(String whereSql, List<Object> params, long limit, long offset) {
		List<Object> bound = new ArrayList<>(params);
		bound.add(limit);
		bound.add(offset);
		return jdbcTemplate.query(
				"SELECT e.*, " + HR_PERMISSION_SELECT + " FROM employees AS e"
						+ " LEFT JOIN hr_permissions AS p ON p.employee_id = e.id"
						+ " WHERE " + whereSql + " ORDER BY e.id DESC LIMIT ? OFFSET ?",
				ROW_MAPPER, bound.toArray());
	}

	/**
	 * {@code update_permissions.php}'s target lookup: id <em>and</em> company, so
	 * another tenant's employee is indistinguishable from a missing one.
	 */
	public Map<String, Object> hrEmployeeRoleInCompany(long employeeId, long companyId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT id, role FROM employees WHERE id=? AND company_id=?",
				ROW_MAPPER, employeeId, companyId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** The employee with its permission columns joined, as both HR mutations reread it. */
	public Map<String, Object> hrEmployeeWithPermissions(long employeeId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT e.*, " + HR_PERMISSION_SELECT + " FROM employees AS e"
						+ " LEFT JOIN hr_permissions AS p ON p.employee_id = e.id WHERE e.id=?",
				ROW_MAPPER, employeeId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * The same row plus the three joined names, which is what
	 * {@code hr_employees/create.php:136-149} reads back and
	 * {@code update_permissions.php:41} does not.
	 *
	 * <p>Two projections for one table, and they are only correct relative to
	 * each other: making them uniform in either direction reintroduces the
	 * defect — {@code create} would drop three keys a client receives today, or
	 * {@code update_permissions} would gain three PHP does not send. The same
	 * shape as the advance projections in D-153(b). The names are selected
	 * between {@code e.*} and the permission columns because that is where PHP
	 * puts them, and the response preserves result-set order.
	 */
	public Map<String, Object> hrEmployeeWithPermissionsAndNames(long employeeId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT e.*, b.name AS branch_name, d.name AS department_name,"
						+ " jt.name AS job_title_name, " + HR_PERMISSION_SELECT
						+ " FROM employees AS e"
						+ " LEFT JOIN branches AS b ON b.id = e.branch_id"
						+ " LEFT JOIN departments AS d ON d.id = e.department_id"
						+ " LEFT JOIN job_titles AS jt ON jt.id = e.job_title_id"
						+ " LEFT JOIN hr_permissions AS p ON p.employee_id = e.id WHERE e.id=?",
				ROW_MAPPER, employeeId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * {@code hr_permissions_upsert_sql()}: one row per employee, every one of the
	 * seventeen columns written on every call.
	 *
	 * <p>{@code ON DUPLICATE KEY UPDATE col = VALUES(col)} for all seventeen is
	 * what makes this a replacement rather than a patch -- a flag the caller did
	 * not mention is written as 0, not left alone.
	 */
	public void upsertHrPermissions(long employeeId, List<Integer> values) {
		String columns = String.join(", ", HR_PERMISSION_KEYS);
		String placeholders = String.join(", ",
				java.util.Collections.nCopies(HR_PERMISSION_KEYS.size() + 1, "?"));
		String updates = HR_PERMISSION_KEYS.stream()
				.map(key -> key + " = VALUES(" + key + ")")
				.collect(java.util.stream.Collectors.joining(", "));
		List<Object> bound = new ArrayList<>();
		bound.add(employeeId);
		bound.addAll(values);
		jdbcTemplate.update(
				"INSERT INTO hr_permissions (employee_id, " + columns + ") VALUES (" + placeholders + ")"
						+ " ON DUPLICATE KEY UPDATE " + updates,
				bound.toArray());
	}

	public void deleteByEmployeeId(String table, long employeeId) {
		if (!CASCADE_TABLES.contains(table)) {
			// A table name reaches SQL by concatenation, so it may only ever be
			// one the helper itself lists.
			throw new IllegalArgumentException("not a cascade table: " + table);
		}
		jdbcTemplate.update("DELETE FROM " + table + " WHERE employee_id = ?", employeeId);
	}

	/**
	 * The cascade's manager clear: company-scoped, so another company's
	 * department keeps its (already impossible) reference. Only the cascade path
	 * runs it -- D-077 keeps the direct path free of any cleanup.
	 */
	public void clearDepartmentManager(long employeeId, long companyId) {
		jdbcTemplate.update(
				"UPDATE departments SET manager_id = NULL WHERE manager_id = ? AND company_id = ?",
				employeeId, companyId);
	}

	/** The cascade's final statement, whose row count the helper requires to be exactly 1. */
	public int deleteEmployeeScoped(long employeeId, long companyId) {
		deleteDeviceIdentity(employeeId, companyId);
		return jdbcTemplate.update("DELETE FROM employees WHERE id = ? AND company_id = ?", employeeId, companyId);
	}

	/**
	 * The device PIN binding (D-164), cleared on <b>both</b> paths that remove
	 * an employee -- the cascade above and {@code delete.php}'s direct path,
	 * which an employee with no related records takes instead.
	 *
	 * <p>Deliberately outside {@link #CASCADE_TABLES}: that list is the PHP
	 * helper's contract (D-078) and this table does not exist in PHP. Leaving
	 * the row behind would orphan it -- the identities endpoint would show a
	 * PIN against a blank employee, and the unique employee key would stay
	 * taken so the PIN could never be reissued. Failures are ignored for the
	 * same reason the company cascade ignores them: a deployment may not have
	 * provisioned these tables yet (R-023 / Q7).
	 */
	private void deleteDeviceIdentity(long employeeId, long companyId) {
		try {
			jdbcTemplate.update(
					"DELETE FROM employee_device_identities WHERE company_id = ? AND employee_id = ?",
					companyId, employeeId);
		} catch (RuntimeException ignored) {
			// See the javadoc: an absent table must not block employee deletion.
		}
	}

	/** The tables the cascade clears, in order, after {@code notifications}. */
	public List<String> cascadeTables() {
		return CASCADE_TABLES;
	}

	/** One row of the preview: its key, the message key for its label, and the count. */
	public record RelatedRecordCount(String key, String labelKey, long count) {
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
			row.put(label, LegacyJdbcValues.read(rs, column, metaData.getColumnType(column)));
		}
		return row;
	};

}
