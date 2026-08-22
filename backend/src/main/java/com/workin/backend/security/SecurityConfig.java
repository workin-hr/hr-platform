package com.workin.backend.security;

import java.util.Optional;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.workin.backend.identity.JwtService;
import com.workin.backend.platformadmin.PlatformAdminJwtService;
import com.workin.backend.tenancy.NoTenantScopeException;
import com.workin.backend.tenancy.TenantScope;
import com.workin.backend.tenancy.TenantScopeFilter;
import com.workin.legacy.auth.LegacyTenantContextService;
import com.workin.legacy.wire.LegacyPhpRoutes;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/**
	 * Evaluated before {@link #tenantSecurityFilterChain} (lower
	 * {@code @Order} wins) and matched only to
	 * {@code /api/platform-admin/**} -- a request under that path is
	 * fully handled here and never reaches the tenant chain's
	 * {@link JwtAuthenticationFilter} at all, and a tenant-identity
	 * token is never even parsed against it. This is what makes the
	 * platform/tenant domain separation
	 * (docs/architecture/authorization-model.md §8) structural rather
	 * than a runtime check.
	 *
	 * <p>{@code @Profile("!phase1-mysql")}: not pulled into the legacy
	 * profile merely because the current PostgreSQL application has it
	 * (ADR-0013 amendment 4) -- {@link PlatformAdminJwtService} lives in
	 * the profile-excluded {@code platformadmin} package, and no
	 * discovery has shown a legacy PHP platform-admin contract Phase 1
	 * must reproduce. Frozen with the new-platform model until that
	 * changes.
	 */
	@Bean
	@Order(1)
	@Profile("!phase1-mysql")
	public SecurityFilterChain platformAdminSecurityFilterChain(
			HttpSecurity http, PlatformAdminJwtService platformAdminJwtService,
			ApiSecurityErrorHandler apiSecurityErrorHandler) throws Exception {
		http
			.securityMatcher("/api/platform-admin/**")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(apiSecurityErrorHandler)
				.accessDeniedHandler(apiSecurityErrorHandler))
			.authorizeHttpRequests(authorize -> authorize
				// Refresh and logout authenticate by refresh-token
				// possession -- the access token may already be expired
				// when they are called.
				.requestMatchers("/api/platform-admin/login", "/api/platform-admin/refresh",
						"/api/platform-admin/logout").permitAll()
				.anyRequest().authenticated())
			.addFilterBefore(
				new PlatformAdminAuthenticationFilter(platformAdminJwtService), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	/**
	 * The {@code phase1-mysql} chain (ADR-0013 Decision §7 / punch-list
	 * item #9): matched only to {@code /api/legacy/**}, so a legacy
	 * request never reaches {@link #tenantSecurityFilterChain} and a
	 * PostgreSQL-identity token is never parsed against it, the same
	 * structural separation the platform-admin chain already
	 * established.
	 *
	 * <p>{@link JwtAuthenticationFilter} is reused unchanged -- the JWT
	 * shape is identical for a legacy identity (D-042: {@code identityId}
	 * and {@code membershipId} both carry the legacy employee id,
	 * {@code companyId} the resolved company). What differs is what
	 * happens to those claims next: {@link TenantScopeFilter}'s resolver
	 * here re-derives and cross-checks them via
	 * {@link LegacyTenantContextService#validate} rather than trusting
	 * them, per {@code TenantScopeFilter}'s own contract that the
	 * resolver -- not the filter -- is responsible for that trust
	 * boundary. A resolver that just read the claim and entered it
	 * directly would satisfy the type signature while reintroducing the
	 * exact trust bug {@code TenantContextIsolationTest} exists to catch
	 * on the PostgreSQL side (punch-list item #10, not yet built).
	 *
	 * <p>A mismatch or missing authentication resolves to
	 * {@code Optional.empty()}, never a thrown exception from the
	 * resolver itself -- {@code TenantScopeFilter} then simply does not
	 * establish scope, and any tenant-scoped read downstream fails
	 * closed via {@code TenantScope.current()} instead. The filter never
	 * needs to reject a request on this account.
	 */
	@Bean
	@Order(2)
	@Profile("phase1-mysql")
	public SecurityFilterChain legacySecurityFilterChain(
			HttpSecurity http, JwtService jwtService, TenantScope tenantScope,
			LegacyTenantContextService legacyTenantContextService,
			ApiSecurityErrorHandler apiSecurityErrorHandler) throws Exception {
		Function<HttpServletRequest, Optional<Long>> resolver = request -> {
			if (!(SecurityContextHolder.getContext().getAuthentication() != null
					&& SecurityContextHolder.getContext().getAuthentication().getPrincipal()
							instanceof AuthenticatedPrincipal principal)) {
				return Optional.empty();
			}
			try {
				return Optional.of(legacyTenantContextService.validate(
						principal.identityId(), principal.claimedMembershipId(), principal.claimedCompanyId()));
			} catch (NoTenantScopeException ex) {
				return Optional.empty();
			}
		};
		http
			// "/apis/**" is legacy's own URL surface (D-021/ADR-0003), which
			// D-074 makes authoritative for Wave 12.4 onwards; "/api/legacy/**"
			// stays matched for the modules merged before that decision.
			.securityMatcher("/api/legacy/**", "/apis/**")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(apiSecurityErrorHandler)
				.accessDeniedHandler(apiSecurityErrorHandler))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/error").permitAll()
				.requestMatchers("/api/legacy/auth/login_employee").permitAll()
				// Legacy checks the HTTP method before authenticating, so a
				// wrong-method request with no token is a 405 in PHP, not a
				// 401. Ending these routes at .authenticated() would reorder
				// that and answer in the platform error body instead of the
				// PHP envelope (D-074). LegacyPhpRoutes documents the
				// convention and the coverage test that keeps it honest: the
				// listed prefixes authenticate inside the controller, via
				// LegacyRequestGuard, exactly where PHP does. Everything else
				// under /apis/** still falls through to .authenticated().
				.requestMatchers(LegacyPhpRoutes.CONTROLLER_GUARDED).permitAll()
				.anyRequest().authenticated())
			.addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
			.addFilterAfter(new TenantScopeFilter(tenantScope, resolver), JwtAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	@Order(3)
	@Profile("!phase1-mysql")
	public SecurityFilterChain tenantSecurityFilterChain(
			HttpSecurity http, JwtService jwtService,
			ApiSecurityErrorHandler apiSecurityErrorHandler) throws Exception {
		http
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			// Issue #70: without these, an expired token is answered by
			// Spring Boot's default error JSON instead of {code, message}.
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(apiSecurityErrorHandler)
				.accessDeniedHandler(apiSecurityErrorHandler))
			.authorizeHttpRequests(authorize -> authorize
				// A ResponseStatusException (e.g. 409/401 thrown from a
				// controller) triggers an internal servlet ERROR
				// dispatch to /error, which re-enters this same filter
				// chain -- without this, that re-entry falls through to
				// .anyRequest().authenticated() and masks the real
				// status code behind a generic 403.
				.requestMatchers("/error").permitAll()
				.requestMatchers("/api/auth/**", "/actuator/health").permitAll()
				.anyRequest().authenticated())
			.addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

}
