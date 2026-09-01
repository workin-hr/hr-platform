package com.workin.legacy.wire;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import tools.jackson.databind.ObjectMapper;

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

	/**
	 * The literal {@code /apis/**} paths this application actually serves.
	 *
	 * <p>Resolved lazily and cached on first use, not injected: this filter is
	 * built at {@code HIGHEST_PRECEDENCE} during servlet registration, which is
	 * earlier than {@link RequestMappingHandlerMapping} can be relied on. The
	 * first request is served after the context is fully refreshed, so the
	 * mapping is available by then.
	 *
	 * <p>Deriving it from the live mapping rather than restating it is the
	 * point: a route added, renamed or lost changes this set with it, so the
	 * router's 501 branch cannot drift away from what is deployed the way a
	 * second hand-written list would.
	 */
	private static java.util.function.Supplier<Set<String>> mappedRoutes(
			ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
		return new java.util.function.Supplier<>() {
			private volatile Set<String> cached;

			@Override
			public Set<String> get() {
				Set<String> local = cached;
				if (local == null) {
					RequestMappingHandlerMapping mapping = handlerMapping.getObject();
					local = mapping.getHandlerMethods().keySet().stream()
							.flatMap(info -> info.getPatternValues().stream())
							.filter(pattern -> pattern.startsWith("/apis/"))
							.collect(Collectors.toUnmodifiableSet());
					cached = local;
				}
				return local;
			}
		};
	}

	@Bean
	public FilterRegistrationBean<LegacyPhpRouterFilter> legacyPhpRouterFilter(
			// Qualified by name: Actuator contributes a second
			// RequestMappingHandlerMapping (controllerEndpointHandlerMapping),
			// and because this provider resolves lazily the ambiguity surfaced
			// as a 500 on the first request rather than as a startup failure.
			@Qualifier("requestMappingHandlerMapping")
			ObjectProvider<RequestMappingHandlerMapping> handlerMapping,
			LegacyMessages messages, ObjectMapper objectMapper) {
		FilterRegistrationBean<LegacyPhpRouterFilter> registration =
				new FilterRegistrationBean<>(new LegacyPhpRouterFilter(
						mappedRoutes(handlerMapping), messages, objectMapper));
		registration.addUrlPatterns("/apis/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

}
