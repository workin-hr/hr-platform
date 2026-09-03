package com.workin.backend.platformadmin.web;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;

import com.workin.backend.platformadmin.PlatformAdminRepository;

/**
 * The cookie-authenticated, CSRF-protected chain for the server-rendered
 * platform-admin UI (ADR-0015).
 *
 * <p><b>This chain exists because the alternative fails open silently.</b>
 * {@code SecurityConfig.tenantSecurityFilterChain()} is the order-3 catch-all:
 * it declares no {@code securityMatcher}, disables CSRF, and authenticates a
 * <em>tenant</em> bearer token. An {@code /admin} mapping that did not land on
 * a chain of its own would not 404 or 403 -- it would be served by that chain,
 * accepting a tenant token and applying no CSRF protection at all. That is why
 * the matcher here is explicit, and why {@code PlatformAdminWebChainCoverageTest}
 * enumerates the handler registry instead of testing a list of routes someone
 * remembered to write down (prerequisite 5).
 *
 * <p>Ordered ahead of every existing chain. It does not overlap them --
 * {@code /admin/**} against {@code /api/platform-admin/**}, {@code /apis/**} and
 * the catch-all -- but ordering it first makes precedence a property of the
 * configuration rather than of the paths happening not to collide.
 */
@Configuration
@Profile("!phase1-mysql")
public class PlatformAdminWebSecurityConfig {

	/** Every route this surface owns. Referenced by the coverage test. */
	public static final String PATH_PREFIX = "/admin";

	public static final String PATH_PATTERN = PATH_PREFIX + "/**";

	public static final String LOGIN_PATH = PATH_PREFIX + "/login";

	public static final String LOGOUT_PATH = PATH_PREFIX + "/logout";

	/**
	 * Idle timeout, mirrored in {@code application.properties} where the
	 * container reads it. ADR-0015 prerequisite 4 requires the number to exist
	 * and be pinned by a test rather than left to a container default.
	 */
	public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

	/**
	 * Non-renewable absolute cap. A session cannot slide past this however
	 * active it is, so a stolen cookie has a bounded worst case that activity
	 * cannot extend. Enforced in {@link PlatformAdminSessionRevalidationFilter},
	 * because a servlet session only knows about idle time.
	 */
	public static final Duration ABSOLUTE_CAP = Duration.ofHours(8);

	@Bean
	@Order(0)
	public SecurityFilterChain platformAdminWebSecurityFilterChain(
			HttpSecurity http, PlatformAdminRepository platformAdminRepository) throws Exception {
		http
			.securityMatcher(PATH_PATTERN)
			// CSRF stays on, deliberately: this chain is cookie-authenticated,
			// which is exactly the exposure the bearer API does not have.
			.csrf(org.springframework.security.config.Customizer.withDefaults())
			.sessionManagement(session -> session
				.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
				// Session fixation: a new id is issued at login, so a cookie
				// planted before authentication is not the one that ends up
				// authenticated.
				.sessionFixation(fixation -> fixation.changeSessionId()))
			// No saved-request replay. The only unauthenticated page is the login
			// form, so there is nothing worth resuming, and a saved request is one
			// more piece of attacker-influenced state carried across the
			// authentication boundary.
			.requestCache(cache -> cache.requestCache(new NullRequestCache()))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(LOGIN_PATH).permitAll()
				.anyRequest().authenticated())
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(new PlatformAdminWebLoginRedirectEntryPoint(LOGIN_PATH)))
			// Prerequisite 9. The bearer chain's PlatformAdminAuthenticationFilter
			// revalidates the admin row on every request, but only after parsing an
			// Authorization header, so a cookie-authenticated request reaches no
			// such check. Without this filter, deactivating an administrator would
			// leave their session working until it expired -- precisely what D-145
			// exists to prevent.
			.addFilterBefore(
				new PlatformAdminSessionRevalidationFilter(platformAdminRepository),
				UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

}
