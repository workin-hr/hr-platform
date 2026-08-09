package com.workin.backend.platformadmin;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.authorization.PublicUseCase;
import com.workin.backend.i18n.ApiException;
import com.workin.backend.i18n.MessageKeys;

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

	@PublicUseCase(reason = "credential presentation for the platform domain -- authentication happens inside")
	@PostMapping("/login")
	public PlatformAdminAuthResponse login(@Valid @RequestBody PlatformAdminLoginRequest request) {
		PlatformAdmin platformAdmin = platformAdminLoginService.login(request);
		PlatformAdminSessionService.IssuedRefreshToken session = platformAdminSessionService
				.issue(platformAdmin.getId());
		String accessToken = platformAdminJwtService.issueAccessToken(
				platformAdmin.getId(), session.familyId().toString());
		return new PlatformAdminAuthResponse(accessToken, session.rawToken(), platformAdmin.getId());
	}

	@PublicUseCase(reason = "refresh-token possession is the credential; the access token may already be expired")
	@PostMapping("/refresh")
	public PlatformAdminAuthResponse refresh(@Valid @RequestBody PlatformAdminRefreshTokenRequest request) {
		PlatformAdminSessionService.RotatedSession session = platformAdminSessionService.rotate(request.refreshToken())
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, MessageKeys.AUTH_INVALID_REFRESH_TOKEN));
		String accessToken = platformAdminJwtService.issueAccessToken(
				session.platformAdminId(), session.familyId().toString());
		return new PlatformAdminAuthResponse(accessToken, session.rawToken(), session.platformAdminId());
	}

	@PublicUseCase(reason = "idempotent revocation by refresh-token possession; never a validity oracle")
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody PlatformAdminRefreshTokenRequest request) {
		platformAdminSessionService.logout(request.refreshToken());
	}

}
