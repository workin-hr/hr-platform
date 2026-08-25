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
import com.workin.legacy.auth.LegacyPhpJwtAuthenticationFilter;
import com.workin.legacy.auth.LegacyPhpJwtService;
import com.workin.legacy.auth.LegacyTenantContextService;
import com.workin.legacy.wire.LegacyPhpRoutes;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

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
				.requestMatchers("/api/platform-admin/login", "/api/platform-admin/refresh",
						"/api/platform-admin/logout").permitAll()
				.anyRequest().authenticated())
			.addFilterBefore(
				new PlatformAdminAuthenticationFilter(platformAdminJwtService), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	/**
	 * Phase-1 compatibility chain. Literal /apis/** requests authenticate with
	 * the exact JWT format produced by frozen PHP, not the new-platform token
	 * format. This is required for a backend-only cutover: already logged-in
	 * mobile/desktop clients must continue working without re-login or code
	 * changes.
	 *
	 * <p>Employee tokens are still re-derived against the employee row before a
	 * tenant scope is established. Company tokens have no employee/membership
	 * claim in PHP; PHP trusts their signed company_id directly, so Phase 1 does
	 * the same and leaves company-active checks at the same controller guard
	 * points as the source application.
	 */
	@Bean
	@Order(2)
	@Profile("phase1-mysql")
	public SecurityFilterChain legacySecurityFilterChain(
			HttpSecurity http, LegacyPhpJwtService legacyPhpJwtService, TenantScope tenantScope,
			LegacyTenantContextService legacyTenantContextService,
			ApiSecurityErrorHandler apiSecurityErrorHandler) throws Exception {
		Function<HttpServletRequest, Optional<Long>> resolver = request -> {
			if (!(SecurityContextHolder.getContext().getAuthentication() != null
					&& SecurityContextHolder.getContext().getAuthentication().getPrincipal()
							instanceof AuthenticatedPrincipal principal)) {
				return Optional.empty();
			}
			if ("company".equals(principal.legacyAuthType())) {
				return principal.claimedCompanyId() != null && principal.claimedCompanyId() > 0
						? Optional.of(principal.claimedCompanyId()) : Optional.empty();
			}
			try {
				return Optional.of(legacyTenantContextService.validate(
						principal.identityId(), principal.claimedMembershipId(), principal.claimedCompanyId()));
			} catch (NoTenantScopeException | NullPointerException ex) {
				return Optional.empty();
			}
		};
		http
			.securityMatcher("/api/legacy/**", "/apis/**")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(apiSecurityErrorHandler)
				.accessDeniedHandler(apiSecurityErrorHandler))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/error").permitAll()
				.requestMatchers("/api/legacy/auth/login_employee").permitAll()
				// PHP checks method/body before auth on the D-074 literal routes;
				// controllers therefore own requireAuth() in the same order.
				.requestMatchers(LegacyPhpRoutes.CONTROLLER_GUARDED).permitAll()
				.anyRequest().authenticated())
			.addFilterBefore(
					new LegacyPhpJwtAuthenticationFilter(legacyPhpJwtService), UsernamePasswordAuthenticationFilter.class)
			.addFilterAfter(new TenantScopeFilter(tenantScope, resolver), LegacyPhpJwtAuthenticationFilter.class);
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
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(apiSecurityErrorHandler)
				.accessDeniedHandler(apiSecurityErrorHandler))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/error").permitAll()
				.requestMatchers("/api/auth/**", "/actuator/health").permitAll()
				.anyRequest().authenticated())
			.addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}
}
