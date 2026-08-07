package com.workin.backend.platformadmin;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.security.AuthenticatedPlatformAdminPrincipal;

/**
 * Proves the platform-admin identity/session chain end to end. There is
 * no real {@code platform.*} business functionality yet (approving/
 * suspending/deleting a company, etc.) -- that is separate follow-up
 * work, tracked independently of this identity substrate
 * (docs/migration/consolidated-task-matrix.md F-26).
 */
@RestController
@RequestMapping("/api/platform-admin")
public class PlatformAdminController {

	private final PlatformAdminRepository platformAdminRepository;

	public PlatformAdminController(PlatformAdminRepository platformAdminRepository) {
		this.platformAdminRepository = platformAdminRepository;
	}

	@AuthenticatedUseCase(reason = "returns the authenticated admin's own record; no platform.* capability involved")
	@GetMapping("/me")
	public PlatformAdminView me() {
		AuthenticatedPlatformAdminPrincipal principal = (AuthenticatedPlatformAdminPrincipal) SecurityContextHolder
				.getContext()
				.getAuthentication()
				.getPrincipal();
		PlatformAdmin platformAdmin = platformAdminRepository.findById(principal.platformAdminId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return new PlatformAdminView(platformAdmin.getId(), platformAdmin.getPhone());
	}

	public record PlatformAdminView(Long id, String phone) {
	}

}
