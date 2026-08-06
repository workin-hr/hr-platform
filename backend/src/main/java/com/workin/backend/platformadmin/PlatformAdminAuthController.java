package com.workin.backend.platformadmin;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * No register endpoint here, deliberately -- see
 * {@link PlatformAdminBootstrap}.
 */
@RestController
@RequestMapping("/api/platform-admin")
public class PlatformAdminAuthController {

	private final PlatformAdminLoginService platformAdminLoginService;
	private final PlatformAdminJwtService platformAdminJwtService;
	private final PlatformAdminSessionService platformAdminSessionService;

	public PlatformAdminAuthController(
			PlatformAdminLoginService platformAdminLoginService,
			PlatformAdminJwtService platformAdminJwtService,
			PlatformAdminSessionService platformAdminSessionService) {
		this.platformAdminLoginService = platformAdminLoginService;
		this.platformAdminJwtService = platformAdminJwtService;
		this.platformAdminSessionService = platformAdminSessionService;
	}

	@PostMapping("/login")
	public PlatformAdminAuthResponse login(@Valid @RequestBody PlatformAdminLoginRequest request) {
		PlatformAdmin platformAdmin = platformAdminLoginService.login(request);
		PlatformAdminSessionService.IssuedRefreshToken session = platformAdminSessionService
				.issue(platformAdmin.getId());
		String accessToken = platformAdminJwtService.issueAccessToken(
				platformAdmin.getId(), session.familyId().toString());
		return new PlatformAdminAuthResponse(accessToken, session.rawToken(), platformAdmin.getId());
	}

	@PostMapping("/refresh")
	public PlatformAdminAuthResponse refresh(@Valid @RequestBody PlatformAdminRefreshTokenRequest request) {
		PlatformAdminSessionService.RotatedSession session = platformAdminSessionService.rotate(request.refreshToken())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
		String accessToken = platformAdminJwtService.issueAccessToken(
				session.platformAdminId(), session.familyId().toString());
		return new PlatformAdminAuthResponse(accessToken, session.rawToken(), session.platformAdminId());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody PlatformAdminRefreshTokenRequest request) {
		platformAdminSessionService.logout(request.refreshToken());
	}

}
