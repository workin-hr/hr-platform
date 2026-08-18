package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Properties;

import com.workin.backend.tenancy.NoTenantScopeException;
import com.workin.backend.tenancy.TenantScope;
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
 * {@link TenantFilterFailClosedTest}'s pattern, extended to P-1b ({@link
 * EmployeeDerivedTenantFilter}, proven via {@link AttendanceProbeRow})
 * and P-1c ({@link DepartmentBranchesTenantFilter}, proven via {@link
 * DepartmentBranchProbeRow}) — PR 12.2's explicit requirement that each
 * policy is "asserted per policy, not once" (Item 12 specification §8).
 * P-1a's own proof is untouched and unrepeated here.
 *
 * <p>Also the "real MariaDB isolation tests, including forged/wrong-
 * tenant access" the owner's Wave 12.2 scope named: two companies, each
 * with its own employee-derived and department-derived rows genuinely
 * present in the database, so "sees only its own" is a real assertion
 * against real cross-tenant data, not a tautology against an
 * empty/single-tenant fixture.
 */
class DerivedTenancyPoliciesFailClosedTest extends AbstractLegacyMySqlTest {

	private static EntityManagerFactory entityManagerFactory;
	private static HikariDataSource dataSource;

	private static final long COMPANY_A = 9401L;
	private static final long COMPANY_B = 9402L;
	private static final long EMPLOYEE_A = 94011L;
	private static final long EMPLOYEE_B = 94021L;
	private static final long DEPARTMENT_A = 9441L;
	private static final long DEPARTMENT_B = 9442L;
	private static final long BRANCH_A = 9431L;
	private static final long BRANCH_B = 9432L;

	private final TenantScope tenantScope = new TenantScope();
	private EntityManager entityManager;

	@BeforeAll
	static void seedTwoTenantsAndBuildFactory() throws Exception {
		seedAsLegacyWould("""
				INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
				  (9401, 'Derived Co A', '+201000009401', 'active', '2025-01-15 09:00:00'),
				  (9402, 'Derived Co B', '+201000009402', 'active', '2025-01-15 09:00:00')
				""", """
				INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
				  (9431, 9401, 'A HQ', 1, '2025-03-01 10:00:00'),
				  (9432, 9402, 'B HQ', 1, '2025-03-01 10:00:00')
				""", """
				INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
				  (9441, 9401, 'A Dept', 1, '2025-03-01 10:00:00'),
				  (9442, 9402, 'B Dept', 1, '2025-03-01 10:00:00')
				""", """
				INSERT INTO department_branches (department_id, branch_id) VALUES
				  (9441, 9431),
				  (9442, 9432)
				""", """
				INSERT INTO employees
				  (id, company_id, branch_id, first_name, last_name, phone, role,
				   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
				   join_request_status, token_version, created_at)
				VALUES
				  (94011, 9401, 9431, 'Ann', 'A', '+201100094011', 'employee', 1, 1, 0,
				   'accepted', 1, '2025-04-01 08:00:00'),
				  (94021, 9402, 9432, 'Bea', 'B', '+201100094021', 'employee', 1, 1, 0,
				   'accepted', 1, '2025-04-01 08:00:00')
				""", """
				INSERT INTO attendance (id, employee_id, check_in, method, created_at, updated_at) VALUES
				  (94101, 94011, '2025-05-01 09:00:00', 'app', '2025-05-01 09:00:00', '2025-05-01 09:00:00'),
				  (94102, 94021, '2025-05-01 09:00:00', 'app', '2025-05-01 09:00:00', '2025-05-01 09:00:00')
				""", """
				INSERT INTO payroll_batches (id, company_id, month, year, period_from, period_to, status, created_at) VALUES
				  (9451, 9401, 5, 2025, '2025-05-01', '2025-05-31', 'draft', '2025-05-01 08:00:00'),
				  (9452, 9402, 5, 2025, '2025-05-01', '2025-05-31', 'draft', '2025-05-01 08:00:00')
				""", """
				INSERT INTO payslips (id, batch_id, employee_id) VALUES
				  (94201, 9451, 94011),
				  (94202, 9452, 94021)
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

	private TenantFilterActivator activatorFor(EntityManager manager) {
		return new TenantFilterActivator(manager, tenantScope);
	}

	private List<AttendanceProbeRow> readSeededAttendance() {
		return entityManager
				.createQuery("SELECT a FROM AttendanceProbeRow a WHERE a.id IN (94101, 94102)",
						AttendanceProbeRow.class)
				.getResultList();
	}

	private List<DepartmentBranchProbeRow> readSeededDepartmentBranches() {
		return entityManager
				.createQuery(
						"SELECT d FROM DepartmentBranchProbeRow d WHERE d.departmentId IN (9441, 9442)",
						DepartmentBranchProbeRow.class)
				.getResultList();
	}

	// ---------- P-1b: employee-derived (attendance) ----------

	@Test
	void p1bActivatedFilterShowsOnlyTheScopedTenantsEmployeeDerivedRows() {
		entityManager = entityManagerFactory.createEntityManager();
		tenantScope.enter(COMPANY_A);
		activatorFor(entityManager).activate();

		assertThat(readSeededAttendance())
				.extracting(AttendanceProbeRow::getId)
				.containsExactly(94101L);
	}

	@Test
	void p1bADifferentScopeShowsThatTenantsEmployeeDerivedRowsInstead() {
		entityManager = entityManagerFactory.createEntityManager();
		tenantScope.enter(COMPANY_B);
		activatorFor(entityManager).activate();

		assertThat(readSeededAttendance())
				.extracting(AttendanceProbeRow::getId)
				.containsExactly(94102L);
	}

	/**
	 * The forged/wrong-tenant case at the filter layer: company A's
	 * scope must never surface company B's employee-derived row, proven
	 * against real, genuinely-existing cross-tenant data rather than an
	 * absence.
	 */
	@Test
	void p1bScopedToCompanyANeverSeesCompanyBsRow() {
		entityManager = entityManagerFactory.createEntityManager();
		tenantScope.enter(COMPANY_A);
		activatorFor(entityManager).activate();

		assertThat(readSeededAttendance())
				.extracting(AttendanceProbeRow::getId)
				.doesNotContain(94102L);
	}

	@Test
	void p1bUnactivatedFilterRestrictsNothingAtAll() {
		entityManager = entityManagerFactory.createEntityManager();

		assertThat(entityManager.unwrap(Session.class).getEnabledFilter(EmployeeDerivedTenantFilter.NAME))
				.describedAs("the filter must not be auto-enabled -- see package-info")
				.isNull();
		assertThat(readSeededAttendance())
				.describedAs("an un-enabled Hibernate filter restricts nothing -- fail-OPEN, "
						+ "the same property P-1a has and the reason TenantScope.current() raises "
						+ "rather than returning empty")
				.extracting(AttendanceProbeRow::getId)
				.containsExactlyInAnyOrder(94101L, 94102L);
	}

	@Test
	void p1bActivatingWithNoScopeEstablishedRaisesRatherThanReadingEveryTenant() {
		entityManager = entityManagerFactory.createEntityManager();

		assertThatThrownBy(() -> activatorFor(entityManager).activate())
				.isInstanceOf(NoTenantScopeException.class)
				.hasMessageContaining("no tenant scope");

		assertThat(entityManager.unwrap(Session.class).getEnabledFilter(EmployeeDerivedTenantFilter.NAME))
				.describedAs("a refused activation must leave the filter off, not half-applied")
				.isNull();
	}

	/**
	 * The property {@link EmployeeDerivedTenantFilter#NO_TENANT}'s
	 * javadoc reasons about but does not itself prove: bound directly to
	 * the sentinel (bypassing {@link TenantScope} entirely, the way
	 * {@link TenantFilterBinder} does for an unscoped transaction), the
	 * subquery's {@code IN (<empty set>)} shape must return zero rows,
	 * not every tenant's -- verified against real MariaDB, not reasoned
	 * about only.
	 */
	@Test
	void p1bTheNoTenantSentinelActuallyReturnsZeroRowsNotEveryTenants() {
		entityManager = entityManagerFactory.createEntityManager();
		entityManager.unwrap(Session.class).enableFilter(EmployeeDerivedTenantFilter.NAME)
				.setParameter(EmployeeDerivedTenantFilter.COMPANY_ID_PARAMETER, EmployeeDerivedTenantFilter.NO_TENANT);

		assertThat(readSeededAttendance())
				.describedAs("binding the NO_TENANT sentinel to the P-1b subquery must return zero rows, "
						+ "not every tenant's, even though real matching rows exist")
				.isEmpty();
	}

	// ---------- P-1c: department_branches ----------

	@Test
	void p1cActivatedFilterShowsOnlyTheScopedTenantsDepartmentBranches() {
		entityManager = entityManagerFactory.createEntityManager();
		tenantScope.enter(COMPANY_A);
		activatorFor(entityManager).activate();

		assertThat(readSeededDepartmentBranches())
				.extracting(DepartmentBranchProbeRow::getDepartmentId)
				.containsExactly(DEPARTMENT_A);
	}

	@Test
	void p1cADifferentScopeShowsThatTenantsDepartmentBranchesInstead() {
		entityManager = entityManagerFactory.createEntityManager();
		tenantScope.enter(COMPANY_B);
		activatorFor(entityManager).activate();

		assertThat(readSeededDepartmentBranches())
				.extracting(DepartmentBranchProbeRow::getDepartmentId)
				.containsExactly(DEPARTMENT_B);
	}

	@Test
	void p1cScopedToCompanyANeverSeesCompanyBsDepartmentBranch() {
		entityManager = entityManagerFactory.createEntityManager();
		tenantScope.enter(COMPANY_A);
		activatorFor(entityManager).activate();

		assertThat(readSeededDepartmentBranches())
				.extracting(DepartmentBranchProbeRow::getDepartmentId)
				.doesNotContain(DEPARTMENT_B);
	}

	@Test
	void p1cUnactivatedFilterRestrictsNothingAtAll() {
		entityManager = entityManagerFactory.createEntityManager();

		assertThat(entityManager.unwrap(Session.class).getEnabledFilter(DepartmentBranchesTenantFilter.NAME))
				.isNull();
		assertThat(readSeededDepartmentBranches())
				.extracting(DepartmentBranchProbeRow::getDepartmentId)
				.containsExactlyInAnyOrder(DEPARTMENT_A, DEPARTMENT_B);
	}

	@Test
	void p1cActivatingWithNoScopeEstablishedRaisesRatherThanReadingEveryTenant() {
		entityManager = entityManagerFactory.createEntityManager();

		assertThatThrownBy(() -> activatorFor(entityManager).activate())
				.isInstanceOf(NoTenantScopeException.class)
				.hasMessageContaining("no tenant scope");

		assertThat(entityManager.unwrap(Session.class).getEnabledFilter(DepartmentBranchesTenantFilter.NAME))
				.isNull();
	}

	@Test
	void p1cTheNoTenantSentinelActuallyReturnsZeroRowsNotEveryTenants() {
		entityManager = entityManagerFactory.createEntityManager();
		entityManager.unwrap(Session.class).enableFilter(DepartmentBranchesTenantFilter.NAME)
				.setParameter(DepartmentBranchesTenantFilter.COMPANY_ID_PARAMETER,
						DepartmentBranchesTenantFilter.NO_TENANT);

		assertThat(readSeededDepartmentBranches())
				.describedAs("binding the NO_TENANT sentinel to the P-1c subquery must return zero rows, "
						+ "not every tenant's, even though real matching rows exist")
				.isEmpty();
	}

	/**
	 * All three filters bound together, the way {@link
	 * TenantFilterActivator#activate()} actually does it in production
	 * -- not each policy tested only in isolation from the others.
	 */
	@Test
	void activateEnablesAllThreePoliciesTogetherConsistently() {
		entityManager = entityManagerFactory.createEntityManager();
		tenantScope.enter(COMPANY_A);
		activatorFor(entityManager).activate();

		Session session = entityManager.unwrap(Session.class);
		assertThat(session.getEnabledFilter(TenantFilter.NAME)).isNotNull();
		assertThat(session.getEnabledFilter(EmployeeDerivedTenantFilter.NAME)).isNotNull();
		assertThat(session.getEnabledFilter(DepartmentBranchesTenantFilter.NAME)).isNotNull();

		assertThat(readSeededAttendance()).extracting(AttendanceProbeRow::getId).containsExactly(94101L);
		assertThat(readSeededDepartmentBranches())
				.extracting(DepartmentBranchProbeRow::getDepartmentId).containsExactly(DEPARTMENT_A);
	}

	/**
	 * The pre-tenant escape hatch disables all three, not just P-1a.
	 */
	@Test
	void deactivateForPreTenantLookupDisablesAllThreePolicies() {
		entityManager = entityManagerFactory.createEntityManager();
		tenantScope.enter(COMPANY_A);
		TenantFilterActivator activator = activatorFor(entityManager);
		activator.activate();
		assertThat(readSeededAttendance()).hasSize(1);

		activator.deactivateForPreTenantLookup();
		assertThat(readSeededAttendance())
				.describedAs("the deliberately pre-tenant path sees across tenants for P-1b too, by design")
				.hasSize(2);
		assertThat(readSeededDepartmentBranches())
				.describedAs("and for P-1c")
				.hasSize(2);

		activator.activate();
		assertThat(readSeededAttendance())
				.describedAs("and scope can be restored afterwards")
				.hasSize(1);
	}

}
