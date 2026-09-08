package com.workin.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * Boot's single-context DataSource/JPA autoconfiguration is excluded and
 * {@link com.workin.backend.config.LegacyPersistenceConfig} supplies the
 * substrate instead: the legacy MariaDB, its session-scoped data source and
 * the tenant-aware transaction manager, none of which the autoconfiguration
 * can express.
 *
 * <p>There used to be two of these, profile-gated, with this class's own
 * component scan anchored at an empty package so that neither could be found
 * by accident (ADR-0013 / D-043). The PostgreSQL half is gone -- MySQL is the
 * production database and stays so (ADR-0017) -- and with one substrate there
 * is nothing to keep apart, so this is an ordinary application class again.
 */
@SpringBootApplication(
		scanBasePackages = "com.workin",
		exclude = {
			DataSourceAutoConfiguration.class,
			DataJpaRepositoriesAutoConfiguration.class,
			HibernateJpaAutoConfiguration.class
		})
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
