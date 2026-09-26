package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.workin.legacy.LegacyMariaDb;

/**
 * {@link LegacyLoginThrottle#bindablePhone} against the collation the phone
 * lookup actually runs under -- {@code utf8mb4_unicode_ci} on
 * {@code mariadb:11.8} -- for every code point of the BMP (D-289).
 *
 * <p>The throttle keys a login on the ASCII digits of the phone the route
 * binds. That is only sound if nothing it lets through can compare equal to a
 * digit other than the one it was folded to, and the collation, not Java's
 * character tables, decides what compares equal. So the collation is asked.
 */
class LegacyLoginPhoneCollationTest {

	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.emptyDatabase();

	/** Every non-digit character {@code bindablePhone} admits. */
	private static final List<String> ADMITTED = List.of(" ", "\t", "\n", "\r", "\0", "\u000B", "+", "-");

	@Test
	void everyCodePointTheCollationEquatesToADigitIsFoldedToThatDigitOrRefused() throws SQLException {
		Map<Integer, TreeSet<String>> equalTo = collationEqualities();
		// Not vacuous: the review's examples are in what the collation says.
		assertThat(equalTo.get(0x24FF)).as("⓿").contains("0");
		assertThat(equalTo.get(0x3021)).as("〡").contains("1");
		assertThat(equalTo.get(0x0661)).as("١").contains("1");

		List<String> divergent = new ArrayList<>();
		for (int codePoint = 1; codePoint <= 0xFFFF; codePoint++) {
			if (Character.isSurrogate((char) codePoint)) {
				continue;
			}
			String bound = LegacyLoginThrottle.bindablePhone(new String(Character.toChars(codePoint)));
			if (bound == null) {
				continue;
			}
			for (int index = 0; index < bound.length(); index++) {
				String character = String.valueOf(bound.charAt(index));
				if (!character.matches("[0-9]") && !ADMITTED.contains(character)) {
					divergent.add(String.format("U+%04X bound to %s", codePoint, bound));
				}
			}
		}
		// Admitted, the lookup binds `bound` and the key is its digits, so a
		// character the collation equates to a digit must bind that digit.
		for (Map.Entry<Integer, TreeSet<String>> entry : equalTo.entrySet()) {
			String bound = LegacyLoginThrottle.bindablePhone(new String(Character.toChars(entry.getKey())));
			if (bound != null && !entry.getValue().contains(bound.replaceAll("[^0-9]", ""))) {
				divergent.add(String.format("U+%04X equates to %s but binds %s",
						entry.getKey(), entry.getValue(), bound));
			}
		}
		assertThat(divergent).as("code points whose key would differ from what the lookup matches").isEmpty();
	}

	@Test
	void noAdmittedPunctuationComparesEqualToADigit() throws SQLException {
		try (Connection connection = MARIADB.connect(); Statement statement = connection.createStatement()) {
			createDigitTable(connection);
			for (String character : ADMITTED) {
				try (PreparedStatement query = connection.prepareStatement(
						"SELECT COUNT(*) FROM ds WHERE d = ? COLLATE utf8mb4_unicode_ci")) {
					query.setString(1, character);
					try (ResultSet rows = query.executeQuery()) {
						rows.next();
						assertThat(rows.getLong(1)).as("U+%04X", (int) character.charAt(0)).isZero();
					}
				}
			}
			statement.execute("DROP TABLE ds");
		}
	}

	/** Code point to every one- or two-digit string the collation says it equals. */
	private static Map<Integer, TreeSet<String>> collationEqualities() throws SQLException {
		Map<Integer, TreeSet<String>> equalTo = new TreeMap<>();
		try (Connection connection = MARIADB.connect(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE cps (cp INT PRIMARY KEY, ch VARCHAR(4) NOT NULL)"
					+ " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
			createDigitTable(connection);
			List<Integer> batch = new ArrayList<>();
			for (int codePoint = 1; codePoint <= 0xFFFF; codePoint++) {
				if (!Character.isSurrogate((char) codePoint)) {
					batch.add(codePoint);
				}
				if (batch.size() == 2000 || (codePoint == 0xFFFF && !batch.isEmpty())) {
					insert(connection, batch);
					batch.clear();
				}
			}
			try (ResultSet rows = statement.executeQuery("SELECT cps.cp, ds.d FROM cps JOIN ds ON cps.ch = ds.d")) {
				while (rows.next()) {
					equalTo.computeIfAbsent(rows.getInt(1), key -> new TreeSet<>()).add(rows.getString(2));
				}
			}
			statement.execute("DROP TABLE cps");
			statement.execute("DROP TABLE ds");
		}
		return equalTo;
	}

	private static void createDigitTable(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE IF NOT EXISTS ds (d VARCHAR(4) NOT NULL)"
					+ " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
			statement.execute("DELETE FROM ds");
			StringBuilder values = new StringBuilder();
			for (int number = 0; number < 10; number++) {
				values.append(values.isEmpty() ? "" : ",").append("('").append(number).append("')");
			}
			for (int number = 0; number < 100; number++) {
				values.append(",('").append(String.format("%02d", number)).append("')");
			}
			statement.execute("INSERT INTO ds (d) VALUES " + values);
		}
	}

	private static void insert(Connection connection, List<Integer> codePoints) throws SQLException {
		StringBuilder sql = new StringBuilder("INSERT INTO cps (cp, ch) VALUES ");
		for (int index = 0; index < codePoints.size(); index++) {
			sql.append(index == 0 ? "(?, ?)" : ", (?, ?)");
		}
		try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
			int parameter = 1;
			for (int codePoint : codePoints) {
				statement.setInt(parameter++, codePoint);
				statement.setString(parameter++, new String(Character.toChars(codePoint)));
			}
			statement.executeUpdate();
		}
	}
}
