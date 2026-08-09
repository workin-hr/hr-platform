package com.workin.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.workin.backend.AbstractIntegrationTest;

/**
 * Pins the Postgres semantics that {@code V36__weekly_rest_token_backfill_rls_safe.sql}
 * depends on, and that {@code V35__weekly_rest_token_backfill.sql} got wrong.
 *
 * <p>V35 issued a bare {@code UPDATE} against {@code employee_schedules},
 * a table carrying FORCE ROW LEVEL SECURITY. FORCE subjects the table
 * <em>owner</em> to the policy too, so under any migration role that is
 * not a superuser (and lacks BYPASSRLS) that UPDATE matched zero rows
 * and reported success. No existing test could catch it, because
 * Testcontainers' default Postgres user is a superuser and therefore
 * bypasses RLS -- the same masking effect ADR-0002 records from the
 * PMR-07 spike, reached from the migration side instead of the runtime
 * side.
 *
 * <p>So this test deliberately does not use the container's superuser
 * for the interesting assertions. It builds a probe table owned by a
 * purpose-made non-superuser role, mirroring the production policy
 * shape from rls/V34, and proves three things: the bare UPDATE silently
 * skips every row, the NO FORCE pattern V36 uses updates the row and
 * leaves FORCE restored, and a superuser connection hides the whole
 * problem. If a future Postgres release changed FORCE semantics, the
 * first two assertions are what would tell us.
 */
class RlsForcedBackfillSemanticsTest extends AbstractIntegrationTest {

	private static final String PROBE_TABLE = "rls_backfill_probe";
	private static final String PROBE_ROLE = "rls_backfill_probe_owner";
	private static final String PROBE_PASSWORD = "rls_backfill_probe_password";
	private static final String LEGACY_LABEL = "Weekly rest";
	private static final String TOKEN = "WEEKLY_REST";

	private static final String BARE_BACKFILL =
			"UPDATE " + PROBE_TABLE + " SET note = '" + TOKEN + "' WHERE note = '" + LEGACY_LABEL + "'";

	/**
	 * A plain {@code DROP ROLE} is not enough: the role owns the probe
	 * table and holds a schema grant, and Postgres refuses to drop a role
	 * anything still depends on. DROP OWNED BY clears both, and the whole
	 * thing is guarded because DROP OWNED BY errors on a role that does
	 * not exist.
	 */
	private static final String DROP_PROBE_ROLE_IF_EXISTS =
			"DO $$ BEGIN"
					+ "  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + PROBE_ROLE + "') THEN"
					+ "    EXECUTE 'DROP OWNED BY " + PROBE_ROLE + "';"
					+ "    EXECUTE 'DROP ROLE " + PROBE_ROLE + "';"
					+ "  END IF;"
					+ "END $$";

	@BeforeAll
	static void createProbeRole() throws SQLException {
		try (Connection superuser = superuserConnection(); Statement statement = superuser.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
			statement.execute(DROP_PROBE_ROLE_IF_EXISTS);
			statement.execute("CREATE ROLE " + PROBE_ROLE + " LOGIN PASSWORD '" + PROBE_PASSWORD + "'");
			statement.execute("GRANT USAGE ON SCHEMA public TO " + PROBE_ROLE);
		}
	}

	@AfterAll
	static void dropProbeRole() throws SQLException {
		try (Connection superuser = superuserConnection(); Statement statement = superuser.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
			statement.execute(DROP_PROBE_ROLE_IF_EXISTS);
		}
	}

	/**
	 * Rebuilt per test so each one starts from a single legacy-labelled
	 * row, independent of execution order.
	 */
	@BeforeEach
	void createProbeTable() throws SQLException {
		try (Connection superuser = superuserConnection(); Statement statement = superuser.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
			statement.execute("CREATE TABLE " + PROBE_TABLE
					+ " (id BIGINT PRIMARY KEY, company_id BIGINT NOT NULL, note TEXT)");
			statement.execute("ALTER TABLE " + PROBE_TABLE + " OWNER TO " + PROBE_ROLE);
			statement.execute("ALTER TABLE " + PROBE_TABLE + " ENABLE ROW LEVEL SECURITY");
			statement.execute("ALTER TABLE " + PROBE_TABLE + " FORCE ROW LEVEL SECURITY");
			// Same policy shape as rls/V34: an unset app.current_company_id
			// resolves to NULL, so nothing is visible by default.
			statement.execute("CREATE POLICY " + PROBE_TABLE + "_isolation ON " + PROBE_TABLE
					+ " USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT)");
			statement.execute("INSERT INTO " + PROBE_TABLE + " VALUES (1, 42, '" + LEGACY_LABEL + "')");
		}
	}

	@Test
	void theProbeRoleIsGenuinelyUnprivileged() throws SQLException {
		// Without this the other assertions could pass vacuously against an
		// accidentally-privileged role, which is precisely how the original
		// defect stayed invisible.
		try (Connection superuser = superuserConnection();
				Statement statement = superuser.createStatement();
				ResultSet result = statement.executeQuery(
						"SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = '" + PROBE_ROLE + "'")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getBoolean("rolsuper")).isFalse();
			assertThat(result.getBoolean("rolbypassrls")).isFalse();
		}
	}

	@Test
	void bareUpdateSilentlySkipsEveryRowForTheNonSuperuserOwner() throws SQLException {
		try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
			int updated = statement.executeUpdate(BARE_BACKFILL);

			// The V35 defect: no error, no rows, apparent success.
			assertThat(updated).isZero();
		}

		assertThat(noteAsSuperuser()).isEqualTo(LEGACY_LABEL);
	}

	@Test
	void theNoForcePatternUpdatesTheRowAndLeavesForceRestored() throws SQLException {
		try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
			statement.execute("ALTER TABLE " + PROBE_TABLE + " NO FORCE ROW LEVEL SECURITY");
			int updated = statement.executeUpdate(BARE_BACKFILL);
			statement.execute("ALTER TABLE " + PROBE_TABLE + " FORCE ROW LEVEL SECURITY");

			assertThat(updated).isEqualTo(1);
		}

		assertThat(noteAsSuperuser()).isEqualTo(TOKEN);
		assertThat(forceRowSecurityEnabled()).isTrue();
	}

	@Test
	void aSuperuserConnectionHidesTheDefectEntirely() throws SQLException {
		// Why CI stayed green through the broken migration.
		try (Connection superuser = superuserConnection(); Statement statement = superuser.createStatement()) {
			assertThat(statement.executeUpdate(BARE_BACKFILL)).isEqualTo(1);
		}

		assertThat(noteAsSuperuser()).isEqualTo(TOKEN);
	}

	private static Connection superuserConnection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), PROBE_ROLE, PROBE_PASSWORD);
	}

	private static String noteAsSuperuser() throws SQLException {
		try (Connection superuser = superuserConnection();
				Statement statement = superuser.createStatement();
				ResultSet result = statement.executeQuery("SELECT note FROM " + PROBE_TABLE + " WHERE id = 1")) {
			assertThat(result.next()).isTrue();
			return result.getString("note");
		}
	}

	private static boolean forceRowSecurityEnabled() throws SQLException {
		try (Connection superuser = superuserConnection();
				Statement statement = superuser.createStatement();
				ResultSet result = statement.executeQuery(
						"SELECT relforcerowsecurity FROM pg_class WHERE relname = '" + PROBE_TABLE + "'")) {
			assertThat(result.next()).isTrue();
			return result.getBoolean("relforcerowsecurity");
		}
	}

}
