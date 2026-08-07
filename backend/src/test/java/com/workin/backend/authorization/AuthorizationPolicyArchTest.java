package com.workin.backend.authorization;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import com.workin.backend.authorization.archfixtures.DoublyDeclaredHandlerFixture;
import com.workin.backend.authorization.archfixtures.PolicyOnNonHandlerFixture;
import com.workin.backend.authorization.archfixtures.UndeclaredHandlerFixture;

/**
 * F-23 / ADR-0010 Dimension 4 (Required Implementation Task 10): the
 * build fails when an externally reachable use case has no
 * authorization-policy declaration. The fixture-based tests below prove
 * each rule actually fails on its violation shape -- the matrix's
 * "proven to fail on an undeclared use case" closure criterion, kept
 * alive in the suite rather than checked once by hand.
 */
class AuthorizationPolicyArchTest {

	private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.workin.backend");

	private static final DescribedPredicate<JavaMethod> HANDLER_METHOD =
			new DescribedPredicate<>("a controller handler method (meta-annotated with @RequestMapping)") {
				@Override
				public boolean test(JavaMethod method) {
					return method.isMetaAnnotatedWith(RequestMapping.class);
				}
			};

	private static final ArchCondition<JavaMethod> HAVE_EXACTLY_ONE_POLICY_DECLARATION =
			new ArchCondition<>("declare exactly one authorization policy "
					+ "(@PublicUseCase, @AuthenticatedUseCase, or @RequiresPermission)") {
				@Override
				public void check(JavaMethod method, ConditionEvents events) {
					int declarations = 0;
					if (method.isAnnotatedWith(PublicUseCase.class)) {
						declarations++;
					}
					if (method.isAnnotatedWith(AuthenticatedUseCase.class)) {
						declarations++;
					}
					if (method.isAnnotatedWith(RequiresPermission.class)) {
						declarations++;
					}
					if (declarations != 1) {
						events.add(SimpleConditionEvent.violated(method, method.getFullName()
								+ " declares " + declarations + " authorization policies (must be exactly 1)"));
					}
				}
			};

	static final ArchRule EVERY_HANDLER_DECLARES_A_POLICY = methods()
			.that(HANDLER_METHOD)
			.should(HAVE_EXACTLY_ONE_POLICY_DECLARATION)
			.because("ADR-0010 Dimension 4: every externally reachable use case must declare its "
					+ "authorization policy or an explicit public/authentication-only marker (F-23)");

	static final ArchRule POLICY_ANNOTATIONS_ONLY_ON_HANDLERS = methods()
			.that().areAnnotatedWith(PublicUseCase.class)
			.or().areAnnotatedWith(AuthenticatedUseCase.class)
			.or().areAnnotatedWith(RequiresPermission.class)
			.should(new ArchCondition<JavaMethod>("be controller handler methods") {
				@Override
				public void check(JavaMethod method, ConditionEvents events) {
					if (!method.isMetaAnnotatedWith(RequestMapping.class)) {
						events.add(SimpleConditionEvent.violated(method, method.getFullName()
								+ " carries a policy annotation but is not a handler method -- "
								+ "nothing enforces it there"));
					}
				}
			})
			.because("a policy annotation on a non-handler method is decorative and misleading; the "
					+ "declaration point moves to the application-service layer only when that layer exists")
			// Zero policy annotations anywhere is a legal state for this
			// rule (it only constrains placement, not existence).
			.allowEmptyShould(true);

	static final ArchRule REQUIRES_PERMISSION_STAYS_OUT_OF_THE_PLATFORM_DOMAIN = noMethods()
			.that().areDeclaredInClassesThat().resideInAPackage("com.workin.backend.platformadmin..")
			.should().beAnnotatedWith(RequiresPermission.class)
			.because("permission evaluation exists only for the tenant domain "
					+ "(AuthorizationPolicyInterceptor + PermissionEvaluationService over membership "
					+ "roles/overrides) -- a platform-domain usage would be a decorative gate. Extend "
					+ "evaluation to the platform domain before removing this rule, alongside the first "
					+ "real platform.* business endpoint (F-26's standing audit criterion applies there too)");

	@Test
	void everyExternallyReachableHandlerDeclaresExactlyOnePolicy() {
		EVERY_HANDLER_DECLARES_A_POLICY.check(PRODUCTION_CLASSES);
	}

	@Test
	void policyAnnotationsAppearOnlyOnHandlerMethods() {
		POLICY_ANNOTATIONS_ONLY_ON_HANDLERS.check(PRODUCTION_CLASSES);
	}

	@Test
	void requiresPermissionStaysOutOfThePlatformDomain() {
		REQUIRES_PERMISSION_STAYS_OUT_OF_THE_PLATFORM_DOMAIN.check(PRODUCTION_CLASSES);
	}

	// ---- proven-to-fail evidence (fixtures imported explicitly) ----

	private static boolean hasViolations(ArchRule rule, Class<?> fixtureClass) {
		JavaClasses fixture = new ClassFileImporter().importClasses(
				fixtureClass, PublicUseCase.class, AuthenticatedUseCase.class, RequiresPermission.class);
		return rule.evaluate(fixture).hasViolation();
	}

	@Test
	void anUndeclaredHandlerIsAViolation() {
		assertThat(hasViolations(EVERY_HANDLER_DECLARES_A_POLICY, UndeclaredHandlerFixture.class)).isTrue();
	}

	@Test
	void aDoublyDeclaredHandlerIsAViolation() {
		assertThat(hasViolations(EVERY_HANDLER_DECLARES_A_POLICY, DoublyDeclaredHandlerFixture.class)).isTrue();
	}

	@Test
	void aPlatformDomainRequiresPermissionUsageIsAViolation() {
		assertThat(hasViolations(REQUIRES_PERMISSION_STAYS_OUT_OF_THE_PLATFORM_DOMAIN,
				com.workin.backend.platformadmin.archfixtures.PlatformRequiresPermissionFixture.class)).isTrue();
	}

	@Test
	void aPolicyAnnotationOnANonHandlerMethodIsAViolation() {
		assertThat(hasViolations(POLICY_ANNOTATIONS_ONLY_ON_HANDLERS, PolicyOnNonHandlerFixture.class)).isTrue();
	}

}
