package com.workin.backend.platformadmin.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Sends an unauthenticated browser to the login page instead of a 401 body.
 *
 * <p>The redirect target is a fixed, server-side constant, never anything
 * derived from the request: a "come back to where you were" parameter on an
 * authentication boundary is an open-redirect waiting to be found, and this
 * surface has one page worth returning to.
 */
class PlatformAdminWebLoginRedirectEntryPoint implements AuthenticationEntryPoint {

	private final String loginPath;

	PlatformAdminWebLoginRedirectEntryPoint(String loginPath) {
		this.loginPath = loginPath;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		response.sendRedirect(request.getContextPath() + this.loginPath);
	}

}
