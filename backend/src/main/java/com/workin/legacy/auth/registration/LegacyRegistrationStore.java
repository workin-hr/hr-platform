package com.workin.legacy.auth.registration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyGeneratedKeys;
import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.phone.LegacyPhoneNumbers;

/** The reads and writes behind the nine account-lifecycle {@code auth} endpoints. */
@Repository
public class LegacyRegistrationStore {

	private final JdbcTemplate jdbcTemplate;

	public LegacyRegistrationStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	// ---------------- get_company_registration_options.php ----------------

	public List<Map<String, Object>> companyActivities() {
		return jdbcTemplate.query(
				"SELECT id, name FROM company_activities ORDER BY id ASC", LegacyJdbcValues.rowMapper());
	}

	public List<Map<String, Object>> companyTitles() {
		return jdbcTemplate.query(
				"SELECT id, name FROM company_titles ORDER BY id ASC", LegacyJdbcValues.rowMapper());
	}

	public List<Map<String, Object>> companySizes() {
		return jdbcTemplate.query(
				"SELECT id, name, min_employees, max_employees FROM company_sizes ORDER BY id ASC",
				LegacyJdbcValues.rowMapper());
	}

	// ---------------- lookup_company.php / join_company.php ----------------

	/** {@code company_find_by_public_code()}: an upper-cased exact match. */
	public Map<String, Object> findByPublicCode(String normalizedCode) {
		return single(jdbcTemplate.query(
				"SELECT * FROM companies WHERE UPPER(company_code) = ? LIMIT 1",
				LegacyJdbcValues.rowMapper(), normalizedCode));
	}

	/** {@code lookup_company.php}'s legacy-id fallback: a narrower projection. */
	public Map<String, Object> findByIdForLookup(long companyId) {
		return single(jdbcTemplate.query(
				"SELECT id, company_name, company_code, logo_url, status FROM companies WHERE id = ?",
				LegacyJdbcValues.rowMapper(), companyId));
	}

	/** {@code company_code_is_taken()}. */
	public boolean codeIsTaken(String normalizedCode, long excludeCompanyId) {
		if (normalizedCode.isEmpty()) {
			return false;
		}
		String sql = "SELECT COUNT(*) FROM companies WHERE UPPER(company_code) = ?";
		Long count = excludeCompanyId > 0
				? jdbcTemplate.queryForObject(sql + " AND id <> ?", Long.class, normalizedCode, excludeCompanyId)
				: jdbcTemplate.queryForObject(sql, Long.class, normalizedCode);
		return count != null && count > 0;
	}

	/** {@code company_has_active_branch()}. */
	public boolean hasActiveBranch(long companyId) {
		if (companyId <= 0) {
			return false;
		}
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM branches WHERE company_id = ? AND is_active = 1", Long.class, companyId);
		return count != null && count > 0;
	}

	/** {@code join_company.php}'s default branch: the lowest-id active one. */
	public long firstActiveBranchId(long companyId) {
		List<Long> ids = jdbcTemplate.queryForList(
				"SELECT id FROM branches WHERE company_id = ? AND is_active = 1 ORDER BY id ASC LIMIT 1",
				Long.class, companyId);
		return ids.isEmpty() ? 0L : ids.get(0);
	}

	// ---------------- check_status.php ----------------

	/**
	 * {@code check_status.php}'s row. The phone is matched <b>exactly</b>,
	 * not through {@code phone_sql_match_clause()} -- so a client that sends a
	 * differently-formatted number than the one stored is told
	 * {@code status_not_found} and pointed back at the company-code screen.
	 */
	public Map<String, Object> employeeStatus(String phone, long companyId) {
		return single(jdbcTemplate.query("""
				SELECT e.id, e.is_active, e.role, c.status AS company_status
				FROM employees AS e
				JOIN companies AS c ON c.id = e.company_id
				WHERE e.phone = ? AND e.company_id = ?""",
				LegacyJdbcValues.rowMapper(), phone, companyId));
	}

	// ---------------- register_company.php ----------------

	/** Its uniqueness probe: an <b>exact</b> phone match, unlike `join_company`'s. */
	public boolean companyPhoneExistsExactly(String phone) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM companies WHERE phone = ?", Long.class, phone);
		return count != null && count > 0;
	}

	/**
	 * The insert. Every column named here exists in the frozen schema, so the
	 * {@code hasColumn()} gates PHP wraps around them all pass -- see
	 * {@link LegacyRegistrationService} for why the {@code ALTER TABLE} beside
	 * them is not ported.
	 *
	 * <p>{@code company_name} is inserted as SQL NULL: PHP sets
	 * {@code $placeholder_name = null} and never assigns it. The column is
	 * nullable, and step two supplies the real name.
	 */
	public long insertCompany(
			String firstName, String lastName, String countryCode, String phone,
			String passwordHash, String email) {
		return LegacyGeneratedKeys.insert(jdbcTemplate, """
				INSERT INTO companies
					(company_name, first_name, last_name, country_code, phone, password_hash,
					 email, status, otp_verified, profile_completed)
				VALUES (NULL, ?, ?, ?, ?, ?, ?, 'pending', 0, 0)""",
				firstName, lastName, countryCode, phone, passwordHash, email);
	}

	public Map<String, Object> company(long companyId) {
		return single(jdbcTemplate.query(
				"SELECT * FROM companies WHERE id = ?", LegacyJdbcValues.rowMapper(), companyId));
	}

	// ---------------- complete_company_registration.php ----------------

	public boolean companyTitleExists(long id) {
		return exists("company_titles", id);
	}

	public boolean companyActivityExists(long id) {
		return exists("company_activities", id);
	}

	public boolean companySizeExists(long id) {
		return exists("company_sizes", id);
	}

	private boolean exists(String table, long id) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM " + table + " WHERE id = ?", Long.class, id);
		return count != null && count > 0;
	}

	/** The step-two update, whose SET list the caller builds from what was supplied. */
	public void completeRegistration(List<String> assignments, List<Object> binds, long companyId) {
		List<Object> bound = new ArrayList<>(binds);
		bound.add(companyId);
		jdbcTemplate.update(
				"UPDATE companies SET " + String.join(", ", assignments) + " WHERE id = ?",
				bound.toArray());
	}

	/** {@code SELECT id ... LIMIT 1} -- any branch, active or not. */
	public boolean hasAnyBranch(long companyId) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM branches WHERE company_id = ?", Long.class, companyId);
		return count != null && count > 0;
	}

	public void insertMainBranch(long companyId, String name, String address) {
		jdbcTemplate.update(
				"INSERT INTO branches (company_id, name, address, is_active) VALUES (?, ?, ?, 1)",
				companyId, name, address);
	}

	// ---------------- register_employee.php / join_company.php ----------------

	/** {@code register_employee.php} finds the company by its <b>phone</b>, not its code. */
	public Map<String, Object> companyByPhoneExactly(String phone) {
		return single(jdbcTemplate.query(
				"SELECT id, status FROM companies WHERE phone = ?", LegacyJdbcValues.rowMapper(), phone));
	}

	/** {@code register_employee.php}'s duplicate probe: exact phone, one company. */
	public boolean employeeExistsInCompanyExactly(String phone, long companyId) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE phone = ? AND company_id = ?",
				Long.class, phone, companyId);
		return count != null && count > 0;
	}

	public long insertEmployeeMinimal(long companyId, String phone, String passwordHash) {
		return LegacyGeneratedKeys.insert(jdbcTemplate,
				"INSERT INTO employees (company_id, phone, password_hash, role) VALUES (?, ?, ?, 'employee')",
				companyId, phone, passwordHash);
	}

	/**
	 * {@code join_company.php}'s duplicate probe. Variant-aware, and it
	 * deliberately treats a <b>rejected</b> row as absent -- a rejected
	 * applicant may apply again, an accepted or pending one may not.
	 */
	public boolean joinRequestAlreadyExists(String phone, long companyId) {
		LegacyPhoneNumbers.MatchClause match = LegacyPhoneNumbers.sqlMatchClause("phone", phone);
		List<Object> binds = new ArrayList<>(match.binds());
		binds.add(companyId);
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE (" + match.sql() + ") AND company_id = ?"
						+ " AND COALESCE(join_request_status, 'accepted') <> 'rejected'",
				Long.class, binds.toArray());
		return count != null && count > 0;
	}

	/** {@code company_phone_exists_globally($phone, $excludeCompanyId)}. */
	public boolean companyPhoneExistsGlobally(String phone, long excludeCompanyId) {
		LegacyPhoneNumbers.MatchClause match = LegacyPhoneNumbers.sqlMatchClause("phone", phone);
		List<Object> binds = new ArrayList<>(match.binds());
		String sql = "SELECT COUNT(*) FROM companies WHERE " + match.sql();
		if (excludeCompanyId > 0) {
			sql += " AND id <> ?";
			binds.add(excludeCompanyId);
		}
		Long count = jdbcTemplate.queryForObject(sql, Long.class, binds.toArray());
		return count != null && count > 0;
	}

	public long insertJoinRequestEmployee(
			long companyId, long branchId, String firstName, String lastName,
			String phone, String passwordHash) {
		return LegacyGeneratedKeys.insert(jdbcTemplate, """
				INSERT INTO employees
					(company_id, branch_id, first_name, last_name, phone, password_hash,
					 role, is_active, join_request_status)
				VALUES (?, ?, ?, ?, ?, ?, 'employee', 0, 'pending')""",
				companyId, branchId, firstName, lastName, phone, passwordHash);
	}

	public Map<String, Object> employee(long employeeId) {
		return single(jdbcTemplate.query(
				"SELECT * FROM employees WHERE id = ?", LegacyJdbcValues.rowMapper(), employeeId));
	}

	// ---------------- login_company.php ----------------

	/** Its lookup is an <b>exact</b> phone match on the submitted value. */
	public Map<String, Object> companyByPhoneForLogin(Object phone) {
		return single(jdbcTemplate.query(
				"SELECT * FROM companies WHERE phone = ?", LegacyJdbcValues.rowMapper(), phone));
	}

	// ---------------- login_desktop.php ----------------

	/**
	 * {@code login_desktop.php}'s HR query. Note {@code ORDER BY e.id ASC} --
	 * <b>oldest first</b>, where every other login path orders newest first --
	 * and that the role and active filters are in the SQL rather than in the
	 * decision, so a non-HR row never reaches the password loop at all.
	 */
	public List<Map<String, Object>> activeHrByPhoneOldestFirst(Object phone) {
		return jdbcTemplate.query("""
				SELECT e.*, c.status AS company_status
				FROM employees AS e
				INNER JOIN companies AS c ON c.id = e.company_id
				WHERE e.phone = ? AND e.phone IS NOT NULL AND TRIM(e.phone) <> ''
				  AND e.role = 'hr' AND e.is_active = 1
				ORDER BY e.id ASC""", LegacyJdbcValues.rowMapper(), phone);
	}

	/** The re-read with the permission columns joined, so the row branch is taken. */
	public Map<String, Object> employeeWithPermissions(long employeeId) {
		String permissions = com.workin.legacy.employees.LegacyEmployeeStore.HR_PERMISSION_KEYS.stream()
				.map(key -> "p." + key).collect(java.util.stream.Collectors.joining(", "));
		return single(jdbcTemplate.query(
				"SELECT e.*, " + permissions + " FROM employees AS e"
						+ " LEFT JOIN hr_permissions AS p ON p.employee_id = e.id WHERE e.id = ?",
				LegacyJdbcValues.rowMapper(), employeeId));
	}

	private static Map<String, Object> single(List<Map<String, Object>> rows) {
		return rows.isEmpty() ? null : rows.get(0);
	}
}
