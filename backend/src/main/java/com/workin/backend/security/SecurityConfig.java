package com.workin.backend.security;

import java.util.Optional;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
	@Order(2)
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

}
