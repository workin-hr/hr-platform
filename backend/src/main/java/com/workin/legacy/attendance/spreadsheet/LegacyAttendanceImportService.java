package com.workin.legacy.attendance.spreadsheet;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyPdoException;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.notifications.LegacyNotifications;
import com.workin.legacy.spreadsheet.LegacySpreadsheetFormat;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code /apis/api/attendance/import_excel.php} -- everything after the method,
 * auth and role guards.
 *
 * <h2>The order is the contract</h2>
 * <ol>
 * <li>the availability gate, <b>before</b> the upload is looked at, so a
 *     too-early import is 403 whether or not a file was attached;</li>
 * <li>the file's presence;</li>
 * <li>the format, from the bytes -- {@code empty} is its own message;</li>
 * <li>{@code mappings}, decoded and required to be an array;</li>
 * <li>{@code beginTransaction}, the import, {@code commit};</li>
 * <li>the {@code inserted === 0} branch;</li>
 * <li>the notification, <b>after</b> the commit;</li>
 * <li>the envelope, chosen from the format detected in step 3.</li>
 * </ol>
 *
 * <h2>Two catches, and the surprising thing is which one claims SQL</h2>
 * <p>{@code catch (RuntimeException)} rolls back and turns the message into a
 * 400: a key beginning {@code attendance_excel_} becomes the message key
 * itself, and anything else becomes {@code invalid_file_type} with the raw
 * message in {@code data}. {@code catch (Throwable)} rolls back and
 * <b>rethrows</b>, which under D-084 is the deterministic 500.
 *
 * <p><b>A database failure takes the first branch, not the second.</b>
 * {@code apis/config/pdo.php} sets {@code PDO::ERRMODE_EXCEPTION} and PHP's
 * {@code PDOException extends RuntimeException}, so a MariaDB constraint
 * violation is a 400 carrying the driver's own message -- see
 * {@link com.workin.legacy.LegacyPdoException}, which exists precisely to keep
 * that distinguishable from a genuine internal error. The Throwable branch is
 * left for failures PHP would not have called a {@code RuntimeException}.
 *
 * <p>Both branches roll back, and neither swallows a rollback that itself
 * fails: PHP calls {@code $pdo->rollBack()} inside the catch body with no
 * nested catch, so a failing rollback escapes and masks the mapped failure. A
 * parser 400 must not survive a transaction that would not roll back.
 *
 * <h2>What "inserted == 0" does and does not do</h2>
 * <p>Zero inserts with <em>no</em> errors is
 * {@code attendance_excel_no_matched_employees} 400 -- the file parsed, and not
 * one code matched an employee. Zero inserts <em>with</em> errors is
 * {@code cannot_detect_csv_columns} 400 <b>only</b> when the first error
 * mentions one of four phrases; otherwise the request falls through and answers
 * <b>200</b> reporting zero inserted rows and the errors. A file of nothing but
 * duplicates lands there.
 */
@Service
public class LegacyAttendanceImportService {

	/** The four phrases {@code str_contains} is asked about, in PHP's order. */
	private static final List<String> DETECTION_FAILURE_PHRASES =
			List.of("Cannot find employee", "Cannot find check-in", "Unsupported file", "not found");

	/** {@code NotificationTypeEnum::ATTENDANCE_IMPORTED->value}. */
	private static final String ATTENDANCE_IMPORTED = "attendance_imported";

	private final DataSource dataSource;
	private final LegacyAttendanceExcelImportAvailability availability;
	private final LegacyNotifications notifications;
	private final LegacyClock clock;

	public LegacyAttendanceImportService(
			DataSource legacyDataSource, LegacyAttendanceExcelImportAvailability availability,
			LegacyNotifications notifications, LegacyClock clock) {
		this.dataSource = legacyDataSource;
		this.availability = availability;
		this.notifications = notifications;
		this.clock = clock;
	}

	/** What the controller needs to build the response: the payload and its envelope. */
	public record Outcome(Map<String, Object> result, long inserted, boolean xlsx) {
	}

	/**
	 * @param file the {@code file} part; {@code null} when the request carried
	 *        none
	 * @param mappingsRaw the {@code mappings} form field, or {@code null}
	 * @param notificationText the notification's title and body for a given
	 *        insert count, as a two-element array. Supplied by the controller
	 *        rather than built here because both are {@code t()} calls in PHP
	 *        and therefore follow the request's locale -- and because it is
	 *        only ever invoked when something was actually inserted
	 */
	public Outcome importExcel(
			LegacyRequestContext context, MultipartFile file, String mappingsRaw,
			java.util.function.LongFunction<String[]> notificationText) {
		// (1) `if (!attendance_excel_import_is_available())`.
		if (!availability.isAvailable()) {
			throw new LegacyApiException(
					403, "attendance_excel_import_not_yet_available", null,
					Map.of("date", availability.availableFromDisplay()));
		}

		// (2) `if (!isset($_FILES['file']) || $_FILES['file']['error'] !== UPLOAD_ERR_OK)`.
		// A part that is absent, or present with no filename at all, is PHP's
		// UPLOAD_ERR_NO_FILE. A zero-byte file that *was* chosen is
		// UPLOAD_ERR_OK in PHP and falls through to the format check below,
		// which is why this is not a plain `isEmpty()` test.
		if (file == null || file.getOriginalFilename() == null
				|| file.getOriginalFilename().isEmpty()) {
			throw new LegacyApiException(400, "no_file_uploaded");
		}
		byte[] content;
		try {
			content = file.getBytes();
		} catch (IOException ex) {
			// The part exists but cannot be read: PHP would not have moved it
			// either, and the upload error is what the client is told.
			throw new LegacyApiException(400, "no_file_uploaded");
		}

		// (3) `$format = detect_spreadsheet_upload_format($temp_file_path)`.
		// Read once here and reused for the envelope at the very end -- the
		// loader detects it again internally and can change its own copy, but
		// this one never moves.
		LegacySpreadsheetFormat format = LegacySpreadsheetFormat.detect(content);
		if (format == LegacySpreadsheetFormat.EMPTY) {
			throw new LegacyApiException(400, "empty_file");
		}

		// (4) `mappings`.
		Map<String, Object> mappings = decodeMappings(mappingsRaw);

		// (5) the transaction.
		Map<String, Object> result = runImport(context.companyId(), content, mappings);

		long inserted = LegacyValues.toPhpLong(result.get("inserted"));
		Object errors = result.get("errors");

		// (6) `if ($inserted === 0)`.
		if (inserted == 0L) {
			List<?> errorList = errors instanceof List<?> list ? list : null;
			boolean hasErrors = errorList != null ? !errorList.isEmpty() : !LegacyValues.isPhpEmpty(errors);
			if (hasErrors) {
				String first = firstError(errors);
				for (String phrase : DETECTION_FAILURE_PHRASES) {
					if (first.contains(phrase)) {
						throw new LegacyApiException(
								400, "cannot_detect_csv_columns", null, Map.of("errors", errors));
					}
				}
				// No phrase matched: PHP falls through to a 200 carrying zero
				// inserted rows and the errors. Not an oversight to be tidied.
			} else {
				throw new LegacyApiException(400, "attendance_excel_no_matched_employees");
			}
		}

		// (7) the notification, after the commit.
		if (inserted > 0) {
			String[] text = notificationText.apply(inserted);
			notifications.toCompany(
					context.companyId(),
					// `(int) ($auth['employee_id'] ?? 0) ?: null` -- zero is null.
					context.employeeId() > 0 ? context.employeeId() : null,
					ATTENDANCE_IMPORTED, text[0], text[1]);
		}

		return new Outcome(result, inserted, format == LegacySpreadsheetFormat.XLSX);
	}

	/**
	 * {@code $mappings_raw = $_POST['mappings'] ?? null;} then
	 * {@code if (is_string($mappings_raw) && trim($mappings_raw) !== '')}.
	 *
	 * <p>So an absent field and a blank one both mean "no mappings" and are
	 * <em>not</em> errors. A present, non-blank field must decode to an array:
	 * a scalar, {@code null}, or malformed JSON is {@code invalid_input} 400
	 * with {@code field: mappings}.
	 *
	 * <p>{@code json_decode(..., true)} turns a JSON object <em>and</em> a JSON
	 * array into a PHP array, and {@code is_array()} accepts both. A top-level
	 * JSON array therefore passes the check and then simply matches no sheet
	 * code, which is a no-op rather than a failure.
	 */
	private static Map<String, Object> decodeMappings(String mappingsRaw) {
		if (mappingsRaw == null || LegacyValues.phpTrim(mappingsRaw).isEmpty()) {
			return Map.of();
		}
		// json_decode() returns null for malformed JSON and for a literal null
		// alike, and is_array() rejects both -- as it rejects a bare scalar.
		Object decoded = LegacyJsonBody.decodeValue(mappingsRaw);
		if (decoded instanceof Map<?, ?> map) {
			Map<String, Object> mappings = new LinkedHashMap<>();
			map.forEach((key, value) -> mappings.put(String.valueOf(key), value));
			return mappings;
		}
		if (decoded instanceof List<?> list) {
			// A JSON array decodes to a PHP list, which is_array() accepts.
			// Keyed by position, exactly as PHP keys it, so a numeric sheet
			// code could in principle match one.
			Map<String, Object> mappings = new LinkedHashMap<>();
			for (int index = 0; index < list.size(); index++) {
				mappings.put(String.valueOf(index), list.get(index));
			}
			return mappings;
		}
		throw new LegacyApiException(400, "invalid_input", null, Map.of("field", "mappings"));
	}

	/**
	 * {@code $pdo->beginTransaction()} ... {@code commit()} / {@code rollBack()}.
	 *
	 * <p>One connection for the whole import, because PHP has one PDO and every
	 * helper the import reaches shares it -- see
	 * {@link LegacyAttendanceImportStore}.
	 */
	private Map<String, Object> runImport(
			long companyId, byte[] content, Map<String, Object> mappings) {
		// `$pdo = getDB(); $pdo->beginTransaction();` sit *above* the try, so a
		// failure in either is uncaught in PHP and is D-084 here. Only what is
		// inside the try can become a 400.
		Connection connection = open();
		boolean autoCommit = autoCommitOf(connection);

		try {
			try {
				connection.setAutoCommit(false);
			} catch (SQLException ex) {
				throw new IllegalStateException("beginTransaction", ex);
			}

			try {
				LegacyAttendanceImportStore store = new LegacyAttendanceImportStore(connection);
				Map<String, Object> result = LegacyAttendanceImporter.importPunchLog(
						content, companyId, store, mappings, clock.now(), clock.offset());
				// commit() is inside the try in PHP too, so a commit failure is
				// a PDOException caught by the first catch -- a 400, not a 500.
				commit(connection);
				return result;
			} catch (LegacyAttendanceImportException | LegacyPdoException ex) {
				// `catch (RuntimeException $e)`. PHP's PDOException *is* a
				// RuntimeException, so a MariaDB constraint failure lands here
				// rather than in the Throwable branch -- which is the whole
				// reason LegacyPdoException exists as a distinct type. Every
				// other Java RuntimeException keeps its D-084 treatment below.
				//
				// rollBack() is called here, not in a helper that swallows: PHP
				// runs it inside the catch body with no nested catch, so a
				// rollback that throws escapes and masks the mapped failure.
				connection.rollback();
				String key = ex.getMessage();
				if (key != null && key.startsWith("attendance_excel_")) {
					throw new LegacyApiException(400, key);
				}
				throw new LegacyApiException(400, "invalid_file_type", key);
			} catch (RuntimeException ex) {
				// `catch (Throwable $e) { $pdo->rollBack(); throw $e; }` --
				// D-084's generic 500, with nothing about the failure on the
				// wire. Same rollback rule: not swallowed.
				connection.rollback();
				throw ex;
			}
		} catch (SQLException ex) {
			// Only a failed rollback reaches here, because every other SQL
			// failure is already a LegacyPdoException. PHP has no catch around
			// its rollBack() either, so this masks the original exception and
			// becomes an unexpected failure -- deliberately, since a parser 400
			// must not survive a transaction that would not roll back.
			throw new IllegalStateException("rollBack", ex);
		} finally {
			close(connection, autoCommit);
		}
	}

	/** {@code getDB()}: a failure here is above the try, so it is never a 400. */
	private Connection open() {
		try {
			return dataSource.getConnection();
		} catch (SQLException ex) {
			throw new IllegalStateException("getDB", ex);
		}
	}

	private static boolean autoCommitOf(Connection connection) {
		try {
			return connection.getAutoCommit();
		} catch (SQLException ex) {
			throw new IllegalStateException("getAutoCommit", ex);
		}
	}

	/** {@code $pdo->commit()} -- a PDOException, so the first catch claims it. */
	private static void commit(Connection connection) {
		try {
			connection.commit();
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	private static void close(Connection connection, boolean autoCommit) {
		if (connection == null) {
			return;
		}
		try {
			connection.setAutoCommit(autoCommit);
		} catch (SQLException ignored) { // NOPMD - restoring pool state, never the request's outcome
			// Nothing to do: the connection is about to go back to the pool.
		}
		try {
			connection.close();
		} catch (SQLException ignored) { // NOPMD - same
			// Same.
		}
	}

	/** {@code is_array($errors) ? (string) ($errors[0] ?? '') : (string) $errors}. */
	private static String firstError(Object errors) {
		if (errors instanceof List<?> list) {
			return list.isEmpty() || list.get(0) == null
					? ""
					: LegacyValues.toPhpString(list.get(0));
		}
		return errors == null ? "" : LegacyValues.toPhpString(errors);
	}

}
