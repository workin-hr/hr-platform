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
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.workin.legacy.LegacyMariaDb;
import com.workin.legacy.phone.CanonicalPhone;
import com.workin.legacy.phone.CanonicalPhones;
import com.workin.legacy.phone.PhoneLookup;

/**
 * The phone lookup against the collation it actually runs under --
 * {@code utf8mb4_unicode_ci} on {@code mariadb:11.8} -- for every code point
 * of the BMP (D-289, D-291).
 *
 * <p>The collation equates far more than ASCII digits to a stored
 * {@code 01012345678}: Arabic-Indic and fullwidth digits, but also circled and
 * dingbat digits, Hangzhou numerals and others outside any digit category.
 * D-289 answered that with an allowlist of what could be bound. D-291 binds
 * nothing the client typed: a lookup binds the stored spellings of a
 * canonical number, which are ASCII digits and a {@code +}. So the collation
 * can no longer widen a match, and what these tests pin is the two halves of
 * that claim, asked of the database rather than of Java's character tables:
 * a code point the collation equates to a digit is either refused or read as
 * exactly that digit, and a lookup binds nothing but ASCII.
 */
class LegacyLoginPhoneCollationTest {

	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.emptyDatabase();

	/** An Egyptian mobile's first ten digits; the eleventh is the code point under test. */
	private static final String STEM = "0101234567";

	@Test
	void everyCodePointTheCollationEquatesToADigitIsRefusedOrReadAsThatDigit() throws SQLException {
		Map<Integer, TreeSet<String>> equalTo = collationEqualities();
		// Not vacuous: the review's examples are in what the collation says.
		assertThat(equalTo.get(0x24FF)).as("⓿").contains("0");
		assertThat(equalTo.get(0x3021)).as("〡").contains("1");
		assertThat(equalTo.get(0x0661)).as("١").contains("1");

		List<String> divergent = new ArrayList<>();
		int refused = 0;
		for (Map.Entry<Integer, TreeSet<String>> entry : equalTo.entrySet()) {
			String phone = STEM + new String(Character.toChars(entry.getKey()));
			Optional<CanonicalPhone> read = CanonicalPhones.parse(phone, null);
			if (read.isEmpty()) {
				refused++;
				continue;
			}
			boolean asTheCollationSays = entry.getValue().stream()
					.map(digit -> CanonicalPhones.parse(STEM + digit, null))
					.anyMatch(expected -> expected.isPresent() && expected.get().e164().equals(read.get().e164()));
			if (!asTheCollationSays) {
				divergent.add(String.format("U+%04X equates to %s but reads as %s",
						entry.getKey(), entry.getValue(), read.get().e164()));
			}
		}
		assertThat(divergent).as("code points read as a digit the collation does not equate them to").isEmpty();
		// Circled, dingbat and Hangzhou digits are among the refused.
		assertThat(refused).isPositive();
		assertThat(CanonicalPhones.parse(STEM + "➇", null)).isEmpty();
		assertThat(CanonicalPhones.parse(STEM + "〨", null)).isEmpty();
		assertThat(CanonicalPhones.parse(STEM + "٨", null).map(CanonicalPhone::e164)).contains("+201012345678");
	}

	@Test
	void aLookupBindsNothingButAsciiWhateverWasTyped() {
		List<String> divergent = new ArrayList<>();
		for (int codePoint = 1; codePoint <= 0xFFFF; codePoint++) {
			if (Character.isSurrogate((char) codePoint)) {
				continue;
			}
			String typed = STEM + new String(Character.toChars(codePoint));
			for (String bind : PhoneLookup.ofInput(typed, () -> List.of("+966", "+971", "+218"))
					.clause("phone").binds()) {
				if (!bind.matches("\\+?[0-9]+")) {
					divergent.add(String.format("U+%04X binds %s", codePoint, bind));
				}
			}
		}
		assertThat(divergent).isEmpty();
	}

	@Test
	void circledAndDingbatDigitsCannotReachAnAccount() throws SQLException {
		try (Connection connection = MARIADB.connect(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE accounts (id INT PRIMARY KEY, phone VARCHAR(20) NOT NULL,"
					+ " country_code VARCHAR(10) NULL, UNIQUE KEY phone (phone))"
					+ " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
			statement.execute("INSERT INTO accounts VALUES (1, '01012345678', '+20')");

			// Each of these compares equal to the stored phone under the
			// collation, which the old WHERE phone = ? would have matched.
			for (String typed : List.of("⓪①⓪①②③④⑤⑥⑦⑧", "⓿➀⓿➀➁➂➃➄➅➆➇", "〇〡〇〡〢〣〤〥〦〧〨", "0101234567➇")) {
				try (PreparedStatement raw = connection.prepareStatement(
						"SELECT COUNT(*) FROM accounts WHERE phone = ?")) {
					raw.setString(1, typed);
					try (ResultSet rows = raw.executeQuery()) {
						rows.next();
						assertThat(rows.getLong(1)).as("the collation equates %s", typed).isOne();
					}
				}
				assertThat(rowsFound(connection, PhoneLookup.ofInput(typed, List::of))).as(typed).isZero();
			}
			// A real spelling of the number does reach it.
			assertThat(rowsFound(connection, PhoneLookup.ofInput("+20 10 1234 5678", List::of))).isOne();
			assertThat(rowsFound(connection, PhoneLookup.ofInput("١٠١٢٣٤٥٦٧٨", List::of))).isOne();
			statement.execute("DROP TABLE accounts");
		}
	}

	/** The verified rows a lookup finds, as the stores run it. */
	private static int rowsFound(Connection connection, PhoneLookup lookup) throws SQLException {
		PhoneLookup.Clause match = lookup.clause("phone");
		try (PreparedStatement query = connection.prepareStatement(
				"SELECT phone, country_code FROM accounts WHERE " + match.sql())) {
			for (int index = 0; index < match.binds().size(); index++) {
				query.setString(index + 1, match.binds().get(index));
			}
			int found = 0;
			try (ResultSet rows = query.executeQuery()) {
				while (rows.next()) {
					if (lookup.matches(rows.getString(1), rows.getString(2))) {
						found++;
					}
				}
			}
			return found;
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
