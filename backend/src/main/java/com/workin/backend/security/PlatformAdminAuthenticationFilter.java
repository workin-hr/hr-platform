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

import com.workin.backend.platformadmin.PlatformAdmin;
import com.workin.backend.platformadmin.PlatformAdminJwtService;
import com.workin.backend.platformadmin.PlatformAdminRefreshTokenRepository;
import com.workin.backend.platformadmin.PlatformAdminRepository;

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
 *
 * <p><b>Deactivation is checked here, on every request</b> (R-026). A valid
 * signature is not sufficient: the {@code platform_admins} row is loaded and
 * {@code active} verified before the principal is set. Without that, a
 * deactivated administrator kept full access until their access token expired
 * -- up to {@code app.platform-admin.jwt.access-token-ttl-seconds} (900s), via
 * the exact control an operator reaches for when someone must lose access
 * immediately.
 *
 * <p>Scoped honestly: the only authenticated route on this surface today is
 * {@code GET /api/platform-admin/me}, so what was realised was continued
 * identity disclosure, not continued destructive capability. The operations
 * this surface exists for -- company suspension and deletion (ADR-0009 Option
 * E) -- are not built. The defect mattered because the first such endpoint
 * would have inherited it silently.
 *
 * <p>{@link com.workin.backend.platformadmin.PlatformAdminSessionService}
 * already refuses to rotate a deactivated admin's refresh token, which bounded
 * the window but did not close it: rotation happens at most every 15 minutes,
 * and the live access token keeps working until then.
 *
 * <p>The cost is one indexed primary-key lookup per platform-admin request,
 * which is the trade ADR-0010 already makes deliberately for tenant routes:
 * immediate revocation over cached authorization state.
 */
public class PlatformAdminAuthenticationFilter extends OncePerRequestFilter {

	private final PlatformAdminJwtService platformAdminJwtService;
	private final PlatformAdminRepository platformAdminRepository;
	private final PlatformAdminRefreshTokenRepository platformAdminRefreshTokenRepository;

	public PlatformAdminAuthenticationFilter(
			PlatformAdminJwtService platformAdminJwtService,
			PlatformAdminRepository platformAdminRepository,
			PlatformAdminRefreshTokenRepository platformAdminRefreshTokenRepository) {
		this.platformAdminJwtService = platformAdminJwtService;
		this.platformAdminRepository = platformAdminRepository;
		this.platformAdminRefreshTokenRepository = platformAdminRefreshTokenRepository;
	}

	/**
	 * Whether the session this token belongs to is still live (R-027).
	 *
	 * <p>A token with no {@code sid} is treated as live: tokens minted before
	 * the claim existed must keep working, and the alternative is logging out
	 * every session on deploy. That gap ages out with the tokens themselves.
	 */
	private boolean sessionIsLive(Claims claims) {
		String sid = claims.get("sid", String.class);
		if (sid == null || sid.isBlank()) {
			return true;
		}
		try {
			return platformAdminRefreshTokenRepository.familyIsLive(UUID.fromString(sid));
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
				Claims claims = platformAdminJwtService.parseAndValidate(header.substring("Bearer ".length()));
				long platformAdminId = Long.parseLong(claims.getSubject());
				// Fail closed: a missing row is as unauthenticated as an inactive
				// one. Not a reachable path today -- platform_admin_audit_events
				// holds a NOT NULL FK to this table (F-26), so an admin with any
				// recorded action cannot be deleted -- but orElse(false) is the
				// only correct default for an authentication decision.
				boolean active = platformAdminRepository.findById(platformAdminId)
						.map(PlatformAdmin::isActive)
						.orElse(false);
				// Both halves of R-026/R-027: the admin must still be active
				// *and* the session must not have been logged out.
				if (active && sessionIsLive(claims)) {
					AuthenticatedPlatformAdminPrincipal principal =
							new AuthenticatedPlatformAdminPrincipal(platformAdminId);
					Authentication authentication = new UsernamePasswordAuthenticationToken(
							principal, null, List.of());
					SecurityContextHolder.getContext().setAuthentication(authentication);
				}
				else {
					// Deliberately no early return, and no doFilter() call here.
					// An inactive admin is handled the same way an unparseable
					// token is: the context stays clear and the single
					// doFilter() below runs the chain once.
					//
					// An earlier version called filterChain.doFilter() inside
					// this try and returned. On a permitAll route -- login,
					// refresh, logout -- the chain really does continue, so a
					// downstream JwtException or IllegalArgumentException landed
					// in the catch below and execution then fell through to the
					// doFilter() at the end: the whole downstream chain and its
					// controller ran a second time.
					SecurityContextHolder.clearContext();
				}
			} catch (JwtException | IllegalArgumentException ex) {
				SecurityContextHolder.clearContext();
			}
		}
		filterChain.doFilter(request, response);
	}

}
