package com.workin.backend.platformadmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first platform administrator, replacing hr-legacy's
 * single shared admin password (hr-legacy#11,
 * docs/migration/consolidated-task-matrix.md F-26). There is no public
 * self-registration endpoint for platform admins -- that would just be
 * a differently-shaped version of the same problem this exists to fix.
 *
 * <p>Idempotent and non-fatal: if {@code platform_admins} already has a
 * row, this does nothing regardless of the configured env vars (never
 * overwrites an existing admin's password on restart). If the table is
 * empty and the bootstrap phone/password env vars are not set, this
 * logs a warning and lets startup continue -- no platform-admin
 * functionality can work yet, but that does not block tenant-facing
 * traffic.
 */
@Component
public class PlatformAdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

	private final PlatformAdminRepository platformAdminRepository;
	private final PasswordEncoder passwordEncoder;
	private final String bootstrapPhone;
	private final String bootstrapPassword;

	public PlatformAdminBootstrap(
			PlatformAdminRepository platformAdminRepository,
			PasswordEncoder passwordEncoder,
			@Value("${app.platform-admin.bootstrap.phone:}") String bootstrapPhone,
			@Value("${app.platform-admin.bootstrap.password:}") String bootstrapPassword) {
		this.platformAdminRepository = platformAdminRepository;
		this.passwordEncoder = passwordEncoder;
		this.bootstrapPhone = bootstrapPhone;
		this.bootstrapPassword = bootstrapPassword;
	}

	/**
	 * Configuration is checked <em>before</em> the table is read, and the order
	 * matters more than it looks.
	 *
	 * <p>Reading first made the {@code platform_admins} table a startup
	 * requirement for every deployment, including ones that never use the
	 * platform-admin surface. That was invisible while the surface was
	 * PostgreSQL-only, because Flyway always created the table. Under
	 * {@code phase1-mysql} the Java-owned tables are provisioned out of band
	 * (**R-023**), so an unconfigured optional feature was failing startup over
	 * a table nobody had asked for.
	 */
	@Override
	public void run(ApplicationArguments args) {
		if (bootstrapPhone.isBlank() || bootstrapPassword.isBlank()) {
			log.warn("APP_PLATFORM_ADMIN_BOOTSTRAP_PHONE/APP_PLATFORM_ADMIN_BOOTSTRAP_PASSWORD are "
					+ "not set -- skipping bootstrap. No platform-admin functionality is usable "
					+ "until an administrator is created.");
			return;
		}
		if (platformAdminRepository.count() > 0) {
			return;
		}
		platformAdminRepository.save(new PlatformAdmin(bootstrapPhone, passwordEncoder.encode(bootstrapPassword)));
		log.info("Bootstrapped the first platform administrator from APP_PLATFORM_ADMIN_BOOTSTRAP_PHONE.");
	}

}
