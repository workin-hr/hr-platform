package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.TenantAwareJpaTransactionManager;
import com.workin.legacy.TenantFilterActivator;
import com.workin.legacy.TenantFilterBinder;
import com.workin.legacy.employees.LegacyEmployeeRepository;
import com.workin.backend.tenancy.TenantScope;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link LegacyRefreshTokenService} against real MariaDB, through real
 * Spring Data repository proxies -- same {@link JpaRepositoryFactory}
 * pattern {@code LegacyTenantContextServiceTest} established, for the
 * same reason: the application still boots against PostgreSQL until
 * auth/authz is reworked, so there is no MySQL context to inject a
 * service from yet.
 *
 * <p>Every case runs inside a transaction with no {@link TenantScope}
 * established, exactly like {@code LegacyTenantContextServiceTest}.
 * {@code legacy_refresh_tokens} itself has no {@code company_id} column
 * and is never tenant-filtered (see {@link LegacyRefreshToken}'s
 * javadoc), but {@link LegacyRefreshTokenService#rotate} still queries
 * the tenant-filtered {@code employees} table for its liveness check, so
 * it still depends on {@link TenantFilterActivator#deactivateForPreTenantLookup()}
 * being called first -- {@link #rotatingATokenForADeactivatedEmployeeIsRefused}
 * and {@link #issuingAndThenRotatingAValidTokenSucceeds} both depend on
 * that call being present, the same way
 * {@code LegacyTenantContextServiceTest} depends on its own.
 */
class LegacyRefreshTokenServiceTest extends AbstractLegacyMySqlTest {

	private static EntityManagerFactory entityManagerFactory;
	private static HikariDataSource dataSource;

	private final TenantScope tenantScope = new TenantScope();
	private TransactionTemplate transactions;

	@BeforeAll
	static void seedAndBuildFactory() throws Exception {
		seedAsLegacyWould("""
				INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
				  (701, 'Refresh Co', '+201000000701', 'active', '2025-01-15 09:00:00')
				""", """
				INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
				  (771, 701, 'HQ', 1, '2025-03-01 10:00:00')
				""", """
				INSERT INTO employees
				  (id, company_id, branch_id, first_name, last_name, phone, role,
				   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
				   join_request_status, token_version, created_at)
				VALUES
				  (7011, 701, 771, 'Cara', 'C', '+201100007011', 'employee', 1, 1, 0,
				   'accepted', 1, '2025-04-01 08:00:00'),
				  (7012, 701, 771, 'Dan', 'D', '+201100007012', 'employee', 0, 1, 0,
				   'accepted', 1, '2025-04-01 08:00:00')
				""");

		dataSource = new HikariDataSource();
		dataSource.setJdbcUrl(MARIADB.getJdbcUrl());
		dataSource.setUsername(MARIADB.getUsername());
		dataSource.setPassword(MARIADB.getPassword());

		Properties jpa = new Properties();
		jpa.put("hibernate.hbm2ddl.auto", "none");

		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(dataSource);
		factory.setPackagesToScan("com.workin.legacy");
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setJpaProperties(jpa);
		factory.afterPropertiesSet();
		entityManagerFactory = factory.getObject();
	}

	@AfterAll
	static void close() {
		if (entityManagerFactory != null) {
			entityManagerFactory.close();
		}
		if (dataSource != null) {
			dataSource.close();
		}
	}

	@AfterEach
	void clearScope() {
		tenantScope.exit();
	}

	private TransactionTemplate transactions() {
		if (transactions == null) {
			transactions = new TransactionTemplate(new TenantAwareJpaTransactionManager(
					entityManagerFactory, new TenantFilterBinder(tenantScope)));
		}
		return transactions;
	}

	private LegacyRefreshTokenService service(EntityManager manager) {
		LegacyRefreshTokenRepository refreshTokenRepository =
				new JpaRepositoryFactory(manager).getRepository(LegacyRefreshTokenRepository.class);
		LegacyEmployeeRepository legacyEmployeeRepository =
				new JpaRepositoryFactory(manager).getRepository(LegacyEmployeeRepository.class);
		TenantFilterActivator tenantFilterActivator = new TenantFilterActivator(manager, tenantScope);
		return new LegacyRefreshTokenService(
				refreshTokenRepository, legacyEmployeeRepository, tenantFilterActivator, 5184000L);
	}

	private <T> T inTransaction(java.util.function.Function<LegacyRefreshTokenService, T> action) {
		return transactions().execute(status -> {
			EntityManager manager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
			return action.apply(service(manager));
		});
	}

	@Test
	void issuingAndThenRotatingAValidTokenSucceeds() {
		var issued = inTransaction(service -> service.issue(7011L));

		var rotated = inTransaction(service -> service.rotate(issued.rawToken()));

		assertThat(rotated).isPresent();
		assertThat(rotated.get().employeeId()).isEqualTo(7011L);
		assertThat(rotated.get().familyId()).isEqualTo(issued.familyId());
		assertThat(rotated.get().rawToken()).isNotEqualTo(issued.rawToken());
	}

	/**
	 * The reuse-detection property {@code RefreshTokenService.rotate}
	 * pins on the PostgreSQL side, reproduced here: presenting an
	 * already-rotated link is treated as a compromise signal and revokes
	 * the whole family, not just the presented link.
	 */
	@Test
	void reusingAnAlreadyRotatedTokenRevokesTheWholeFamily() {
		// Employee 7011 (active) -- reuse detection, not liveness, is
		// what this test proves; 7012 is reserved for the liveness case.
		var issued = inTransaction(service -> service.issue(7011L));

		var firstRotation = inTransaction(service -> service.rotate(issued.rawToken()));
		assertThat(firstRotation).isPresent();

		// Presenting the original (now-ROTATED) token again.
		var reuseAttempt = inTransaction(service -> service.rotate(issued.rawToken()));
		assertThat(reuseAttempt)
				.describedAs("a retired link presented again must be refused, not silently re-accepted")
				.isEmpty();

		// The rotated link from the legitimate first rotation must also
		// now be revoked, proving the whole family was retired -- not
		// just the reused link.
		var secondRotationAttempt = inTransaction(service -> service.rotate(firstRotation.get().rawToken()));
		assertThat(secondRotationAttempt)
				.describedAs("reuse must revoke the entire family, including links issued after the reused one")
				.isEmpty();
	}

	/**
	 * The liveness re-check this service exists to perform, using
	 * {@code LegacyEmployeeRepository} in place of the PostgreSQL
	 * identity/membership lookups {@code RefreshTokenService} uses.
	 * Employee 7012 was seeded {@code is_active = 0}.
	 */
	@Test
	void rotatingATokenForADeactivatedEmployeeIsRefused() {
		var issued = inTransaction(service -> service.issue(7012L));

		var rotated = inTransaction(service -> service.rotate(issued.rawToken()));

		assertThat(rotated)
				.describedAs("a deactivated employee's session must not survive rotation")
				.isEmpty();
	}

	@Test
	void logoutRevokesTheFamilySoARotationAfterwardsIsRefused() {
		var issued = inTransaction(service -> service.issue(7011L));

		inTransaction(service -> {
			service.logout(issued.rawToken());
			return null;
		});

		var rotated = inTransaction(service -> service.rotate(issued.rawToken()));
		assertThat(rotated).isEmpty();
	}

}
