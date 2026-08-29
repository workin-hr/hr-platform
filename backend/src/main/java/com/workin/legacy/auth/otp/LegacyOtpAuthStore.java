package com.workin.legacy.auth.otp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyLoginCandidate;
import com.workin.legacy.phone.LegacyPhoneNumbers;

/**
 * The account lookups and password writes behind the four OTP auth endpoints.
 *
 * <p>Every phone lookup here goes through
 * {@link LegacyPhoneNumbers#sqlMatchClause}, so a number stored as
 * {@code +201012345678}, {@code 01012345678} or {@code 1012345678} all match
 * the same request. {@code verify_otp.php} is the exception and matches the
 * column exactly -- see {@link #markCompanyOtpVerified}.
 */
@Repository
public class LegacyOtpAuthStore {

	private final JdbcTemplate jdbcTemplate;

	public LegacyOtpAuthStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** {@code forgot_password.php}'s company branch: id, phone and country code. */
	public Map<String, Object> findCompanyByPhone(String phone) {
		LegacyPhoneNumbers.MatchClause match = LegacyPhoneNumbers.sqlMatchClause("phone", phone);
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT id, phone, country_code FROM companies WHERE " + match.sql() + " LIMIT 1",
				LegacyJdbcValues.rowMapper(), match.binds().toArray());
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** {@code forgot_password.php}'s employee branch when a company id was supplied. */
	public Map<String, Object> findEmployeeByPhoneInCompany(String phone, long companyId) {
		LegacyPhoneNumbers.MatchClause match = LegacyPhoneNumbers.sqlMatchClause("phone", phone);
		List<Object> binds = new ArrayList<>(match.binds());
		binds.add(companyId);
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT id, phone, country_code FROM employees WHERE " + match.sql()
						+ " AND company_id = ? LIMIT 1",
				LegacyJdbcValues.rowMapper(), binds.toArray());
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * {@code resolve_single_employee_auth_by_phone()}'s query: every row owning
	 * the phone, newest first, joined to its company's status.
	 *
	 * <p>The two guards after the phone match ({@code phone IS NOT NULL} and
	 * {@code TRIM(phone) <> ''}) are redundant once a variant matched, and are
	 * kept because removing them would widen the result set if
	 * {@code sqlMatchClause} ever produced an empty-string variant.
	 */
	public List<LegacyLoginCandidate> employeeAuthCandidatesByPhone(String phone) {
		LegacyPhoneNumbers.MatchClause match = LegacyPhoneNumbers.sqlMatchClause("e.phone", phone);
		List<Map<String, Object>> rows = jdbcTemplate.query("""
				SELECT e.*, c.status AS company_status
				FROM employees AS e
				JOIN companies AS c ON c.id = e.company_id
				WHERE %s AND e.phone IS NOT NULL AND TRIM(e.phone) <> ''
				ORDER BY e.id DESC""".formatted(match.sql()),
				LegacyJdbcValues.rowMapper(), match.binds().toArray());

		List<LegacyLoginCandidate> candidates = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			Object hash = row.get("password_hash");
			candidates.add(new LegacyLoginCandidate(
					LegacyValues.toPhpLong(row.get("id")),
					LegacyValues.toPhpLong(row.get("company_id")),
					LegacyValues.toPhpString(row.get("role")),
					LegacyValues.toPhpString(row.get("join_request_status")),
					LegacyValues.toPhpLong(row.get("is_active")) == 1,
					LegacyValues.toPhpString(row.get("company_status")),
					hash == null ? null : String.valueOf(hash)));
		}
		return candidates;
	}

	/** The phone as stored on one employee row, so the OTP keys off the stored spelling. */
	public String employeePhone(long employeeId) {
		List<String> values = jdbcTemplate.queryForList(
				"SELECT phone FROM employees WHERE id = ?", String.class, employeeId);
		return values.isEmpty() ? null : values.get(0);
	}

	/**
	 * {@code verify_otp.php}'s company update.
	 *
	 * <p>{@code WHERE phone = ?} -- an <b>exact</b> match on the normalised
	 * digits, not {@code phone_sql_match_clause()}. A company whose stored
	 * phone carries a {@code +} or a space therefore verifies its OTP
	 * successfully and is never marked {@code otp_verified}, leaving it stuck
	 * at {@code login_company.php}'s verify-first branch. Preserved: the
	 * endpoint's own success response does not depend on this UPDATE matching.
	 */
	public void markCompanyOtpVerified(String normalizedPhone) {
		jdbcTemplate.update("UPDATE companies SET otp_verified = 1 WHERE phone = ?", normalizedPhone);
	}

	/** {@code reset_password.php}'s company branch -- every matching row. */
	public void updateCompanyPasswordByPhone(String phone, String hash) {
		LegacyPhoneNumbers.MatchClause match = LegacyPhoneNumbers.sqlMatchClause("phone", phone);
		List<Object> binds = new ArrayList<>();
		binds.add(hash);
		binds.addAll(match.binds());
		jdbcTemplate.update(
				"UPDATE companies SET password_hash = ? WHERE " + match.sql(), binds.toArray());
	}

	/** {@code reset_password.php}'s employee branch, scoped by company. */
	public void updateEmployeePasswordByPhone(String phone, long companyId, String hash) {
		LegacyPhoneNumbers.MatchClause match = LegacyPhoneNumbers.sqlMatchClause("phone", phone);
		List<Object> binds = new ArrayList<>();
		binds.add(hash);
		binds.addAll(match.binds());
		binds.add(companyId);
		jdbcTemplate.update(
				"UPDATE employees SET password_hash = ? WHERE " + match.sql() + " AND company_id = ?",
				binds.toArray());
	}

	/** {@code request_phone_change.php}/{@code confirm_phone_change.php}: the current phone. */
	public String companyPhone(long companyId) {
		List<String> values = jdbcTemplate.queryForList(
				"SELECT phone FROM companies WHERE id = ?", String.class, companyId);
		return values.isEmpty() ? null : values.get(0);
	}

	/** The uniqueness probe for a new company phone, excluding the caller. */
	public boolean anotherCompanyHasPhone(String phone, long excludeCompanyId) {
		LegacyPhoneNumbers.MatchClause match = LegacyPhoneNumbers.sqlMatchClause("phone", phone);
		List<Object> binds = new ArrayList<>(match.binds());
		binds.add(excludeCompanyId);
		List<Long> ids = jdbcTemplate.queryForList(
				"SELECT id FROM companies WHERE (" + match.sql() + ") AND id <> ?",
				Long.class, binds.toArray());
		return !ids.isEmpty();
	}

	/** {@code confirm_phone_change.php}'s write: phone, country code and the verified flag. */
	public void changeCompanyPhone(long companyId, String phone, String countryCode) {
		jdbcTemplate.update(
				"UPDATE companies SET phone = ?, country_code = ?, otp_verified = 1 WHERE id = ?",
				phone, countryCode, companyId);
	}

	/** The whole company row, for the response. */
	public Map<String, Object> company(long companyId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT * FROM companies WHERE id = ?", LegacyJdbcValues.rowMapper(), companyId);
		return rows.isEmpty() ? null : rows.get(0);
	}
}
