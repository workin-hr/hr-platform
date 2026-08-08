package com.workin.backend.authorization;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import tools.jackson.databind.ObjectMapper;

import com.workin.backend.i18n.ApiErrorBody;
import com.workin.backend.i18n.Messages;
import com.workin.backend.i18n.MessageKeys;
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
	private final Messages messages;
	private final ObjectMapper objectMapper;

	public AuthorizationPolicyInterceptor(
			TenantContextService tenantContextService, Messages messages, ObjectMapper objectMapper) {
		this.tenantContextService = tenantContextService;
		this.messages = messages;
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws IOException {
		if (!(handler instanceof HandlerMethod handlerMethod)) {
			return true;
		}
		RequiresPermission required = handlerMethod.getMethodAnnotation(RequiresPermission.class);
		if (required == null) {
			return true;
		}

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
			return reject(response);
		}

		try {
			AuthorizationContext context = tenantContextService.establishContext(
					principal.identityId(), principal.claimedMembershipId(), principal.claimedCompanyId());
			if (!context.hasPermission(required.value())) {
				return reject(response);
			}
			request.setAttribute(AuthorizationContext.class.getName(), context);
			return true;
		} catch (TenantContextException ex) {
			return reject(response);
		}
	}

	/** Interceptor rejections bypass the advice, so the body is written here. */
	private boolean reject(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write(objectMapper.writeValueAsString(
				new ApiErrorBody(MessageKeys.ERROR_FORBIDDEN, messages.get(MessageKeys.ERROR_FORBIDDEN))));
		return false;
	}

}
