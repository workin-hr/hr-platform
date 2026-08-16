package com.workin.backend.migration;

import java.util.Map;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Phase 2 base: a real PostgreSQL with the target schema applied, and
 * nothing else.
 *
 * <p>This suite used to extend {@code AbstractIntegrationTest}, which
 * meant booting the entire Spring application purely to get Flyway to
 * run. That coupling is what made a storage-migration asset able to fail
 * an application build, and it is removed here (strategy reset,
 * 2026-08-16): Flyway is invoked directly against the container, so this
 * suite depends on the <em>migrations</em> and not on the application at
 * all.
 *
 * <p>That independence is the point. Phase 1 moves the application onto
 * the legacy MySQL contract, at which point the Spring context will not
 * start against PostgreSQL — and this suite must keep working anyway,
 * because what it proves (the target schema and the load program) is
 * exactly what Phase 2 resumes from.
 *
 * <p>Singleton container, never stopped, for the same reason
 * {@code AbstractIntegrationTest} uses one: {@code @Testcontainers} stops
 * the container after each test <em>class</em>, and Ryuk reaps it at JVM
 * exit regardless.
 *
 * <p>Migrations are applied once, here, rather than per test. Every test
 * in this suite drops and recreates only the {@code migration} schema,
 * never the target tables, so a single application is correct — and it
 * matches what an operator does, which is run the migrations once and
 * then load.
 */
public abstract class AbstractPostgresMigrationTest {

	protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	/**
	 * The runtime role V6 creates. Phase 1 removes the application's
	 * two-DataSource split (there is no RLS on MySQL to enforce), but the
	 * migrations still create this role, so the placeholders still have
	 * to resolve for them to apply.
	 */
	protected static final String RUNTIME_DB_USERNAME = "app_runtime_test";
	protected static final String RUNTIME_DB_PASSWORD = "app_runtime_test_password";

	static {
		POSTGRES.start();
		Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration/common", "classpath:db/migration/rls")
				.placeholders(Map.of(
						"app_runtime_db_username", RUNTIME_DB_USERNAME,
						"app_runtime_db_password", RUNTIME_DB_PASSWORD))
				.load()
				.migrate();
	}

}
