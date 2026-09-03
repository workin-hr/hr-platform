package com.workin.backend.platformadmin.web;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.workin.backend.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0015 prerequisite 5, in the form the ADR asks for.
 *
 * <p>The failure this guards against is silent. {@code tenantSecurityFilterChain}
 * is the order-3 catch-all: no {@code securityMatcher}, CSRF disabled,
 * authenticating a <em>tenant</em> bearer token. An {@code /admin} mapping that
 * did not land on the admin chain would not 404 or 403 -- it would be served
 * under that chain, unprotected. As the ADR puts it, "testing a handful of named
 * routes cannot detect the omission, because the failure is precisely the route
 * nobody listed."
 *
 * <p>So this test does not name routes. It reads every mapping Spring actually
 * registered for the admin controllers out of the handler registry, and asserts
 * each one resolves to the admin chain. A page added tomorrow is covered the day
 * it is added, and a page added under the wrong path fails the build.
 */
class PlatformAdminWebChainCoverageTest extends AbstractIntegrationTest {

	// Qualified by name: actuator contributes a second
	// RequestMappingHandlerMapping (controllerEndpointHandlerMapping), so an
	// unqualified injection is ambiguous.
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	@Qualifier("platformAdminWebSecurityFilterChain")
	private SecurityFilterChain adminChain;

	/** The package that owns the admin UI. Everything mapped from here is in scope. */
	private static final String ADMIN_WEB_PACKAGE = PlatformAdminWebController.class.getPackageName();

	@Test
	void everyAdminUiMappingResolvesToTheAdminChain() {
		Set<String> checked = new TreeSet<>();

		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : this.handlerMapping.getHandlerMethods().entrySet()) {
			HandlerMethod handler = entry.getValue();
			if (!handler.getBeanType().getPackageName().equals(ADMIN_WEB_PACKAGE)) {
				continue;
			}
			for (String pattern : patternsOf(entry.getKey())) {
				for (HttpMethod method : methodsOf(entry.getKey())) {
					checked.add(method + " " + pattern);
					assertThat(chainFor(method, pattern))
						.as("%s %s must be served by the admin UI security chain, "
								+ "not by the CSRF-disabled tenant catch-all", method, pattern)
						.isSameAs(this.adminChain);
				}
			}
		}

		// The enumeration must actually have found something. A test that
		// silently iterates an empty registry would pass forever.
		assertThat(checked)
			.as("no admin UI mappings were discovered -- the enumeration is broken, not the surface")
			.isNotEmpty();
	}

	@Test
	void everyAdminUiMappingLivesUnderTheAdminPrefix() {
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : this.handlerMapping.getHandlerMethods().entrySet()) {
			if (!entry.getValue().getBeanType().getPackageName().equals(ADMIN_WEB_PACKAGE)) {
				continue;
			}
			assertThat(patternsOf(entry.getKey()))
				.allSatisfy(pattern -> assertThat(pattern)
					.as("an admin UI mapping outside %s cannot be covered by the chain's matcher",
							PlatformAdminWebSecurityConfig.PATH_PREFIX)
					.startsWith(PlatformAdminWebSecurityConfig.PATH_PREFIX));
		}
	}

	/**
	 * A path deliberately outside the admin prefix must NOT reach the admin
	 * chain -- otherwise the first assertion would pass for the wrong reason,
	 * with one over-broad matcher swallowing the whole application.
	 */
	@Test
	void theAdminChainDoesNotSwallowUnrelatedPaths() {
		assertThat(chainFor(HttpMethod.GET, "/api/platform-admin/me")).isNotSameAs(this.adminChain);
		assertThat(chainFor(HttpMethod.GET, "/actuator/health")).isNotSameAs(this.adminChain);
	}

	private SecurityFilterChain chainFor(HttpMethod method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method.name(), path);
		request.setServletPath(path);
		for (SecurityFilterChain chain : this.springSecurityFilterChain.getFilterChains()) {
			if (chain.matches(request)) {
				return chain;
			}
		}
		return null;
	}

	private static Set<String> patternsOf(RequestMappingInfo info) {
		if (info.getPathPatternsCondition() != null) {
			return new TreeSet<>(info.getPathPatternsCondition().getPatternValues());
		}
		return Set.of();
	}

	private static List<HttpMethod> methodsOf(RequestMappingInfo info) {
		if (info.getMethodsCondition().getMethods().isEmpty()) {
			return List.of(HttpMethod.GET);
		}
		return info.getMethodsCondition().getMethods().stream()
			.map(method -> HttpMethod.valueOf(method.name()))
			.toList();
	}

}
