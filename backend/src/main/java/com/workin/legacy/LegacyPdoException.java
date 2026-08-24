package com.workin.legacy;

import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A PHP {@code PDOException}, as raised by a legacy connection running under
 * {@code PDO::ERRMODE_EXCEPTION} ({@code apis/config/pdo.php:15}).
 *
 * <h2>Why this class exists at all</h2>
 * <p>In PHP, <b>{@code PDOException extends RuntimeException}</b>. That single
 * fact decides the response of every legacy endpoint that wraps its database
 * work in {@code try { ... } catch (RuntimeException $e) { ... } catch (Throwable $e) { throw $e; }}
 * -- a MariaDB constraint violation is caught by the <em>first</em> catch, not
 * the second, and therefore becomes that endpoint's mapped 4xx rather than an
 * unexpected failure. {@code attendance/import_excel.php} is the first ported
 * endpoint shaped that way, and reading a SQL failure as an unexpected error
 * there would answer 500 where PHP answers 400.
 *
 * <p>Java's exception hierarchy carries no equivalent signal:
 * {@code SQLException} is checked and unrelated to {@code RuntimeException}, and
 * wrapping it in a generic {@code IllegalStateException} would make it
 * indistinguishable from a real internal error. So the distinction is made
 * explicit -- this type means "PHP would have called this a
 * {@code RuntimeException}", and every other Java {@code RuntimeException}
 * keeps its D-084 treatment.
 *
 * <h2>The message is reconstructed, not invented</h2>
 * <p>{@code fail(INVALID_FILE_TYPE, 400, $e->getMessage())} puts the exception
 * message on the wire as {@code data}, so it is part of the contract. PDO builds
 * it as {@code SQLSTATE[%s]: %s: %d %s} -- state, a description of that state,
 * the driver's numeric code, and the driver's own text.
 *
 * <p>Measured against PHP 8.3 (mysqlnd) and MariaDB Connector/J 3.5.8 against
 * the same MariaDB 11.8 and the same statement, the three variable parts agree
 * exactly:
 *
 * <table border="1">
 * <caption>{@code employees.branch_id} NOT NULL, the create-mapping INSERT</caption>
 * <tr><th></th><th>PHP PDO</th><th>JDBC</th></tr>
 * <tr><td>state</td><td>{@code 23000}</td><td>{@code getSQLState()} = {@code 23000}</td></tr>
 * <tr><td>code</td><td>{@code 1048}</td><td>{@code getErrorCode()} = {@code 1048}</td></tr>
 * <tr><td>text</td><td>{@code Column 'branch_id' cannot be null}</td>
 *     <td>{@code getMessage()} = {@code (conn=5) Column 'branch_id' cannot be null}</td></tr>
 * </table>
 *
 * <p>The server's text is identical; Connector/J only prefixes {@code (conn=N) },
 * which is stripped. The one part with no JDBC counterpart is the description,
 * which comes from a fixed table inside PDO -- so that table was extracted
 * empirically rather than transcribed, by raising each state through MariaDB's
 * {@code SIGNAL SQLSTATE '...'} and reading PDO's message back. <b>Including the
 * miss:</b> a state PDO does not know renders literally as
 * {@code <<Unknown error>>}, measured on {@code ZZZZZ}, {@code 99999},
 * {@code XX999}, {@code 45000}, {@code 70100} and {@code XA100}. So every input
 * has a measured answer and nothing is fabricated.
 */
public class LegacyPdoException extends RuntimeException {

	/** {@code (conn=5) } -- Connector/J's prefix, absent from PDO's message. */
	private static final Pattern CONNECTION_PREFIX = Pattern.compile("^\\(conn=-?\\d+\\) ");

	/** PDO's own fallback for a state its table does not carry. */
	static final String UNKNOWN = "<<Unknown error>>";

	/**
	 * PDO's SQLSTATE description table, extracted by measurement.
	 *
	 * <p>Not exhaustive and does not need to be: an absent entry has its own
	 * measured rendering, so a state nobody probed still produces exactly what
	 * PHP would produce.
	 */
	static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
			Map.entry("02000", "No data"),
			Map.entry("07002", "COUNT field incorrect"),
			Map.entry("08004", "Server rejected the connection"),
			Map.entry("08S01", "Communication link failure"),
			Map.entry("0A000", "Feature not supported"),
			Map.entry("21000", "Cardinality violation"),
			Map.entry("21S01", "Insert value list does not match column list"),
			Map.entry("22001", "String data, right truncated"),
			Map.entry("22003", "Numeric value out of range"),
			Map.entry("22004", "Null value not allowed"),
			Map.entry("22007", "Invalid datetime format"),
			Map.entry("22008", "Datetime field overflow"),
			Map.entry("22012", "Division by zero"),
			Map.entry("22026", "String data, length mismatch"),
			Map.entry("22P02", "Invalid text representation"),
			Map.entry("23000", "Integrity constraint violation"),
			Map.entry("23503", "Foreign key violation"),
			Map.entry("23514", "Check violation"),
			Map.entry("24000", "Invalid cursor state"),
			Map.entry("25000", "Invalid transaction state"),
			Map.entry("28000", "Invalid authorization specification"),
			Map.entry("34000", "Invalid cursor name"),
			Map.entry("3D000", "Invalid catalog name"),
			Map.entry("40001", "Serialization failure"),
			Map.entry("42000", "Syntax error or access violation"),
			Map.entry("42S02", "Base table or view not found"),
			Map.entry("42S22", "Column not found"),
			Map.entry("44000", "WITH CHECK OPTION violation"),
			Map.entry("HY000", "General error"),
			Map.entry("HY001", "Memory allocation error"),
			Map.entry("HY010", "Function sequence error"),
			Map.entry("HY093", "Invalid parameter number"),
			Map.entry("HYT00", "Timeout expired"));

	private LegacyPdoException(String message, SQLException cause) {
		super(message, cause);
	}

	/**
	 * The {@code PDOException} PHP would have raised for this JDBC failure.
	 *
	 * <p>A {@code null} SQLSTATE renders as {@code SQLSTATE[]} with the unknown
	 * description. That combination was not measured because Connector/J
	 * reports the server's state for every server-side error, and a server-side
	 * error is the only kind this endpoint's statements can produce -- but the
	 * <em>class</em> of the outcome is still right, which is the part that
	 * decides 400 against 500.
	 */
	public static LegacyPdoException from(SQLException cause) {
		String state = cause.getSQLState() == null ? "" : cause.getSQLState();
		String description = DESCRIPTIONS.getOrDefault(state, UNKNOWN);
		String server = cause.getMessage() == null ? "" : cause.getMessage();
		server = CONNECTION_PREFIX.matcher(server).replaceFirst("");
		return new LegacyPdoException(
				"SQLSTATE[" + state + "]: " + description + ": " + cause.getErrorCode() + " " + server,
				cause);
	}

}
