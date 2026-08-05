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

import com.workin.backend.identity.JwtService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

/**
 * Authenticates the identity (docs/adr/ADR-0010-authorization-model.md
 * Dimension 2, step 1) and extracts the tenant-context claims for step
 * 2. Deliberately does <em>not</em> validate tenant membership itself --
 * that is TenantContextService's job, run explicitly by whichever
 * controller needs a tenant context, not implicitly by every request.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtService jwtService;

	public JwtAuthenticationFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith("Bearer ")) {
			try {
				Claims claims = jwtService.parseAndValidate(header.substring("Bearer ".length()));
				AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
						Long.valueOf(claims.getSubject()),
						claims.get("membership_id", Number.class).longValue(),
						claims.get("tenant_id", Number.class).longValue());
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
