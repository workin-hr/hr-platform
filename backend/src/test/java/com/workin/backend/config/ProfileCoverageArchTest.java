package com.workin.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * ADR-0013 amendment 2: the {@code phase1-mysql} profile-coverage guard,
 * built first / in the same commit as the bootstrap wiring it protects
 * -- not after. {@link LegacyPersistenceConfig}'s
 * {@code @ComponentScan} reaches five packages that mix cross-cutting
 * beans (needed under both profiles) with Postgres-specific ones
 * (needed under neither the {@code phase1-mysql} profile). This is what
 * makes sure the omission is a build failure, not a
 * {@code NoSuchBeanDefinitionException} someone notices at boot.
 *
 * <p>Scope is deliberately narrower than "every class in the five mixed
 * packages": only classes Spring would actually try to instantiate as
 * beans under a plain {@code @ComponentScan} matter here --
 * {@code @Component}-family stereotypes (meta-annotation aware, so
 * {@code @Service}/{@code @RestController}/{@code @Controller}/
 * {@code @Configuration} all count) and {@link ApplicationRunner}
 * implementations. {@code @Entity} classes and Spring Data repository
 * interfaces are excluded from the check because they are never reached
 * by {@code @ComponentScan} at all -- only {@code @EntityScan}/
 * {@code @EnableJpaRepositories} find them, and both of
 * {@code LegacyPersistenceConfig}'s are scoped to {@code com.workin.legacy}
 * only, never the mixed backend packages. Checking them here would
 * assert a property the mechanism does not actually depend on.
 */
class ProfileCoverageArchTest {

	private static final List<String> MIXED_PACKAGES = List.of(
			"com.workin.backend.identity",
			"com.workin.backend.security",
			"com.workin.backend.tenancy",
			"com.workin.backend.config",
			"com.workin.backend.authorization");

	/**
	 * Cross-cutting by ADR-0013's own inventory -- reused as-is under
	 * both profiles, never guarded. {@code SecurityConfig} is handled
	 * separately (its profile-gating is per-{@code @Bean}, not
	 * class-level -- see {@link #securityConfigGatesEachChainCorrectly}).
	 */
	private static final Set<String> CROSS_CUTTING_ALLOWLIST = Set.of(
			"com.workin.backend.identity.JwtService",
			"com.workin.backend.security.ApiSecurityErrorHandler",
			"com.workin.backend.security.SecurityConfig",
			"com.workin.backend.tenancy.TenantScope",
			"com.workin.backend.config.JwtSecretStartupCheck");

	private static List<JavaClass> beanCandidates() {
		JavaClasses imported = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages(MIXED_PACKAGES);
		return imported.stream()
				.filter(ProfileCoverageArchTest::isSpringManagedCandidate)
				.filter(clazz -> !CROSS_CUTTING_ALLOWLIST.contains(clazz.getName()))
				.toList();
	}

	private static boolean isSpringManagedCandidate(JavaClass clazz) {
		return clazz.isMetaAnnotatedWith(Component.class)
				|| clazz.isMetaAnnotatedWith(Service.class)
				|| clazz.isMetaAnnotatedWith(Configuration.class)
				|| clazz.isMetaAnnotatedWith(RestController.class)
				|| clazz.isMetaAnnotatedWith(Controller.class)
				|| clazz.isAssignableTo(ApplicationRunner.class);
	}

	/** Guards the guard: if the candidate set is ever empty, every assertion below passes vacuously. */
	@Test
	void thereAreActuallyPostgresOnlyBeanCandidatesToCheck() {
		assertThat(beanCandidates())
				.describedAs("no Spring-managed class found outside the cross-cutting allowlist in %s "
						+ "-- this guard would pass vacuously", MIXED_PACKAGES)
				.isNotEmpty();
	}

	@Test
	void everyPostgresOnlyBeanInAMixedPackageCarriesTheProfileGuard() {
		List<String> unguarded = beanCandidates().stream()
				.filter(clazz -> !carriesExclusionProfile(clazz))
				.map(JavaClass::getName)
				.toList();

		assertThat(unguarded)
				.describedAs("Postgres-specific beans in identity/security/tenancy/config/authorization "
						+ "missing @Profile(\"!phase1-mysql\") -- LegacyPersistenceConfig's @ComponentScan "
						+ "reaches these packages too, so an unguarded class here fails phase1-mysql context "
						+ "startup with NoSuchBeanDefinitionException instead of failing this build")
				.isEmpty();
	}

	/**
	 * Proven to fail on the mistake it exists to catch: a class exactly
	 * like the ones above, minus the annotation.
	 */
	@Test
	void aClassMissingTheProfileGuardIsAViolation() {
		JavaClasses fixture = new ClassFileImporter().importClasses(
				com.workin.backend.config.archfixtures.UnguardedPostgresOnlyFixture.class);
		JavaClass fixtureClass = fixture.get(
				com.workin.backend.config.archfixtures.UnguardedPostgresOnlyFixture.class);

		assertThat(isSpringManagedCandidate(fixtureClass)).isTrue();
		assertThat(carriesExclusionProfile(fixtureClass)).isFalse();
	}

	/**
	 * The one class the ArchUnit sweep above deliberately skips, checked
	 * directly instead: {@code SecurityConfig} must stay reachable under
	 * both profiles (it is on the cross-cutting allowlist), but its
	 * platform-admin chain must not.
	 */
	@Test
	void securityConfigGatesEachChainCorrectly() throws NoSuchMethodException {
		Class<com.workin.backend.security.SecurityConfig> securityConfig =
				com.workin.backend.security.SecurityConfig.class;
		assertThat(securityConfig.isAnnotationPresent(Profile.class))
				.describedAs("SecurityConfig itself must stay unguarded -- it is reused under both profiles")
				.isFalse();

		var platformAdminChain = java.util.Arrays.stream(securityConfig.getMethods())
				.filter(method -> method.getName().equals("platformAdminSecurityFilterChain"))
				.findFirst()
				.orElseThrow();
		assertThat(platformAdminChain.getAnnotation(Profile.class))
				.describedAs("platform-admin chain must not be reachable under phase1-mysql (ADR-0013 amendment 4)")
				.isNotNull();
		assertThat(platformAdminChain.getAnnotation(Profile.class).value()).containsExactly("!phase1-mysql");

		var legacyChain = java.util.Arrays.stream(securityConfig.getMethods())
				.filter(method -> method.getName().equals("legacySecurityFilterChain"))
				.findFirst()
				.orElseThrow();
		assertThat(legacyChain.getAnnotation(Profile.class))
				.describedAs("legacy chain must not be reachable under the default profile "
						+ "-- it depends on LegacyTenantContextService, which does not exist there")
				.isNotNull();
		assertThat(legacyChain.getAnnotation(Profile.class).value()).containsExactly("phase1-mysql");
	}

	/**
	 * Accepts either direction of guard: {@code @Profile("!phase1-mysql")}
	 * (a Postgres-only bean, excluded from the legacy profile -- the
	 * common case) or {@code @Profile("phase1-mysql")}
	 * ({@link LegacyPersistenceConfig} itself, excluded from the
	 * default profile instead). Either one prevents the class from
	 * being live under both profiles at once, which is the actual
	 * property this guard protects.
	 */
	private static boolean carriesExclusionProfile(JavaClass clazz) {
		if (!clazz.isAnnotatedWith(Profile.class)) {
			return false;
		}
		Profile profile = clazz.reflect().getAnnotation(Profile.class);
		for (String value : profile.value()) {
			if ("!phase1-mysql".equals(value) || "phase1-mysql".equals(value)) {
				return true;
			}
		}
		return false;
	}

}
