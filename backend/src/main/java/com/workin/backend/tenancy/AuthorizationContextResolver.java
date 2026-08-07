package com.workin.backend.tenancy;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.workin.backend.security.AuthenticatedPrincipal;

/**
 * Factors out the principal-extraction + {@link TenantContextService#establishContext}
 * sequence {@link TenantController} demonstrates inline -- every
 * tenant-scoped controller needs this exact sequence at the top of
 * every request, not just the one demonstration endpoint.
 */
@Component
public class AuthorizationContextResolver {

	private final TenantContextService tenantContextService;

	public AuthorizationContextResolver(TenantContextService tenantContextService) {
		this.tenantContextService = tenantContextService;
	}

	public AuthorizationContext resolve() {
		AuthenticatedPrincipal principal = (AuthenticatedPrincipal) SecurityContextHolder.getContext()
				.getAuthentication()
				.getPrincipal();
		return tenantContextService.establishContext(
				principal.identityId(), principal.claimedMembershipId(), principal.claimedCompanyId());
	}

}
