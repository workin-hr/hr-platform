package com.workin.backend.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;

import com.workin.backend.AbstractIntegrationTest;

/**
 * F-22: proves the startup check actually fails loudly on a superuser
 * connection, and actually passes on the real app_runtime role -- not
 * just that the code compiles. Testcontainers' PostgreSQLContainer
 * default credentials are the initdb superuser (the exact H2 spike
 * finding this check exists to guard against); this test connects
 * directly with those credentials to prove the check catches it.
 */
class SuperuserStartupCheckTest extends AbstractIntegrationTest {

	@Test
	void failsLoudlyWhenConnectedAsASuperuser() {
		var superuserDataSource = DataSourceBuilder.create()
				.url(POSTGRES.getJdbcUrl())
				.username(POSTGRES.getUsername())
				.password(POSTGRES.getPassword())
				.build();

		var check = new SuperuserStartupCheck(superuserDataSource);

		assertThatThrownBy(() -> check.run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("superuser");
	}

	@Test
	void passesWhenConnectedAsTheNonSuperuserAppRuntimeRole() {
		var appRuntimeDataSource = DataSourceBuilder.create()
				.url(POSTGRES.getJdbcUrl())
				.username(TEST_RUNTIME_DB_USERNAME)
				.password(TEST_RUNTIME_DB_PASSWORD)
				.build();

		var check = new SuperuserStartupCheck(appRuntimeDataSource);

		assertDoesNotThrow(() -> check.run(null));
	}

}
