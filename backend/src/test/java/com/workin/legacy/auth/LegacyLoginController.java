package com.workin.legacy.auth;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Test-only pre-D-074 login alias retained solely for legacy regression coverage. */
@RestController
public class LegacyLoginController {

	private final LegacyLoginService legacyLoginService;

	public LegacyLoginController(LegacyLoginService legacyLoginService) {
		this.legacyLoginService = legacyLoginService;
	}

	@PostMapping("/api/legacy/auth/login_employee")
	public LegacyAuthResponse login(@Valid @RequestBody LegacyLoginRequest request) {
		return legacyLoginService.login(request);
	}
}
