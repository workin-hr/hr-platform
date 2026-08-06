package com.workin.backend.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.AbstractIntegrationTest;

/**
 * Exercises PlatformAdminBootstrap directly (constructed with literal
 * test values, same pattern as JwtSecretStartupCheckTest) rather than
 * relying on the application-context-level bean, which always runs with
 * blank bootstrap properties in this test suite (AbstractIntegrationTest
 * sets none) and is therefore always a no-op at context startup.
 *
 * <p>platform_admins is shared, unreset database state across every
 * test class in this suite (AbstractIntegrationTest's singleton
 * container). PlatformAdminAuthFlowTest/PlatformAdminDomainSeparationTest
 * insert their own fixture rows directly, so "table is empty" cannot be
 * assumed here -- the empty-table scenario is established explicitly.
 */
class PlatformAdminBootstrapTest extends AbstractIntegrationTest {

	@Autowired
	private PlatformAdminRepository platformAdminRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Test
	void firstRunCreatesTheAdminAndASecondRunDoesNotDuplicateOrResetIt() {
		new JdbcTemplate(flywayDataSource).update("DELETE FROM platform_admins");
		String phone = "+2099" + System.nanoTime() % 100_000_000L;
		PlatformAdminBootstrap bootstrap = new PlatformAdminBootstrap(
				platformAdminRepository, passwordEncoder, phone, "correct horse battery staple");

		bootstrap.run(null);

		PlatformAdmin created = platformAdminRepository.findByPhone(phone).orElseThrow();
		assertThat(created.isActive()).isTrue();
		assertThat(passwordEncoder.matches("correct horse battery staple", created.getPasswordHash())).isTrue();

		// A second run, even with different would-be credentials, must be
		// a no-op -- platform_admins already has a row, so this must
		// never silently reset an existing administrator's password.
		PlatformAdminBootstrap secondRun = new PlatformAdminBootstrap(
				platformAdminRepository, passwordEncoder, "+201111111111", "a-completely-different-password");
		secondRun.run(null);

		assertThat(platformAdminRepository.count()).isEqualTo(1);
		PlatformAdmin unchanged = platformAdminRepository.findByPhone(phone).orElseThrow();
		assertThat(passwordEncoder.matches("correct horse battery staple", unchanged.getPasswordHash())).isTrue();
	}

	@Test
	void doesNothingWhenBootstrapCredentialsAreBlank() {
		long countBefore = platformAdminRepository.count();
		PlatformAdminBootstrap bootstrap = new PlatformAdminBootstrap(platformAdminRepository, passwordEncoder, "", "");

		bootstrap.run(null);

		assertThat(platformAdminRepository.count()).isEqualTo(countBefore);
	}

}
