package com.workin.backend.config;

import java.util.Properties;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The default profile's persistence substrate, made explicit
 * (ADR-0013 / D-043). Everything here reproduces what Spring Boot's
 * single-context autoconfiguration did implicitly before this class
 * existed -- {@code BackendApplication} now excludes that
 * autoconfiguration globally so {@link LegacyPersistenceConfig} can be
 * its mutually exclusive MariaDB counterpart, and this class is what
 * keeps default-profile behaviour byte-for-byte unchanged.
 *
 * <p>{@code @Profile("!phase1-mysql")}: the default, or any profile
 * other than {@code phase1-mysql}. Never both this and
 * {@link LegacyPersistenceConfig} active at once -- that is what "full
 * profile swap, not simultaneous" means at the bean-definition level
 * (D-041).
 */
@Configuration
@Profile("!phase1-mysql")
@ComponentScan("com.workin.backend")
@EntityScan("com.workin.backend")
@EnableJpaRepositories(basePackages = "com.workin.backend")
public class PostgresPersistenceConfig {

	/**
	 * Two-DataSource RLS pattern proven in the H2 spike
	 * (docs/migration/technical-spike-plan.md's "Full Spike Findings"):
	 * Flyway (migrations/DDL) connects as the superuser so it can create
	 * roles and RLS policies; the application's runtime JPA DataSource
	 * connects as the non-superuser {@code app_runtime} role so Postgres
	 * RLS actually applies to it -- Postgres always bypasses RLS for a
	 * superuser connection regardless of FORCE ROW LEVEL SECURITY.
	 *
	 * <p>Moved here unchanged from the former {@code RlsDataSourceConfig}
	 * (ADR-0013 Decision §3) -- same beans, now explicitly profile-gated
	 * rather than unconditional.
	 */
	@Bean
	@FlywayDataSource
	public DataSource flywayDataSource(JdbcConnectionDetails connectionDetails) {
		HikariDataSource dataSource = DataSourceBuilder.create()
			.type(HikariDataSource.class)
			.url(connectionDetails.getJdbcUrl())
			.username(connectionDetails.getUsername())
			.password(connectionDetails.getPassword())
			.build();
		dataSource.setConnectionTimeout(5000);
		dataSource.setInitializationFailTimeout(5000);
		return dataSource;
	}

	@Bean
	@Primary
	public DataSource applicationDataSource(
			JdbcConnectionDetails connectionDetails,
			@Value("${app.runtime-db.username}") String runtimeDbUsername,
			@Value("${app.runtime-db.password}") String runtimeDbPassword) {
		HikariDataSource dataSource = DataSourceBuilder.create()
			.type(HikariDataSource.class)
			.url(connectionDetails.getJdbcUrl())
			.username(runtimeDbUsername)
			.password(runtimeDbPassword)
			.build();
		dataSource.setConnectionTimeout(5000);
		dataSource.setInitializationFailTimeout(5000);
		return dataSource;
	}

	/**
	 * Flyway against the superuser datasource, {@code common} +
	 * {@code rls} locations -- what
	 * {@code spring.flyway.locations=classpath:db/migration/common,classpath:db/migration/rls}
	 * did implicitly via {@code FlywayAutoConfiguration} before this
	 * profile split. {@code app_runtime_db_username}/{@code _password}
	 * placeholders match the RLS migrations' own
	 * {@code CREATE ROLE app_runtime} statements.
	 */
	@Bean
	public Flyway flyway(
			@FlywayDataSource DataSource flywayDataSource,
			@Value("${app.runtime-db.username}") String runtimeDbUsername,
			@Value("${app.runtime-db.password}") String runtimeDbPassword) {
		return Flyway.configure()
				.dataSource(flywayDataSource)
				.locations("classpath:db/migration/common", "classpath:db/migration/rls")
				.placeholders(java.util.Map.of(
						"app_runtime_db_username", runtimeDbUsername,
						"app_runtime_db_password", runtimeDbPassword))
				.load();
	}

	@Bean
	public LocalContainerEntityManagerFactoryBean entityManagerFactory(
			// No qualifier needed: applicationDataSource is @Primary among
			// the two DataSource beans, so plain by-type injection already
			// resolves to it, not flywayDataSource.
			DataSource applicationDataSource,
			// Depending on the initializer itself, not just the Flyway
			// bean, forces migration to actually have run (afterPropertiesSet)
			// before Hibernate validates the schema it produced --
			// depending on Flyway alone would only order bean creation,
			// not the migrate() call FlywayMigrationInitializer makes.
			FlywayMigrationInitializer flywayInitializer) {
		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(applicationDataSource);
		factory.setPackagesToScan("com.workin.backend");
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		Properties jpaProperties = new Properties();
		jpaProperties.put("hibernate.hbm2ddl.auto", "validate");
		factory.setJpaProperties(jpaProperties);
		return factory;
	}

	@Bean
	public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
		return new JpaTransactionManager(entityManagerFactory);
	}

	/**
	 * Forces Flyway to run before Hibernate validates the schema it
	 * produced -- {@code FlywayAutoConfiguration} ordered this
	 * implicitly via {@code FlywayMigrationInitializer}; explicit here
	 * for the same reason the rest of this class is explicit.
	 */
	@Bean
	public FlywayMigrationInitializer flywayInitializer(Flyway flyway) {
		return new FlywayMigrationInitializer(flyway);
	}

}
