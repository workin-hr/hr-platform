package com.workin.backend.security;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.authorization.PublicUseCase;
import com.workin.backend.platformadmin.PlatformAdminAuthController;
import com.workin.backend.platformadmin.web.PlatformAdminWebController;
import com.workin.backend.platformadmin.web.PlatformAdminWebSecurityConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The security configuration must agree with what the handlers declare about
 * themselves.
 *
 * <p>This exists because of a specific failure. {@code /admin/enrol/confirm} is
 * annotated {@code @PublicUseCase} -- it is the second step of an enrolment that
 * happens before any session exists -- but it was left out of the chain's
 * {@code permitAll} list, whose matchers are exact and so did not cover it under
 * {@code /admin/enrol}. The route became unreachable, and nothing failed: an
 * unpermitted route lands on the authentication entry point, which redirects to
 * {@code /admin/login}, which is exactly where a <em>successful</em> confirmation
 * also goes. The end-to-end test asserted the redirect target and passed while
 * the feature was broken.
 *
 * <p>Asserting on HTTP responses cannot separate those two outcomes, so this
 * compares the two declarations directly instead, in both directions:
 *
 * <ul>
 * <li>a handler declared {@code @PublicUseCase} that is not permitted is
 * unreachable;</li>
 * <li>a path permitted that no handler declares public is a hole nobody asked
 * for -- a stale entry left behind when a route moved or was deleted.</li>
 * </ul>
 */
class SecurityPolicyAgreementTest extends AbstractIntegrationTest {

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	void theAdminUiChainPermitsExactlyItsPublicHandlers() {
		assertAgreement(PlatformAdminWebController.class.getPackageName(),
				PlatformAdminWebSecurityConfig.PUBLIC_PATHS);
	}

	@Test
	void thePlatformAdminApiChainPermitsExactlyItsPublicHandlers() {
		assertAgreement(PlatformAdminAuthController.class.getPackageName(),
				SecurityConfig.PLATFORM_ADMIN_API_PUBLIC_PATHS);
	}

	/**
	 * The guard has to be shown to fail on the shape it exists for, or it is
	 * just two sets that happen to match today.
	 *
	 * <p>Demonstrated against a deliberately truncated copy of the real list
	 * rather than by loosening the real configuration: the point is to exercise
	 * the comparison, and a test that has to break production settings to prove
	 * itself is one nobody will run twice.
	 */
	@Test
	void theCheckItselfFailsWhenAPublicRouteIsLeftOutOfTheList() {
		String[] withoutConfirm = java.util.Arrays.stream(PlatformAdminWebSecurityConfig.PUBLIC_PATHS)
			.filter(path -> !path.equals(PlatformAdminWebSecurityConfig.ENROL_CONFIRM_PATH))
			.toArray(String[]::new);

		assertThatThrownBy(() -> assertAgreement(
				PlatformAdminWebController.class.getPackageName(), withoutConfirm))
			.as("this is the exact omission that shipped an unreachable route once")
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining(PlatformAdminWebSecurityConfig.ENROL_CONFIRM_PATH);
	}

	private void assertAgreement(String handlerPackage, String[] configuredPublicPaths) {
		Set<String> declaredPublic = new TreeSet<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : this.handlerMapping.getHandlerMethods().entrySet()) {
			HandlerMethod handler = entry.getValue();
			if (!handler.getBeanType().getPackageName().equals(handlerPackage)) {
				continue;
			}
			if (handler.getMethodAnnotation(PublicUseCase.class) == null) {
				continue;
			}
			if (entry.getKey().getPathPatternsCondition() != null) {
				declaredPublic.addAll(entry.getKey().getPathPatternsCondition().getPatternValues());
			}
		}

		assertThat(declaredPublic)
			.as("the enumeration found no public handlers in %s -- it is broken, not the config",
					handlerPackage)
			.isNotEmpty();

		assertThat(declaredPublic)
			.as("every handler declared @PublicUseCase must actually be permitted; one that is "
					+ "not becomes unreachable *silently*, because the entry point's redirect is "
					+ "indistinguishable from a successful one")
			.containsExactlyInAnyOrderElementsOf(new TreeSet<>(Set.of(configuredPublicPaths)));
	}

}
