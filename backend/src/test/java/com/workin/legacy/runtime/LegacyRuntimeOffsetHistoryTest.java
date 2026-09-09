package com.workin.legacy.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.workin.legacy.AbstractLegacyMySqlTest;

/**
 * The triggers that record legacy runtime-offset changes.
 *
 * <p>They exist because {@code configs.is_daylight_saving} has no timestamps,
 * a unique key per config_key and no audit anywhere -- its past values are
 * simply gone. Recording changes from the application would capture when Java
 * first NOTICED a new value, not when it changed, and a punch delivered between
 * those moments would still be converted with the wrong offset.
 */
public class LegacyRuntimeOffsetHistoryTest extends AbstractLegacyMySqlTest {

	private static void exec(String sql) throws Exception {
		try (Connection c = connect(); Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	/**
	 * Apply the hooks file the way provisioning does, minus the DELIMITER
	 * lines -- those are a mysql-client construct and mean nothing over JDBC.
	 */
	public static void installHooks() throws Exception {
		String sql = readResource("db/phase1-mysql/legacy_runtime_offset_hooks.sql");
		List<String> statements = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String line : sql.split("\n")) {
			String trimmed = line.strip();
			if (trimmed.startsWith("--") || trimmed.toUpperCase().startsWith("DELIMITER")) {
				continue;
			}
			current.append(line).append('\n');
			if (trimmed.endsWith("$$") || (trimmed.endsWith(";") && current.indexOf("BEGIN") < 0)) {
				statements.add(current.toString().replace("$$", "").strip());
				current.setLength(0);
			}
		}
		for (String statement : statements) {
			if (!statement.isBlank()) {
				exec(statement);
			}
		}
	}

	private static List<Integer> offsets() throws Exception {
		List<Integer> found = new ArrayList<>();
		try (Connection c = connect(); Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(
						"SELECT offset_seconds FROM legacy_runtime_offset_history"
								+ " ORDER BY effective_from_utc ASC, id ASC")) {
			while (rs.next()) {
				found.add(rs.getInt(1));
			}
		}
		return found;
	}

	@BeforeEach
	void reset() throws Exception {
		exec("DELETE FROM configs WHERE config_key = 'is_daylight_saving'");
		exec("DELETE FROM configs WHERE config_key = 'something_else'");
		installHooks();
		// AFTER installing: the hooks file ends with the provisioning seed,
		// which is correct there and noise here. Each test starts from an empty
		// timeline so it asserts only the transitions it caused.
		exec("DELETE FROM legacy_runtime_offset_history");
	}

	@Test
	void aChangeToSummerAndBackIsRecordedAsTwoTransitions() throws Exception {
		exec("INSERT INTO configs (config_key, config_value) VALUES ('is_daylight_saving', '0')");
		exec("UPDATE configs SET config_value = '1' WHERE config_key = 'is_daylight_saving'");
		exec("UPDATE configs SET config_value = '0' WHERE config_key = 'is_daylight_saving'");

		assertThat(offsets()).containsExactly(7200, 10800, 7200);
	}

	@Test
	void aDifferentSpellingOfTheSameOffsetIsNotATransition() throws Exception {
		exec("INSERT INTO configs (config_key, config_value) VALUES ('is_daylight_saving', 'true')");
		int afterInsert = offsets().size();

		// Both mean +03:00. Recording a row here would invent a boundary that
		// never existed, and a punch either side of it would resolve to a
		// configuration change that did not happen.
		exec("UPDATE configs SET config_value = 'dst' WHERE config_key = 'is_daylight_saving'");
		exec("UPDATE configs SET config_value = 'YES' WHERE config_key = 'is_daylight_saving'");
		exec("UPDATE configs SET config_value = ' Summer ' WHERE config_key = 'is_daylight_saving'");

		assertThat(offsets()).hasSize(afterInsert);
		assertThat(offsets()).containsExactly(10800);
	}

	@Test
	void deletingTheKeyRecordsTheDefaultOffset() throws Exception {
		exec("INSERT INTO configs (config_key, config_value) VALUES ('is_daylight_saving', '1')");
		exec("DELETE FROM configs WHERE config_key = 'is_daylight_saving'");

		assertThat(offsets()).containsExactly(10800, 7200);
	}

	@Test
	void renamingIntoAndOutOfTheKeyIsATransition() throws Exception {
		exec("INSERT INTO configs (config_key, config_value) VALUES ('something_else', '1')");
		assertThat(offsets()).as("an unrelated key changes nothing").isEmpty();

		exec("UPDATE configs SET config_key = 'is_daylight_saving' WHERE config_key = 'something_else'");
		assertThat(offsets()).as("renaming INTO the key starts +03:00").containsExactly(10800);

		exec("UPDATE configs SET config_key = 'something_else' WHERE config_key = 'is_daylight_saving'");
		assertThat(offsets()).as("renaming OUT of it returns to the default").containsExactly(10800, 7200);
	}

	@Test
	void anUnrecognisedValueMeansTheDefaultRatherThanFailing() throws Exception {
		// PHP's catch (Throwable $ignored) keeps +02:00 for anything it cannot
		// read, and the trigger must agree or the two disagree about history.
		exec("INSERT INTO configs (config_key, config_value) VALUES ('is_daylight_saving', 'maybe?')");

		assertThat(offsets()).containsExactly(7200);
	}

	@Test
	void anOffsetOutsideTheTwoLegacyValuesIsRejectedEvenUnderNonStrictSqlMode() throws Exception {
		try (Connection c = connect(); Statement s = c.createStatement()) {
			s.execute("SET SESSION sql_mode=''");
			boolean refused = false;
			try {
				s.executeUpdate("INSERT INTO legacy_runtime_offset_history"
						+ " (effective_from_utc, offset_seconds) VALUES (UTC_TIMESTAMP(), 3600)");
			} catch (Exception expected) {
				refused = true;
			}
			assertThat(refused)
					.as("a third offset would be a silent provenance error, so the CHECK must hold")
					.isTrue();
		}
	}

	@Test
	void sameSecondTransitionsResolveDeterministicallyById() throws Exception {
		exec("INSERT INTO legacy_runtime_offset_history (effective_from_utc, offset_seconds)"
				+ " VALUES ('2025-06-01 00:00:00', 7200)");
		exec("INSERT INTO legacy_runtime_offset_history (effective_from_utc, offset_seconds)"
				+ " VALUES ('2025-06-01 00:00:00', 10800)");

		try (Connection c = connect(); Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(
						"SELECT offset_seconds FROM legacy_runtime_offset_history"
								+ " WHERE effective_from_utc <= '2025-06-02 00:00:00'"
								+ " ORDER BY effective_from_utc DESC, id DESC LIMIT 1")) {
			rs.next();
			assertThat(rs.getInt(1))
					.as("the later id wins the tie, and does so every time")
					.isEqualTo(10800);
		}
	}
}
