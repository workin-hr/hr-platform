package com.workin.devices;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.workin.backend.config.LegacyPersistenceConfig;

/**
 * {@code com.workin.devices} is reached by exactly one scan, on purpose
 * (design section 6). These pin the wiring, the way
 * {@code LegacyAdapterIsolationTest} pins the legacy adapter's.
 */
class DevicesModuleIsolationTest {

	private static final String MODULE = "com.workin.devices";

	private static JavaClasses module() {
		return new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages(MODULE);
	}

	/**
	 * This used to assert that the module was reachable <em>only</em> through
	 * {@code LegacyPersistenceConfig}'s explicit {@code @ComponentScan}, because
	 * the application's own scan root was deliberately empty while two
	 * profile-gated substrates had to be kept apart. ADR-0017 left one database
	 * and the scan root went back to {@code com.workin}, so that assertion now
	 * describes a structure that no longer exists.
	 *
	 * <p>What still matters is the half that was never about scanning: this is
	 * its own root, not a corner of {@code com.workin.backend}, so nothing here
	 * is picked up by that package's JPA wiring.
	 */
	@Test
	void theModuleIsItsOwnRootAndNotPartOfTheBackendPackage() {
		assertThat(module().stream().map(JavaClass::getPackageName))
				.isNotEmpty()
				.allSatisfy(name -> assertThat(name).startsWith(MODULE))
				.allSatisfy(name -> assertThat(name).doesNotStartWith("com.workin.backend"));

		EntityScan entityScan = LegacyPersistenceConfig.class.getAnnotation(EntityScan.class);
		assertThat(entityScan.value())
				.describedAs("the module persists with JdbcTemplate and declares no entity; "
						+ "adding it to @EntityScan would pull it into TenantFilterCoverageTest's scope")
				.noneMatch(pkg -> pkg.startsWith(MODULE));
	}

	/** JdbcTemplate over legacyDataSource: no entity ever reaches @EntityScan, so TenantFilterCoverageTest stays untouched. */
	@Test
	void theModuleDeclaresNoJpaEntities() {
		assertThat(module().stream()
				.filter(clazz -> clazz.isAnnotatedWith(jakarta.persistence.Entity.class))
				.map(JavaClass::getName))
				.isEmpty();
	}

	/**
	 * Controllers may not reach a store directly.
	 *
	 * <p>The rule the module is built on: a controller owns HTTP and nothing
	 * else, so every decision sits in a service where it can be tested without
	 * a request and reused when a second vendor adapter arrives. A controller
	 * that talks to a store is how that erodes -- one "just this once" query
	 * at a time -- and the erosion is invisible in review because each step
	 * looks small.
	 */
	@Test
	void noControllerTalksToAStoreDirectly() {
		List<JavaClass> controllers = module().stream()
				.filter(clazz -> clazz.isMetaAnnotatedWith(Controller.class))
				.toList();
		// Guards the guard: with no controllers found, every assertion below
		// would pass by describing nothing.
		assertThat(controllers).describedAs("no device controller found -- this rule would pass vacuously")
				.hasSizeGreaterThanOrEqualTo(2);

		List<String> offenders = controllers.stream()
				.flatMap(clazz -> clazz.getDirectDependenciesFromSelf().stream()
						.map(dependency -> dependency.getTargetClass().getSimpleName())
						.filter(name -> name.endsWith("Store"))
						.map(name -> clazz.getSimpleName() + " -> " + name))
				.distinct()
				.toList();

		assertThat(offenders)
				.describedAs("a device controller reached a store directly; put the rule in a service instead")
				.isEmpty();
	}

	/**
	 * Every bean of the unauthenticated device surface must be gated by
	 * {@code app.devices.ingest.enabled}, which defaults to false.
	 *
	 * <p>This replaces a rule that required the {@code phase1-mysql} profile,
	 * retired with the PostgreSQL half it existed to separate. The replacement
	 * is the stricter one: the profile guard was carried by the security
	 * configuration alone, while the controller that maps {@code /iclock/**}
	 * was gated by the flag only. Had the profile simply been deleted, the
	 * endpoints would still have been mapped with their own chain missing.
	 *
	 * <p>Scoped to {@code zkteco} on purpose. The tenant-facing surface in
	 * {@code api} is authenticated and always on, so it must not be caught by
	 * this rule.
	 */
	@Test
	void everyBeanOfTheDeviceReceiverIsGatedByTheIngestFlag() {
		List<JavaClass> receiverBeans = module().stream()
				.filter(clazz -> clazz.getPackageName().startsWith(MODULE + ".zkteco"))
				.filter(clazz -> clazz.isMetaAnnotatedWith(Configuration.class)
						|| clazz.isMetaAnnotatedWith(Controller.class)
						|| clazz.isMetaAnnotatedWith(org.springframework.stereotype.Component.class))
				.toList();

		// Guards the guard: an empty list would let every assertion below pass
		// by describing nothing at all.
		assertThat(receiverBeans)
				.describedAs("no receiver bean found -- this rule would pass vacuously")
				.isNotEmpty();

		List<String> unguarded = receiverBeans.stream()
				.filter(clazz -> !clazz.isAnnotatedWith(ConditionalOnProperty.class)
						|| !"app.devices.ingest.enabled".equals(
								clazz.reflect().getAnnotation(ConditionalOnProperty.class).name()[0]))
				.map(JavaClass::getName)
				.toList();
		assertThat(unguarded)
				.describedAs("an unauthenticated device bean that a deployment cannot turn off")
				.isEmpty();
	}
}
