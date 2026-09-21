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
 * <p>ADR-0015 prerequisite 9: the session holds an identifier to reload by,
 * never a cached decision. Without this filter, deactivating the administrator
 * would leave an existing session working until it expired.
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
 *
 * <p>It does not run for {@code /admin/_assets/**}. The chain's matcher is
 * {@code /admin/**}, so it used to: every stylesheet, script and image on a
 * page cost a {@code findById} against the legacy database, and a page pulls
 * about twenty. Measured against a database 106 ms away, that is over two
 * seconds of a page's wait, and it bought nothing -- the assets are
 * {@code permitAll} and hold no session-dependent content, so refusing to
 * serve one to a deactivated administrator protects nothing the next page
 * request does not. Skipping is the safe direction only because it is the
 * *filter* being skipped, not an authorization rule: an asset request cannot
 * reach a controller, and the first real request after it revalidates as
 * before.
 */
class PlatformAdminSessionRevalidationFilter extends OncePerRequestFilter {

	/** Session attribute holding the login instant, for the absolute cap. */
	static final String ESTABLISHED_AT = PlatformAdminSessionRevalidationFilter.class.getName() + ".establishedAt";

	private final PlatformAdminRepository platformAdminRepository;

	PlatformAdminSessionRevalidationFilter(PlatformAdminRepository platformAdminRepository) {
		this.platformAdminRepository = platformAdminRepository;
	}

	/**
	 * Whether this request is for a static asset, decided on the raw path.
	 *
	 * <p>Anything the path could still mean something else -- a traversal
	 * segment, a double slash, any percent-encoding -- is filtered rather than
	 * skipped, so the decision does not depend on the firewall in front of it
	 * having normalised the URI first. Running the filter is always the safe
	 * answer; skipping it is the one that has to be earned.
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return path.startsWith(PlatformAdminWebSecurityConfig.ASSETS_PREFIX)
				&& path.indexOf('%') < 0
				&& !path.contains("//")
				&& !path.contains("..");
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
