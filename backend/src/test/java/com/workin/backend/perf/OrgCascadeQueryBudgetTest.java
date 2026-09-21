package com.workin.backend.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.backend.platformadmin.org.OrgCascade;
import com.workin.backend.platformadmin.org.OrgCascadeStore;
import com.workin.legacy.AbstractLegacyMySqlTest;

/**
 * The form cascade's five lists cost one round trip, and are the same five
 * lists five round trips returned.
 *
 * <p>Every page that opens an add or edit form builds this payload, and so does
 * every unfiltered list page through {@code AdminViewModelAdvice}. Against a
 * database on the other side of a network -- 106 ms away on the remote-db
 * stack, measured -- five statements are half a second of a page's wait for
 * data one statement returns. The statements are unioned with a discriminator
 * and split in the store.
 *
 * <p>The count is the point, so it is asserted rather than described: the five
 * lists are easy to re-split by hand later, and nothing else in the suite would
 * notice. The contents are asserted beside it because a cheaper query that
 * returns something else is not the same page -- in particular each list's own
 * order, which one {@code ORDER BY} over the union now has to reproduce for all
 * five.
 */
class OrgCascadeQueryBudgetTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 9970;
	private static final long OTHER_COMPANY = 9971;
	private static final long YARD = 997001;
	private static final long BARN = 997002;
	private static final long OPS = 997101;
	private static final long RETIRED = 997102;
	private static final long WELDER = 997201;
	private static final long FITTER = 997202;

	private QueryCounter counter;
	private OrgCascadeStore store;

	private static void exec(String sql) throws Exception {
		try (Connection c = connect(); Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	@BeforeEach
	void seed() throws Exception {
		exec("DELETE FROM department_branches WHERE branch_id IN (" + YARD + ", " + BARN + ")");
		exec("DELETE FROM job_titles WHERE company_id IN (" + COMPANY + ", " + OTHER_COMPANY + ")");
		exec("DELETE FROM departments WHERE company_id IN (" + COMPANY + ", " + OTHER_COMPANY + ")");
		exec("DELETE FROM branches WHERE company_id IN (" + COMPANY + ", " + OTHER_COMPANY + ")");
		exec("DELETE FROM companies WHERE id IN (" + COMPANY + ", " + OTHER_COMPANY + ")");
		seedAsLegacyWould(
				company(COMPANY, "Cascade Co", "+201100249970"),
				company(OTHER_COMPANY, "Other Co", "+201100249971"),
				// Seeded out of name order: each list's order is the query's, not the id's.
				branch(YARD, COMPANY, "Cascade Yard"),
				branch(BARN, COMPANY, "Cascade Barn"),
				department(OPS, COMPANY, "Cascade Ops", true),
				department(RETIRED, COMPANY, "Cascade Retired", false),
				"INSERT INTO department_branches (department_id, branch_id) VALUES (" + OPS + ", " + YARD + ")",
				jobTitle(WELDER, COMPANY, OPS, "Cascade Welder"),
				jobTitle(FITTER, COMPANY, OPS, "Cascade Fitter"));

		this.counter = new QueryCounter();
		DataSource counted = this.counter.wrap(new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword()));
		this.store = new OrgCascadeStore(new JdbcTemplate(counted));
	}

	@Test
	void theWholeCascadeIsOneRoundTripAndListsEachGroupByName() {
		OrgCascade[] cascade = new OrgCascade[1];
		List<String> issued = this.counter.measure(() -> cascade[0] = this.store.cascade(COMPANY));

		assertThat(issued).as("statements issued for the whole payload").hasSize(1);
		assertThat(names(cascade[0].branchesByCompany(), COMPANY))
				.containsExactly("Cascade Barn", "Cascade Yard");
		assertThat(names(cascade[0].departmentsByCompany(), COMPANY)).containsExactly("Cascade Ops");
		assertThat(names(cascade[0].departmentsByBranch(), YARD)).containsExactly("Cascade Ops");
		assertThat(cascade[0].departmentsByBranch()).as("a branch with no department has no group")
				.doesNotContainKey(BARN);
		assertThat(names(cascade[0].jobTitlesByDepartment(), OPS))
				.containsExactly("Cascade Fitter", "Cascade Welder");
		assertThat(names(cascade[0].jobTitlesByCompany(), COMPANY))
				.containsExactly("Cascade Fitter", "Cascade Welder");
		assertThat(cascade[0].branchesByCompany()).as("R-051: one company's session gets one company")
				.doesNotContainKey(OTHER_COMPANY);
	}

	@Test
	void anEditsKeptRowsCostTheSameOneRoundTrip() throws Exception {
		// The kept arm adds a second select inside one of the five lists (D-250), which
		// is the part of the union that is not a plain SELECT. It still travels with the
		// rest.
		OrgCascade[] cascade = new OrgCascade[1];
		OrgCascade.Kept kept = new OrgCascade.Kept(YARD, RETIRED, 0);
		List<String> issued = this.counter.measure(() -> cascade[0] = this.store.cascade(COMPANY, kept));

		assertThat(issued).as("statements issued for an edit's payload").hasSize(1);
		assertThat(names(cascade[0].departmentsByCompany(), COMPANY))
				.as("the retired department the employee still holds").containsExactly("Cascade Ops", "Cascade Retired");
		assertThat(names(cascade[0].departmentsByBranch(), YARD))
				.as("and it is listed under the kept branch, linked or not")
				.containsExactly("Cascade Ops", "Cascade Retired");

		exec("INSERT INTO department_branches (department_id, branch_id) VALUES (" + RETIRED + ", " + YARD + ")");
		assertThat(names(this.store.cascade(COMPANY, kept).departmentsByBranch(), YARD))
				.as("listed once when it is linked as well").containsExactly("Cascade Ops", "Cascade Retired");
	}

	@Test
	void theAdministratorsUnfilteredPayloadIsOneRoundTripToo() {
		// The filter cascade every unfiltered list page carries: every company's rows,
		// and the one query that was five on the busiest of the pages.
		OrgCascade[] cascade = new OrgCascade[1];
		List<String> issued = this.counter.measure(() -> cascade[0] = this.store.cascade(0));

		assertThat(issued).as("statements issued with no company filter").hasSize(1);
		assertThat(names(cascade[0].branchesByCompany(), COMPANY))
				.containsExactly("Cascade Barn", "Cascade Yard");
		assertThat(cascade[0].departmentsByCompany()).containsKey(COMPANY);
	}

	private static List<String> names(Map<Long, List<OrgCascade.Option>> grouped, long group) {
		return grouped.getOrDefault(group, List.of()).stream().map(OrgCascade.Option::name).toList();
	}

	private static String company(long id, String name, String phone) {
		return "INSERT INTO companies (id, company_name, phone, password_hash, status, otp_verified,"
				+ " profile_completed, created_at) VALUES (" + id + ", '" + name + "', '" + phone
				+ "', 'x', 'active', 1, 1, '2025-01-01 09:00:00')";
	}

	private static String branch(long id, long companyId, String name) {
		return "INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES ("
				+ id + ", " + companyId + ", '" + name + "', 1, '2025-01-01 09:00:00')";
	}

	private static String department(long id, long companyId, String name, boolean active) {
		return "INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES ("
				+ id + ", " + companyId + ", '" + name + "', " + (active ? 1 : 0) + ", '2025-01-01 09:00:00')";
	}

	private static String jobTitle(long id, long companyId, long departmentId, String name) {
		return "INSERT INTO job_titles (id, company_id, department_id, name, is_active, created_at) VALUES ("
				+ id + ", " + companyId + ", " + departmentId + ", '" + name + "', 1, '2025-01-01 09:00:00')";
	}

}
