package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Entity;

/**
 * ADR-0012's build-failing coverage guard: every tenant-owned legacy
 * entity must carry the tenant filter.
 *
 * <p>This is the control that makes the rest of the posture credible.
 * Phase 1 has no row-level security, so "queries should be scoped" is
 * otherwise a convention — and {@code hr-platform#74} is what a
 * convention looks like after it decays: one table with a
 * {@code NOT NULL company_id} sitting outside RLS, not exploitable, kept
 * safe only by the shape of the queries that happen to be written
 * against it. A new entity added without a filter is the same failure,
 * and on MySQL there would be nothing behind it.
 *
 * <p>"Tenant-owned" is decided by the legacy schema rather than by a
 * marker somebody has to remember to apply: an entity is tenant-owned if
 * it maps a {@code company_id} column. That means adding an entity for a
 * tenant-owned legacy table fails this test by default, which is the
 * right direction for a security control to fail in.
 */
class TenantFilterCoverageTest {

	private static final String LEGACY_ADAPTER = "com.workin.legacy";

	private static List<JavaClass> legacyEntities() {
		JavaClasses imported = new ClassFileImporter().importPackages(LEGACY_ADAPTER);
		return imported.stream()
				.filter(clazz -> clazz.isAnnotatedWith(Entity.class))
				.toList();
	}

	/**
	 * Guards the guard. If the adapter is ever empty or the package is
	 * renamed, every assertion below would pass vacuously and the
	 * control would be silently gone.
	 */
	@Test
	void theLegacyAdapterActuallyContainsEntitiesToCheck() {
		assertThat(legacyEntities())
				.describedAs("no @Entity found under %s -- this suite would pass vacuously",
						LEGACY_ADAPTER)
				.isNotEmpty();
	}

	@Test
	void everyTenantOwnedLegacyEntityCarriesTheTenantFilter() {
		List<String> unfiltered = legacyEntities().stream()
				.filter(TenantFilterCoverageTest::isTenantOwned)
				.filter(clazz -> !clazz.isAnnotatedWith(Filter.class))
				.map(JavaClass::getName)
				.toList();

		assertThat(unfiltered)
				.describedAs("tenant-owned legacy entities missing @Filter(TenantFilter.NAME) -- "
						+ "Phase 1 has no RLS behind this (ADR-0012), so an unfiltered "
						+ "tenant-owned entity reads every company")
				.isEmpty();
	}

	/**
	 * A filter whose condition is not the shared one would scope by
	 * something else, or by nothing, while still looking annotated. The
	 * annotation being present is not the property that matters; the
	 * predicate is.
	 */
	@Test
	void everyTenantFilterUsesTheSharedCompanyIdCondition() {
		List<String> wrongCondition = legacyEntities().stream()
				.filter(clazz -> clazz.isAnnotatedWith(Filter.class))
				.filter(clazz -> {
					Filter filter = clazz.getAnnotationOfType(Filter.class);
					return !TenantFilter.NAME.equals(filter.name())
							|| !TenantFilter.CONDITION.equals(filter.condition());
				})
				.map(JavaClass::getName)
				.toList();

		assertThat(wrongCondition)
				.describedAs("legacy entities whose @Filter is not the shared tenant filter")
				.isEmpty();
	}

	/**
	 * An entity is tenant-owned when it maps {@code company_id}. Read
	 * from the field mapping rather than from a hand-applied marker, so
	 * the schema decides and a new entity cannot opt itself out by
	 * omission.
	 */
	private static boolean isTenantOwned(JavaClass clazz) {
		return clazz.getAllFields().stream()
				.anyMatch(field -> {
					var column = field.tryGetAnnotationOfType(jakarta.persistence.Column.class);
					return column.isPresent() && "company_id".equals(column.get().name());
				});
	}

}
