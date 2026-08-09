package com.workin.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.workin.backend.identity.JwtService;
import com.workin.backend.platformadmin.PlatformAdminJwtService;

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
	 */
	@Bean
	@Order(1)
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

	@Bean
	@Order(2)
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
