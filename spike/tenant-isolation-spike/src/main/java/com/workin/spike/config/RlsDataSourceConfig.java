package com.workin.spike.config;

import javax.sql.DataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * H2 spike, "isolation-rls" profile only. Real finding, confirmed by
 * actually running this spike: Postgres Row-Level Security is always
 * bypassed for superusers, regardless of {@code FORCE ROW LEVEL
 * SECURITY} -- and Testcontainers' {@code PostgreSQLContainer} default
 * user becomes the initdb superuser for a fresh container. Connecting
 * the application through that default connection would make RLS
 * silently do nothing.
 *
 * <p>V4__create_non_superuser_app_role.sql (rls profile only) creates a
 * real, unprivileged {@code app_runtime} role. This configuration gives
 * JPA/Hibernate a {@code @Primary} DataSource connecting as that role,
 * while Flyway (which needs DDL/role-creation privileges) keeps using
 * the original superuser connection via the {@code @FlywayDataSource}
 * qualifier -- mirroring the realistic production shape where
 * migrations run as an owner/admin role and the application runtime
 * connects as a more restricted one.
 */
@Configuration
@Profile("isolation-rls")
public class RlsDataSourceConfig {

    @Bean
    @FlywayDataSource
    public DataSource flywayDataSource(JdbcConnectionDetails connectionDetails) {
        return DataSourceBuilder.create()
                .url(connectionDetails.getJdbcUrl())
                .username(connectionDetails.getUsername())
                .password(connectionDetails.getPassword())
                .build();
    }

    @Bean
    @Primary
    public DataSource applicationDataSource(JdbcConnectionDetails connectionDetails) {
        return DataSourceBuilder.create()
                .url(connectionDetails.getJdbcUrl())
                .username("app_runtime")
                .password("app_runtime_password")
                .build();
    }
}
