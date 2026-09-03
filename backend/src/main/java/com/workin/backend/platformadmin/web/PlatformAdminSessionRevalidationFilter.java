package com.workin.backend.platformadmin.web;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.workin.backend.platformadmin.PlatformAdmin;
import com.workin.backend.platformadmin.PlatformAdminRepository;

/**
 * Revalidates the authenticated administrator on every request of the cookie
 * chain, and enforces the session's absolute cap.
 *
 * <p>ADR-0015 prerequisite 9 exists because this is <em>not</em> inherited.
 * {@code PlatformAdminAuthenticationFilter} performs the same lookup, but it is
 * installed only on the stateless {@code /api/platform-admin/**} chain and does
 * its work only after parsing an {@code Authorization: Bearer} header. A
 * cookie-authenticated request never reaches it. Without this filter,
 * deactivating an administrator would leave their existing session working
 * until it expired -- the exact window D-145 closed for the bearer surface.
 *
 * <p>The absolute cap lives here too, because a servlet session only understands
 * idle time: {@code setMaxInactiveInterval} slides forward on every request, so
 * an active session never ages out. The creation instant is recorded once at
 * login and compared on each request, which makes the cap non-renewable by
 * construction rather than by the caller remembering not to extend it.
 *
 * <p>Both failures invalidate the session rather than merely clearing the
 * security context. A session that survives its own rejection is a session that
 * gets tried again.
 */
class PlatformAdminSessionRevalidationFilter extends OncePerRequestFilter {

	/** Session attribute holding the login instant, for the absolute cap. */
	static final String ESTABLISHED_AT = PlatformAdminSessionRevalidationFilter.class.getName() + ".establishedAt";

	private final PlatformAdminRepository platformAdminRepository;

	PlatformAdminSessionRevalidationFilter(PlatformAdminRepository platformAdminRepository) {
		this.platformAdminRepository = platformAdminRepository;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null
				&& authentication.getPrincipal() instanceof PlatformAdminWebPrincipal principal) {
			if (!stillValid(request, principal)) {
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
				SecurityContextHolder.clearContext();
			}
		}
		filterChain.doFilter(request, response);
	}

	private boolean stillValid(HttpServletRequest request, PlatformAdminWebPrincipal principal) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return false;
		}
		if (capExceeded(session)) {
			return false;
		}
		// Fail closed: a missing row is as unauthenticated as an inactive one.
		return this.platformAdminRepository.findById(principal.platformAdminId())
				.map(PlatformAdmin::isActive)
				.orElse(false);
	}

	private boolean capExceeded(HttpSession session) {
		Object establishedAt = session.getAttribute(ESTABLISHED_AT);
		if (!(establishedAt instanceof Long epochMilli)) {
			// A session carrying no establishment stamp cannot be shown to be
			// within the cap, so it is not treated as within it.
			return true;
		}
		Duration age = Duration.between(Instant.ofEpochMilli(epochMilli), Instant.now());
		return age.compareTo(PlatformAdminWebSecurityConfig.ABSOLUTE_CAP) >= 0;
	}

}
