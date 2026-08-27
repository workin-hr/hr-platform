package com.workin.legacy.auth.php;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyLoginCandidate;
import com.workin.legacy.auth.LegacyLoginOutcome;
import com.workin.legacy.auth.LegacyLoginResolution;
import com.workin.legacy.auth.LegacyLoginResolver;
import com.workin.legacy.auth.LegacyPhpJwtService;
import com.workin.legacy.wire.LegacyApiException;

/** Exact application port of frozen auth/login_employee.php. */
@Service
public class LegacyPhpLoginService {

	private static final String LOGIN_ROWS = """
			SELECT
				e.*,
				b.name AS branch_name,
				(b.latitude IS NOT NULL AND b.longitude IS NOT NULL) AS branch_location_configured,
				s.name AS department_name,
				jt.name AS job_title_name,
				c.company_name AS company_name,
				c.logo_url AS logo_url,
				ct.name AS company_title_name,
				c.status AS company_status
			FROM employees e
			LEFT JOIN branches b ON b.id = e.branch_id
			LEFT JOIN departments s ON s.id = e.department_id
			LEFT JOIN job_titles jt ON jt.id = e.job_title_id
			JOIN companies c ON c.id = e.company_id
			LEFT JOIN company_titles ct ON ct.id = c.company_title_id
			WHERE e.phone = ?
			  AND e.phone IS NOT NULL
			  AND TRIM(e.phone) <> ''
			ORDER BY e.id DESC
			""";

	private final JdbcTemplate jdbcTemplate;
	private final PasswordEncoder passwordEncoder;
	private final LegacyPhpJwtService jwtService;

	public LegacyPhpLoginService(
			DataSource legacyDataSource, PasswordEncoder passwordEncoder, LegacyPhpJwtService jwtService) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
	}

	/**
	 * Intentionally not transactional. Frozen PHP runs these PDO statements in
	 * autocommit mode, so the token-version bump and push-token deletion are
	 * durable before the later read/response work. Wrapping this in a Java
	 * transaction would change failure semantics during the compatibility phase.
	 */
	public LoginResult login(String phone, String password) {
		List<Map<String, Object>> rows = jdbcTemplate.query(LOGIN_ROWS, LegacyJdbcValues.rowMapper(), phone);
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

		LegacyLoginResolution resolution = LegacyLoginResolver.resolve(candidates, hash -> passwordMatches(password, hash));
		if (!resolution.outcome().isSuccess()) {
			LegacyLoginOutcome outcome = resolution.outcome();
			throw new LegacyApiException(outcome.status(), outcome.messageKey());
		}

		LegacyLoginCandidate authenticated = resolution.authenticated();
		Map<String, Object> employee = rows.stream()
				.filter(row -> LegacyValues.toPhpLong(row.get("id")) == authenticated.employeeId())
				.findFirst()
				.map(LinkedHashMap::new)
				.orElseThrow(() -> new IllegalStateException("Resolved employee row disappeared"));

		// employee_issue_session_token(): bump session version first, delete any
		// push tokens owned by the old session, re-read the fresh version, issue.
		jdbcTemplate.update("UPDATE employees SET token_version = token_version + 1 WHERE id = ?",
				authenticated.employeeId());
		jdbcTemplate.update("DELETE FROM push_tokens WHERE employee_id = ?", authenticated.employeeId());
		Long tokenVersion = jdbcTemplate.queryForObject(
				"SELECT token_version FROM employees WHERE id = ?", Long.class, authenticated.employeeId());
		long version = tokenVersion == null ? 1L : tokenVersion;
		String token = jwtService.issueEmployeeToken(
				authenticated.employeeId(), authenticated.companyId(), authenticated.role(), version);

		attachAttendanceLocationFlag(employee, authenticated.companyId());
		employee.remove("password_hash");
		employee.remove("token_version");
		return new LoginResult(token, employee);
	}

	private boolean passwordMatches(String password, String hash) {
		try {
			return hash != null && !hash.isEmpty() && passwordEncoder.matches(password, hash);
		} catch (RuntimeException ex) {
			// PHP password_verify() returns false for an unusable hash.
			return false;
		}
	}

	private void attachAttendanceLocationFlag(Map<String, Object> employee, long companyId) {
		if (LegacyValues.toPhpLong(employee.get("can_check_in_any_branch")) == 0) {
			return;
		}
		Long configured = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*) FROM branches
				WHERE company_id = ? AND is_active = 1
				  AND latitude IS NOT NULL AND longitude IS NOT NULL
				""",
				Long.class, companyId);
		employee.put("branch_location_configured", configured != null && configured > 0 ? 1L : 0L);
	}

	public record LoginResult(String token, Map<String, Object> employee) {
	}
}
