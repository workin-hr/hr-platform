package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;

/**
 * {@link LegacyJdbcValues} against a real MariaDB, under the Phase-1 SQL mode.
 *
 * <p>A unit test with a mocked {@code ResultSet} would prove nothing here: the
 * whole point is what the <em>driver</em> does with an all-zero temporal value,
 * and that only a real MariaDB and a real Connector/J can answer.
 */
class LegacyJdbcValuesTest {

	private static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:11.8");

	private static Map<String, Object> normalRow;
	private static Map<String, Object> nullRow;
	private static Map<String, Object> zeroRow;
	private static Map<String, Object> partialRow;

	@BeforeAll
	static void seedAndRead() throws Exception {
		DB.start();
		try (Connection c = connect(); Statement st = c.createStatement()) {
			st.execute("SET SESSION sql_mode=''");
			st.execute("""
					CREATE TABLE probe (
						id INT PRIMARY KEY,
						flag TINYINT(1) NULL,
						count_col INT NULL,
						name VARCHAR(50) NULL,
						money DECIMAL(10,2) NULL,
						d DATE NULL,
						dt DATETIME NULL,
						ts TIMESTAMP NULL
					)""");
			st.executeUpdate("INSERT INTO probe VALUES (1, 1, 42, 'Nour', 12.50,"
					+ " '2026-01-15', '2026-01-15 09:30:00', '2026-01-15 09:30:00')");
			st.executeUpdate("INSERT INTO probe VALUES (2, NULL, NULL, NULL, NULL, NULL, NULL, NULL)");
			st.executeUpdate("INSERT INTO probe VALUES (3, 0, 0, '', 0.00,"
					+ " '0000-00-00', '0000-00-00 00:00:00', '0000-00-00 00:00:00')");
			st.executeUpdate("INSERT INTO probe VALUES (4, 0, 0, 'x', 1.00,"
					+ " '0000-01-15', '2026-00-15 00:00:00', '0000-00-00 00:00:00')");
		}
		try (Connection c = connect(); Statement st = c.createStatement();
				ResultSet rs = st.executeQuery("SELECT * FROM probe ORDER BY id")) {
			rs.next();
			normalRow = read(rs);
			rs.next();
			nullRow = read(rs);
			rs.next();
			zeroRow = read(rs);
			rs.next();
			partialRow = read(rs);
		}
	}

	private static Map<String, Object> read(ResultSet rs) throws Exception {
		ResultSetMetaData meta = rs.getMetaData();
		Map<String, Object> row = new LinkedHashMap<>();
		for (int column = 1; column <= meta.getColumnCount(); column++) {
			row.put(meta.getColumnLabel(column),
					LegacyJdbcValues.read(rs, column, meta.getColumnType(column)));
		}
		return row;
	}

	// ---- numeric ------------------------------------------------------

	@Test
	void numericColumnsAreNumbersAndNullStaysNull() {
		assertThat(normalRow.get("count_col")).isInstanceOf(Long.class).isEqualTo(42L);
		assertThat(normalRow.get("flag")).isInstanceOf(Long.class).isEqualTo(1L);
		assertThat(nullRow.get("count_col")).isNull();
		assertThat(nullRow.get("flag")).isNull();
	}

	@Test
	void numericZeroIsZeroAndNotNull() {
		// The reason the numeric branch still needs wasNull(): getLong()
		// returns 0 for SQL NULL, so the two are otherwise identical.
		assertThat(zeroRow.get("count_col")).isEqualTo(0L);
		assertThat(zeroRow.get("flag")).isEqualTo(0L);
	}

	// ---- strings and decimals ----------------------------------------

	@Test
	void stringsAndDecimalsStayLexical() {
		assertThat(normalRow.get("name")).isEqualTo("Nour");
		assertThat(nullRow.get("name")).isNull();
		assertThat(zeroRow.get("name")).isEqualTo("");

		// DECIMAL is deliberately in the string branch: mysqlnd hands PHP a
		// string and json_encode renders it as one.
		assertThat(normalRow.get("money")).isInstanceOf(String.class).isEqualTo("12.50");
		assertThat(nullRow.get("money")).isNull();
	}

	// ---- temporal -----------------------------------------------------

	@Test
	void ordinaryTemporalValuesAreLexicalStrings() {
		assertThat(normalRow.get("d")).isEqualTo("2026-01-15");
		assertThat(normalRow.get("dt")).isEqualTo("2026-01-15 09:30:00");
		assertThat(normalRow.get("ts")).isEqualTo("2026-01-15 09:30:00");
	}

	@Test
	void temporalNullStaysNull() {
		assertThat(nullRow.get("d")).isNull();
		assertThat(nullRow.get("dt")).isNull();
		assertThat(nullRow.get("ts")).isNull();
	}

	/**
	 * The defect D-096 corrects. The driver returns these strings correctly and
	 * then reports {@code wasNull()} true for the two datetime-shaped ones; a
	 * mapper trusting {@code wasNull()} sent JSON null where PHP sends the
	 * literal.
	 */
	@Test
	void allZeroTemporalValuesSurviveAsTheirLiteralString() {
		assertThat(zeroRow.get("d")).isEqualTo("0000-00-00");
		assertThat(zeroRow.get("dt")).isEqualTo("0000-00-00 00:00:00");
		assertThat(zeroRow.get("ts")).isEqualTo("0000-00-00 00:00:00");

		// Stated as the property, not just the values: none of them is null.
		assertThat(zeroRow.get("d")).isNotNull();
		assertThat(zeroRow.get("dt")).isNotNull();
		assertThat(zeroRow.get("ts")).isNotNull();
	}

	/**
	 * Partial-zero values are why temporal columns are read as strings at all:
	 * {@code getObject()} rolls them into valid dates that were never stored.
	 */
	@Test
	void partialZeroTemporalValuesAreNotRolled() {
		assertThat(partialRow.get("d")).isEqualTo("0000-01-15");
		assertThat(partialRow.get("dt")).isEqualTo("2026-00-15 00:00:00");
		// Measured: getObject() would give 0001-01-15 and 2025-12-15 here.
		assertThat(partialRow.get("d")).isNotEqualTo("0001-01-15");
		assertThat(partialRow.get("dt")).isNotEqualTo("2025-12-15 00:00:00");
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
	}

}
