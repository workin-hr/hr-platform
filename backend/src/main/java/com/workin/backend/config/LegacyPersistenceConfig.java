package com.workin.backend.config;

import java.util.Properties;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import com.workin.legacy.LegacySessionDataSource;
import com.workin.legacy.TenantAwareJpaTransactionManager;
import com.workin.legacy.TenantFilterBinder;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The application's persistence substrate: the legacy MariaDB, the schema the
 * frozen PHP stack owns, and the handful of tables this application adds
 * beside it.
 *
 * <p>This used to be one of two, profile-gated against a PostgreSQL
 * counterpart and given its own component scan so that neither could reach
 * the other (ADR-0013 / D-043). The PostgreSQL half is gone -- MySQL is the
 * production database and stays so (ADR-0017) -- and this is an ordinary
 * configuration again, found by the application's scan like everything else.
 * What it still does deliberately: build the data source itself rather than
 * through Boot's autoconfiguration, because the session-scoped
 * {@link LegacySessionDataSource} and the tenant-aware transaction manager
 * are not things that autoconfiguration can express.
 *
 * <p><b>No Flyway ownership of any MariaDB schema</b> (ADR-0013 amendment 3):
 * the vendored legacy schema and the Phase-1-owned tables
 * (e.g. {@code legacy_refresh_tokens}) are both treated as an external
 * contract. This class only ever connects to a MariaDB that already has its
 * schema applied by something else -- {@code phase1_extensions.sql}, applied
 * by the operator ({@code docs/operations/provisioning-phase1-tables.md}) and
 * checked at startup by {@link Phase1SchemaCheck}.
 */
@Configuration
@EntityScan({"com.workin.legacy", "com.workin.backend.platformadmin"})
@EnableJpaRepositories(
		basePackages = {"com.workin.legacy", "com.workin.backend.platformadmin"},
		entityManagerFactoryRef = "legacyEntityManagerFactory",
		transactionManagerRef = "legacyTransactionManager")
public class LegacyPersistenceConfig {

	/**
	 * Plain {@code @Value}-bound connection info, not
	 * {@code JdbcConnectionDetails}: that abstraction is supplied by
	 * {@code DataSourceAutoConfiguration}, which {@code BackendApplication}
	 * excludes globally -- there is one database and this class configures
	 * it (ADR-0017). No committed fallback values, matching
	 * {@code app.jwt.secret}'s pattern, but defaulted to empty so a context
	 * that never opens the database is not held hostage to their absence.
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
			//
			// Static session state only. The runtime time zone is deliberately NOT here:
			// it is resolved from `configs.is_daylight_saving` on every checkout by
			// LegacySessionDataSource (D-099), because a pooled connection outlives the
			// request that created it and this hook would leave the pool pinned to
			// whatever the flag said at startup.
			dataSource.setConnectionInitSql(connectionInitSql);
		}
		// getDB() resolves the offset and issues SET time_zone every time it opens a
		// PDO connection; the wrapper does the same on every checkout (D-099/D-083).
		return new LegacySessionDataSource(dataSource);
	}

	@Bean
	public LocalContainerEntityManagerFactoryBean legacyEntityManagerFactory(DataSource legacyDataSource) {
		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(legacyDataSource);
		factory.setPackagesToScan("com.workin.legacy", "com.workin.backend.platformadmin");
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
	 * bean name: the reference is explicit at every use, so nothing depends
	 * on which manager Spring would otherwise pick by name.
	 */
	@Bean
	public PlatformTransactionManager legacyTransactionManager(
			EntityManagerFactory legacyEntityManagerFactory, TenantFilterBinder tenantFilterBinder) {
		return new TenantAwareJpaTransactionManager(legacyEntityManagerFactory, tenantFilterBinder);
	}

}
