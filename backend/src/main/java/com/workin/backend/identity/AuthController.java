package com.workin.backend.identity;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

	private final RegistrationService registrationService;
	private final LoginService loginService;
	private final JwtService jwtService;

	public AuthController(RegistrationService registrationService, LoginService loginService, JwtService jwtService) {
		this.registrationService = registrationService;
		this.loginService = loginService;
		this.jwtService = jwtService;
	}

	@PostMapping("/api/auth/register")
	public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterCompanyRequest request) {
		RegistrationService.Registered registered = registrationService.register(request);
		String token = jwtService.issueAccessToken(
				registered.identityId(), registered.membershipId(), registered.companyId());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new AuthResponse(token, registered.membershipId(), registered.companyId()));
	}

	@PostMapping("/api/auth/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		LoginService.Authenticated authenticated = loginService.login(request);
		String token = jwtService.issueAccessToken(
				authenticated.identityId(), authenticated.membershipId(), authenticated.companyId());
		return new AuthResponse(token, authenticated.membershipId(), authenticated.companyId());
	}

}
