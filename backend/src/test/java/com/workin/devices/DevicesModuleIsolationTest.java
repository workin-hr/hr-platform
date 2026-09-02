package com.workin.devices;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

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

	@Test
	void theModuleIsReachedOnlyByThePhase1ScanNeverByTheApplicationRoot() {
		ComponentScan scan = LegacyPersistenceConfig.class.getAnnotation(ComponentScan.class);
		assertThat(scan.value()).contains(MODULE);
		assertThat(module().stream().map(JavaClass::getPackageName))
				.isNotEmpty()
				.allSatisfy(name -> assertThat(name).doesNotStartWith("com.workin.backend"));
	}

	/** JdbcTemplate over legacyDataSource: no entity ever reaches @EntityScan, so TenantFilterCoverageTest stays untouched. */
	@Test
	void theModuleDeclaresNoJpaEntities() {
		assertThat(module().stream()
				.filter(clazz -> clazz.isAnnotatedWith(jakarta.persistence.Entity.class))
				.map(JavaClass::getName))
				.isEmpty();
	}

	/** A configuration here must never become live under the default (Postgres) profile. */
	@Test
	void everyConfigurationIsGuardedToThePhase1Profile() {
		List<String> unguarded = module().stream()
				.filter(clazz -> clazz.isMetaAnnotatedWith(Configuration.class))
				.filter(clazz -> !clazz.isAnnotatedWith(Profile.class)
						|| !List.of(clazz.reflect().getAnnotation(Profile.class).value()).contains("phase1-mysql"))
				.map(JavaClass::getName)
				.toList();
		assertThat(unguarded).isEmpty();
	}
}
