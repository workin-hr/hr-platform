package com.workin.legacy.auth.registration;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.workin.legacy.auth.LegacyPhpJwtService;

/**
 * {@code employee_issue_session_token()} ({@code helpers/functions.php:536-556}).
 *
 * <p>Three steps in this order, and the order is the point: bump
 * {@code token_version}, delete the employee's push tokens, then re-read the
 * fresh version and sign it. The bump is what invalidates the previous session
 * -- legacy allows exactly one active employee session -- and the push-token
 * delete is what stops the old device receiving notifications for a session it
 * no longer owns.
 *
 * <p>Not transactional, and deliberately so: frozen PHP runs these in
 * autocommit, so the bump is durable before the token is signed. A failure
 * between them leaves the old session invalidated and no new one issued, which
 * is the safe direction and the one legacy already takes.
 *
 * <p>{@code LegacyPhpLoginService} does the same three steps inline for
 * {@code login_employee.php}. This service exists for the two Wave 13.1
 * callers that need them without the surrounding login decision --
 * {@code join_company.php} and {@code login_desktop.php}'s HR branch.
 */
@Service
public class LegacyEmployeeSessionTokens {

	private final JdbcTemplate jdbcTemplate;
	private final LegacyPhpJwtService jwtService;

	public LegacyEmployeeSessionTokens(DataSource legacyDataSource, LegacyPhpJwtService jwtService) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.jwtService = jwtService;
	}

	/** @return the signed employee token, carrying the freshly bumped version */
	public String issue(long employeeId, long companyId, String role) {
		jdbcTemplate.update("UPDATE employees SET token_version = token_version + 1 WHERE id = ?", employeeId);
		jdbcTemplate.update("DELETE FROM push_tokens WHERE employee_id = ?", employeeId);
		Long version = jdbcTemplate.queryForObject(
				"SELECT token_version FROM employees WHERE id = ?", Long.class, employeeId);
		return jwtService.issueEmployeeToken(employeeId, companyId, role, version == null ? 1L : version);
	}
}
