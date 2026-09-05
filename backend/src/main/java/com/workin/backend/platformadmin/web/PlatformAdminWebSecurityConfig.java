package com.workin.backend.platformadmin.web;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * <p><b>Active under both profiles.</b> Legacy has a platform admin web of its
 * own (`dashboard/pages/companies/`), so the MySQL deployment shape needs one
 * too. What is deliberately not carried over is how legacy authenticates it:
 * `doAdminLogin()` checks a single shared password held in a config constant
 * (`hr-legacy#11`), which F-26/D-027 rejected. The identity model here is the
 * same on either database; only the schema the entities map to differs.
 *
 * <p>Ordered ahead of every existing chain. It does not overlap them --
 * {@code /admin/**} against {@code /api/platform-admin/**}, {@code /apis/**} and
 * the catch-all -- but ordering it first makes precedence a property of the
 * configuration rather than of the paths happening not to collide.
 */
@Configuration
public class PlatformAdminWebSecurityConfig {

	/** Every route this surface owns. Referenced by the coverage test. */
	public static final String PATH_PREFIX = "/admin";

	public static final String PATH_PATTERN = PATH_PREFIX + "/**";

	public static final String LOGIN_PATH = PATH_PREFIX + "/login";

	public static final String LOGOUT_PATH = PATH_PREFIX + "/logout";

	/**
	 * The second-factor challenge. Reachable without an authenticated session on
	 * purpose: at this point the password has passed but no security context
	 * exists yet, so the route has to be permitted here and gated on the
	 * session's pending marker in the controller instead. Anything else would
	 * mean granting a context before the second factor, which is the thing the
	 * factor exists to prevent.
	 */
	public static final String MFA_PATH = PATH_PREFIX + "/mfa";

	/** D-152's enrolment ceremony: password and bootstrap token, no session yet. */
	public static final String ENROL_PATH = PATH_PREFIX + "/enrol";

	/**
	 * The ceremony's second step. Named separately because the matcher below is
	 * exact: {@code /admin/enrol} does not cover {@code /admin/enrol/confirm},
	 * and the omission was invisible in testing -- an unpermitted route lands on
	 * the entry point, which redirects to the login page, which is also where a
	 * successful confirmation goes. The test now asserts the factor is bound
	 * rather than trusting the destination.
	 */
	public static final String ENROL_CONFIRM_PATH = ENROL_PATH + "/confirm";

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

	/** Individual session listing and revocation (ADR-0015 prerequisite 13). */
	public static final String SESSIONS_PATH = PATH_PREFIX + "/sessions";

	public static final String SESSIONS_REVOKE_PATH = SESSIONS_PATH + "/revoke";

	/** Platform administration of companies (ADR-0009 Option E). */
	public static final String COMPANIES_PATH = PATH_PREFIX + "/companies";

	public static final String COMPANIES_CONFIRM_PATH = COMPANIES_PATH + "/confirm";

	/**
	 * One company's detail page, {@code /admin/companies/{id}}.
	 *
	 * <p>Its pattern would also match {@code /admin/companies/confirm} and
	 * {@code /admin/companies/apply}, which is harmless -- all three are
	 * authenticated, and Spring resolves the literal mappings ahead of the
	 * variable one. Worth naming because the reverse (a literal shadowed by a
	 * variable) is the mistake this shape usually produces.
	 */
	public static final String COMPANY_DETAIL_PATH = COMPANIES_PATH + "/{companyId}";

	public static final String COMPANIES_APPLY_PATH = COMPANIES_PATH + "/apply";

	/**
	 * Platform content the clients read but cannot write -- dial codes first
	 * (ADR-0016). Authenticated like every other page here; the write side is
	 * gated again in the service by the surface flag and a bound second
	 * factor.
	 */
	public static final String PHONE_COUNTRIES_PATH = PATH_PREFIX + "/phone_countries";

	public static final String FAQS_PATH = PATH_PREFIX + "/faqs";

	public static final String BANNERS_PATH = PATH_PREFIX + "/banners";

	public static final String NOTIFICATIONS_PATH = PATH_PREFIX + "/notifications";

	/**
	 * The org pages. Each is one company's own data, reachable by the
	 * administrator across companies through the session filter -- the
	 * cross-tenant mode <b>R-044</b> covers.
	 */
	public static final String BRANCHES_PATH = PATH_PREFIX + "/branches";

	public static final String DEPARTMENTS_PATH = PATH_PREFIX + "/departments";

	public static final String JOB_TITLES_PATH = PATH_PREFIX + "/job_titles";

	public static final String SHIFTS_PATH = PATH_PREFIX + "/shifts";

	public static final String LEAVE_BALANCES_PATH = PATH_PREFIX + "/leave_balances";

	public static final String REQUESTS_PATH = PATH_PREFIX + "/requests";

	public static final String PENALTIES_PATH = PATH_PREFIX + "/penalties";

	public static final String ASSETS_PATH = PATH_PREFIX + "/assets";

	/**
	 * Every route on this surface that is reachable without authentication.
	 *
	 * <p>A named constant so it can be checked against the handlers' own
	 * {@code @PublicUseCase} declarations. {@code SecurityPolicyAgreementTest}
	 * asserts the two agree in both directions, which is the only reliable guard
	 * against the failure this list already had once: {@code /admin/enrol/confirm}
	 * was declared public and omitted here, and an omitted route does not 404 --
	 * it lands on the entry point and redirects to the login page, which is also
	 * where a successful confirmation goes. The test that should have caught it
	 * passed.
	 */
	public static final String[] PUBLIC_PATHS = {
		LOGIN_PATH, MFA_PATH, ENROL_PATH, ENROL_CONFIRM_PATH,
	};

	/**
	 * The stylesheets and scripts the admin pages load, served from
	 * <p>The prefix is {@code /admin/_assets/**}, with the underscore, and that
	 * is load-bearing. It was {@code /admin/assets/**} until the dashboard's own
	 * {@code assets} page was ported: Spring's {@code /**} matches zero segments,
	 * so {@code /admin/assets} matched the permitAll rule and the page answered
	 * without a session. A leading underscore cannot be a page name -- they come
	 * from {@code dashboard/pages/*} -- so this closes the collision for every
	 * future page rather than for that one.
	 *
	 * <p>Served from
	 * {@code classpath:/static/admin/assets/} and copied from the PHP
	 * dashboard so the two look the same (ADR-0016).
	 *
	 * <p>Deliberately <b>not</b> in {@link #PUBLIC_PATHS}: that list is
	 * handler routes, checked against their own {@code @PublicUseCase}
	 * declarations in both directions, and a pattern with no handler behind
	 * it would read there as a stale entry -- the exact signal that list
	 * exists to raise.
	 *
	 * <p>Why a public prefix under {@code /admin} at all, when this chain's
	 * whole point is that nothing here is reachable unauthenticated: a
	 * stylesheet is not a secret, and the alternative -- the previous
	 * layout's several hundred lines of inlined CSS -- does not scale to the
	 * dashboard's copied 2,500. The exposure is bounded by there being no
	 * handler under the prefix: it resolves against the static resource
	 * classpath only, and Spring's firewall rejects a traversal attempt
	 * before matching. {@code PlatformAdminAssetsExposureTest} holds both
	 * halves of that.
	 */
	public static final String ASSETS_PATTERN = PATH_PREFIX + "/_assets/**";

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
				.requestMatchers(ASSETS_PATTERN).permitAll()
				.requestMatchers(PUBLIC_PATHS).permitAll()
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
