package com.workin.legacy.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.workin.backend.identity.JwtService;
import com.workin.backend.security.AuthenticatedPrincipal;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Phase-1 compatibility authentication. Frozen PHP tokens are authoritative.
 * A fallback parser for the pre-retrofit Java token remains temporarily so
 * older internal /api/legacy tests and aliases do not hide regressions while
 * Wave 12.R is being retired; unchanged real clients use the PHP branch.
 */
public class LegacyPhpJwtAuthenticationFilter extends OncePerRequestFilter {

	private final LegacyPhpJwtService legacyJwtService;
	private final JwtService transitionalJwtService;

	public LegacyPhpJwtAuthenticationFilter(
			LegacyPhpJwtService legacyJwtService, JwtService transitionalJwtService) {
		this.legacyJwtService = legacyJwtService;
		this.transitionalJwtService = transitionalJwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header != null && header.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
			String raw = header.substring("Bearer ".length());
			LegacyPhpJwtService.DecodedToken token = legacyJwtService.decode(raw);
			if (token != null) {
				setPhpAuthentication(token);
			} else {
				setTransitionalAuthentication(raw);
			}
		}
		filterChain.doFilter(request, response);
	}

	private static void setPhpAuthentication(LegacyPhpJwtService.DecodedToken token) {
		long identityId = "employee".equals(token.type()) ? token.employeeId() : token.companyId();
		long membershipId = "employee".equals(token.type()) ? token.employeeId() : 0L;
		AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
				identityId, membershipId, token.companyId(), token.role(), token.tokenVersion(), token.type());
		setAuthentication(principal);
	}

	private void setTransitionalAuthentication(String raw) {
		try {
			Claims claims = transitionalJwtService.parseAndValidate(raw);
			Number tokenVersionClaim = claims.get("token_version", Number.class);
			AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
					Long.valueOf(claims.getSubject()),
					claims.get("membership_id", Number.class).longValue(),
					claims.get("tenant_id", Number.class).longValue(),
					claims.get("role", String.class),
					tokenVersionClaim == null ? null : tokenVersionClaim.longValue());
			setAuthentication(principal);
		} catch (JwtException | IllegalArgumentException | NullPointerException ex) {
			SecurityContextHolder.clearContext();
		}
	}

	private static void setAuthentication(AuthenticatedPrincipal principal) {
		Authentication authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}
}
