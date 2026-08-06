package com.workin.backend.platformadmin;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * No register endpoint here, deliberately -- see
 * {@link PlatformAdminBootstrap}.
 */
@RestController
@RequestMapping("/api/platform-admin")
public class PlatformAdminAuthController {

	private final PlatformAdminLoginService platformAdminLoginService;
	private final PlatformAdminJwtService platformAdminJwtService;

	public PlatformAdminAuthController(
			PlatformAdminLoginService platformAdminLoginService, PlatformAdminJwtService platformAdminJwtService) {
		this.platformAdminLoginService = platformAdminLoginService;
		this.platformAdminJwtService = platformAdminJwtService;
	}

	@PostMapping("/login")
	public PlatformAdminAuthResponse login(@Valid @RequestBody PlatformAdminLoginRequest request) {
		PlatformAdmin platformAdmin = platformAdminLoginService.login(request);
		String token = platformAdminJwtService.issueAccessToken(platformAdmin.getId());
		return new PlatformAdminAuthResponse(token, platformAdmin.getId());
	}

}
