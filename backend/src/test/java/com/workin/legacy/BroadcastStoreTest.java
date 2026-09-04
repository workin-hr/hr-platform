package com.workin.legacy;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.backend.platformadmin.content.BroadcastStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the broadcast writes the rows the dashboard's loop would have
 * written -- against a real MariaDB, because the whole change is which SQL
 * runs, and a mock would assert only that this test knows what it wrote.
 *
 * <p>R-045 records the defect being avoided: the dashboard inserts one row
 * per recipient in one untransacted request, so PHP's execution limit can
 * cut it off partway. The property that matters here is that one statement
 * produces the same set, scoped correctly, and skips inactive employees.
 */
class BroadcastStoreTest extends AbstractLegacyMySqlTest {

	private BroadcastStore store;

	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() throws Exception {
		DataSource dataSource = new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
		this.jdbc = new JdbcTemplate(dataSource);
		this.store = new BroadcastStore(this.jdbc);

		seedAsLegacyWould(
				"DELETE FROM notifications WHERE title LIKE 'BCAST-%'",
				"DELETE FROM employees WHERE id BETWEEN 990100 AND 990199",
				"DELETE FROM branches WHERE id BETWEEN 990100 AND 990199",
				"DELETE FROM companies WHERE id BETWEEN 990100 AND 990199",
				"INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
						+ " (990101, 'Bcast A', '+201000990101', 'active', '2019-01-15 09:00:00'),"
						+ " (990102, 'Bcast B', '+201000990102', 'active', '2019-01-15 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
						+ " (990101, 990101, 'A', 1, '2019-03-01 10:00:00'),"
						+ " (990102, 990102, 'B', 1, '2019-03-01 10:00:00')",
				// two active in company A, one inactive, one active in company B
				employee(990111, 990101, 990101, 1),
				employee(990112, 990101, 990101, 1),
				employee(990113, 990101, 990101, 0),
				employee(990121, 990102, 990102, 1));
	}

	/** {@code branch_id} is a foreign key, so the branch has to exist first. */
	private static String employee(long id, long companyId, long branchId, int active) {
		return "INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, created_at) VALUES ("
				+ id + ", " + companyId + ", " + branchId + ", '" + id + "', 'B', 'C',"
				+ " '+2019" + id + "', 'employee', " + active + ", '2019-04-01 08:00:00')";
	}

	private int rowsFor(String title) {
		Integer count = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM notifications WHERE title = ?", Integer.class, title);
		return count == null ? 0 : count;
	}

	@Test
	void aCompanyBroadcastReachesOnlyThatCompanysActiveEmployees() {
		int sent = this.store.broadcastToCompanyEmployees(990101, "BCAST-company", "body");

		assertThat(sent).isEqualTo(2);
		assertThat(rowsFor("BCAST-company")).isEqualTo(2);

		List<Long> recipients = this.jdbc.queryForList(
				"SELECT to_employee_id FROM notifications WHERE title = ? ORDER BY to_employee_id",
				Long.class, "BCAST-company");
		assertThat(recipients).containsExactly(990111L, 990112L);
	}

	/** The inactive employee is the negative control: scoping alone would still include them. */
	@Test
	void anInactiveEmployeeIsNotNotified() {
		this.store.broadcastToCompanyEmployees(990101, "BCAST-inactive", null);

		Integer forInactive = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM notifications WHERE title = ? AND to_employee_id = ?",
				Integer.class, "BCAST-inactive", 990113L);
		assertThat(forInactive).isZero();
	}

	@Test
	void aBroadcastToAnotherCompanyDoesNotLeakAcross() {
		this.store.broadcastToCompanyEmployees(990102, "BCAST-b", null);

		Integer inA = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM notifications WHERE title = ? AND company_id = ?",
				Integer.class, "BCAST-b", 990101L);
		assertThat(inA).isZero();
	}

	@Test
	void theRowCarriesTheCompanyOfTheEmployeeItIsFor() {
		this.store.broadcastToCompanyEmployees(990102, "BCAST-tenant", null);

		List<Long> companies = this.jdbc.queryForList(
				"SELECT DISTINCT company_id FROM notifications WHERE title = ?", Long.class, "BCAST-tenant");
		assertThat(companies).containsExactly(990102L);
	}

	@Test
	void anAllEmployeesBroadcastCountsEveryActiveEmployee() {
		int reach = this.store.countAllEmployees();
		int sent = this.store.broadcastToAllEmployees("BCAST-all", null);

		assertThat(sent).isEqualTo(reach);
		assertThat(rowsFor("BCAST-all")).isEqualTo(reach);
	}

	/** An empty body is NULL, not an empty string -- the clients render the two differently. */
	@Test
	void aNullBodyIsStoredAsNull() {
		this.store.broadcastToCompanyEmployees(990102, "BCAST-nullbody", null);

		Integer nulls = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM notifications WHERE title = ? AND body IS NULL",
				Integer.class, "BCAST-nullbody");
		assertThat(nulls).isEqualTo(1);
	}

	@Test
	void sentBroadcastsAreGroupedRatherThanListedPerRecipient() {
		this.store.broadcastToCompanyEmployees(990101, "BCAST-grouped", "x");

		List<BroadcastStore.SentBroadcast> recent = this.store.recentBroadcasts(20);

		assertThat(recent).anySatisfy(broadcast -> {
			assertThat(broadcast.title()).isEqualTo("BCAST-grouped");
			assertThat(broadcast.recipients()).isEqualTo(2);
		});
	}

}
