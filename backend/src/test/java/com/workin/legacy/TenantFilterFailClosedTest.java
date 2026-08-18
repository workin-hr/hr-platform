package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Properties;

import com.workin.backend.tenancy.NoTenantScopeException;
import com.workin.backend.tenancy.TenantScope;
import com.workin.legacy.employees.LegacyEmployee;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * The Phase 1 replacement for {@code RlsFailClosedTest}, against a real
 * MariaDB (ADR-0012 / D-041).
 *
 * <p>The original proved PostgreSQL RLS fails closed: with no
 * {@code app.current_company_id} set, a read of a row that genuinely
 * exists returned nothing. This proves the corresponding properties for
 * the mechanism that replaces it, and one of them is the opposite shape
 * — which is the entire reason the surrounding design looks the way it
 * does.
 *
 * <p>Runs against its own {@link EntityManagerFactory} rather than the
 * application context: the application still boots on PostgreSQL until
 * auth/authz is finished, so there is no MySQL context to load these
 * entities into yet. The factory scans only {@code com.workin.legacy},
 * which is also a check that the adapter stands up on its own.
 */
class TenantFilterFailClosedTest extends AbstractLegacyMySqlTest {

	private static EntityManagerFactory entityManagerFactory;
	private static HikariDataSource dataSource;

	private final TenantScope tenantScope = new TenantScope();
	private EntityManager entityManager;

	@BeforeAll
	static void seedTwoTenantsAndBuildFactory() throws Exception {
		// Two companies, so "sees only its own" is a real assertion
		// rather than a tautology about a single-tenant database.
		seedAsLegacyWould("""
				INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
				  (901, 'Filter Co A', '+201000000901', 'active', '2025-01-15 09:00:00'),
				  (902, 'Filter Co B', '+201000000902', 'active', '2025-01-15 09:00:00')
				""", """
				INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
				  (971, 901, 'A HQ', 1, '2025-03-01 10:00:00'),
				  (972, 902, 'B HQ', 1, '2025-03-01 10:00:00')
				""", """
				INSERT INTO employees
				  (id, company_id, branch_id, first_name, last_name, phone, role,
				   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
				   join_request_status, token_version, created_at)
				VALUES
				  (9011, 901, 971, 'Ann', 'A', '+201100009011', 'employee', 1, 1, 0,
				   'accepted', 1, '2025-04-01 08:00:00'),
				  (9012, 901, 971, 'Adam', 'A', '+201100009012', 'employee', 1, 1, 0,
				   'accepted', 1, '2025-04-01 08:00:00'),
				  (9021, 902, 972, 'Bea', 'B', '+201100009021', 'employee', 1, 1, 0,
				   'accepted', 1, '2025-04-01 08:00:00')
				""");

		dataSource = new HikariDataSource();
		dataSource.setJdbcUrl(MARIADB.getJdbcUrl());
		dataSource.setUsername(MARIADB.getUsername());
		dataSource.setPassword(MARIADB.getPassword());

		Properties jpa = new Properties();
		// Phase 1 adopts an existing schema; it never owns its DDL and
		// must not validate against a schema Java did not design.
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
	static void closeFactory() {
		if (entityManagerFactory != null) {
			entityManagerFactory.close();
		}
		if (dataSource != null) {
			dataSource.close();
		}
	}

	@AfterEach
	void closeEntityManagerAndScope() {
		if (entityManager != null && entityManager.isOpen()) {
			entityManager.close();
		}
		tenantScope.exit();
	}

	private List<LegacyEmployee> readSeededEmployees() {
		return entityManager
				.createQuery("SELECT e FROM LegacyEmployee e WHERE e.id IN (9011, 9012, 9021)",
						LegacyEmployee.class)
				.getResultList();
	}

	private TenantFilterActivator activatorFor(EntityManager manager) {
		return new TenantFilterActivator(manager, tenantScope);
	}

	@Test
	void anActivatedFilterShowsOnlyTheScopedTenantsRows() {
		entityManager = entityManagerFactory.createEntityManager();
		tenantScope.enter(901L);
		activatorFor(entityManager).activate();

		assertThat(readSeededEmployees())
				.extracting(LegacyEmployee::getId)
				.containsExactlyInAnyOrder(9011L, 9012L);
	}

	/** The other tenant, to prove the predicate is bound, not constant. */
	@Test
	void adifferentScopeShowsThatTenantsRowsInstead() {
		entityManager = entityManagerFactory.createEntityManager();
		tenantScope.enter(902L);
		activatorFor(entityManager).activate();

		assertThat(readSeededEmployees())
				.extracting(LegacyEmployee::getId)
				.containsExactly(9021L);
	}

	/**
	 * <b>The finding that justifies the whole design.</b>
	 *
	 * <p>Under PostgreSQL RLS, forgetting to establish scope returned
	 * <em>zero</em> rows — safe by default. Under a Hibernate filter,
	 * forgetting to activate returns <em>every tenant's</em> rows. This
	 * test asserts that fail-open default explicitly, because it is the
	 * hazard the rest of ADR-0012 exists to contain, and a design
	 * premise nobody should have to take on trust.
	 *
	 * <p>If this test ever starts failing because Hibernate began
	 * filtering by default, the surrounding controls could be relaxed —
	 * and we would want to find that out here rather than assume it.
	 */
	@Test
	void anUnactivatedFilterRestrictsNothingAtAll() {
		entityManager = entityManagerFactory.createEntityManager();

		assertThat(entityManager.unwrap(Session.class).getEnabledFilter(TenantFilter.NAME))
				.describedAs("the filter must not be auto-enabled -- see package-info")
				.isNull();
		assertThat(readSeededEmployees())
				.describedAs("an un-enabled Hibernate filter restricts nothing: this is fail-OPEN, "
						+ "and is why TenantScope.current() raises rather than returning empty")
				.extracting(LegacyEmployee::getId)
				.containsExactlyInAnyOrder(9011L, 9012L, 9021L);
	}

	/**
	 * So the only sanctioned path to a tenant-scoped read refuses to
	 * proceed unscoped. This is the fail-closed guarantee, relocated
	 * from the database into the one component that turns the filter on.
	 */
	@Test
	void activatingWithNoScopeEstablishedRaisesRatherThanReadingEveryTenant() {
		entityManager = entityManagerFactory.createEntityManager();

		assertThatThrownBy(() -> activatorFor(entityManager).activate())
				.isInstanceOf(NoTenantScopeException.class)
				.hasMessageContaining("no tenant scope");

		assertThat(entityManager.unwrap(Session.class).getEnabledFilter(TenantFilter.NAME))
				.describedAs("a refused activation must leave the filter off, not half-applied")
				.isNull();
	}

	/**
	 * The pre-tenant escape hatch works and is explicit. Legacy resolves
	 * a phone before any tenant is known
	 * ({@code login_employee.php:18-48}), so this path has to exist —
	 * naming it is what keeps it a reviewable choice rather than the
	 * accident of having forgotten to scope.
	 */
	@Test
	void thePreTenantLookupEscapeHatchIsExplicitAndReversible() {
		entityManager = entityManagerFactory.createEntityManager();
		tenantScope.enter(901L);
		TenantFilterActivator activator = activatorFor(entityManager);
		activator.activate();
		assertThat(readSeededEmployees()).hasSize(2);

		activator.deactivateForPreTenantLookup();
		assertThat(readSeededEmployees())
				.describedAs("the deliberately pre-tenant path sees across tenants, by design")
				.hasSize(3);

		activator.activate();
		assertThat(readSeededEmployees())
				.describedAs("and scope can be restored afterwards")
				.hasSize(2);
	}

}
