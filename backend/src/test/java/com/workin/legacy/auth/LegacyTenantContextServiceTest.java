package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;

import com.workin.backend.tenancy.NoTenantScopeException;
import com.workin.backend.tenancy.TenantScope;
import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.TenantAwareJpaTransactionManager;
import com.workin.legacy.TenantFilterActivator;
import com.workin.legacy.TenantFilterBinder;
import com.workin.legacy.employees.LegacyEmployeeRepository;
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
 * The legacy-side {@code TenantContextIsolationTest} equivalent for
 * {@link LegacyTenantContextService} — proving punch-list item #9's
 * trust boundary before item #10 gets to attack it over real HTTP.
 *
 * <p>Deliberately against real MariaDB, through a real Spring Data
 * {@link LegacyEmployeeRepository} proxy (built with
 * {@link JpaRepositoryFactory} rather than a full Spring context, the
 * same reason {@code TenantBindingEndToEndTest} builds its own
 * {@link EntityManagerFactory} — the application still boots against
 * PostgreSQL until auth/authz is reworked, so there is no MySQL context
 * to inject a repository from yet). Using the real repository interface
 * here, rather than raw JPQL like its siblings, is also the first proof
 * that {@code LegacyEmployeeRepository}'s derived query methods actually
 * work through Hibernate against the real legacy schema —
 * {@code LegacyEmployeeAdapterTest}'s javadoc names this as deferred
 * work; this is that step, scoped to exactly the one method this
 * service calls.
 *
 * <p>Every test runs the service inside a transaction bound by
 * {@link TenantAwareJpaTransactionManager} with no {@link TenantScope}
 * established, so the filter is bound to
 * {@code TenantFilter.NO_TENANT} before the service's own
 * {@code deactivateForPreTenantLookup()} call runs — proving that call
 * is load-bearing, not decorative. Forgetting it would make every case
 * below fail with "no such employee", for the wrong reason.
 */
class LegacyTenantContextServiceTest extends AbstractLegacyMySqlTest {

	private static EntityManagerFactory entityManagerFactory;
	private static HikariDataSource dataSource;

	private final TenantScope tenantScope = new TenantScope();
	private TransactionTemplate transactions;

	@BeforeAll
	static void seedAndBuildFactory() throws Exception {
		// Two companies and two employees, so a cross-tenant claim has a
		// real, wrong answer to land on rather than "the only row".
		seedAsLegacyWould("""
				INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
				  (601, 'Ctx Co A', '+201000000601', 'active', '2025-01-15 09:00:00'),
				  (602, 'Ctx Co B', '+201000000602', 'active', '2025-01-15 09:00:00')
				""", """
				INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
				  (671, 601, 'A HQ', 1, '2025-03-01 10:00:00'),
				  (672, 602, 'B HQ', 1, '2025-03-01 10:00:00')
				""", """
				INSERT INTO employees
				  (id, company_id, branch_id, first_name, last_name, phone, role,
				   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
				   join_request_status, token_version, created_at)
				VALUES
				  (6011, 601, 671, 'Ann', 'A', '+201100006011', 'employee', 1, 1, 0,
				   'accepted', 1, '2025-04-01 08:00:00'),
				  (6021, 602, 672, 'Bea', 'B', '+201100006021', 'employee', 1, 1, 0,
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

	/**
	 * Every case runs the service fresh, inside its own NO_TENANT-bound
	 * transaction — no scope established beforehand, exactly the state
	 * the real resolver call site is in before this method returns.
	 */
	private long validate(long authenticatedIdentityId, long claimedEmployeeId, long claimedCompanyId) {
		return transactions().execute(status -> {
			EntityManager manager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
			LegacyEmployeeRepository repository = new JpaRepositoryFactory(manager)
					.getRepository(LegacyEmployeeRepository.class);
			LegacyTenantContextService service = new LegacyTenantContextService(
					repository, new TenantFilterActivator(manager, tenantScope));
			return service.validate(authenticatedIdentityId, claimedEmployeeId, claimedCompanyId);
		});
	}

	@Test
	void aLegitimateClaimIsAcceptedAndReturnsTheEmployeesRealCompany() {
		assertThat(validate(6011L, 6011L, 601L)).isEqualTo(601L);
	}

	/**
	 * <b>The scenario item #10 exists to attack.</b> A token honestly
	 * identifies employee 6011, but its {@code tenant_id} claim names the
	 * company that employee does not belong to — whether from tampering,
	 * an issuance bug, or a stale token after a transfer. The claim must
	 * not be trusted; the employee's real company must be looked up and
	 * compared, and the mismatch refused.
	 */
	@Test
	void aClaimedCompanyThatDoesNotMatchTheEmployeesRealCompanyIsRejected() {
		assertThatThrownBy(() -> validate(6011L, 6011L, 602L))
				.isInstanceOf(NoTenantScopeException.class)
				.hasMessageContaining("does not belong to claimed company");
	}

	/**
	 * The other half of the cross-check: a claimed employee id that
	 * belongs to someone else entirely, not just a different company.
	 * {@code TenantContextService}'s ownership check has the same shape
	 * on the PostgreSQL side ("membership does not belong to the
	 * authenticated identity").
	 */
	@Test
	void aclaimedEmployeeThatIsNotTheAuthenticatedIdentityIsRejected() {
		assertThatThrownBy(() -> validate(6011L, 6021L, 602L))
				.isInstanceOf(NoTenantScopeException.class)
				.hasMessageContaining("does not belong to the authenticated identity");
	}

	@Test
	void aclaimedEmployeeThatDoesNotExistAtAllIsRejected() {
		assertThatThrownBy(() -> validate(9999L, 9999L, 601L))
				.isInstanceOf(NoTenantScopeException.class)
				.hasMessageContaining("no legacy employee 9999 exists");
	}

	/**
	 * Proves {@code deactivateForPreTenantLookup()} is load-bearing.
	 * Every case above already depends on it implicitly — this one makes
	 * the dependency explicit by checking the honest-path result against
	 * what {@code TenantFilterFailClosedTest} already established:
	 * {@code TenantAwareJpaTransactionManager} binds
	 * {@code TenantFilter.NO_TENANT} to a fresh, unscoped transaction, so
	 * without the deactivation call this lookup would see zero rows
	 * unconditionally and every legitimate login would fail closed for
	 * the wrong reason.
	 */
	@Test
	void theLookupRunsInsideATransactionWithNoTenantScopeEstablishedYet() {
		assertThat(tenantScope.isEstablished())
				.describedAs("this method exists to establish scope -- it must not require it first")
				.isFalse();

		assertThat(validate(6021L, 6021L, 602L)).isEqualTo(602L);
	}

}
