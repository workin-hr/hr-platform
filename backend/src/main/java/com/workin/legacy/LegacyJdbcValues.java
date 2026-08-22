package com.workin.legacy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * The one place Phase 1 turns a JDBC column into a legacy wire value (D-096).
 *
 * <h2>Why null detection differs by getter</h2>
 * <p>JDBC's {@code getLong()} returns primitive {@code 0} for SQL NULL, so a
 * numeric read genuinely needs {@link ResultSet#wasNull()} to tell them apart.
 * {@code getString()} does not: it returns {@code null} for SQL NULL, so its
 * own result is the oracle.
 *
 * <p>Using {@code wasNull()} after {@code getString()} is not merely redundant
 * -- it is wrong. Measured on MariaDB Connector/J 3.5.8 against MariaDB 11.8
 * with {@code sql_mode=''}:
 *
 * <pre>
 * SQL NULL                       getString -> null                    wasNull -> true
 * DATE     '2026-01-15'          getString -> "2026-01-15"            wasNull -> false
 * DATE     '0000-00-00'          getString -> "0000-00-00"            wasNull -> false
 * DATETIME '0000-00-00 00:00:00' getString -> "0000-00-00 00:00:00"   wasNull -> TRUE
 * TIMESTAMP '0000-00-00 00:00:00' getString -> "0000-00-00 00:00:00"  wasNull -> TRUE
 * </pre>
 *
 * <p>So for an all-zero {@code DATETIME} or {@code TIMESTAMP} the driver hands
 * back exactly the string PHP/mysqlnd would, and then reports {@code wasNull()}
 * true. A mapper that trusted {@code wasNull()} discarded a correct value and
 * sent JSON null where legacy sends {@code "0000-00-00 00:00:00"}. That was
 * this codebase's defect, not the driver's, and it is the whole reason this
 * class exists.
 *
 * <h2>Why temporal columns stay lexical</h2>
 * <p>{@code getObject()} normalizes what MariaDB stores literally. Measured:
 * {@code DATE '0000-01-15'} comes back as a {@code java.sql.Date} of
 * {@code 0001-01-15}, and {@code DATETIME '2026-00-15 00:00:00'} as a
 * {@code Timestamp} rolled to {@code 2025-12-15}. Legacy exposes the stored
 * text, so temporal columns are read as strings and never through JDBC
 * temporal objects.
 *
 * <h2>What this class deliberately does not do</h2>
 * <p>There is no zero-date detection here -- no {@code "0000-"} prefix test, no
 * special case on {@link Types#DATE} or {@link Types#TIMESTAMP}. Zero dates
 * merely exposed the bug; the rule is about getter semantics and applies
 * equally to every string-backed column. Nothing is normalized, repaired or
 * rejected.
 */
public final class LegacyJdbcValues {

	private LegacyJdbcValues() {
	}

	/**
	 * One column, as the legacy wire contract sees it.
	 *
	 * <p>{@code BIT} and {@code BOOLEAN} are in the numeric set because MariaDB
	 * reports {@code tinyint(1)} as one of them, and legacy's flag columns are
	 * all {@code tinyint(1)} -- reading them as strings would send
	 * {@code "1"} where mysqlnd sends {@code 1}. {@code DECIMAL} is
	 * deliberately absent: mysqlnd hands PHP a string for a DECIMAL and
	 * {@code json_encode} renders it as a JSON string, so it belongs in the
	 * default branch.
	 *
	 * @param sqlType the column's {@link java.sql.ResultSetMetaData#getColumnType} value
	 * @return a {@link Long} for numeric columns, a {@link String} otherwise,
	 *         or {@code null} for SQL NULL
	 */
	public static Object read(ResultSet rs, int column, int sqlType) throws SQLException {
		switch (sqlType) {
			case Types.BIT, Types.BOOLEAN, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
				long value = rs.getLong(column);
				// Required here: getLong() cannot distinguish NULL from zero.
				return rs.wasNull() ? null : value;
			}
			default -> {
				// NOT rs.wasNull(): see the class note. getString() already
				// returns null for SQL NULL, and reports wasNull() true for a
				// non-null all-zero temporal value.
				return rs.getString(column);
			}
		}
	}

}
