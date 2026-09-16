package com.workin.devices.agentapi;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.workin.devices.agent.DeviceAgentService;

/**
 * The on-premises agent surface: bearer token in, one active agent out, and
 * nothing on these paths reachable any other way.
 *
 * <p>Its own chain rather than a branch of the legacy one: an agent token is
 * not a legacy JWT, and letting the legacy filter see these requests would make
 * a malformed agent token a JWT parsing question. Ordered after the admin and
 * device-receiver chains and before the legacy chain; the matchers do not
 * overlap, so the order only has to be unique.
 *
 * <p>Off by default. A deployment that has not turned it on maps no agent route
 * and registers no chain.
 *
 * <p>No body is read before authentication. The receiver's body filter runs
 * ahead of every chain because a terminal has no credential to check first;
 * an agent has one, so an unauthenticated caller is refused here without the
 * server buffering a byte of what it sent, and the controller reads a bounded
 * body only for an agent this chain let through.
 */
@Configuration
@ConditionalOnProperty(name = "app.devices.agents.enabled", havingValue = "true")
public class DeviceAgentSecurityConfig {

	public static final String BASE_PATH = "/api/v1/device-agents";

	static final String AUTHORITY = "DEVICE_AGENT";

	@Bean
	@Order(1)
	public SecurityFilterChain deviceAgentSecurityFilterChain(HttpSecurity http, DeviceAgentService agents)
			throws Exception {
		http
			.securityMatcher(BASE_PATH + "/**")
			.csrf(csrf -> csrf.disable())
			.httpBasic(basic -> basic.disable())
			.formLogin(form -> form.disable())
			.logout(logout -> logout.disable())
			.requestCache(cache -> cache.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.addFilterBefore(new BearerAgentFilter(agents), AnonymousAuthenticationFilter.class)
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint((request, response, ex) -> unauthorized(response))
				.accessDeniedHandler((request, response, ex) -> unauthorized(response)))
			.authorizeHttpRequests(authorize -> authorize.anyRequest().hasAuthority(AUTHORITY));
		return http.build();
	}

	private static void unauthorized(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"error\":\"unauthorized\"}");
	}

	/** Sets the agent as the principal when the token is one; otherwise leaves the request anonymous, and the chain refuses it. */
	static final class BearerAgentFilter extends OncePerRequestFilter {

		private static final String BEARER = "Bearer ";

		private final DeviceAgentService agents;
		private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

		BearerAgentFilter(DeviceAgentService agents) {
			this.agents = agents;
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			String header = request.getHeader(HttpHeaders.AUTHORIZATION);
			if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
				agents.authenticate(header.substring(BEARER.length()).strip()).ifPresent(agent -> {
					SecurityContext context = contexts.createEmptyContext();
					context.setAuthentication(new PreAuthenticatedAuthenticationToken(
							agent, null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
					contexts.setContext(context);
				});
			}
			chain.doFilter(request, response);
		}
	}
}
