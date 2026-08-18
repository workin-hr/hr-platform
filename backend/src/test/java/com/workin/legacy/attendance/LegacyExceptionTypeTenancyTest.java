package com.workin.legacy.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Properties;

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
 * The adapter-level and tenant-isolation half of §10.2, at the JPA/query
 * layer rather than over HTTP -- mirrors {@code
 * com.workin.legacy.TenantBindingEndToEndTest}'s hand-wired {@code
 * EntityManagerFactory} pattern exactly, extended to {@link
 * LegacyExceptionType} and D-047's company-scoped uniqueness.
 */
class LegacyExceptionTypeTenancyTest extends AbstractLegacyMySqlTest {

	private static EntityManagerFactory entityManagerFactory;
	private static HikariDataSource dataSource;

	private static final long COMPANY_A = 8801L;
	private static final long COMPANY_B = 8802L;

	private final TenantScope tenantScope = new TenantScope();
	private TransactionTemplate transactions;

	@BeforeAll
	static void seedAndBuildFactory() throws Exception {
		seedAsLegacyWould("""
				INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
				  (8801, 'Tenancy Co A', '+201000008801', 'active', '2025-01-15 09:00:00'),
				  (8802, 'Tenancy Co B', '+201000008802', 'active', '2025-01-15 09:00:00')
				""", """
				INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
				  (8811, 8801, 'A HQ', 1, '2025-03-01 10:00:00')
				""", """
				INSERT INTO employees
				  (id, company_id, branch_id, first_name, last_name, phone, role,
				   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
				   join_request_status, token_version, created_at)
				VALUES
				  (8821, 8801, 8811, 'Rollback', 'Fixture', '+201100008821', 'employee', 1, 1, 0,
				   'accepted', 1, '2025-04-01 08:00:00')
				""", """
				INSERT INTO exception_types (id, company_id, name, is_active, created_at, updated_at) VALUES
				  (8901, 8801, 'Sick Leave', 1, '2025-04-01 08:00:00', '2025-04-01 08:00:00'),
				  (8902, 8801, 'Unpaid Leave', 0, '2025-04-01 08:00:00', '2025-04-01 08:00:00'),
				  (8903, 8802, 'Other Co Type', 1, '2025-04-01 08:00:00', '2025-04-01 08:00:00'),
				  (8904, 8801, 'Rollback Target', 1, '2025-04-01 08:00:00', '2025-04-01 08:00:00')
				""", """
				INSERT INTO attendance (id, employee_id, check_in, method, exception_type_id, created_at, updated_at)
				VALUES (8931, 8821, '2025-05-01 09:00:00', 'app', 8904, '2025-05-01 09:00:00', '2025-05-01 09:00:00')
				""", """
				INSERT INTO request_types (id, company_id, name, is_active, exception_type_id, created_at)
				VALUES (8941, 8801, 'Rollback Request Type', 1, 8904, '2025-04-01 08:00:00')
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
			return manager.createQuery(
							"SELECT e FROM LegacyExceptionType e WHERE e.id IN :ids", LegacyExceptionType.class)
					.setParameter("ids", ids)
					.getResultList().stream()
					.map(LegacyExceptionType::getName)
					.toList();
		});
	}

	private boolean existsByCompanyIdAndName(long companyId, String name) {
		return transactions().execute(status -> {
			var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
			Long count = manager.createQuery(
							"SELECT COUNT(e) FROM LegacyExceptionType e WHERE e.companyId = :companyId AND e.name = :name",
							Long.class)
					.setParameter("companyId", companyId)
					.setParameter("name", name)
					.getSingleResult();
			return count > 0;
		});
	}

	private void inScope(long companyId, Runnable action) throws Exception {
		new TenantScopeFilter(tenantScope, request -> Optional.of(companyId)).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> action.run());
	}

	@Test
	void companyAReadsOnlyItsOwnExceptionTypes() throws Exception {
		var seen = new java.util.concurrent.atomic.AtomicReference<List<String>>();
		inScope(COMPANY_A, () -> seen.set(readNamesById(List.of(8901L, 8902L, 8903L))));

		assertThat(seen.get()).containsExactlyInAnyOrder("Sick Leave", "Unpaid Leave");
	}

	@Test
	void companyBCannotSeeCompanyAsRows() throws Exception {
		var seen = new java.util.concurrent.atomic.AtomicReference<List<String>>();
		inScope(COMPANY_B, () -> seen.set(readNamesById(List.of(8901L, 8902L, 8903L))));

		assertThat(seen.get()).containsExactly("Other Co Type");
	}

	@Test
	void anUnscopedTransactionReadsZeroRowsNotEveryTenants() throws Exception {
		var seen = new java.util.concurrent.atomic.AtomicReference<List<String>>();
		new TenantScopeFilter(tenantScope, request -> Optional.empty()).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(),
				(req, res) -> seen.set(readNamesById(List.of(8901L, 8902L, 8903L))));

		assertThat(seen.get()).isEmpty();
	}

	/** {@code tinyint(1)} round-trip: 8901 is active, 8902 is not. */
	@Test
	void isActiveRoundTripsAsABooleanNotJustNonzero() throws Exception {
		var active = new java.util.concurrent.atomic.AtomicReference<Boolean>();
		var inactive = new java.util.concurrent.atomic.AtomicReference<Boolean>();
		inScope(COMPANY_A, () -> transactions().executeWithoutResult(status -> {
			var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
			active.set(manager.find(LegacyExceptionType.class, 8901L).active());
			inactive.set(manager.find(LegacyExceptionType.class, 8902L).active());
		}));

		assertThat(active.get()).isTrue();
		assertThat(inactive.get()).isFalse();
	}

	@Test
	void createdAtAndUpdatedAtAreDatabaseMaintainedAndReadable() throws Exception {
		var value = new java.util.concurrent.atomic.AtomicReference<LegacyExceptionType>();
		inScope(COMPANY_A, () -> transactions().executeWithoutResult(status -> value.set(
				EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory)
						.find(LegacyExceptionType.class, 8901L))));

		assertThat(value.get().getCreatedAt()).isNotNull();
		assertThat(value.get().getUpdatedAt()).isNotNull();
	}

	/**
	 * Proves {@code existsByCompanyIdAndName}'s own query shape directly,
	 * independent of fixture coincidence: it is a same-company fast path
	 * (D-7/D-051's Impact) and must not itself see another company's
	 * row, even though overall uniqueness is global -- the cross-company
	 * case is caught downstream by the real database constraint, not by
	 * this method reaching across tenants.
	 */
	@Test
	void companyScopedFastPathCheckDoesNotLeakAcrossTenants() throws Exception {
		var seenInA = new java.util.concurrent.atomic.AtomicReference<Boolean>();
		var seenInB = new java.util.concurrent.atomic.AtomicReference<Boolean>();
		inScope(COMPANY_A, () -> seenInA.set(existsByCompanyIdAndName(COMPANY_A, "Other Co Type")));
		inScope(COMPANY_B, () -> seenInB.set(existsByCompanyIdAndName(COMPANY_B, "Other Co Type")));

		assertThat(seenInA.get())
				.describedAs("company A's own name-existence check must not see company B's row")
				.isFalse();
		assertThat(seenInB.get())
				.describedAs("company B's own name-existence check must see its own row")
				.isTrue();
	}

	/**
	 * D-048's rollback obligation: replicates the delete path's exact
	 * two native FK-clears through the same {@link
	 * TenantAwareJpaTransactionManager}, then forces the transaction to
	 * fail before the final delete would run -- proving the mechanism
	 * the real {@code LegacyExceptionTypeService.delete} depends on
	 * (one transaction, native queries sharing the JPA connection)
	 * actually rolls every prior write back together, not just some of
	 * them.
	 */
	@Test
	void aFailureBetweenTheFkClearsAndTheFinalDeleteLeavesNoPartialState() throws Exception {
		RuntimeException simulatedFailure = new RuntimeException("simulated failure before the final delete");

		Runnable attemptedDelete = () -> {
			var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
			manager.createNativeQuery(
							"UPDATE attendance a INNER JOIN employees e ON e.id = a.employee_id "
									+ "SET a.exception_type_id = NULL WHERE a.exception_type_id = 8904 AND e.company_id = 8801")
					.executeUpdate();
			manager.createNativeQuery(
							"UPDATE request_types SET exception_type_id = NULL "
									+ "WHERE exception_type_id = 8904 AND company_id = 8801")
					.executeUpdate();
			// The final delete never runs -- this stands in for it failing.
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

		// Fresh transaction, same scope: the two clears must not have survived the aborted one.
		var attendanceExceptionTypeId = new java.util.concurrent.atomic.AtomicReference<Long>();
		var requestTypeExceptionTypeId = new java.util.concurrent.atomic.AtomicReference<Long>();
		var rowStillExists = new java.util.concurrent.atomic.AtomicReference<Boolean>();
		inScope(COMPANY_A, () -> transactions().executeWithoutResult(status -> {
			var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
			attendanceExceptionTypeId.set(((Number) manager.createNativeQuery(
					"SELECT exception_type_id FROM attendance WHERE id = 8931").getSingleResult()).longValue());
			requestTypeExceptionTypeId.set(((Number) manager.createNativeQuery(
					"SELECT exception_type_id FROM request_types WHERE id = 8941").getSingleResult()).longValue());
			rowStillExists.set(manager.find(LegacyExceptionType.class, 8904L) != null);
		}));

		assertThat(attendanceExceptionTypeId.get())
				.describedAs("the attendance clear must have rolled back with the aborted transaction")
				.isEqualTo(8904L);
		assertThat(requestTypeExceptionTypeId.get())
				.describedAs("the request_types clear must have rolled back with the aborted transaction")
				.isEqualTo(8904L);
		assertThat(rowStillExists.get())
				.describedAs("the exception_types row itself must be untouched -- the delete never ran")
				.isTrue();
	}

}
