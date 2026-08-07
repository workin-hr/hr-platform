package com.workin.backend.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.workin.backend.platformadmin.PlatformAdminJwtService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

/**
 * Authenticates a platform-admin request. Parallel to
 * {@link JwtAuthenticationFilter}, but wired into its own
 * {@code SecurityFilterChain} (see {@code SecurityConfig}) matched only
 * to {@code /api/platform-admin/**} -- a tenant-identity token can
 * never reach this filter, and this filter never runs for tenant
 * routes, regardless of any claim content. Structural separation, not
 * an {@code if} check.
 */
public class PlatformAdminAuthenticationFilter extends OncePerRequestFilter {

	private final PlatformAdminJwtService platformAdminJwtService;

	public PlatformAdminAuthenticationFilter(PlatformAdminJwtService platformAdminJwtService) {
		this.platformAdminJwtService = platformAdminJwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith("Bearer ")) {
			try {
				Claims claims = platformAdminJwtService.parseAndValidate(header.substring("Bearer ".length()));
				AuthenticatedPlatformAdminPrincipal principal = new AuthenticatedPlatformAdminPrincipal(
						Long.valueOf(claims.getSubject()));
				Authentication authentication = new UsernamePasswordAuthenticationToken(
						principal, null, List.of());
				SecurityContextHolder.getContext().setAuthentication(authentication);
			} catch (JwtException | IllegalArgumentException ex) {
				SecurityContextHolder.clearContext();
			}
		}
		filterChain.doFilter(request, response);
	}

}
