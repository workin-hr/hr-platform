package com.workin.legacy.organization;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

/** Adapter-level type and fail-closed P-1a tenancy proof for Wave 12.3c. */
class LegacyJobTitleTenancyTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY_A = 20801L;
	private static final long COMPANY_B = 20802L;

	private static EntityManagerFactory entityManagerFactory;
	private static HikariDataSource dataSource;

	private final TenantScope tenantScope = new TenantScope();
	private TransactionTemplate transactions;

	@BeforeAll
	static void seedAndBuildFactory() throws Exception {
		seedAsLegacyWould("""
				INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
				  (20801, 'Job Title Tenancy Co A', '+201000020801', 'active', '2025-01-15 09:00:00'),
				  (20802, 'Job Title Tenancy Co B', '+201000020802', 'active', '2025-01-15 09:00:00')
				""", """
				INSERT INTO departments (id, company_id, name, manager_id, is_active, created_at) VALUES
				  (20831, 20801, 'A Department', NULL, 1, '2025-04-01 10:00:00'),
				  (20841, 20802, 'B Department', NULL, 1, '2025-04-03 10:00:00')
				""", """
				INSERT INTO job_titles (id, company_id, department_id, name, work_hours, is_active, created_at) VALUES
				  (20851, 20801, 20831, 'A Active Title', 7.25, 1, '2025-05-01 10:00:00'),
				  (20852, 20801, NULL, 'A Nullable Department Title', 16.00, 0, '2025-05-02 10:00:00'),
				  (20861, 20802, 20841, 'B Title', 8.00, 1, '2025-05-03 10:00:00')
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
	void directCompanyFilterScopesJobTitles() throws Exception {
		var names = new AtomicReference<List<String>>();
		inScope(COMPANY_A, () -> names.set(readJobTitleNames()));
		assertThat(names.get()).containsExactlyInAnyOrder("A Active Title", "A Nullable Department Title");
	}

	@Test
	void anotherTenantCannotReadCompanyARows() throws Exception {
		var names = new AtomicReference<List<String>>();
		inScope(COMPANY_B, () -> names.set(readJobTitleNames()));
		assertThat(names.get()).containsExactly("B Title");
	}

	@Test
	void unscopedTransactionsReadZeroJobTitles() throws Exception {
		var names = new AtomicReference<List<String>>();
		new TenantScopeFilter(tenantScope, request -> Optional.empty()).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(),
				(req, res) -> names.set(readJobTitleNames()));
		assertThat(names.get()).isEmpty();
	}

	@Test
	void columnsRoundTripIncludingDecimalBooleanAndNullableDepartment() throws Exception {
		var active = new AtomicReference<LegacyJobTitle>();
		var nullable = new AtomicReference<LegacyJobTitle>();
		inScope(COMPANY_A, () -> transactions().executeWithoutResult(status -> {
			var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
			active.set(manager.find(LegacyJobTitle.class, 20851L));
			nullable.set(manager.find(LegacyJobTitle.class, 20852L));
		}));

		assertThat(active.get().getWorkHours()).isEqualByComparingTo(new BigDecimal("7.25"));
		assertThat(active.get().active()).isTrue();
		assertThat(nullable.get().getDepartmentId()).isNull();
		assertThat(nullable.get().active()).isFalse();
		assertThat(nullable.get().getCreatedAt()).isNotNull();
	}

	private TransactionTemplate transactions() {
		if (transactions == null) {
			transactions = new TransactionTemplate(new TenantAwareJpaTransactionManager(
					entityManagerFactory, new TenantFilterBinder(tenantScope)));
		}
		return transactions;
	}

	private List<String> readJobTitleNames() {
		return transactions().execute(status -> EntityManagerFactoryUtils
				.getTransactionalEntityManager(entityManagerFactory)
				.createQuery("select j from LegacyJobTitle j", LegacyJobTitle.class)
				.getResultList().stream().map(LegacyJobTitle::getName).toList());
	}

	private void inScope(long companyId, Runnable action) throws Exception {
		new TenantScopeFilter(tenantScope, request -> Optional.of(companyId)).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> action.run());
	}

}
