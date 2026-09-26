package com.workin.legacy.auth.php;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyLoginOutcome;
import com.workin.legacy.auth.LegacyLoginThrottle;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/** Literal, zero-client-change port of frozen auth/login_employee.php. */
@RestController
@RequestMapping("/apis/api/auth")
public class LegacyLoginPhpController {

	private final LegacyPhpLoginService service;
	private final LegacyMessages messages;
	private final LegacyLoginThrottle loginThrottle;

	public LegacyLoginPhpController(
			LegacyPhpLoginService service, LegacyMessages messages, LegacyLoginThrottle loginThrottle) {
		this.service = service;
		this.messages = messages;
		this.loginThrottle = loginThrottle;
	}

	@RequestMapping("/login_employee.php")
	public LegacyApiResponse login(HttpServletRequest request) {
		requireMethod(request, "POST");
		Map<String, Object> body = LegacyJsonBody.read(request);
		required(body, "phone");
		required(body, "password");

		String password = LegacyValues.toPhpString(body.get("password"));
		// The lookup binds the throttle's folded phone, trimmed as before, so
		// the budget is keyed on exactly what it matches (D-289).
		LegacyPhpLoginService.LoginResult login = loginThrottle.guard(
				body.get("phone"), request.getRemoteAddr(),
				() -> new LegacyApiException(
						LegacyLoginOutcome.USER_NOT_FOUND.status(), LegacyLoginOutcome.USER_NOT_FOUND.messageKey()),
				phone -> service.login(LegacyValues.phpTrim(phone), password));

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("token", login.token());
		data.put("employee", login.employee());
		return LegacyApiResponse.ok(message(request, "login_successful"), data);
	}

	private static void required(Map<String, Object> body, String field) {
		// PHP required(): isset() rejects null, while numeric/boolean zero is
		// present and therefore valid. Only an actual empty string is empty.
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
