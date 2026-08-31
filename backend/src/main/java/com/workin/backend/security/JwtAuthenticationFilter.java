package com.workin.backend.security;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.workin.backend.identity.JwtService;
import com.workin.backend.identity.RefreshTokenRepository;

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
	private final RefreshTokenRepository refreshTokenRepository;

	public JwtAuthenticationFilter(JwtService jwtService, RefreshTokenRepository refreshTokenRepository) {
		this.jwtService = jwtService;
		this.refreshTokenRepository = refreshTokenRepository;
	}

	/**
	 * Whether the session this token belongs to is still live (R-027).
	 *
	 * <p>Logout revokes the refresh <em>family</em>; until this check existed
	 * nothing read that on the request path, so a logged-out access token kept
	 * authenticating until {@code exp} — up to
	 * {@code app.jwt.access-token-ttl-seconds}. On the tenant surface that
	 * covered 58 mutating endpoints, so a user who logged out, or an operator
	 * who logged out a suspected-compromised session, left a token that could
	 * still create, alter and delete payroll and organisational records.
	 *
	 * <p>A token with no {@code sid} is treated as live: tokens minted before
	 * this claim existed must keep working, and the alternative is logging out
	 * every existing session on deploy. That is the one case this check does
	 * not cover, and it ages out with the tokens themselves.
	 */
	private boolean sessionIsLive(Claims claims) {
		String sid = claims.get("sid", String.class);
		if (sid == null || sid.isBlank()) {
			return true;
		}
		try {
			return refreshTokenRepository.familyIsLive(UUID.fromString(sid));
		}
		catch (IllegalArgumentException ex) {
			// A malformed sid is not a session this service issued.
			return false;
		}
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith("Bearer ")) {
			try {
				Claims claims = jwtService.parseAndValidate(header.substring("Bearer ".length()));
				if (sessionIsLive(claims)) {
					Number tokenVersionClaim = claims.get("token_version", Number.class);
					AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
							Long.valueOf(claims.getSubject()),
							claims.get("membership_id", Number.class).longValue(),
							claims.get("tenant_id", Number.class).longValue(),
							claims.get("role", String.class),
							tokenVersionClaim == null ? null : tokenVersionClaim.longValue());
					Authentication authentication = new UsernamePasswordAuthenticationToken(
							principal, null, List.of());
					SecurityContextHolder.getContext().setAuthentication(authentication);
				}
				else {
					// Deliberately no early return and no doFilter() here: a
					// revoked session is handled exactly as an unparseable token
					// is, and the single doFilter() at the end runs the chain
					// once. Calling it inside this try would let a downstream
					// JwtException or IllegalArgumentException land in the catch
					// below and then fall through to the doFilter() at the end,
					// running the whole chain and its controller twice -- the
					// defect independent review found in
					// PlatformAdminAuthenticationFilter, which had the same shape.
					SecurityContextHolder.clearContext();
				}
			} catch (JwtException | IllegalArgumentException ex) {
				SecurityContextHolder.clearContext();
			}
		}
		filterChain.doFilter(request, response);
	}

}
