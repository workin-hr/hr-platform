package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * The Phase 1 legacy adapter must stay outside the application's
 * component-scan root, and this is why.
 *
 * <p>{@code BackendApplication} is annotated {@code @SpringBootApplication}
 * in {@code com.workin.backend}, so Spring's default entity and
 * repository scanning covers {@code com.workin.backend.**} and nothing
 * else. {@link com.workin.legacy.employees.LegacyEmployee} maps the
 * legacy {@code employees} table, whose columns are not the PostgreSQL
 * ones -- {@code is_active} where the target schema has {@code active},
 * plus seven columns the target dropped entirely.
 *
 * <p>Put it one package higher, under {@code com.workin.backend}, and
 * Hibernate picks it up; {@code spring.jpa.hibernate.ddl-auto=validate}
 * then compares it against PostgreSQL, fails on the first missing
 * column, and every {@code @SpringBootTest} in the suite stops starting.
 * The application still runs on PostgreSQL until auth/authz is reworked,
 * so that would be a self-inflicted outage of the whole test suite in
 * exchange for nothing.
 *
 * <p>When the application moves to MySQL, this package gets scanned
 * deliberately -- an explicit {@code @EntityScan}/{@code @EnableJpaRepositories}
 * under the MySQL profile -- rather than by accident of placement. Until
 * then the adapter is compiled, unit-tested and invisible to the running
 * context, which is exactly the state a not-yet-adopted seam should be
 * in.
 */
class LegacyAdapterIsolationTest {

	private static final String SCAN_ROOT = "com.workin.backend";

	@Test
	void theLegacyAdapterLivesOutsideTheApplicationsComponentScanRoot() {
		JavaClasses adapter = new ClassFileImporter().importPackages("com.workin.legacy");

		assertThat(adapter).isNotEmpty();
		assertThat(adapter.stream().map(clazz -> clazz.getPackageName()))
				.isNotEmpty()
				.allSatisfy(packageName -> assertThat(packageName).doesNotStartWith(SCAN_ROOT));
	}

	/**
	 * The converse, and the assertion that actually catches the mistake:
	 * no entity mapped to a legacy table may appear under the scan root.
	 * Moving the file back is the failure mode this guards, and it would
	 * otherwise only show up as 38 unrelated integration tests failing
	 * to start a Spring context.
	 */
	@Test
	void noLegacyShapedEntityIsReachableByDefaultScanning() {
		JavaClasses scanned = new ClassFileImporter().importPackages(SCAN_ROOT);

		assertThat(scanned.stream()
				.filter(clazz -> clazz.isAnnotatedWith(jakarta.persistence.Entity.class))
				.filter(clazz -> clazz.getSimpleName().startsWith("Legacy"))
				.map(clazz -> clazz.getName()))
				.isEmpty();
	}

}
