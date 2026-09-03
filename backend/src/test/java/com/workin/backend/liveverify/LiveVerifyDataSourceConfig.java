package com.workin.backend.liveverify;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Supplies {@link JdbcConnectionDetails} so the real application can be started
 * outside a test, for manual runtime verification (`./gradlew bootTestRun`).
 *
 * <p>It exists because the application <b>cannot currently be booted from a
 * plain jar</b>. {@code BackendApplication} excludes
 * {@code DataSourceAutoConfiguration}, so nothing contributes
 * {@code JdbcConnectionDetails} from {@code spring.datasource.*}, and the only
 * implementation in the repository is Testcontainers'
 * {@code @ServiceConnection} in {@code AbstractIntegrationTest}. Running the jar
 * against a real Postgres fails at startup with "required a bean of type
 * JdbcConnectionDetails that could not be found".
 *
 * <p><b>This is deliberately in test sources, not main.</b> It is a hole in the
 * deployment story, and putting a fix in production code here would paper over
 * it — the real answer belongs with the deployment work (`infrastructure/` is
 * still an empty Phase-0 boundary), not smuggled in under a verification task.
 *
 * <p>Guarded by the {@code live-verify} profile so it cannot affect any test.
 */
@Configuration
@Profile("live-verify")
public class LiveVerifyDataSourceConfig {

	@Bean
	public JdbcConnectionDetails jdbcConnectionDetails(Environment environment) {
		String url = environment.getRequiredProperty("spring.datasource.url");
		String username = environment.getRequiredProperty("spring.datasource.username");
		String password = environment.getRequiredProperty("spring.datasource.password");
		return new JdbcConnectionDetails() {

			@Override
			public String getJdbcUrl() {
				return url;
			}

			@Override
			public String getUsername() {
				return username;
			}

			@Override
			public String getPassword() {
				return password;
			}

		};
	}

}
