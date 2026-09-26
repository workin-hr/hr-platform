package com.workin.legacy.auth.otp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyLoginCandidate;
import com.workin.legacy.phone.CanonicalPhone;
import com.workin.legacy.phone.PhoneLookup;

/**
 * The account lookups and password writes behind the four OTP auth endpoints.
 *
 * <p>Every phone lookup here is a {@link PhoneLookup}: the stored spellings of
 * the number are bound, and only rows that canonicalise to it are kept
 * (ADR-0020, D-291). So a number stored as {@code 01012345678},
 * {@code 1012345678} or {@code 201012345678} is found by any spelling of the
 * request, and nothing the client typed reaches a {@code WHERE}.
 *
 * <p>The writes that PHP expressed as {@code UPDATE ... WHERE phone matches}
 * are a verified read followed by an update of exactly those ids, so the set
 * written is the set verified.
 */
@Repository
public class LegacyOtpAuthStore {

	private final JdbcTemplate jdbcTemplate;

	public LegacyOtpAuthStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** {@code forgot_password.php}'s company branch: id, phone and country code, lowest id first. */
	public Map<String, Object> findCompanyByPhone(PhoneLookup phone) {
		return first(verified(phone, "phone", "SELECT id, phone, country_code FROM companies WHERE %s ORDER BY id ASC"));
	}

	/** {@code forgot_password.php}'s employee branch when a company id was supplied. */
	public Map<String, Object> findEmployeeByPhoneInCompany(PhoneLookup phone, long companyId) {
		return first(verified(phone, "phone",
				"SELECT id, phone, country_code FROM employees WHERE %s AND company_id = ? ORDER BY id ASC",
				companyId));
	}

	/**
	 * {@code resolve_single_employee_auth_by_phone()}'s query: every row owning
	 * the number, newest first, joined to its company's status.
	 */
	public List<LegacyLoginCandidate> employeeAuthCandidatesByPhone(PhoneLookup phone) {
		List<Map<String, Object>> rows = verified(phone, "e.phone", """
				SELECT e.*, c.status AS company_status
				FROM employees AS e
				JOIN companies AS c ON c.id = e.company_id
				WHERE %s
				ORDER BY e.id DESC""");

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

	/**
	 * The phone and country code as stored on one employee row.
	 *
	 * <p>Both are needed: {@code forgot_password.php} carries the row's
	 * {@code COUNTRY_CODE} into the WhatsApp send, and the number is read in
	 * that country.
	 */
	public Map<String, Object> employeeContact(long employeeId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT phone, country_code FROM employees WHERE id = ?",
				LegacyJdbcValues.rowMapper(), employeeId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * {@code verify_otp.php}'s company update: the company holding the
	 * number -- the one row when the number is stored once, or, when it is
	 * stored twice (the duplicate registrations ADR-0020 counts), the row
	 * stored exactly as the request's digits and none otherwise.
	 *
	 * <p>PHP matched {@code WHERE phone = ?} on the request's digits, so a
	 * company stored in a different spelling from the one it typed verified
	 * its OTP and was never marked; that quirk is gone with the exact match
	 * (D-291).
	 */
	public void markCompanyOtpVerified(CanonicalPhone phone, Object typedPhone) {
		Map<String, Object> company = PhoneLookup.singleRow(
				verified(PhoneLookup.of(phone), "phone", "SELECT id, phone, country_code FROM companies WHERE %s ORDER BY id ASC"),
				typedPhone);
		if (company != null) {
			jdbcTemplate.update("UPDATE companies SET otp_verified = 1 WHERE id = ?", company.get("id"));
		}
	}

	/** {@code reset_password.php}'s company branch -- every company holding the number. */
	public void updateCompanyPasswordByPhone(CanonicalPhone phone, String hash) {
		List<Object> ids = ids(verified(PhoneLookup.of(phone), "phone", "SELECT id, phone, country_code FROM companies WHERE %s"));
		if (ids.isEmpty()) {
			return;
		}
		List<Object> binds = new ArrayList<>();
		binds.add(hash);
		binds.addAll(ids);
		jdbcTemplate.update("UPDATE companies SET password_hash = ? WHERE id IN ("
				+ String.join(", ", Collections.nCopies(ids.size(), "?")) + ")", binds.toArray());
	}

	/**
	 * The ids {@link #updateEmployeePasswords} is given.
	 *
	 * <p>Read <b>before</b> the update and handed to it, so the set revoked is
	 * exactly the set whose credential changed -- ADR-0005 requires a password
	 * reset to revoke the relevant sessions.
	 */
	public List<Long> employeeIdsByPhoneInCompany(CanonicalPhone phone, long companyId) {
		List<Long> ids = new ArrayList<>();
		for (Map<String, Object> row : verified(PhoneLookup.of(phone), "phone",
				"SELECT id, phone, country_code FROM employees WHERE %s AND company_id = ?", companyId)) {
			ids.add(LegacyValues.toPhpLong(row.get("id")));
		}
		return ids;
	}

	/** {@code reset_password.php}'s employee branch, scoped by company. */
	public void updateEmployeePasswords(List<Long> employeeIds, long companyId, String hash) {
		if (employeeIds.isEmpty()) {
			return;
		}
		List<Object> binds = new ArrayList<>();
		binds.add(hash);
		binds.addAll(employeeIds);
		binds.add(companyId);
		jdbcTemplate.update("UPDATE employees SET password_hash = ? WHERE id IN ("
				+ String.join(", ", Collections.nCopies(employeeIds.size(), "?")) + ") AND company_id = ?",
				binds.toArray());
	}

	/** {@code request_phone_change.php}/{@code confirm_phone_change.php}: the current phone and its country. */
	public Map<String, Object> companyPhone(long companyId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT phone, country_code FROM companies WHERE id = ?", LegacyJdbcValues.rowMapper(), companyId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** The uniqueness probe for a new company phone, excluding the caller. */
	public boolean anotherCompanyHasPhone(CanonicalPhone phone, long excludeCompanyId) {
		// Or holds the exact digits the change stores, which the raw unique
		// index would refuse (PhoneLookup#writeProbe).
		PhoneLookup lookup = PhoneLookup.of(phone);
		PhoneLookup.Clause probe = lookup.writeProbe("phone", "id, phone, country_code", "companies");
		List<Object> binds = new ArrayList<>(probe.binds());
		binds.add(excludeCompanyId);
		return jdbcTemplate.queryForList(probe.sql() + " AND id <> ?", binds.toArray()).stream()
				.anyMatch(lookup::blocksWrite);
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

	/**
	 * The rows {@code sql} returns with its {@code %s} replaced by the
	 * lookup's {@code phone IN (...)}, kept only when they are the number.
	 * The trailing binds follow the lookup's.
	 */
	private List<Map<String, Object>> verified(PhoneLookup phone, String column, String sql, Object... trailing) {
		PhoneLookup.Clause match = phone.clause(column);
		List<Object> binds = new ArrayList<>(match.binds());
		Collections.addAll(binds, trailing);
		return phone.verified(jdbcTemplate.query(
				sql.formatted(match.sql()), LegacyJdbcValues.rowMapper(), binds.toArray()));
	}

	private static List<Object> ids(List<Map<String, Object>> rows) {
		List<Object> ids = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			ids.add(row.get("id"));
		}
		return ids;
	}

	private static Map<String, Object> first(List<Map<String, Object>> rows) {
		return rows.isEmpty() ? null : rows.get(0);
	}
}
