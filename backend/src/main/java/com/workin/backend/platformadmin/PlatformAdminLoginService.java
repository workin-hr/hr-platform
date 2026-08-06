package com.workin.backend.platformadmin;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authenticates a platform administrator by phone/password. There is no
 * self-registration counterpart -- platform-admin accounts are only
 * ever created by {@link PlatformAdminBootstrap} or, in later work, an
 * authenticated admin-management endpoint (not built in this slice).
 */
@Service
public class PlatformAdminLoginService {

	private final PlatformAdminRepository platformAdminRepository;
	private final PasswordEncoder passwordEncoder;

	public PlatformAdminLoginService(PlatformAdminRepository platformAdminRepository, PasswordEncoder passwordEncoder) {
		this.platformAdminRepository = platformAdminRepository;
		this.passwordEncoder = passwordEncoder;
	}

	public PlatformAdmin login(PlatformAdminLoginRequest request) {
		return platformAdminRepository.findByPhone(request.phone())
				.filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
				.filter(PlatformAdmin::isActive)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
	}

}
