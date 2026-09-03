package com.workin.devices.zkteco;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@code /iclock/**} has no bearer token to check: device identity is the
 * serial-to-registry resolution the controller performs, and an unknown or
 * inactive serial is refused there. This chain exists so that decision is
 * explicit rather than the accident of a request matching no chain at all,
 * and so the legacy JWT filter never runs against a terminal's requests.
 * Created together with the controller, under the same flag.
 */
@Configuration
@Profile("phase1-mysql")
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

	@Bean
	@Order(0)
	public SecurityFilterChain deviceReceiverSecurityFilterChain(HttpSecurity http) throws Exception {
		http
			.securityMatcher("/iclock/**")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
		return http.build();
	}
}
