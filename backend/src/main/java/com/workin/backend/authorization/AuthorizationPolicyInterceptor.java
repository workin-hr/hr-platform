package com.workin.backend.authorization;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.workin.backend.security.AuthenticatedPrincipal;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantContextException;
import com.workin.backend.tenancy.TenantContextService;

/**
 * Runtime enforcement of {@link RequiresPermission} (ADR-0010 Dimension
 * 4's authoritative business-authorization layer; the component whose
 * arrival unfreezes F-23's tripwire). For a gated handler: the
 * principal must be a tenant-domain {@link AuthenticatedPrincipal}, the
 * membership is re-validated fail-closed through
 * {@link TenantContextService#establishContext} (a disabled membership
 * or identity/tenant mismatch never reaches evaluation), and the
 * effective permission set decides. Every denial is a bare 403 --
 * cross-tenant/permission denials never reveal detail (§8).
 *
 * <p>On success the validated {@link AuthorizationContext} is stashed
 * as a request attribute (keyed by the class name) so the handler can
 * reuse it instead of re-establishing -- Dimension 5's permitted
 * request-local memoization, and nothing more.
 */
@Component
public class AuthorizationPolicyInterceptor implements HandlerInterceptor {

	private final TenantContextService tenantContextService;

	public AuthorizationPolicyInterceptor(TenantContextService tenantContextService) {
		this.tenantContextService = tenantContextService;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod handlerMethod)) {
			return true;
		}
		RequiresPermission required = handlerMethod.getMethodAnnotation(RequiresPermission.class);
		if (required == null) {
			return true;
		}

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			return false;
		}

		try {
			AuthorizationContext context = tenantContextService.establishContext(
					principal.identityId(), principal.claimedMembershipId(), principal.claimedCompanyId());
			if (!context.hasPermission(required.value())) {
				response.setStatus(HttpServletResponse.SC_FORBIDDEN);
				return false;
			}
			request.setAttribute(AuthorizationContext.class.getName(), context);
			return true;
		} catch (TenantContextException ex) {
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			return false;
		}
	}

}
