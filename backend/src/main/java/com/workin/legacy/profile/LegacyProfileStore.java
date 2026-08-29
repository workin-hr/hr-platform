package com.workin.legacy.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.employees.LegacyEmployeeStore;

/** The read and write path behind {@code apis/api/profile/*.php}. */
@Repository
public class LegacyProfileStore {

	/**
	 * {@code profile/employee.php}'s GET projection. It is <b>not</b>
	 * {@code employees/one.php}'s: this one adds the company name and logo, the
	 * company title, a {@code branch_location_configured} expression computed
	 * in SQL, and the department manager's display name through a second
	 * {@code employees} join.
	 */
	private static final String PROFILE_SELECT = """
			SELECT
				e.*,
				b.name AS branch_name,
				(b.latitude IS NOT NULL AND b.longitude IS NOT NULL) AS branch_location_configured,
				s.name AS department_name,
				jt.name AS job_title_name,
				c.company_name AS company_name,
				c.logo_url AS logo_url,
				ct.name AS company_title_name%s
			FROM employees AS e
			LEFT JOIN branches AS b ON b.id = e.branch_id
			LEFT JOIN departments AS s ON s.id = e.department_id
			LEFT JOIN job_titles AS jt ON jt.id = e.job_title_id
			LEFT JOIN companies AS c ON c.id = e.company_id
			LEFT JOIN company_titles AS ct ON ct.id = c.company_title_id%s
			WHERE e.id=? AND e.company_id=?""";

	/**
	 * The GET adds {@code manager_name} and the join that produces it; the PUT's
	 * re-read does not. Two projections that differ by one column is exactly the
	 * kind of thing a shared constant would erase, so both are spelled out.
	 */
	private static final String GET_SELECT = PROFILE_SELECT.formatted(
			",\n\t\t\t\tTRIM(CONCAT(COALESCE(mgr.first_name,''),' ',COALESCE(mgr.last_name,''))) AS manager_name",
			"\n\t\t\tLEFT JOIN employees AS mgr ON mgr.id = s.manager_id");

	private static final String PUT_REREAD_SELECT = PROFILE_SELECT.formatted("", "");

	private final JdbcTemplate jdbcTemplate;

	public LegacyProfileStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	public Map<String, Object> profileForGet(long employeeId, long companyId) {
		return single(jdbcTemplate.query(GET_SELECT, LegacyJdbcValues.rowMapper(), employeeId, companyId));
	}

	public Map<String, Object> profileAfterUpdate(long employeeId, long companyId) {
		return single(jdbcTemplate.query(PUT_REREAD_SELECT, LegacyJdbcValues.rowMapper(), employeeId, companyId));
	}

	/** The PUT's pre-check: the bare row, no joins, scoped by id and company. */
	public Map<String, Object> employeeInCompany(long employeeId, long companyId) {
		return single(jdbcTemplate.query(
				"SELECT * FROM employees WHERE id=? AND company_id=?",
				LegacyJdbcValues.rowMapper(), employeeId, companyId));
	}

	/**
	 * {@code UPDATE employees SET <assignments> WHERE id=? AND company_id=?}.
	 *
	 * <p>The assignment list is built by the caller from the columns the body
	 * actually carried, so a key the request omitted is not written -- the
	 * difference between {@code null} and absent is observable here.
	 */
	public void updateEmployee(List<String> assignments, List<Object> binds, long employeeId, long companyId) {
		List<Object> bound = new ArrayList<>(binds);
		bound.add(employeeId);
		bound.add(companyId);
		jdbcTemplate.update(
				"UPDATE employees SET " + String.join(", ", assignments) + " WHERE id=? AND company_id=?",
				bound.toArray());
	}

	/** {@code profile/company.php}: the whole company row. */
	public Map<String, Object> company(long companyId) {
		return single(jdbcTemplate.query(
				"SELECT * FROM companies WHERE id=?", LegacyJdbcValues.rowMapper(), companyId));
	}

	/**
	 * {@code profile/company.php}'s employee half, which joins the permission
	 * columns -- so {@code employee_row_attach_hr_permissions()} takes its
	 * row branch rather than issuing a second query.
	 */
	public Map<String, Object> companyProfileEmployee(long employeeId, long companyId) {
		String permissions = LegacyEmployeeStore.HR_PERMISSION_KEYS.stream()
				.map(key -> "p." + key).collect(java.util.stream.Collectors.joining(", "));
		return single(jdbcTemplate.query(
				"SELECT e.*, b.name AS branch_name, " + permissions
						+ " FROM employees AS e"
						+ " LEFT JOIN branches AS b ON b.id = e.branch_id"
						+ " LEFT JOIN hr_permissions AS p ON p.employee_id = e.id"
						+ " WHERE e.id = ? AND e.company_id = ?",
				LegacyJdbcValues.rowMapper(), employeeId, companyId));
	}

	/** {@code change_password.php}/{@code delete_account.php}: the hash alone. */
	public String companyPasswordHash(long companyId) {
		return hash("SELECT password_hash FROM companies WHERE id = ?", companyId);
	}

	public String employeePasswordHash(long employeeId) {
		return hash("SELECT password_hash FROM employees WHERE id = ?", employeeId);
	}

	/** {@code delete_account.php}'s employee branch scopes by company as well. */
	public String employeePasswordHashInCompany(long employeeId, long companyId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT password_hash FROM employees WHERE id=? AND company_id=?",
				LegacyJdbcValues.rowMapper(), employeeId, companyId);
		return rows.isEmpty() ? null : (String) rows.get(0).get("password_hash");
	}

	public void updateCompanyPassword(long companyId, String hash) {
		jdbcTemplate.update("UPDATE companies SET password_hash = ? WHERE id = ?", hash, companyId);
	}

	public void updateEmployeePassword(long employeeId, String hash) {
		jdbcTemplate.update("UPDATE employees SET password_hash = ? WHERE id = ?", hash, employeeId);
	}

	/** {@code logout.php}: the caller's own token only. */
	public void deletePushToken(String token, long employeeId) {
		jdbcTemplate.update("DELETE FROM push_tokens WHERE token = ? AND employee_id = ?", token, employeeId);
	}

	public void deletePushTokensForEmployee(long employeeId) {
		jdbcTemplate.update("DELETE FROM push_tokens WHERE employee_id = ?", employeeId);
	}

	/** {@code logout.php}'s pre-update read: is_active plus the display name and phone. */
	public Map<String, Object> employeeForLogout(long employeeId, long companyId) {
		return single(jdbcTemplate.query("""
				SELECT e.is_active,
					TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,''))) AS employee_name,
					e.phone
				FROM employees AS e
				WHERE e.id = ? AND e.company_id = ?""",
				LegacyJdbcValues.rowMapper(), employeeId, companyId));
	}

	public void deactivate(long employeeId, long companyId) {
		jdbcTemplate.update("UPDATE employees SET is_active = 0 WHERE id = ? AND company_id = ?",
				employeeId, companyId);
	}

	/**
	 * {@code register_push_token.php}'s upsert. Only {@code token} and
	 * {@code platform} are refreshed on a duplicate key; the owning ids are
	 * not, because they are the key.
	 */
	public void upsertPushToken(Long employeeId, Long companyId, Object token, Object platform) {
		jdbcTemplate.update("""
				INSERT INTO push_tokens (employee_id, company_id, token, platform)
				VALUES (?,?,?,?)
				ON DUPLICATE KEY UPDATE token = VALUES(token), platform = VALUES(platform)""",
				employeeId, companyId, token, platform);
	}

	/** {@code delete_account_preview.php}'s existence probe. */
	public boolean companyExists(long companyId) {
		Long found = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM companies WHERE id = ?", Long.class, companyId);
		return found != null && found != 0;
	}

	private String hash(String sql, long id) {
		List<Map<String, Object>> rows = jdbcTemplate.query(sql, LegacyJdbcValues.rowMapper(), id);
		return rows.isEmpty() ? null : (String) rows.get(0).get("password_hash");
	}

	private static Map<String, Object> single(List<Map<String, Object>> rows) {
		return rows.isEmpty() ? null : rows.get(0);
	}
}
