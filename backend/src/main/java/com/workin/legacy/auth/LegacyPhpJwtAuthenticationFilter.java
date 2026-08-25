package com.workin.legacy.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.workin.backend.security.AuthenticatedPrincipal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Authenticates the exact bearer-token shape produced by frozen PHP. */
public class LegacyPhpJwtAuthenticationFilter extends OncePerRequestFilter {

	private final LegacyPhpJwtService jwtService;

	public LegacyPhpJwtAuthenticationFilter(LegacyPhpJwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header != null && header.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
			LegacyPhpJwtService.DecodedToken token = jwtService.decode(header.substring("Bearer ".length()));
			if (token != null) {
				long identityId = "employee".equals(token.type()) ? token.employeeId() : token.companyId();
				long membershipId = "employee".equals(token.type()) ? token.employeeId() : 0L;
				AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
						identityId, membershipId, token.companyId(), token.role(), token.tokenVersion(), token.type());
				Authentication authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
				SecurityContextHolder.getContext().setAuthentication(authentication);
			} else {
				SecurityContextHolder.clearContext();
			}
		}
		filterChain.doFilter(request, response);
	}
}
