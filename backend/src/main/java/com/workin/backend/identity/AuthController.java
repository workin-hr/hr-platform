package com.workin.backend.identity;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.authorization.PublicUseCase;

@RestController
public class AuthController {

	private final RegistrationService registrationService;
	private final LoginService loginService;
	private final JwtService jwtService;
	private final RefreshTokenService refreshTokenService;

	public AuthController(
			RegistrationService registrationService,
			LoginService loginService,
			JwtService jwtService,
			RefreshTokenService refreshTokenService) {
		this.registrationService = registrationService;
		this.loginService = loginService;
		this.jwtService = jwtService;
		this.refreshTokenService = refreshTokenService;
	}

	@PublicUseCase(reason = "company self-registration is the entry point that creates the first credential")
	@PostMapping("/api/auth/register")
	public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterCompanyRequest request) {
		RegistrationService.Registered registered = registrationService.register(request);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(openSession(registered.identityId(), registered.membershipId(), registered.companyId()));
	}

	@PublicUseCase(reason = "credential presentation -- authentication happens inside, not before")
	@PostMapping("/api/auth/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		LoginService.Authenticated authenticated = loginService.login(request);
		return openSession(authenticated.identityId(), authenticated.membershipId(), authenticated.companyId());
	}

	@PublicUseCase(reason = "refresh-token possession is the credential; the access token may already be expired")
	@PostMapping("/api/auth/refresh")
	public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
		RefreshTokenService.RotatedSession session = refreshTokenService.rotate(request.refreshToken())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
		String accessToken = jwtService.issueAccessToken(
				session.identityId(), session.membershipId(), session.companyId(), session.familyId().toString());
		return new AuthResponse(accessToken, session.rawToken(), session.membershipId(), session.companyId());
	}

	@PublicUseCase(reason = "idempotent revocation by refresh-token possession; never a validity oracle")
	@PostMapping("/api/auth/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody RefreshTokenRequest request) {
		refreshTokenService.logout(request.refreshToken());
	}

	private AuthResponse openSession(Long identityId, Long membershipId, Long companyId) {
		RefreshTokenService.IssuedRefreshToken session = refreshTokenService.issue(identityId, membershipId, companyId);
		String accessToken = jwtService.issueAccessToken(
				identityId, membershipId, companyId, session.familyId().toString());
		return new AuthResponse(accessToken, session.rawToken(), membershipId, companyId);
	}

}
