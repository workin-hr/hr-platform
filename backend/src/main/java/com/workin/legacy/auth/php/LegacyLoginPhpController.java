package com.workin.legacy.auth.php;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyValues;
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

	public LegacyLoginPhpController(LegacyPhpLoginService service, LegacyMessages messages) {
		this.service = service;
		this.messages = messages;
	}

	@RequestMapping("/login_employee.php")
	public LegacyApiResponse login(HttpServletRequest request) {
		requireMethod(request, "POST");
		Map<String, Object> body = LegacyJsonBody.read(request);
		required(body, "phone");
		required(body, "password");

		String phone = LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("phone")));
		String password = LegacyValues.toPhpString(body.get("password"));
		LegacyPhpLoginService.LoginResult login = service.login(phone, password);

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
