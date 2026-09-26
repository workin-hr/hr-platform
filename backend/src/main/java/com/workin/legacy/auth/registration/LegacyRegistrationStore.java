package com.workin.legacy.auth.registration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyGeneratedKeys;
import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.phone.CanonicalPhone;
import com.workin.legacy.phone.PhoneLookup;

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
	 * {@code check_status.php}'s row: the newest row in the company holding
	 * the number. PHP matched the column exactly, so a differently formatted
	 * number was {@code status_not_found}; any spelling of the number is found
	 * now (D-291).
	 */
	public Map<String, Object> employeeStatus(PhoneLookup phone, long companyId) {
		return single(verified(phone, "e.phone", """
				SELECT e.id, e.phone, e.country_code, e.is_active, e.role, c.status AS company_status
				FROM employees AS e
				JOIN companies AS c ON c.id = e.company_id
				WHERE %s AND e.company_id = ?
				ORDER BY e.id DESC""", companyId));
	}

	// ---------------- register_company.php ----------------

	/**
	 * Its uniqueness probe: any company holding the number, in any spelling.
	 * PHP matched the column exactly, which is how 16 pairs of companies came
	 * to share one number under two spellings (ADR-0020).
	 */
	public boolean companyPhoneExists(CanonicalPhone phone) {
		// Or a company holding the exact digits the insert stores, which the
		// raw unique index would refuse (PhoneLookup#writeProbe).
		PhoneLookup lookup = PhoneLookup.of(phone);
		PhoneLookup.Clause probe = lookup.writeProbe("phone", "id, phone, country_code", "companies");
		return jdbcTemplate.queryForList(probe.sql(), probe.binds().toArray()).stream().anyMatch(lookup::blocksWrite);
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

	/**
	 * {@code register_employee.php} finds the company by its <b>phone</b>, not
	 * its code: every company holding the number, lowest id first, for the
	 * caller to pick one with {@link PhoneLookup#singleRow}.
	 */
	public List<Map<String, Object>> companiesByPhone(PhoneLookup phone) {
		return verified(phone, "phone",
				"SELECT id, status, phone, country_code FROM companies WHERE %s ORDER BY id ASC");
	}

	/** {@code register_employee.php}'s duplicate probe: the number, in any spelling, in one company. */
	public boolean employeeExistsInCompany(CanonicalPhone phone, long companyId) {
		return !verified(PhoneLookup.of(phone), "phone",
				"SELECT id, phone, country_code FROM employees WHERE %s AND company_id = ?", companyId).isEmpty();
	}

	/** Stored in the convention every Java write uses: national digits beside their dial code. */
	public long insertEmployeeMinimal(long companyId, CanonicalPhone phone, String passwordHash) {
		return LegacyGeneratedKeys.insert(jdbcTemplate,
				"INSERT INTO employees (company_id, phone, country_code, password_hash, role)"
						+ " VALUES (?, ?, ?, ?, 'employee')",
				companyId, phone.nationalDigits(), phone.dialCode(), passwordHash);
	}

	/**
	 * {@code join_company.php}'s duplicate probe: the number, in any spelling,
	 * in one company -- and it deliberately treats a <b>rejected</b> row as
	 * absent: a rejected applicant may apply again, an accepted or pending one
	 * may not.
	 */
	public boolean joinRequestAlreadyExists(CanonicalPhone phone, long companyId) {
		return !verified(PhoneLookup.of(phone), "phone", """
				SELECT id, phone, country_code FROM employees
				WHERE %s AND company_id = ? AND COALESCE(join_request_status, 'accepted') <> 'rejected'""",
				companyId).isEmpty();
	}

	/** {@code company_phone_exists_globally($phone, $excludeCompanyId)}: the number, in any spelling. */
	public boolean companyPhoneExistsGlobally(CanonicalPhone phone, long excludeCompanyId) {
		return !verified(PhoneLookup.of(phone), "phone",
				"SELECT id, phone, country_code FROM companies WHERE %s AND id <> ?", excludeCompanyId).isEmpty();
	}

	/**
	 * The pending row, stored in the convention every Java write uses. PHP
	 * wrote the digits and left {@code country_code} NULL; the dial code is
	 * written now, so the row reads as the number it is in any country.
	 */
	public long insertJoinRequestEmployee(
			long companyId, long branchId, String firstName, String lastName,
			CanonicalPhone phone, String passwordHash) {
		return LegacyGeneratedKeys.insert(jdbcTemplate, """
				INSERT INTO employees
					(company_id, branch_id, first_name, last_name, phone, country_code, password_hash,
					 role, is_active, join_request_status)
				VALUES (?, ?, ?, ?, ?, ?, ?, 'employee', 0, 'pending')""",
				companyId, branchId, firstName, lastName, phone.nationalDigits(), phone.dialCode(), passwordHash);
	}

	public Map<String, Object> employee(long employeeId) {
		return single(jdbcTemplate.query(
				"SELECT * FROM employees WHERE id = ?", LegacyJdbcValues.rowMapper(), employeeId));
	}

	// ---------------- login_company.php ----------------

	/** Every company holding the number, lowest id first; the caller picks with {@link PhoneLookup#singleRow}. */
	public List<Map<String, Object>> companiesForLogin(PhoneLookup phone) {
		return verified(phone, "phone", "SELECT * FROM companies WHERE %s ORDER BY id ASC");
	}

	// ---------------- login_desktop.php ----------------

	/**
	 * {@code login_desktop.php}'s HR query. Note {@code ORDER BY e.id ASC} --
	 * <b>oldest first</b>, where every other login path orders newest first --
	 * and that the role and active filters are in the SQL rather than in the
	 * decision, so a non-HR row never reaches the password loop at all.
	 */
	public List<Map<String, Object>> activeHrByPhoneOldestFirst(PhoneLookup phone) {
		return verified(phone, "e.phone", """
				SELECT e.*, c.status AS company_status
				FROM employees AS e
				INNER JOIN companies AS c ON c.id = e.company_id
				WHERE %s AND e.role = 'hr' AND e.is_active = 1
				ORDER BY e.id ASC""");
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

	/**
	 * The rows {@code sql} returns with its {@code %s} replaced by the
	 * lookup's {@code column IN (...)}, kept only when they are the number
	 * (ADR-0020). The trailing binds follow the lookup's.
	 */
	private List<Map<String, Object>> verified(PhoneLookup phone, String column, String sql, Object... trailing) {
		PhoneLookup.Clause match = phone.clause(column);
		List<Object> binds = new ArrayList<>(match.binds());
		java.util.Collections.addAll(binds, trailing);
		return phone.verified(jdbcTemplate.query(
				sql.formatted(match.sql()), LegacyJdbcValues.rowMapper(), binds.toArray()));
	}

	private static Map<String, Object> single(List<Map<String, Object>> rows) {
		return rows.isEmpty() ? null : rows.get(0);
	}
}
