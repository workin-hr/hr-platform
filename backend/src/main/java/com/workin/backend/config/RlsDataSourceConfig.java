package com.workin.backend.config;

import javax.sql.DataSource;

import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Two-DataSource RLS pattern proven in the H2 spike
 * (docs/migration/technical-spike-plan.md's "Full Spike Findings"):
 * Flyway (migrations/DDL) connects as the superuser so it can create
 * roles and RLS policies; the application's runtime JPA DataSource
 * connects as the non-superuser {@code app_runtime} role so Postgres
 * RLS actually applies to it -- Postgres always bypasses RLS for a
 * superuser connection regardless of FORCE ROW LEVEL SECURITY.
 */
@Configuration
public class RlsDataSourceConfig {

	@Bean
	@FlywayDataSource
	public DataSource flywayDataSource(JdbcConnectionDetails connectionDetails) {
		HikariDataSource dataSource = DataSourceBuilder.create()
			.type(HikariDataSource.class)
			.url(connectionDetails.getJdbcUrl())
			.username(connectionDetails.getUsername())
			.password(connectionDetails.getPassword())
			.build();
		// Fail fast on a bad connection instead of retrying silently for
		// minutes -- a hung connection attempt should be loud, not a
		// mysteriously stuck test run.
		dataSource.setConnectionTimeout(5000);
		dataSource.setInitializationFailTimeout(5000);
		return dataSource;
	}

	@Bean
	@Primary
	public DataSource applicationDataSource(JdbcConnectionDetails connectionDetails) {
		HikariDataSource dataSource = DataSourceBuilder.create()
			.type(HikariDataSource.class)
			.url(connectionDetails.getJdbcUrl())
			.username("app_runtime")
			.password("app_runtime_password")
			.build();
		dataSource.setConnectionTimeout(5000);
		dataSource.setInitializationFailTimeout(5000);
		return dataSource;
	}

}
