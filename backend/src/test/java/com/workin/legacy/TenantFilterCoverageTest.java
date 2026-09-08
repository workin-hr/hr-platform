package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * ADR-0012's build-failing coverage guard (P-4): every tenant-owned
 * legacy entity must declare exactly one of the three recognized
 * tenancy policies, matching its actual columns.
 *
 * <p><b>Redesigned for PR 12.2 (F-1, U-1).</b> The original version of
 * this guard decided "tenant-owned" by the presence of a {@code
 * company_id}-named {@code @Column} — correct for P-1a, but F-1 proved
 * ten of Item 12's tables carry no such column at all. A P-1b/P-1c
 * entity's Java shape cannot self-report "I should be tenant-scoped"
 * the way a {@code company_id} field does, so this guard no longer asks
 * the Java mapping what it thinks it is; it asks the vendored legacy
 * schema what the table actually is, the same authority {@code
 * scripts/check_legacy_schema_drift.py} already treats as ground truth
 * for this codebase. A future entity for a known tenant-owned legacy
 * table therefore still cannot opt itself out by omission — the schema,
 * not a hand-applied marker, decides.
 *
 * <p>Deliberately does not extend {@link AbstractLegacyMySqlTest}: the
 * vendored schema file's own text is authoritative for "what columns
 * does this table have" (mysqldump's {@code CREATE TABLE} blocks are
 * pure column definitions here — every index/key/FK in this dump is
 * added later via a separate {@code ALTER TABLE}, confirmed by
 * inspection), so this guard needs no MariaDB container and stays a
 * fast, always-on architecture test.
 *
 * <p><b>Phase-1-owned tables are structurally exempt, not
 * hand-exempted.</b> A table absent from the vendored schema (e.g.
 * {@code legacy_refresh_tokens}, added only by {@code
 * phase1_extensions.sql}) is not part of Item 12's tenant-owned
 * inventory at all, so it is skipped — the same way {@code
 * LegacyRefreshToken}'s own javadoc already documents, now enforced by
 * the guard itself rather than by that javadoc being trusted.
 */
class TenantFilterCoverageTest {

	private static final String LEGACY_ADAPTER = "com.workin.legacy";
	private static final String VENDORED_SCHEMA_RESOURCE = "legacy/mysql_workin.schema.sql";

	/** {@code CREATE TABLE `name` (...columns...) ENGINE=...} — this dump's only column-defining shape. */
	private static final Pattern CREATE_TABLE = Pattern.compile(
			"CREATE TABLE `(\\w+)` \\((.*?)\\n\\)\\s*ENGINE", Pattern.DOTALL);

	/** The first backtick-quoted token on each line inside a {@code CREATE TABLE} block is that column's name. */
	private static final Pattern COLUMN_NAME = Pattern.compile("(?m)^\\s*`(\\w+)`");

	private enum TenancyPolicy {
		DIRECT_COMPANY_ID(TenantFilter.NAME, TenantFilter.CONDITION),
		EMPLOYEE_DERIVED(EmployeeDerivedTenantFilter.NAME, EmployeeDerivedTenantFilter.CONDITION),
		DEPARTMENT_BRANCHES(DepartmentBranchesTenantFilter.NAME, DepartmentBranchesTenantFilter.CONDITION),
		NOT_TENANT_OWNED(null, null);

		final String filterName;
		final String condition;

		TenancyPolicy(String filterName, String condition) {
			this.filterName = filterName;
			this.condition = condition;
		}
	}

	private static Map<String, Set<String>> vendoredTableColumns() {
		String schema = readResource(VENDORED_SCHEMA_RESOURCE);
		Map<String, Set<String>> tables = new HashMap<>();
		Matcher tableMatcher = CREATE_TABLE.matcher(schema);
		while (tableMatcher.find()) {
			String tableName = tableMatcher.group(1);
			String body = tableMatcher.group(2);
			Set<String> columns = new HashSet<>();
			Matcher columnMatcher = COLUMN_NAME.matcher(body);
			while (columnMatcher.find()) {
				columns.add(columnMatcher.group(1));
			}
			tables.put(tableName, columns);
		}
		return tables;
	}

	private static String readResource(String name) {
		try (InputStream in = TenantFilterCoverageTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new IllegalStateException("could not read " + name, ex);
		}
	}

	/**
	 * Priority order matches how the schema actually composes: a table
	 * with a direct {@code company_id} is P-1a even if it also happens
	 * to carry {@code employee_id}/{@code department_id} for unrelated
	 * FK reasons (e.g. {@code job_titles.department_id}); among the
	 * tables with neither, {@code employee_id} (nine tables) is checked
	 * before {@code department_id} (one table, {@code
	 * department_branches}), matching F-1's own characterisation exactly
	 * rather than an arbitrary tie-break.
	 */
	private static TenancyPolicy classify(Set<String> columns) {
		if (columns.contains("company_id")) {
			return TenancyPolicy.DIRECT_COMPANY_ID;
		}
		if (columns.contains("employee_id")) {
			return TenancyPolicy.EMPLOYEE_DERIVED;
		}
		if (columns.contains("department_id")) {
			return TenancyPolicy.DEPARTMENT_BRANCHES;
		}
		return TenancyPolicy.NOT_TENANT_OWNED;
	}

	private static List<JavaClass> legacyEntities() {
		JavaClasses imported = new ClassFileImporter().importPackages(LEGACY_ADAPTER);
		return imported.stream()
				.filter(clazz -> clazz.isAnnotatedWith(Entity.class))
				.toList();
	}

	private static String tableNameOf(JavaClass clazz) {
		Table table = clazz.reflect().getAnnotation(Table.class);
		assertThat(table)
				.describedAs("%s is @Entity but carries no @Table -- this guard cannot classify it "
						+ "against the vendored schema without one", clazz.getName())
				.isNotNull();
		return table.name();
	}

	/** Uses real reflection, not ArchUnit's single-annotation API, because {@code @Filter} is {@code @Repeatable}. */
	private static Filter[] filtersOn(JavaClass clazz) {
		return clazz.reflect().getAnnotationsByType(Filter.class);
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

	/**
	 * Guards the guard's own data source. If the regex parser ever stops
	 * matching this dump's actual format (e.g. a future vendored-schema
	 * refresh reformats it), every classification below would silently
	 * fall back to "table not found -> not tenant-owned" and this guard
	 * would pass vacuously in the worst possible way -- accepting an
	 * entity for a real tenant-owned table as exempt merely because the
	 * parser stopped finding it.
	 */
	@Test
	void theVendoredSchemaParserActuallyFindsKnownTables() {
		Map<String, Set<String>> tables = vendoredTableColumns();
		assertThat(tables)
				.describedAs("vendored-schema table parser found no tables at all -- "
						+ "CREATE_TABLE regex no longer matches %s's actual format", VENDORED_SCHEMA_RESOURCE)
				.isNotEmpty();
		assertThat(tables.getOrDefault("employees", Set.of()))
				.describedAs("known direct-company_id table 'employees' not parsed correctly")
				.contains("id", "company_id", "employee_code");
		assertThat(tables.getOrDefault("attendance", Set.of()))
				.describedAs("known employee-derived table 'attendance' not parsed correctly")
				.contains("id", "employee_id")
				.doesNotContain("company_id");
		assertThat(tables.getOrDefault("department_branches", Set.of()))
				.describedAs("known department_branches table not parsed correctly")
				.contains("department_id", "branch_id")
				.doesNotContain("company_id", "employee_id");
	}

	/**
	 * The core guard: every legacy entity mapping a vendored table whose
	 * real columns indicate tenant ownership carries exactly one {@code
	 * @Filter}, and it is the one matching that table's columns -- not
	 * zero, not the wrong policy, not more than one.
	 */
	@Test
	void everyTenantOwnedLegacyEntityDeclaresExactlyOneMatchingTenancyPolicy() {
		Map<String, Set<String>> vendoredTables = vendoredTableColumns();
		List<String> violations = new java.util.ArrayList<>();

		for (JavaClass clazz : legacyEntities()) {
			String tableName = tableNameOf(clazz);
			Set<String> columns = vendoredTables.get(tableName);

			if (columns == null) {
				// Not in the vendored contract at all -- a Phase-1-owned
				// addition (phase1_extensions.sql), structurally
				// out of Item 12's tenant-owned inventory. It must still
				// carry no tenancy filter of its own invention.
				if (filtersOn(clazz).length > 0) {
					violations.add(clazz.getName() + ": maps table `" + tableName + "`, which is not part of "
							+ "the vendored legacy schema, but carries a tenancy @Filter anyway -- a "
							+ "Phase-1-owned table is not in Item 12's tenant-owned inventory by definition");
				}
				continue;
			}

			TenancyPolicy expected = classify(columns);
			Filter[] filters = filtersOn(clazz);

			if (expected == TenancyPolicy.NOT_TENANT_OWNED) {
				if (filters.length > 0) {
					violations.add(clazz.getName() + ": maps table `" + tableName + "`, which carries none of "
							+ "company_id/employee_id/department_id -- not tenant-owned, but carries a "
							+ "tenancy @Filter anyway");
				}
				continue;
			}

			if (filters.length == 0) {
				violations.add(clazz.getName() + ": maps tenant-owned table `" + tableName + "` (expected "
						+ expected + ", filter `" + expected.filterName + "`) but carries no @Filter at all -- "
						+ "Phase 1 has no RLS behind this (ADR-0012), so an unfiltered tenant-owned entity "
						+ "reads every company");
				continue;
			}
			if (filters.length > 1) {
				violations.add(clazz.getName() + ": maps tenant-owned table `" + tableName + "` but carries "
						+ filters.length + " tenancy filters -- exactly one recognized policy is required, not "
						+ "several");
				continue;
			}

			Filter actual = filters[0];
			boolean matches = expected.filterName.equals(actual.name()) && expected.condition.equals(actual.condition());
			if (!matches) {
				violations.add(clazz.getName() + ": maps tenant-owned table `" + tableName + "` (columns "
						+ columns + ", expected " + expected + " via filter `" + expected.filterName + "`) but "
						+ "carries @Filter(name=\"" + actual.name() + "\", condition=\"" + actual.condition()
						+ "\") -- the wrong policy for its columns");
			}
		}

		assertThat(violations)
				.describedAs("tenant-owned legacy entities failing P-4's exactly-one-recognized-policy guard")
				.isEmpty();
	}

}
