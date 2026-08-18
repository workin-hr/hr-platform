package com.workin.legacy.organization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import com.workin.backend.tenancy.TenantScope;
import com.workin.backend.tenancy.TenantScopeFilter;
import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.TenantAwareJpaTransactionManager;
import com.workin.legacy.TenantFilterBinder;
import com.zaxxer.hikari.HikariDataSource;

/** Adapter-level type, identity, and fail-closed tenancy proof for Wave 12.3b. */
class LegacyDepartmentTenancyTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY_A = 19801L;
	private static final long COMPANY_B = 19802L;

	private static EntityManagerFactory entityManagerFactory;
	private static HikariDataSource dataSource;

	private final TenantScope tenantScope = new TenantScope();
	private TransactionTemplate transactions;

	@BeforeAll
	static void seedAndBuildFactory() throws Exception {
		seedAsLegacyWould("""
				INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
				  (19801, 'Organization Tenancy Co A', '+201000019801', 'active', '2025-01-15 09:00:00'),
				  (19802, 'Organization Tenancy Co B', '+201000019802', 'active', '2025-01-15 09:00:00')
				""", """
				INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
				  (19811, 19801, 'A Branch One', 1, '2025-03-01 10:00:00'),
				  (19812, 19801, 'A Branch Two', 0, '2025-03-01 10:00:00'),
				  (19821, 19802, 'B Branch', 1, '2025-03-01 10:00:00')
				""", """
				INSERT INTO departments (id, company_id, name, manager_id, is_active, created_at) VALUES
				  (19831, 19801, 'A Active Department', NULL, 1, '2025-04-01 10:00:00'),
				  (19832, 19801, 'A Inactive Department', 12345, 0, '2025-04-02 10:00:00'),
				  (19841, 19802, 'B Department', NULL, 1, '2025-04-03 10:00:00')
				""", """
				INSERT INTO department_branches (department_id, branch_id) VALUES
				  (19831, 19811), (19831, 19812), (19841, 19821)
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

	@Test
	void directCompanyFilterScopesDepartments() throws Exception {
		var departments = new AtomicReference<List<String>>();
		inScope(COMPANY_A, () -> departments.set(readDepartmentNames()));

		assertThat(departments.get()).containsExactlyInAnyOrder("A Active Department", "A Inactive Department");
	}

	@Test
	void anotherTenantCannotReadCompanyARows() throws Exception {
		var departments = new AtomicReference<List<String>>();
		inScope(COMPANY_B, () -> departments.set(readDepartmentNames()));

		assertThat(departments.get()).containsExactly("B Department");
	}

	@Test
	void p1cScopesDepartmentBranchesThroughTheDepartmentCompany() throws Exception {
		var linksA = new AtomicReference<List<LegacyDepartmentBranch>>();
		var linksB = new AtomicReference<List<LegacyDepartmentBranch>>();
		inScope(COMPANY_A, () -> linksA.set(readLinks()));
		inScope(COMPANY_B, () -> linksB.set(readLinks()));

		assertThat(linksA.get()).extracting(LegacyDepartmentBranch::getBranchId)
				.containsExactlyInAnyOrder(19811L, 19812L);
		assertThat(linksB.get()).extracting(LegacyDepartmentBranch::getBranchId).containsExactly(19821L);
	}

	@Test
	void compositeIdentityKeepsBothLinksForOneDepartmentDistinct() throws Exception {
		var links = new AtomicReference<List<LegacyDepartmentBranch>>();
		inScope(COMPANY_A, () -> links.set(transactions().execute(status -> {
			var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
			return List.of(
					manager.find(LegacyDepartmentBranch.class, new LegacyDepartmentBranchId(19831L, 19811L)),
					manager.find(LegacyDepartmentBranch.class, new LegacyDepartmentBranchId(19831L, 19812L)));
		})));

		assertThat(links.get()).allMatch(java.util.Objects::nonNull).hasSize(2);
	}

	@Test
	void unscopedTransactionsReadZeroRowsUnderBothDepartmentPolicies() throws Exception {
		var departments = new AtomicReference<List<String>>();
		var links = new AtomicReference<List<LegacyDepartmentBranch>>();
		new TenantScopeFilter(tenantScope, request -> Optional.empty()).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {
					departments.set(readDepartmentNames());
					links.set(readLinks());
				});

		assertThat(departments.get()).isEmpty();
		assertThat(links.get()).isEmpty();
	}

	@Test
	void departmentColumnsRoundTripIncludingInactiveAndDatabaseTimestamp() throws Exception {
		var row = new AtomicReference<LegacyDepartment>();
		inScope(COMPANY_A, () -> transactions().executeWithoutResult(status -> row.set(
				EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory)
						.find(LegacyDepartment.class, 19832L))));

		assertThat(row.get().getCompanyId()).isEqualTo(COMPANY_A);
		assertThat(row.get().getManagerId()).isEqualTo(12345L);
		assertThat(row.get().active()).isFalse();
		assertThat(row.get().getCreatedAt()).isNotNull();
	}

	private TransactionTemplate transactions() {
		if (transactions == null) {
			transactions = new TransactionTemplate(new TenantAwareJpaTransactionManager(
					entityManagerFactory, new TenantFilterBinder(tenantScope)));
		}
		return transactions;
	}

	private List<String> readDepartmentNames() {
		return transactions().execute(status -> EntityManagerFactoryUtils
				.getTransactionalEntityManager(entityManagerFactory)
				.createQuery("select d from LegacyDepartment d", LegacyDepartment.class)
				.getResultList().stream().map(LegacyDepartment::getName).toList());
	}

	private List<LegacyDepartmentBranch> readLinks() {
		return transactions().execute(status -> EntityManagerFactoryUtils
				.getTransactionalEntityManager(entityManagerFactory)
				.createQuery("select db from LegacyDepartmentBranch db", LegacyDepartmentBranch.class)
				.getResultList());
	}

	private void inScope(long companyId, Runnable action) throws Exception {
		new TenantScopeFilter(tenantScope, request -> Optional.of(companyId)).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> action.run());
	}

}
