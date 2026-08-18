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

/**
 * The adapter-level and tenant-isolation half of PR 12.3a, mirroring
 * {@link com.workin.legacy.attendance.LegacyExceptionTypeTenancyTest}'s
 * hand-wired {@code EntityManagerFactory} pattern exactly, extended to
 * {@link LegacyBranch} and D-059's production-verified data shapes
 * (real {@code radius_meters = 0}, both-null lat/lng, {@code is_active
 * = 0}).
 */
class LegacyBranchTenancyTest extends AbstractLegacyMySqlTest {

	private static EntityManagerFactory entityManagerFactory;
	private static HikariDataSource dataSource;

	private static final long COMPANY_A = 8701L;
	private static final long COMPANY_B = 8702L;

	private final TenantScope tenantScope = new TenantScope();
	private TransactionTemplate transactions;

	@BeforeAll
	static void seedAndBuildFactory() throws Exception {
		seedAsLegacyWould("""
				INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
				  (8701, 'Branch Tenancy Co A', '+201000008701', 'active', '2025-01-15 09:00:00'),
				  (8702, 'Branch Tenancy Co B', '+201000008702', 'active', '2025-01-15 09:00:00')
				""", """
				INSERT INTO branches (id, company_id, name, address, latitude, longitude, radius_meters, is_active, created_at) VALUES
				  (8711, 8701, 'A HQ', '123 Main St', 30.0444200, 31.2357100, 100, 1, '2025-03-01 10:00:00'),
				  (8712, 8701, 'A Zero Radius', NULL, NULL, NULL, 0, 1, '2025-03-01 10:00:00'),
				  (8713, 8701, 'A Inactive', NULL, NULL, NULL, 200, 0, '2025-03-01 10:00:00'),
				  (8721, 8702, 'B HQ', NULL, NULL, NULL, 200, 1, '2025-03-01 10:00:00'),
				  (8731, 8701, 'A Rollback Target', NULL, NULL, NULL, 200, 1, '2025-03-01 10:00:00')
				""", """
				INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
				  (8741, 8701, 'A Dept', 1, '2025-03-01 10:00:00')
				""", """
				INSERT INTO department_branches (department_id, branch_id) VALUES (8741, 8731)
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

	private List<String> readNamesById(List<Long> ids) {
		return transactions().execute(status -> {
			var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
			return manager.createQuery("SELECT b FROM LegacyBranch b WHERE b.id IN :ids", LegacyBranch.class)
					.setParameter("ids", ids)
					.getResultList().stream()
					.map(LegacyBranch::getName)
					.toList();
		});
	}

	private void inScope(long companyId, Runnable action) throws Exception {
		new TenantScopeFilter(tenantScope, request -> Optional.of(companyId)).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> action.run());
	}

	@Test
	void companyAReadsOnlyItsOwnBranches() throws Exception {
		var seen = new AtomicReference<List<String>>();
		inScope(COMPANY_A, () -> seen.set(readNamesById(List.of(8711L, 8712L, 8713L, 8721L))));

		assertThat(seen.get()).containsExactlyInAnyOrder("A HQ", "A Zero Radius", "A Inactive");
	}

	@Test
	void companyBCannotSeeCompanyAsBranches() throws Exception {
		var seen = new AtomicReference<List<String>>();
		inScope(COMPANY_B, () -> seen.set(readNamesById(List.of(8711L, 8712L, 8713L, 8721L))));

		assertThat(seen.get()).containsExactly("B HQ");
	}

	@Test
	void anUnscopedTransactionReadsZeroRowsNotEveryTenants() throws Exception {
		var seen = new AtomicReference<List<String>>();
		new TenantScopeFilter(tenantScope, request -> Optional.empty()).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(),
				(req, res) -> seen.set(readNamesById(List.of(8711L, 8712L, 8713L, 8721L))));

		assertThat(seen.get()).isEmpty();
	}

	/** D-059: production carries real {@code radius_meters = 0} rows -- must round-trip, not default up. */
	@Test
	void radiusMetersZeroRoundTripsAsZeroNotTheDefault() throws Exception {
		var value = new AtomicReference<Integer>();
		inScope(COMPANY_A, () -> transactions().executeWithoutResult(status -> value.set(
				EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory)
						.find(LegacyBranch.class, 8712L).getRadiusMeters())));

		assertThat(value.get()).isEqualTo(0);
	}

	/** D-059: ~60% of production branches have both lat/lng NULL -- the common case, not an edge case. */
	@Test
	void bothNullLatitudeAndLongitudeRoundTrip() throws Exception {
		var branch = new AtomicReference<LegacyBranch>();
		inScope(COMPANY_A, () -> transactions().executeWithoutResult(status -> branch.set(
				EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory)
						.find(LegacyBranch.class, 8712L))));

		assertThat(branch.get().getLatitude()).isNull();
		assertThat(branch.get().getLongitude()).isNull();
	}

	@Test
	void nonNullLatitudeAndLongitudeRoundTripWithPrecision() throws Exception {
		var branch = new AtomicReference<LegacyBranch>();
		inScope(COMPANY_A, () -> transactions().executeWithoutResult(status -> branch.set(
				EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory)
						.find(LegacyBranch.class, 8711L))));

		assertThat(branch.get().getLatitude()).isEqualByComparingTo(new BigDecimal("30.0444200"));
		assertThat(branch.get().getLongitude()).isEqualByComparingTo(new BigDecimal("31.2357100"));
	}

	/** D-059: 8 of 419 production branches are is_active = 0 despite no discovered write path -- must still read correctly. */
	@Test
	void isActiveRoundTripsAsABooleanForBothValues() throws Exception {
		var active = new AtomicReference<Boolean>();
		var inactive = new AtomicReference<Boolean>();
		inScope(COMPANY_A, () -> transactions().executeWithoutResult(status -> {
			var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
			active.set(manager.find(LegacyBranch.class, 8711L).active());
			inactive.set(manager.find(LegacyBranch.class, 8713L).active());
		}));

		assertThat(active.get()).isTrue();
		assertThat(inactive.get()).isFalse();
	}

	@Test
	void createdAtIsDatabaseMaintainedAndReadable() throws Exception {
		var value = new AtomicReference<LegacyBranch>();
		inScope(COMPANY_A, () -> transactions().executeWithoutResult(status -> value.set(
				EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory)
						.find(LegacyBranch.class, 8711L))));

		assertThat(value.get().getCreatedAt()).isNotNull();
	}

	/**
	 * D-056's rollback obligation: replicates the delete path's exact
	 * {@code department_branches} clear through the same {@link
	 * TenantAwareJpaTransactionManager}, then forces the transaction to
	 * fail before the final branch delete would run -- proving the
	 * mechanism the real {@code LegacyBranchService.delete} depends on
	 * (one transaction, the native clear and the delete sharing one
	 * connection) actually rolls both back together.
	 */
	@Test
	void aFailureBetweenTheDepartmentBranchesClearAndTheFinalDeleteLeavesNoPartialState() throws Exception {
		RuntimeException simulatedFailure = new RuntimeException("simulated failure before the final delete");

		Runnable attemptedDelete = () -> {
			var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
			manager.createNativeQuery("DELETE FROM department_branches WHERE branch_id = 8731").executeUpdate();
			// The final branch delete never runs -- this stands in for it failing.
			throw simulatedFailure;
		};

		try {
			new TenantScopeFilter(tenantScope, request -> Optional.of(COMPANY_A)).doFilter(
					new MockHttpServletRequest(), new MockHttpServletResponse(),
					(req, res) -> transactions().executeWithoutResult(status -> attemptedDelete.run()));
		} catch (RuntimeException ex) {
			if (ex != simulatedFailure) {
				throw ex;
			}
		}

		var linkStillExists = new AtomicReference<Boolean>();
		var branchStillExists = new AtomicReference<Boolean>();
		inScope(COMPANY_A, () -> transactions().executeWithoutResult(status -> {
			var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
			Number count = (Number) manager.createNativeQuery(
					"SELECT COUNT(*) FROM department_branches WHERE branch_id = 8731 AND department_id = 8741")
					.getSingleResult();
			linkStillExists.set(count.longValue() > 0);
			branchStillExists.set(manager.find(LegacyBranch.class, 8731L) != null);
		}));

		assertThat(linkStillExists.get())
				.describedAs("the department_branches clear must have rolled back with the aborted transaction")
				.isTrue();
		assertThat(branchStillExists.get())
				.describedAs("the branch row itself must be untouched -- the delete never ran")
				.isTrue();
	}

}
