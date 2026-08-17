package com.workin.backend.tenancy;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.security.AuthenticatedPrincipal;

/**
 * Demonstrates the full docs/adr/ADR-0010-authorization-model.md
 * Dimension 2 sequence end to end: JwtAuthenticationFilter has already
 * authenticated the identity and extracted the claimed tenant context;
 * this endpoint runs {@link TenantContextService#establishContext} to
 * turn that claim into a validated {@link AuthorizationContext} before
 * returning anything.
 */
@Profile("!phase1-mysql")
@RestController
public class TenantController {

	private final TenantContextService tenantContextService;

	public TenantController(TenantContextService tenantContextService) {
		this.tenantContextService = tenantContextService;
	}

	@AuthenticatedUseCase(reason = "builds and returns the caller's own validated membership context; "
			+ "no catalog permission of its own")
	@GetMapping("/api/tenant/me")
	public MembershipView me() {
		AuthenticatedPrincipal principal = (AuthenticatedPrincipal) SecurityContextHolder.getContext()
				.getAuthentication()
				.getPrincipal();
		AuthorizationContext context = tenantContextService.establishContext(
				principal.identityId(), principal.claimedMembershipId(), principal.claimedCompanyId());
		return new MembershipView(context.membershipId(), context.companyId(), context.roles());
	}

	public record MembershipView(Long membershipId, Long companyId, java.util.List<TenantRole> roles) {
	}

}
