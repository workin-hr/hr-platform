package com.workin.backend.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.AbstractIntegrationTest;

/**
 * The one administrator's row follows {@code APP_PLATFORM_ADMIN_PASSWORD}
 * (ADR-0018): created when absent, re-encoded when the configured password
 * changes, left alone when nothing is configured.
 */
class PlatformAdminBootstrapTest extends AbstractIntegrationTest {

	@Autowired
	private PlatformAdminRepository platformAdminRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	@Qualifier("legacyDataSource")
	private DataSource legacyDataSource;

	/**
	 * Every class on this base shares the context and its database, so the row
	 * the other classes log in with is put back the way the context started it.
	 */
	@AfterEach
	void restoreTheConfiguredPassword() {
		new PlatformAdminBootstrap(this.platformAdminRepository, this.passwordEncoder,
				TEST_ADMIN_PASSWORD).run(null);
	}

	private void startFromNothing() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		jdbc.update("DELETE FROM platform_admin_audit_events");
		jdbc.update("DELETE FROM platform_admin_login_attempts");
		jdbc.update("DELETE FROM platform_admins");
	}

	private PlatformAdmin theAdmin() {
		return this.platformAdminRepository.findByPhone(PlatformAdminLoginService.ADMIN_IDENTIFIER).orElseThrow();
	}

	@Test
	void firstRunCreatesTheAdminFromTheConfiguredPassword() {
		startFromNothing();

		new PlatformAdminBootstrap(this.platformAdminRepository, this.passwordEncoder,
				"correct horse battery staple").run(null);

		assertThat(this.platformAdminRepository.count()).isEqualTo(1);
		assertThat(theAdmin().isActive()).isTrue();
		assertThat(this.passwordEncoder.matches("correct horse battery staple", theAdmin().getPasswordHash()))
			.as("stored as a hash, never as the value")
			.isTrue();
	}

	@Test
	void aChangedPasswordIsRotatedOnTheNextStart() {
		startFromNothing();
		new PlatformAdminBootstrap(this.platformAdminRepository, this.passwordEncoder, "first").run(null);

		new PlatformAdminBootstrap(this.platformAdminRepository, this.passwordEncoder, "second").run(null);

		assertThat(this.platformAdminRepository.count())
			.as("a rotation replaces the hash on the one row; it does not add a second administrator")
			.isEqualTo(1);
		assertThat(this.passwordEncoder.matches("second", theAdmin().getPasswordHash())).isTrue();
		assertThat(this.passwordEncoder.matches("first", theAdmin().getPasswordHash())).isFalse();
	}

	@Test
	void anUnchangedPasswordIsNotReEncoded() {
		startFromNothing();
		new PlatformAdminBootstrap(this.platformAdminRepository, this.passwordEncoder, "same").run(null);
		String hash = theAdmin().getPasswordHash();

		new PlatformAdminBootstrap(this.platformAdminRepository, this.passwordEncoder, "same").run(null);

		assertThat(theAdmin().getPasswordHash())
			.as("bcrypt salts every encoding, so an unchanged password must not be re-encoded on every start")
			.isEqualTo(hash);
	}

	@Test
	void anUnsetPasswordLeavesTheRowAlone() {
		startFromNothing();
		new PlatformAdminBootstrap(this.platformAdminRepository, this.passwordEncoder, "kept").run(null);

		new PlatformAdminBootstrap(this.platformAdminRepository, this.passwordEncoder, "").run(null);

		assertThat(this.platformAdminRepository.count()).isEqualTo(1);
		assertThat(this.passwordEncoder.matches("kept", theAdmin().getPasswordHash()))
			.as("an operator who forgot the variable keeps the last password rather than losing the login")
			.isTrue();
	}

}
