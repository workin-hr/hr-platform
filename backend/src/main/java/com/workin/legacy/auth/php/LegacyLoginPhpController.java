package com.workin.legacy.auth.php;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyAuthResponse;
import com.workin.legacy.auth.LegacyLoginRequest;
import com.workin.legacy.auth.LegacyLoginService;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Literal {@code /apis/api/auth/login_employee.php} wire adapter.
 *
 * <p>Login business semantics remain those already ported by
 * {@link LegacyLoginService}. ADR-0005/ADR-0011's one explicit Phase-1
 * security divergence remains intact: the access token is short-lived and a
 * rotating refresh token is issued instead of recreating legacy's ten-year
 * JWT. The PHP-visible token key and employee payload are restored here.
 */
@RestController
@RequestMapping("/apis/api/auth")
public class LegacyLoginPhpController {

	private static final String EMPLOYEE_SELECT = """
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
			WHERE e.id = ?
			""";

	private final LegacyLoginService service;
	private final LegacyMessages messages;
	private final JdbcTemplate jdbcTemplate;

	public LegacyLoginPhpController(
			LegacyLoginService service,
			LegacyMessages messages,
			DataSource legacyDataSource) {
		this.service = service;
		this.messages = messages;
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	@RequestMapping("/login_employee.php")
	public LegacyApiResponse login(HttpServletRequest request) {
		requireMethod(request, "POST");
		Map<String, Object> body = LegacyJsonBody.read(request);
		required(body, "phone");
		required(body, "password");

		String phone = LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("phone")));
		String password = LegacyValues.toPhpString(body.get("password"));
		LegacyAuthResponse session = service.login(new LegacyLoginRequest(phone, password));

		Map<String, Object> employee = employeeRow(session.employeeId(), session.companyId());
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("token", session.accessToken());
		data.put("refresh_token", session.refreshToken());
		data.put("employee", employee);
		return LegacyApiResponse.ok(message(request, "login_successful"), data);
	}

	private Map<String, Object> employeeRow(long employeeId, long companyId) {
		Map<String, Object> row = new LinkedHashMap<>(
				jdbcTemplate.queryForObject(EMPLOYEE_SELECT, LegacyJdbcValues.rowMapper(), employeeId));
		row.remove("password_hash");
		row.remove("token_version");

		if (LegacyValues.toPhpLong(row.get("can_check_in_any_branch")) != 0) {
			Long configured = jdbcTemplate.queryForObject(
					"""
					SELECT COUNT(*) FROM branches
					WHERE company_id=? AND is_active=1
					  AND latitude IS NOT NULL AND longitude IS NOT NULL
					""",
					Long.class, companyId);
			row.put("branch_location_configured", configured != null && configured > 0 ? 1L : 0L);
		}
		return row;
	}

	private static void required(Map<String, Object> body, String field) {
		if (!body.containsKey(field) || body.get(field) == null || "".equals(body.get(field))) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", field));
		}
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}
}
