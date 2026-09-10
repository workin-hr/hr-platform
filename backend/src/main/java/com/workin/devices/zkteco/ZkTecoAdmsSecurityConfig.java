package com.workin.devices.zkteco;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;

/**
 * {@code /iclock/**} has no bearer token to check: device identity is the
 * serial-to-registry resolution the controller performs, and an unknown or
 * inactive serial is refused there. This chain exists so that decision is
 * explicit rather than the accident of a request matching no chain at all,
 * and so the legacy JWT filter never runs against a terminal's requests.
 * Created together with the controller, under the same flag -- and under that
 * flag alone. It used to carry a {@code phase1-mysql} profile guard as well,
 * which ADR-0017 retired along with the PostgreSQL half that profile existed
 * to keep apart. Left in place, that guard would have been a hole of the
 * quietest kind: the controller is conditioned on the flag only, so
 * {@code /iclock/**} would have been mapped with this chain absent and a
 * device's request falling through to whichever chain matched next.
 */
@Configuration
@ConditionalOnProperty(name = "app.devices.ingest.enabled", havingValue = "true")
public class ZkTecoAdmsSecurityConfig {

	/**
	 * Ahead of the security chain, and therefore ahead of everything that
	 * could turn a device's punch batch into an empty parameter-parsed body.
	 */
	@Bean
	public FilterRegistrationBean<DeviceRequestBodyFilter> deviceRequestBodyFilter(
			@Value("${app.devices.ingest.max-body-bytes}") int maxBodyBytes) {
		FilterRegistrationBean<DeviceRequestBodyFilter> registration =
				new FilterRegistrationBean<>(new DeviceRequestBodyFilter(maxBodyBytes));
		registration.addUrlPatterns("/iclock/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

	/**
	 * The receiver answers on ONE hostname, and only that one.
	 *
	 * <p>The chain matched the path alone, and the shipped edge
	 * ({@code deploy/Caddyfile}) proxies every path on {@code APP_DOMAIN} to
	 * this application. So {@code /iclock/**} was reachable, unauthenticated,
	 * on the ordinary employee and admin hostname -- the separate device
	 * hostname the design gives its own rate limits and WAF rules was simply
	 * bypassed by asking the other name for the same path.
	 *
	 * <p>Enforced here rather than only at the proxy because the application
	 * must not depend on an edge configuration it does not own: a second
	 * ingress, a port-forward, or a future proxy change would silently
	 * reopen it.
	 *
	 * <p>A request on any other host is {@code denyAll}, not a fall-through.
	 * Falling through would hand {@code /iclock/**} to the ordinary chain,
	 * where an authenticated employee could reach a device endpoint that has
	 * no business being reachable by a person at all.
	 */
	@Bean
	@Order(0)
	public SecurityFilterChain deviceReceiverSecurityFilterChain(
			HttpSecurity http,
			@Value("${app.devices.ingest.host:}") String ingestHost) throws Exception {
		if (!StringUtils.hasText(ingestHost)) {
			throw new IllegalStateException(
					"app.devices.ingest.enabled=true requires app.devices.ingest.host to name the "
							+ "hostname the device receiver answers on. Without it /iclock/** would "
							+ "be reachable unauthenticated on every hostname this application "
							+ "serves, including the employee and admin API. Set it to the device "
							+ "ingress name (D-164).");
		}
		String expected = ingestHost.strip();
		RequestMatcher onDeviceHost =
				request -> expected.equalsIgnoreCase(request.getServerName());
		http
			.securityMatcher("/iclock/**")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> authorize
					.requestMatchers(onDeviceHost).permitAll()
					.anyRequest().denyAll());
		return http.build();
	}
}
