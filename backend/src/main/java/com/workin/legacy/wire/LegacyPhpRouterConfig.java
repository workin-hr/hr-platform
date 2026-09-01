package com.workin.legacy.wire;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

/**
 * Registers {@link LegacyPhpRouterFilter} ahead of everything else.
 *
 * <p>Highest precedence, and deliberately outside the Spring Security chain
 * rather than inside it. The legacy chain's permit-list
 * ({@link LegacyPhpRoutes#CONTROLLER_GUARDED} and the public routes beside it)
 * is written in {@code .php} paths, so authorization has to evaluate the
 * rewritten path. Registering the rewrite as an ordinary servlet filter at
 * {@code HIGHEST_PRECEDENCE} means the security matcher, the authorization
 * rules and the dispatcher all observe one consistent path -- rather than
 * authorizing the client's URL and dispatching the file's.
 */
@Configuration
@Profile("phase1-mysql")
public class LegacyPhpRouterConfig {

	@Bean
	public FilterRegistrationBean<LegacyPhpRouterFilter> legacyPhpRouterFilter() {
		FilterRegistrationBean<LegacyPhpRouterFilter> registration =
				new FilterRegistrationBean<>(new LegacyPhpRouterFilter());
		registration.addUrlPatterns("/apis/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

}
