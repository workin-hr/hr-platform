package com.workin.backend.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Reports, at startup, which of the Phase-1-owned tables the configured
 * MariaDB is missing -- the R-023 gap made visible.
 *
 * <p>ADR-0013 gives Flyway no ownership of any MariaDB schema, so
 * nothing creates these tables at runtime: they are applied by hand from
 * {@code db/phase1-mysql/phase1_extensions.sql} before cutover. Until
 * this check existed, an unprovisioned database announced itself as a
 * "table doesn't exist" SQLException from whichever feature a user
 * happened to reach first -- the platform admin discovering it at the
 * login screen, a mobile client at its first token refresh.
 *
 * <p><b>Deliberately not fatal.</b> Refusing to start would make a
 * missing admin table take down {@code /apis/**} for every employee,
 * which is the opposite of the containment the rest of the code is built
 * for: {@code LegacyBranchService} and {@code LegacyEmployeeStore} both
 * swallow an absent device table on purpose so a partially-provisioned
 * deployment still serves. This logs at ERROR and names the feature each
 * missing table disables, so the gap is loud, attributable, and visible
 * in the first seconds of a deployment rather than in a user report.
 */
@Component
@Profile("phase1-mysql")
public class Phase1SchemaCheck implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(Phase1SchemaCheck.class);

	/**
	 * Table to the capability it carries, in the order
	 * {@code phase1_extensions.sql} declares them. The text is what an
	 * operator reads at 03:00, so it names the user-visible consequence
	 * rather than the class that throws.
	 */
	static final SequencedMap<String, String> OWNED_TABLES = new LinkedHashMap<>();

	static {
		OWNED_TABLES.put("legacy_refresh_tokens",
				"token refresh -- every mobile and desktop client is logged out when its access token expires");
		OWNED_TABLES.put("platform_admins", "the platform-admin surface at /admin -- nobody can sign in");
		OWNED_TABLES.put("platform_admin_refresh_tokens", "platform-admin token refresh");
		OWNED_TABLES.put("platform_admin_audit_events",
				"the platform-admin audit trail -- admin actions refuse to run without it, by design");
		OWNED_TABLES.put("platform_admin_login_attempts", "platform-admin login throttling");
		OWNED_TABLES.put("platform_admin_mfa", "platform-admin TOTP");
		OWNED_TABLES.put("platform_admin_mfa_bootstrap_tokens", "platform-admin MFA enrolment and recovery");
		OWNED_TABLES.put("platform_admin_step_up_approvals", "step-up approval for platform-admin actions");
		OWNED_TABLES.put("SPRING_SESSION", "the platform-admin web session -- login succeeds and is then forgotten");
		OWNED_TABLES.put("SPRING_SESSION_ATTRIBUTES", "the platform-admin web session's contents");
	}

	private final DataSource dataSource;

	public Phase1SchemaCheck(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(ApplicationArguments args) {
		List<String> missing;
		try {
			missing = missingTables();
		} catch (SQLException ex) {
			log.error("Could not check for the Phase 1 tables; assume they are unverified. "
					+ "See docs/operations/provisioning-phase1-tables.md", ex);
			return;
		}
		if (missing.isEmpty()) {
			log.info("Phase 1 schema check: all {} owned tables are present.", OWNED_TABLES.size());
			return;
		}
		log.error("Phase 1 schema check: {} of {} owned tables are MISSING from this database. "
						+ "Nothing creates them at runtime (ADR-0013); apply "
						+ "db/phase1-mysql/phase1_extensions.sql -- see "
						+ "docs/operations/provisioning-phase1-tables.md (R-023).",
				missing.size(), OWNED_TABLES.size());
		for (String table : missing) {
			log.error("  missing table {} -- disables {}", table, OWNED_TABLES.get(table));
		}
	}

	/**
	 * Names present in the connected schema, compared case-insensitively:
	 * {@code SPRING_SESSION} is upper case because Spring Session's own
	 * MySQL schema declares it that way, while MariaDB's table-name case
	 * sensitivity depends on {@code lower_case_table_names} and therefore
	 * on the host filesystem. Matching case-sensitively here would report
	 * a phantom missing table on a platform that folded the name.
	 */
	List<String> missingTables() throws SQLException {
		TreeSet<String> present = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		try (Connection connection = dataSource.getConnection()) {
			DatabaseMetaData metaData = connection.getMetaData();
			try (ResultSet tables = metaData.getTables(
					connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
				while (tables.next()) {
					present.add(tables.getString("TABLE_NAME"));
				}
			}
		}
		return OWNED_TABLES.keySet().stream().filter(table -> !present.contains(table)).toList();
	}

	/** The owned table names, for the test that pins this list to the shipped DDL. */
	static List<String> ownedTables() {
		return List.copyOf(OWNED_TABLES.keySet());
	}

}
