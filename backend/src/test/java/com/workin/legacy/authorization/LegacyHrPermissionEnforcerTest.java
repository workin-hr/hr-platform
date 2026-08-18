package com.workin.legacy.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;

import com.workin.backend.i18n.ApiException;
import com.workin.backend.security.AuthenticatedPrincipal;
import com.workin.legacy.AbstractLegacyMySqlTest;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link LegacyHrPermissionEnforcer} against real MariaDB (punch-list
 * item #11, D-044) — the same {@code JpaRepositoryFactory}-without-a-
 * full-Spring-context pattern {@code LegacyTenantContextServiceTest}
 * established, since the application still boots against PostgreSQL
 * under the default profile.
 *
 * <p>{@code hr_permissions} carries no {@code company_id} column
 * (confirmed against {@code mysql_workin.schema.sql} and documented on
 * {@link LegacyHrPermissions}), so unlike its siblings this test needs
 * no {@code TenantAwareJpaTransactionManager}/{@code TenantFilterBinder}
 * — a plain {@link JpaTransactionManager} is the honest transaction
 * manager for a table the tenant filter was never meant to touch.
 */
class LegacyHrPermissionEnforcerTest extends AbstractLegacyMySqlTest {

	private static EntityManagerFactory entityManagerFactory;
	private static HikariDataSource dataSource;
	private static TransactionTemplate transactions;

	@BeforeAll
	static void seedAndBuildFactory() throws Exception {
		seedAsLegacyWould("""
				INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
				  (1001, 'Perm Co', '+201000001001', 'active', '2025-01-15 09:00:00')
				""", """
				INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
				  (1071, 1001, 'HQ', 1, '2025-03-01 10:00:00')
				""", """
				INSERT INTO employees
				  (id, company_id, branch_id, first_name, last_name, phone, role,
				   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
				   join_request_status, token_version, created_at)
				VALUES
				  (10011, 1001, 1071, 'Hana', 'H', '+201100010011', 'hr', 1, 1, 0,
				   'accepted', 1, '2025-04-01 08:00:00'),
				  (10012, 1001, 1071, 'Momen', 'M', '+201100010012', 'hr', 1, 1, 0,
				   'accepted', 1, '2025-04-01 08:00:00')
				""", """
				INSERT INTO hr_permissions
				  (id, employee_id, can_dashboard, can_recent_activities, can_branches,
				   can_departments, can_job_titles, can_shifts, can_leave_balances,
				   can_assets, can_advances, can_workforce_planning, can_salary_calculator,
				   can_company_settings, can_employees, can_attendance, can_requests,
				   can_payroll, can_penalties)
				VALUES
				  (10101, 10011, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0)
				""");
		// Employee 10012 deliberately gets no hr_permissions row at all --
		// the "no row" / deny-by-default case, matching legacy's
		// hr_permissions_for_employee() returning an all-zeros default
		// map rather than erroring.

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

		transactions = new TransactionTemplate(new JpaTransactionManager(entityManagerFactory));
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
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private void authenticateAs(long employeeId) {
		AuthenticatedPrincipal principal = new AuthenticatedPrincipal(employeeId, employeeId, 1001L);
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null));
	}

	private void require(LegacyHrPermissionKey key) {
		transactions.executeWithoutResult(status -> {
			EntityManager manager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
			LegacyHrPermissionsRepository repository = new JpaRepositoryFactory(manager)
					.getRepository(LegacyHrPermissionsRepository.class);
			new LegacyHrPermissionEnforcer(repository).require(key);
		});
	}

	@Test
	void anEmployeeWithTheFlagSetPassesTheCheck() {
		authenticateAs(10011L);
		assertThatCode(() -> require(LegacyHrPermissionKey.CAN_DASHBOARD)).doesNotThrowAnyException();
	}

	@Test
	void anEmployeeWithTheFlagUnsetIsForbidden() {
		authenticateAs(10011L);
		assertThatThrownBy(() -> require(LegacyHrPermissionKey.CAN_PAYROLL))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).getStatus())
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	/**
	 * Matches {@code hr_permissions_for_employee()}'s own behaviour on a
	 * missing row: deny by default, not an error.
	 */
	@Test
	void anEmployeeWithNoHrPermissionsRowAtAllIsDeniedByDefault() {
		authenticateAs(10012L);
		assertThatThrownBy(() -> require(LegacyHrPermissionKey.CAN_DASHBOARD))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void noAuthenticatedPrincipalAtAllIsDeniedByDefault() {
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertThatThrownBy(() -> require(LegacyHrPermissionKey.CAN_DASHBOARD))
				.isInstanceOf(ApiException.class);
	}

}
