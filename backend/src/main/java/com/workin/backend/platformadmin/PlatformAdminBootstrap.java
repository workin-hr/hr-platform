package com.workin.backend.platformadmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.workin.backend.platformadmin.web.PlatformAdminSessionInventory;

/**
 * Keeps the one administrator's row in step with {@code APP_PLATFORM_ADMIN_PASSWORD}.
 *
 * <p>PHP's model, deliberately (ADR-0018): the dashboard has one admin login
 * and its password is deployment configuration, changed by changing the
 * configuration and restarting. What differs is where the value lives -- a
 * bcrypt hash in {@code platform_admins}, never the plaintext -- and that the
 * row exists at all, because the audit log and the session index hang off its
 * id.
 *
 * <p>Idempotent per start. The row is created if absent and its hash
 * refreshed if the configured password no longer matches it, so a rotation is
 * a restart and nothing else. An unset password is logged and otherwise left
 * alone: the row keeps its last hash, which is what an operator who forgot to
 * carry the variable over would want, and a database with no row at all
 * simply cannot be logged into -- as PHP with an empty constant cannot.
 *
 * <p><b>A rotation ends the sessions opened under the old password.</b> They
 * are server-side rows, and nothing about a changed hash invalidates one on
 * its own: per-request revalidation asks whether the administrator is active,
 * not which password let them in. Without this, rotating after a session was
 * believed stolen would leave the thief up to the eight-hour absolute limit --
 * and rotating is exactly what an operator reaches for in that moment.
 */
@Component
public class PlatformAdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

	private final PlatformAdminRepository platformAdminRepository;
	private final PasswordEncoder passwordEncoder;
	private final PlatformAdminSessionInventory sessions;
	private final String password;

	public PlatformAdminBootstrap(
			PlatformAdminRepository platformAdminRepository,
			PasswordEncoder passwordEncoder,
			PlatformAdminSessionInventory sessions,
			@Value("${app.platform-admin.password:}") String password) {
		this.platformAdminRepository = platformAdminRepository;
		this.passwordEncoder = passwordEncoder;
		this.sessions = sessions;
		this.password = password;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (this.password.isBlank()) {
			log.warn("APP_PLATFORM_ADMIN_PASSWORD is not set -- the dashboard keeps whatever "
					+ "password it last had, or cannot be logged into if it never had one.");
			return;
		}
		PlatformAdmin admin = this.platformAdminRepository
			.findByPhone(PlatformAdminLoginService.ADMIN_IDENTIFIER).orElse(null);
		if (admin == null) {
			this.platformAdminRepository.save(new PlatformAdmin(
					PlatformAdminLoginService.ADMIN_IDENTIFIER, this.passwordEncoder.encode(this.password)));
			log.info("Provisioned the dashboard administrator from APP_PLATFORM_ADMIN_PASSWORD.");
			return;
		}
		if (!this.passwordEncoder.matches(this.password, admin.getPasswordHash())) {
			admin.setPasswordHash(this.passwordEncoder.encode(this.password));
			this.platformAdminRepository.save(admin);
			// Nothing is spared: a session opened under the old password must
			// not outlive it.
			this.sessions.revokeEverything(admin.getId(), null);
			log.info("The dashboard administrator's password was rotated from "
					+ "APP_PLATFORM_ADMIN_PASSWORD; every existing session was ended.");
		}
	}

}
