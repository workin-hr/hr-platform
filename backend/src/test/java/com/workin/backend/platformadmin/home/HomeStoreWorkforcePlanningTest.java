package com.workin.backend.platformadmin.home;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;

/**
 * The admin home page's workforce-planning chart, against a real MariaDB.
 *
 * <p>This query is the same one {@code dashboard/stats.php} answers with, and
 * it carries the same two tenant predicates for the same reason (D-277):
 * neither {@code workforce_planning.department_id} nor
 * {@code employees.department_id} has a foreign key, so a row owned by one
 * company can name another company's department, and the chart would then draw
 * that company's name and its headcount.
 *
 * <p>A store test rather than a page test because the whole property is which
 * SQL runs; the page adds only the labels' translation.
 */
class HomeStoreWorkforcePlanningTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 990301L;
	private static final long VICTIM = 990302L;
	private static final long OWN_DEPARTMENT = 990301L;
	private static final long VICTIM_DEPARTMENT = 990302L;

	private HomeStore store;

	@BeforeEach
	void setUp() throws Exception {
		DataSource dataSource = new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
		this.store = new HomeStore(new JdbcTemplate(dataSource));

		seedAsLegacyWould(
				"DELETE FROM workforce_planning WHERE company_id BETWEEN 990300 AND 990399",
				"DELETE FROM employees WHERE id BETWEEN 990300 AND 990399",
				"DELETE FROM departments WHERE id BETWEEN 990300 AND 990399",
				"DELETE FROM branches WHERE id BETWEEN 990300 AND 990399",
				"DELETE FROM companies WHERE id BETWEEN 990300 AND 990399",
				"INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
						+ " (" + COMPANY + ", 'Home A', '+201000990301', 'active', '2019-01-15 09:00:00'),"
						+ " (" + VICTIM + ", 'Home B', '+201000990302', 'active', '2019-01-15 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
						+ " (990311, " + COMPANY + ", 'A', 1, '2019-03-01 10:00:00'),"
						+ " (990312, " + VICTIM + ", 'B', 1, '2019-03-01 10:00:00')",
				"INSERT INTO departments (id, company_id, name, created_at) VALUES"
						+ " (" + OWN_DEPARTMENT + ", " + COMPANY + ", 'A Engineering', '2019-03-01 10:00:00'),"
						+ " (" + VICTIM_DEPARTMENT + ", " + VICTIM + ", 'B Secret', '2019-03-01 10:00:00')",
				employee(990321, COMPANY, 990311, OWN_DEPARTMENT),
				// The victim's own worker, and a second one of theirs pointing at
				// this company's department -- which no foreign key forbids.
				employee(990322, VICTIM, 990312, VICTIM_DEPARTMENT),
				employee(990323, VICTIM, 990312, OWN_DEPARTMENT),
				"INSERT INTO workforce_planning (id, company_id, branch_id, department_id,"
						+ " job_title_id, planned_count) VALUES"
						+ " (990301, " + COMPANY + ", 990311, " + OWN_DEPARTMENT + ", 0, 5),"
						// What a pre-D-277 save_target.php would have written.
						+ " (990302, " + COMPANY + ", 990311, " + VICTIM_DEPARTMENT + ", 0, 9),"
						+ " (990303, " + VICTIM + ", 990312, " + VICTIM_DEPARTMENT + ", 0, 4)");
	}

	@Test
	void aRowNamingAnotherCompanysDepartmentIsNotDrawnAndItsWorkersAreNotCounted() {
		List<HomeChart> charts = this.store.workforcePlanning(COMPANY);

		assertThat(charts.get(0).labels())
				.as("the planted row names a department this company does not own, so it is not drawn")
				.containsExactly("A Engineering");
		assertThat(charts.get(0).values()).containsExactly(5d);
		assertThat(charts.get(1).values())
				.as("and the actual headcount counts this company's own worker only, not the "
						+ "victim's worker whose department_id points here")
				.containsExactly(1d);
	}

	@Test
	void theUnfilteredPlatformViewDrawsEachCompanysOwnRowsAndNoCrossedOne() {
		List<HomeChart> charts = this.store.workforcePlanning(0L);

		assertThat(charts.get(0).labels())
				.as("companyId 0 is the platform administrator's unfiltered view, where the "
						+ "predicate has to relate the two tables rather than name one company")
				.containsExactly("A Engineering", "B Secret");
		assertThat(charts.get(0).values()).containsExactly(5d, 4d);
		assertThat(charts.get(1).values())
				.as("one own worker in A Engineering; B Secret's own worker, not the one it lent "
						+ "to this company's department")
				.containsExactly(1d, 1d);
	}

	private static String employee(long id, long companyId, long branchId, long departmentId) {
		return "INSERT INTO employees (id, company_id, branch_id, department_id, employee_code,"
				+ " first_name, last_name, phone, role, is_active, created_at) VALUES (" + id + ", "
				+ companyId + ", " + branchId + ", " + departmentId + ", '" + id + "', 'F', 'L',"
				+ " '+2010" + id + "', 'employee', 1, '2019-04-01 08:00:00')";
	}
}
