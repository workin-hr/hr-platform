package com.workin.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyMariaDb;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * That the application ships no account nobody asked for.
 *
 * <p>Spring Boot's {@code UserDetailsServiceAutoConfiguration} creates a `user`
 * with a password printed at startup whenever no {@link UserDetailsService}
 * bean exists. {@code BackendApplication} excludes it, and the exclusion is one
 * line in a list -- easy to drop while adding another, and silent when dropped:
 * the application still starts, the log line scrolls past, and a credential
 * that authenticates against every chain without one exists in production.
 *
 * <p>Asserted against a real context rather than by reading the annotation,
 * because what matters is the bean not being there, however it came not to be.
 */
@SpringBootTest(classes = BackendApplication.class)
class NoDefaultUserTest {

	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	@Autowired
	private ApplicationContext context;

	@Test
	void bootGeneratesNoDefaultAccount() {
		Map<String, UserDetailsService> beans = this.context.getBeansOfType(UserDetailsService.class);

		assertThat(beans)
				.as("Boot's in-memory `user` authenticates against any chain that has no "
						+ "authentication of its own, with a password only the startup log has "
						+ "seen. The exclusion in BackendApplication is what stops it")
				.isEmpty();
	}

}
