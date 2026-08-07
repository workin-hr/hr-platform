package com.workin.backend.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantRole;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * F-16/F-25 enforcement boundary 3: the scope-limited rule (role-based
 * fallback -- only a pure MANAGER consults scopes) and the reachable
 * employee-set resolution (branch/department union, deny-by-default on
 * empty scopes). Service methods run inside the caller's tenant
 * transaction like PermissionEvaluationService, so the test wraps them
 * in a TransactionTemplate with the session variable applied.
 */
class ResourceScopeServiceTest extends AbstractIntegrationTest {

	@Autowired
	private ResourceScopeService resourceScopeService;

	@Autowired
	private TenantSessionVariable tenantSessionVariable;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(flywayDataSource);
	}

	private record Fixture(Long companyId, Long membershipId) {
	}

	private Fixture company() {
		JdbcTemplate jdbc = jdbc();
		String phone = "+2034" + System.nanoTime() % 100_000_000L;
		Long companyId = jdbc.queryForObject(
				"INSERT INTO companies (name, phone) VALUES ('Scope Co', ?) RETURNING id", Long.class, phone);
		Long identityId = jdbc.queryForObject(
				"INSERT INTO identities (phone, password_hash) VALUES (?, 'x') RETURNING id", Long.class, phone);
		Long membershipId = jdbc.queryForObject(
				"INSERT INTO tenant_memberships (identity_id, company_id, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
				Long.class, identityId, companyId);
		return new Fixture(companyId, membershipId);
	}

	private Long createBranch(Long companyId, String name) {
		return jdbc().queryForObject(
				"INSERT INTO branches (company_id, name) VALUES (?, ?) RETURNING id", Long.class, companyId, name);
	}

	private Long createDepartment(Long companyId, String name) {
		return jdbc().queryForObject(
				"INSERT INTO departments (company_id, name) VALUES (?, ?) RETURNING id", Long.class, companyId, name);
	}

	private Long createEmployee(Long companyId, Long branchId, Long departmentId) {
		return jdbc().queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name, branch_id, department_id) "
						+ "VALUES (?, 'S', 'E', ?, ?) RETURNING id",
				Long.class, companyId, branchId, departmentId);
	}

	private void addScope(Fixture f, String scopeType, Long scopeId) {
		jdbc().update(
				"INSERT INTO membership_resource_scopes (membership_id, company_id, scope_type, scope_id) "
						+ "VALUES (?, ?, ?, ?)",
				f.membershipId(), f.companyId(), scopeType, scopeId);
	}

	private AuthorizationContext context(Fixture f, TenantRole... roles) {
		return new AuthorizationContext(0L, f.membershipId(), f.companyId(), List.of(roles), Set.of());
	}

	private <T> T inTenantTx(Long companyId, java.util.function.Supplier<T> body) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			tenantSessionVariable.apply(companyId);
			return body.get();
		});
	}

	@Test
	void onlyAPureManagerIsScopeLimited() {
		Fixture f = company();
		assertThat(resourceScopeService.isScopeLimited(context(f, TenantRole.MANAGER))).isTrue();
		assertThat(resourceScopeService.isScopeLimited(context(f, TenantRole.MANAGER, TenantRole.HR))).isFalse();
		assertThat(resourceScopeService.isScopeLimited(context(f, TenantRole.COMPANY_ADMIN))).isFalse();
		assertThat(resourceScopeService.isScopeLimited(context(f, TenantRole.EMPLOYEE))).isFalse();
		assertThat(resourceScopeService.isScopeLimited(context(f))).isFalse();
	}

	@Test
	void reachableSetIsTheBranchDepartmentUnionAndEmptyByDefault() {
		Fixture f = company();
		Long branchA = createBranch(f.companyId(), "A");
		Long branchB = createBranch(f.companyId(), "B");
		Long dept = createDepartment(f.companyId(), "D");
		Long empInA = createEmployee(f.companyId(), branchA, null);
		Long empInB = createEmployee(f.companyId(), branchB, null);
		Long empInDept = createEmployee(f.companyId(), branchB, dept);

		AuthorizationContext ctx = context(f, TenantRole.MANAGER);

		// No scope rows -> deny by default.
		assertThat(inTenantTx(f.companyId(), () -> resourceScopeService.reachableEmployeeIds(ctx))).isEmpty();

		// Branch A scope -> only employees in branch A.
		addScope(f, "BRANCH", branchA);
		assertThat(inTenantTx(f.companyId(), () -> resourceScopeService.reachableEmployeeIds(ctx)))
				.containsExactly(empInA);

		// Add a DEPARTMENT scope -> union of branch A and the department.
		addScope(f, "DEPARTMENT", dept);
		assertThat(inTenantTx(f.companyId(), () -> resourceScopeService.reachableEmployeeIds(ctx)))
				.containsExactlyInAnyOrder(empInA, empInDept);
		// empInB is in neither the scoped branch nor the scoped department.
		assertThat(inTenantTx(f.companyId(), () -> resourceScopeService.canReachEmployee(ctx, empInB))).isFalse();
		assertThat(inTenantTx(f.companyId(), () -> resourceScopeService.canReachEmployee(ctx, empInDept))).isTrue();
	}

}
