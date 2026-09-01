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
import com.workin.backend.identity.RefreshTokenRepository;
import com.workin.backend.platformadmin.PlatformAdminJwtService;
import com.workin.backend.platformadmin.PlatformAdminRefreshTokenRepository;
import com.workin.backend.platformadmin.PlatformAdminRepository;
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
			PlatformAdminRepository platformAdminRepository,
			PlatformAdminRefreshTokenRepository platformAdminRefreshTokenRepository,
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
				new PlatformAdminAuthenticationFilter(
						platformAdminJwtService, platformAdminRepository, platformAdminRefreshTokenRepository),
				UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	/**
	 * Phase-1 compatibility chain. Literal /apis/** requests authenticate with
	 * the exact JWT format produced by frozen PHP. The JwtService dependency is
	 * retained only for the temporary /api/legacy/** regression aliases that
	 * predate the literal-route retrofit; it is not the client contract.
	 *
	 * <p>PHP employee tokens are re-derived against the employee row. Any signed
	 * PHP non-employee token (the frozen desktop/company login emits type=company)
	 * has no employee membership claim; PHP trusts its signed company_id, so the
	 * compatibility chain does the same. Company-active checks remain at the same
	 * controller guard points as the frozen source.
	 */
	@Bean
	@Order(2)
	@Profile("phase1-mysql")
	public SecurityFilterChain legacySecurityFilterChain(
			HttpSecurity http, LegacyPhpJwtService legacyPhpJwtService, JwtService jwtService,
			TenantScope tenantScope, LegacyTenantContextService legacyTenantContextService,
			ApiSecurityErrorHandler apiSecurityErrorHandler) throws Exception {
		Function<HttpServletRequest, Optional<Long>> resolver = request -> {
			if (!(SecurityContextHolder.getContext().getAuthentication() != null
					&& SecurityContextHolder.getContext().getAuthentication().getPrincipal()
							instanceof AuthenticatedPrincipal principal)) {
				return Optional.empty();
			}

			// PHP's requireEmployeeSessionValid() is employee-type-specific.
			// Other signed PHP auth types use their company_id directly.
			if (principal.legacyAuthType() != null && !"employee".equals(principal.legacyAuthType())) {
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

		LegacyPhpJwtAuthenticationFilter legacyJwtFilter =
				new LegacyPhpJwtAuthenticationFilter(legacyPhpJwtService, jwtService);

		http
			.securityMatcher("/api/legacy/**", "/apis/**")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(apiSecurityErrorHandler)
				.accessDeniedHandler(apiSecurityErrorHandler))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/error").permitAll()
				// Not dangling: LegacyLoginController no longer exists in src/main
				// (moved to src/test as a D-074 regression-harness shim -- see the
				// PR description), but that test-scoped controller is
				// component-scanned and hit directly by
				// LegacyLoginServiceRollbackTest over real HTTP during the test
				// run, so this permitAll must stay for tests to exercise it; a
				// login endpoint is unauthenticated by definition in production too.
				.requestMatchers("/api/legacy/auth/login_employee").permitAll()
				// PHP checks method/body before auth on D-074 literal routes;
				// controllers therefore own requireAuth() in the same order.
				.requestMatchers(LegacyPhpRoutes.CONTROLLER_GUARDED).permitAll()
				.anyRequest().authenticated())
			.addFilterBefore(legacyJwtFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterAfter(new TenantScopeFilter(tenantScope, resolver), LegacyPhpJwtAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	@Order(3)
	@Profile("!phase1-mysql")
	public SecurityFilterChain tenantSecurityFilterChain(
			HttpSecurity http, JwtService jwtService,
			RefreshTokenRepository refreshTokenRepository,
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
			.addFilterBefore(new JwtAuthenticationFilter(jwtService, refreshTokenRepository),
				UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}
}
