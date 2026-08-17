package com.workin.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

import com.workin.backend.config.LegacyPersistenceConfig;
import com.workin.backend.config.PostgresPersistenceConfig;
import com.workin.bootstrap.NoScanMarker;

/**
 * Boot's single-context JPA/DataSource/Flyway autoconfiguration is
 * excluded globally and replaced by two explicit, mutually exclusive,
 * profile-gated configuration classes (ADR-0013 / D-043) --
 * {@link PostgresPersistenceConfig} (the default profile, reproducing
 * today's behaviour unchanged) and {@link LegacyPersistenceConfig} (the
 * {@code phase1-mysql} profile, new). Neither is reachable by ordinary
 * component scanning here: {@code scanBasePackageClasses = NoScanMarker.class}
 * anchors this class's own {@code @ComponentScan} at an empty package
 * -- {@code scanBasePackages = {}} looks equivalent but is not; with
 * both scan attributes empty, Spring falls back to scanning the
 * annotated class's own package, silently re-scanning
 * {@code com.workin.backend} in full (see {@link NoScanMarker}'s own
 * javadoc; caught by this class's own end-to-end test scanning up
 * a Postgres-only controller under the {@code phase1-mysql} profile
 * before this fix). The two persistence configs are wired via
 * {@code @Import} instead -- each still resolves its own
 * {@code @Profile} correctly when imported, and each supplies its own
 * {@code @ComponentScan} for everything else once active.
 */
@SpringBootApplication(
		scanBasePackageClasses = NoScanMarker.class,
		exclude = {
			DataSourceAutoConfiguration.class,
			DataJpaRepositoriesAutoConfiguration.class,
			HibernateJpaAutoConfiguration.class,
			FlywayAutoConfiguration.class
		})
@Import({PostgresPersistenceConfig.class, LegacyPersistenceConfig.class})
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
