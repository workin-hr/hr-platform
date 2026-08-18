package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Properties;

import com.workin.backend.tenancy.TenantScope;
import com.workin.backend.tenancy.TenantScopeFilter;
import com.workin.legacy.employees.LegacyEmployee;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The end-to-end tenant binding: an HTTP request carrying an
 * authenticated tenant, through the transaction manager, to a real
 * MariaDB — with nothing in between remembering to scope anything.
 *
 * <p>This is the proof the earlier pieces could not give on their own.
 * {@code TenantScopeTest} proved the scope holder fails closed;
 * {@code TenantFilterFailClosedTest} proved the filter filters when
 * somebody activates it; {@code TenantScopeFilterTest} proved the scope
 * is released. None of them proved the pieces are actually
 * <em>connected</em> — that a query issued by ordinary repository code,
 * with no tenant-aware call site anywhere near it, is scoped anyway.
 *
 * <p>That is the difference between an isolation control and a
 * convention, and it is what ADR-0012's "one enforcement point" means.
 */
class TenantBindingEndToEndTest extends AbstractLegacyMySqlTest {

	private static EntityManagerFactory entityManagerFactory;
	private static HikariDataSource dataSource;

	private final TenantScope tenantScope = new TenantScope();
	private TransactionTemplate transactions;

	@BeforeAll
	static void seedAndBuildFactory() throws Exception {
		seedAsLegacyWould("""
				INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
				  (801, 'E2E Co A', '+201000000801', 'active', '2025-01-15 09:00:00'),
				  (802, 'E2E Co B', '+201000000802', 'active', '2025-01-15 09:00:00')
				""", """
				INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
				  (871, 801, 'A HQ', 1, '2025-03-01 10:00:00'),
				  (872, 802, 'B HQ', 1, '2025-03-01 10:00:00')
				""", """
				INSERT INTO employees
				  (id, company_id, branch_id, first_name, last_name, phone, role,
				   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
				   join_request_status, token_version, created_at)
				VALUES
				  (8011, 801, 871, 'Ann', 'A', '+201100008011', 'employee', 1, 1, 0,
				   'accepted', 1, '2025-04-01 08:00:00'),
				  (8021, 802, 872, 'Bea', 'B', '+201100008021', 'employee', 1, 1, 0,
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

	/** Ordinary repository-shaped code. Nothing here mentions a tenant. */
	private List<Long> readSeededEmployeeIds() {
		return transactions().execute(status -> {
			var manager = org.springframework.orm.jpa.EntityManagerFactoryUtils
					.getTransactionalEntityManager(entityManagerFactory);
			return manager.createQuery(
							"SELECT e FROM LegacyEmployee e WHERE e.id IN (8011, 8021)",
							LegacyEmployee.class)
					.getResultList().stream()
					.map(LegacyEmployee::getId)
					.toList();
		});
	}

	/**
	 * A request with an authenticated tenant sees only that tenant, and
	 * the query that proves it is written as if tenancy did not exist.
	 */
	@Test
	void arequestScopedToOneTenantReadsOnlyThatTenant() throws Exception {
		var seen = new java.util.concurrent.atomic.AtomicReference<List<Long>>();

		new TenantScopeFilter(tenantScope, request -> Optional.of(801L)).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(),
				(req, res) -> seen.set(readSeededEmployeeIds()));

		assertThat(seen.get()).containsExactly(8011L);
	}

	@Test
	void adifferentAuthenticatedTenantReadsTheOtherOne() throws Exception {
		var seen = new java.util.concurrent.atomic.AtomicReference<List<Long>>();

		new TenantScopeFilter(tenantScope, request -> Optional.of(802L)).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(),
				(req, res) -> seen.set(readSeededEmployeeIds()));

		assertThat(seen.get()).containsExactly(8021L);
	}

	/**
	 * <b>The property Phase 1 would otherwise lose.</b> An unauthenticated
	 * request reads <em>nothing</em> — not everything.
	 *
	 * <p>{@code TenantFilterFailClosedTest} shows that an un-enabled
	 * Hibernate filter returns every tenant's rows. Binding
	 * {@link TenantFilter#NO_TENANT} on every transaction converts that
	 * into an empty result, which is exactly what PostgreSQL RLS did
	 * when {@code app.current_company_id} was never set — the behaviour
	 * {@code RlsFailClosedTest} pinned, now reproduced without RLS.
	 */
	@Test
	void anUnauthenticatedRequestReadsNothingRatherThanEverything() throws Exception {
		var seen = new java.util.concurrent.atomic.AtomicReference<List<Long>>();

		new TenantScopeFilter(tenantScope, request -> Optional.empty()).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(),
				(req, res) -> seen.set(readSeededEmployeeIds()));

		assertThat(seen.get())
				.describedAs("unscoped must be zero rows, as RLS gave for free -- "
						+ "not every tenant, which is the Hibernate default")
				.isEmpty();
	}

	/**
	 * And the scope does not outlive the request that established it, so
	 * the next transaction on this pooled thread is unscoped rather than
	 * inheriting the previous tenant.
	 */
	@Test
	void aTransactionAfterTheRequestEndsIsUnscopedNotInherited() throws Exception {
		new TenantScopeFilter(tenantScope, request -> Optional.of(801L)).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(),
				(req, res) -> readSeededEmployeeIds());

		assertThat(readSeededEmployeeIds())
				.describedAs("the previous request's tenant must not leak into the next transaction")
				.isEmpty();
	}

}
