package com.workin.backend.config;

import java.util.Properties;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import com.workin.legacy.TenantAwareJpaTransactionManager;
import com.workin.legacy.TenantFilterBinder;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The {@code phase1-mysql} profile's persistence substrate (ADR-0013 /
 * D-043) -- {@link PostgresPersistenceConfig}'s mutually exclusive
 * counterpart, never active at the same time (D-041's "full profile
 * swap, not simultaneous").
 *
 * <p>Component-scans {@code com.workin.legacy} (the whole adapter,
 * finally reachable now that a MySQL context exists to load it into --
 * see {@code LegacyAdapterIsolationTest}) plus exactly the packages
 * ADR-0013's inventory found genuinely cross-cutting:
 * {@code identity} (for {@code JwtService} only -- everything else
 * there is guarded with {@code @Profile("!phase1-mysql")}),
 * {@code security}, {@code tenancy}, {@code config} and
 * {@code authorization} (same pattern -- the Postgres-specific classes
 * in each carry their own guard) and {@code i18n} (uniformly
 * cross-cutting, no guard needed anywhere in it). The twelve
 * pure-Postgres-domain packages are never listed here at all --
 * package-level exclusion, not per-class annotation, per ADR-0013
 * Decision.
 *
 * <p><b>No Flyway ownership of any MariaDB schema</b> (amendment 3):
 * the vendored legacy schema and Phase-1-owned tables
 * (e.g. {@code legacy_refresh_tokens}) are both treated as an external
 * contract here. This class only ever connects to a MariaDB that
 * already has its schema applied by something else -- a test container
 * ({@code AbstractLegacyMySqlTest}'s pattern) today; a real,
 * persistent instance needs its own, separately-approved
 * provisioning mechanism first (ADR-0013 Open Questions).
 */
@Configuration
@Profile("phase1-mysql")
@ComponentScan({
	"com.workin.legacy",
	"com.workin.backend.identity",
	"com.workin.backend.security",
	"com.workin.backend.tenancy",
	"com.workin.backend.config",
	"com.workin.backend.authorization",
	"com.workin.backend.i18n"
})
@EntityScan("com.workin.legacy")
@EnableJpaRepositories(
		basePackages = "com.workin.legacy",
		entityManagerFactoryRef = "legacyEntityManagerFactory",
		transactionManagerRef = "legacyTransactionManager")
public class LegacyPersistenceConfig {

	/**
	 * Plain {@code @Value}-bound connection info, not
	 * {@code JdbcConnectionDetails}: that abstraction is supplied by
	 * {@code DataSourceAutoConfiguration}, which {@code BackendApplication}
	 * excludes globally so this profile and
	 * {@link PostgresPersistenceConfig} can be mutually exclusive. No
	 * committed fallback values, matching {@code app.jwt.secret}'s
	 * pattern -- but defaulted to empty so the default profile's context
	 * (which never evaluates these) is not affected by their absence.
	 */
	@Bean
	public DataSource legacyDataSource(
			@Value("${app.legacy-db.jdbc-url}") String jdbcUrl,
			@Value("${app.legacy-db.username}") String username,
			@Value("${app.legacy-db.password}") String password,
			@Value("${app.legacy-db.connection-init-sql:}") String connectionInitSql) {
		HikariDataSource dataSource = DataSourceBuilder.create()
			.type(HikariDataSource.class)
			.url(jdbcUrl)
			.username(username)
			.password(password)
			.build();
		dataSource.setConnectionTimeout(5000);
		dataSource.setInitializationFailTimeout(5000);
		if (!connectionInitSql.isBlank()) {
			// Hikari executes this once for every new physical connection before pooling it.
			// Phase 1 tests use it to reproduce production's non-strict MariaDB session mode
			// on the application's connections, rather than only on fixture connections.
			dataSource.setConnectionInitSql(connectionInitSql);
		}
		return dataSource;
	}

	@Bean
	public LocalContainerEntityManagerFactoryBean legacyEntityManagerFactory(DataSource legacyDataSource) {
		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(legacyDataSource);
		factory.setPackagesToScan("com.workin.legacy");
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		Properties jpaProperties = new Properties();
		// No Flyway ownership of this schema (amendment 3) -- Hibernate
		// must not validate against it either; the vendored schema's own
		// drift check (check_legacy_schema_drift.py) is what keeps this
		// honest instead, the same rule the test suite already follows.
		jpaProperties.put("hibernate.hbm2ddl.auto", "none");
		factory.setJpaProperties(jpaProperties);
		return factory;
	}

	/**
	 * A shared, transaction-aware {@code EntityManager} proxy --
	 * {@link com.workin.legacy.TenantFilterActivator} takes one by plain
	 * constructor injection (no {@code @PersistenceContext}), which
	 * needs an actual {@code EntityManager}-typed bean to resolve
	 * against. {@code DataSourceAutoConfiguration}/{@code JpaBaseConfiguration}
	 * supplied this implicitly before the profile split; explicit here
	 * for the same reason the rest of this class is explicit.
	 */
	@Bean
	@Primary
	public EntityManager legacyEntityManager(EntityManagerFactory legacyEntityManagerFactory) {
		return SharedEntityManagerCreator.createSharedEntityManager(legacyEntityManagerFactory);
	}

	/**
	 * ADR-0012 / D-041's one enforcement point: binds the tenant filter
	 * to every fresh persistence context, not just the ones a call site
	 * remembers to scope. Named explicitly ({@code transactionManagerRef}
	 * above) rather than left as the default {@code transactionManager}
	 * bean name, since only one of this and
	 * {@link PostgresPersistenceConfig#transactionManager} is ever
	 * active per profile but Spring's bean-name resolution does not know
	 * that ahead of time.
	 */
	@Bean
	public PlatformTransactionManager legacyTransactionManager(
			EntityManagerFactory legacyEntityManagerFactory, TenantFilterBinder tenantFilterBinder) {
		return new TenantAwareJpaTransactionManager(legacyEntityManagerFactory, tenantFilterBinder);
	}

}
