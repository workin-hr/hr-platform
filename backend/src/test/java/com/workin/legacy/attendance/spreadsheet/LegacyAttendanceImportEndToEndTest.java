package com.workin.legacy.attendance.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * Wave 12.6 slice 1b: {@code attendance/import_excel.php}.
 *
 * <p>Weighted towards the parts a clean-room port gets wrong: the availability
 * gate running <em>before</em> the file is looked at, the two different success
 * messages with two different placeholder names, the {@code inserted == 0}
 * branch that answers 200 as often as it answers 400, and the fact that
 * {@code skipped} counts something different on each of the two import paths.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyAttendanceImportEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String IMPORT = "/apis/api/attendance/import_excel.php";
	private static final String CONFIG_KEY = "attendance_excel_import_available_from";

	private static final long COMPANY_1 = 20901L;
	private static final long COMPANY_2 = 20902L;

	private static final long ADMIN_1 = 209011L;
	private static final long HR_1 = 209012L;
	private static final long MANAGER_1 = 209013L;
	private static final long EMPLOYEE_1 = 209014L;
	private static final long EMPLOYEE_2 = 209015L;
	private static final long ADMIN_2 = 209021L;
	private static final long EMPLOYEE_OTHER_CO = 209022L;

	private static final long BRANCH_1 = 20911L;
	private static final long BRANCH_2 = 20912L;
	private static final long SHIFT_1 = 20931L;
	private static final long SHIFT_OTHER_CO = 20932L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the import fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	@BeforeEach
	void reset() {
		execute("DELETE FROM attendance");
		execute("DELETE FROM notifications");
		execute("DELETE FROM employee_shift_assignments");
		execute("DELETE FROM employees WHERE id > 209100");
		execute("UPDATE employees SET employee_code = id WHERE id < 209100");
		availableFrom("2020-01-01");
	}

	// ------------------------------------------------------------------
	// The availability gate
	// ------------------------------------------------------------------

	/**
	 * The gate is the very first thing after the role check, so a request with
	 * no file at all is still 403 rather than {@code no_file_uploaded}. Getting
	 * this order wrong is invisible on the happy path and wrong on every
	 * rejection.
	 */
	@Test
	void theAvailabilityGateRunsBeforeTheFileIsLookedAt() {
		availableFrom("2099-06-05");

		Map<String, Object> body = postWithoutFile(ADMIN_1, 403);

		assertThat(body.get("success")).isEqualTo(false);
		// j/n/Y, so no leading zeros on the day or the month.
		assertThat(body.get("message")).isEqualTo("Will be available on 5/6/2099.");
	}

	/** No config row at all: unavailable, and the placeholder is an em dash. */
	@Test
	void anAbsentConfigValueRefusesWithAnEmDash() {
		execute("DELETE FROM configs WHERE config_key = '" + CONFIG_KEY + "'");

		assertThat(postWithoutFile(ADMIN_1, 403).get("message"))
				.isEqualTo("Will be available on —.");
	}

	/**
	 * {@code parse_ymd_config_date()} rolls rather than validating, so an
	 * impossible date is a real date -- and 30 February 2020 lands in the past,
	 * which makes the import available. A strict parser would have answered 403
	 * here.
	 */
	@Test
	void anImpossibleConfigDateRollsAndStillOpensTheGate() {
		availableFrom("2020-02-30");

		Map<String, Object> body = post(ADMIN_1, "punch.csv", punchCsv(), null, 200);
		assertThat(body.get("success")).isEqualTo(true);
	}

	/** The same roll, displayed: month 13 of 2099 is January 2100. */
	@Test
	void aRolledFutureConfigDateIsDisplayedAsTheRolledDate() {
		availableFrom("2099-13-01");

		assertThat(postWithoutFile(ADMIN_1, 403).get("message"))
				.isEqualTo("Will be available on 1/1/2100.");
	}

	/** Today itself is available: the comparison is {@code >=}, not {@code >}. */
	@Test
	void theGateOpensOnTheDayItself() {
		availableFrom(LocalDate.now(java.time.ZoneOffset.ofHours(2)).toString());

		assertThat(post(ADMIN_1, "punch.csv", punchCsv(), null, 200).get("success")).isEqualTo(true);
	}

	// ------------------------------------------------------------------
	// Guards
	// ------------------------------------------------------------------

	@Test
	void theMethodGuardRunsBeforeAuthentication() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + IMPORT), HttpMethod.GET,
				new HttpEntity<>("{}", headers), mapType());
		assertThat(response.getStatusCode().value()).isEqualTo(405);
	}

	/**
	 * A MANAGER authenticates and is then refused by the endpoint's own role
	 * test -- and is refused <b>before</b> the availability gate, which is why
	 * this stays 403 {@code forbidden} rather than the gate's 403.
	 */
	@Test
	void aManagerIsForbidden() {
		assertThat(postWithoutFile(MANAGER_1, 403).get("message")).isEqualTo("Forbidden");
	}

	@Test
	void hrMayImport() {
		assertThat(post(HR_1, "punch.csv", punchCsv(), null, 200).get("success")).isEqualTo(true);
	}

	@Test
	void aMissingFilePartIsNoFileUploaded() {
		assertThat(postWithoutFile(ADMIN_1, 400).get("message")).isEqualTo("No file uploaded");
	}

	/**
	 * A zero-byte upload is <b>not</b> {@code no_file_uploaded}: PHP sees
	 * {@code UPLOAD_ERR_OK} for a file that was chosen and happens to be empty,
	 * and the format detector then reports {@code empty}.
	 */
	@Test
	void aZeroByteUploadIsEmptyFile() {
		assertThat(post(ADMIN_1, "punch.csv", new byte[0], null, 400).get("message"))
				.isEqualTo("File is empty");
	}

	// ------------------------------------------------------------------
	// mappings
	// ------------------------------------------------------------------

	@Test
	void malformedMappingsJsonIsInvalidInput() {
		Map<String, Object> body = post(ADMIN_1, "punch.csv", punchCsv(), "{not json", 400);
		assertThat(body.get("message")).isEqualTo("Invalid input");
	}

	@Test
	void scalarMappingsJsonIsInvalidInput() {
		assertThat(post(ADMIN_1, "punch.csv", punchCsv(), "\"abc\"", 400).get("message"))
				.isEqualTo("Invalid input");
	}

	/** A blank field is "no mappings", not an error -- {@code trim(...) !== ''}. */
	@Test
	void blankMappingsAreIgnored() {
		assertThat(post(ADMIN_1, "punch.csv", punchCsv(), "   ", 200).get("success")).isEqualTo(true);
	}

	// ------------------------------------------------------------------
	// The punch-log happy path
	// ------------------------------------------------------------------

	@Test
	void aPunchLogCsvImportsOneRowPerEmployeeDay() {
		Map<String, Object> body = post(ADMIN_1, "punch.csv", punchCsv(), null, 200);

		// imported_csv carries a {count} placeholder, not {inserted}.
		assertThat(body.get("message")).isEqualTo("Imported 2 records from CSV");

		Map<String, Object> data = dataOf(body);
		assertThat(data.keySet()).containsExactly("inserted", "skipped", "errors");
		assertThat(number(data.get("inserted"))).isEqualTo(2L);
		assertThat(number(data.get("skipped"))).isZero();
		assertThat((List<?>) data.get("errors")).isEmpty();

		List<Map<String, Object>> rows = query(
				"SELECT employee_id, check_in, check_out, method FROM attendance ORDER BY employee_id");
		assertThat(rows).hasSize(2);
		// First punch of the day is the check-in, last is the check-out.
		assertThat(rows.get(0).get("employee_id").toString()).isEqualTo(String.valueOf(EMPLOYEE_1));
		assertThat(timestamp(rows.get(0).get("check_in"))).isEqualTo("2026-04-26 08:03:00");
		assertThat(timestamp(rows.get(0).get("check_out"))).isEqualTo("2026-04-26 17:11:00");
		// method is the literal 'excel', never the request's.
		assertThat(rows.get(0).get("method")).isEqualTo("excel");
		// A single punch leaves no check-out at all.
		assertThat(rows.get(1).get("check_out")).isNull();
	}

	/**
	 * The success message and the placeholder both change with the format, and
	 * the format comes from the bytes. Same rows, same count, different key.
	 */
	@Test
	void anXlsxImportAnswersTheOtherMessageKey() {
		Map<String, Object> body = post(ADMIN_1, "punch.xlsx", punchXlsx(), null, 200);

		assertThat(body.get("message")).isEqualTo("Imported 2 records from XLSX");
		assertThat(number(dataOf(body).get("inserted"))).isEqualTo(2L);
	}

	/**
	 * The import notifies the company once, after the commit, with
	 * {@code recipient_kind = 'company'} and no recipient employee -- one row,
	 * not one per employee.
	 */
	@Test
	void aSuccessfulImportNotifiesTheCompanyOnce() {
		post(ADMIN_1, "punch.csv", punchCsv(), null, 200);

		List<Map<String, Object>> rows = query(
				"SELECT company_id, recipient_kind, from_employee_id, to_employee_id, title, body,"
						+ " notification_type FROM notifications");
		assertThat(rows).hasSize(1);
		Map<String, Object> notification = rows.get(0);
		assertThat(notification.get("recipient_kind")).isEqualTo("company");
		assertThat(notification.get("to_employee_id")).isNull();
		assertThat(number(notification.get("from_employee_id"))).isEqualTo(ADMIN_1);
		assertThat(notification.get("notification_type")).isEqualTo("attendance_imported");
		assertThat(notification.get("title")).isEqualTo("Attendance import");
		assertThat(notification.get("body"))
				.isEqualTo("2 attendance records were imported from file.");
	}

	/** Nothing inserted, nothing notified -- the guard is {@code $inserted > 0}. */
	@Test
	void aFullyDuplicateImportNotifiesNobody() {
		post(ADMIN_1, "punch.csv", punchCsv(), null, 200);
		execute("DELETE FROM notifications");

		post(ADMIN_1, "punch.csv", punchCsv(), null, 200);

		assertThat(query("SELECT id FROM notifications")).isEmpty();
	}

	// ------------------------------------------------------------------
	// inserted == 0
	// ------------------------------------------------------------------

	/**
	 * The fall-through. Zero inserted rows <em>with</em> errors is a 400 only
	 * when the first error mentions one of four phrases; a duplicate error
	 * mentions none of them, so the request answers <b>200</b> reporting that
	 * nothing was imported.
	 */
	@Test
	void aRepeatedImportAnswers200WithZeroInsertedAndTheDuplicateErrors() {
		post(ADMIN_1, "punch.csv", punchCsv(), null, 200);

		Map<String, Object> data = dataOf(post(ADMIN_1, "punch.csv", punchCsv(), null, 200));

		assertThat(number(data.get("inserted"))).isZero();
		List<?> errors = (List<?>) data.get("errors");
		assertThat(errors).hasSize(2);
		assertThat(errors.get(0).toString())
				.isEqualTo("Day 1: duplicate for '209014' on 2026-04-26 — skipped");
		// skipped is `$skipped + count($errors)` on this path, so the two
		// duplicates are counted even though nothing was "skipped" for want of
		// an employee.
		assertThat(number(data.get("skipped"))).isEqualTo(2L);
	}

	/**
	 * Zero inserted rows and <em>no</em> errors is the other branch: every code
	 * failed to resolve, which is counted in {@code skipped} rather than
	 * reported as an error, so {@code errors} is empty and the answer is 400.
	 */
	@Test
	void aFileWhoseCodesMatchNobodyIs400() {
		byte[] csv = csv("code,datetime", "777777,26/04/2026 08:03", "777777,26/04/2026 17:11");

		Map<String, Object> body = post(ADMIN_1, "punch.csv", csv, null, 400);

		assertThat(body.get("message"))
				.isEqualTo("No matching employees found in the system — nothing to import.");
		assertThat(body).doesNotContainKey("data");
	}

	/**
	 * An employee of another company is not a match: the lookup is
	 * company-scoped, so a valid code from company 2 imports nothing into
	 * company 1.
	 */
	@Test
	void aCodeBelongingToAnotherCompanyIsNotAMatch() {
		byte[] csv = csv("code,datetime",
				EMPLOYEE_OTHER_CO + ",26/04/2026 08:03", EMPLOYEE_OTHER_CO + ",26/04/2026 17:11");

		post(ADMIN_1, "punch.csv", csv, null, 400);

		assertThat(query("SELECT id FROM attendance")).isEmpty();
	}

	/**
	 * A file that is neither a punch log nor a template answers
	 * {@code cannot_detect_csv_columns} -- and the errors it collected are
	 * <b>not</b> on the wire.
	 *
	 * <p>{@code fail(CANNOT_DETECT_CSV_COLUMNS, 400, null, [ERRORS => $errors])}
	 * passes the errors as the fourth argument, which is {@code t()}'s
	 * placeholder map, not the third, which is {@code data}. The catalog text
	 * has no {@code {errors}} placeholder, so the substitution matches nothing
	 * and the list is discarded. Easy to "fix" into a data payload while
	 * reading the call; pinned here so nobody does.
	 */
	@Test
	void anUnrecognisedSheetIs400AndItsErrorsNeverReachTheClient() {
		byte[] csv = csv("alpha,beta,gamma", "1,2,3", "4,5,6");

		Map<String, Object> body = post(ADMIN_1, "sheet.csv", csv, null, 400);

		assertThat(body.get("message")).isEqualTo(
				"Cannot detect employee id (or national id column) or check_in columns in CSV");
		assertThat(body).doesNotContainKey("data");
		assertThat(body.get("message").toString()).doesNotContain("Unsupported file format");
	}

	// ------------------------------------------------------------------
	// The helper's own RuntimeException messages
	// ------------------------------------------------------------------

	/**
	 * A message starting {@code attendance_excel_} becomes the API message key
	 * directly, so the client reads the catalog text rather than
	 * {@code invalid_file_type}.
	 */
	@Test
	void aNonDigitCodeIsItsOwnMessageKey() {
		byte[] csv = csv("code,datetime", "AB12,26/04/2026 08:03", "AB12,26/04/2026 17:11");

		assertThat(post(ADMIN_1, "punch.csv", csv, null, 400).get("message"))
				.isEqualTo("Employee codes must be digits only (no letters).");
	}

	@Test
	void anUnparseableDateTimeColumnIsItsOwnMessageKey() {
		byte[] csv = csv("code,datetime",
				EMPLOYEE_1 + ",not-a-date", EMPLOYEE_1 + ",also-not-a-date");

		assertThat(post(ADMIN_1, "punch.csv", csv, null, 400).get("message"))
				.isEqualTo("The punch date/time column is invalid. Please use a date with time.");
	}

	/**
	 * Three columns with no template shape at all is {@code unknown} rather
	 * than a two-column complaint -- {@code must_have_two_columns} is only
	 * reachable once something has already decided the sheet is a punch log.
	 */
	@Test
	void aPunchLogWithNoUsableRowsIsItsOwnMessageKey() {
		byte[] csv = csv("code,datetime", ",", ",");

		assertThat(post(ADMIN_1, "punch.csv", csv, null, 400).get("message"))
				.isEqualTo("No valid punch rows were found in the file.");
	}

	// ------------------------------------------------------------------
	// Column-order detection
	// ------------------------------------------------------------------

	/**
	 * A reversed device export -- datetime first, code second -- is detected
	 * from the values, not the headers, which the punch-log path ignores
	 * entirely.
	 */
	@Test
	void aReversedDeviceExportIsDetectedFromItsValues() {
		byte[] csv = csv("when,who",
				"26/04/2026 08:03," + EMPLOYEE_1,
				"26/04/2026 17:11," + EMPLOYEE_1);

		Map<String, Object> data = dataOf(post(ADMIN_1, "punch.csv", csv, null, 200));

		assertThat(number(data.get("inserted"))).isEqualTo(1L);
		assertThat(timestamp(query("SELECT check_in FROM attendance").get(0).get("check_in")))
				.isEqualTo("2026-04-26 08:03:00");
	}

	// ------------------------------------------------------------------
	// mappings semantics
	// ------------------------------------------------------------------

	/** {@code link}: an unknown sheet code is attached to an existing employee. */
	@Test
	void aLinkMappingAttachesAnUnknownCodeToAnEmployee() {
		byte[] csv = csv("code,datetime", "555001,26/04/2026 08:03", "555001,26/04/2026 17:11");
		String mappings = "{\"555001\":{\"type\":\"link\",\"employee_id\":" + EMPLOYEE_2 + "}}";

		Map<String, Object> data = dataOf(post(ADMIN_1, "punch.csv", csv, mappings, 200));

		assertThat(number(data.get("inserted"))).isEqualTo(1L);
		assertThat(number(query("SELECT employee_id FROM attendance").get(0).get("employee_id")))
				.isEqualTo(EMPLOYEE_2);
	}

	/**
	 * A {@code link} to another company's employee resolves to null, so the day
	 * is skipped rather than written against a foreign employee.
	 */
	@Test
	void aLinkMappingToAForeignEmployeeIsRefused() {
		byte[] csv = csv("code,datetime", "555001,26/04/2026 08:03", "555001,26/04/2026 17:11");
		String mappings =
				"{\"555001\":{\"type\":\"link\",\"employee_id\":" + EMPLOYEE_OTHER_CO + "}}";

		post(ADMIN_1, "punch.csv", csv, mappings, 400);

		assertThat(query("SELECT id FROM attendance")).isEmpty();
	}

	/** {@code sync_code}: the employee's own code is rewritten to the sheet's. */
	@Test
	void aSyncCodeMappingRewritesTheEmployeeCode() {
		byte[] csv = csv("code,datetime", "555002,26/04/2026 08:03", "555002,26/04/2026 17:11");
		String mappings = "{\"555002\":{\"type\":\"sync_code\",\"employee_id\":" + EMPLOYEE_2 + "}}";

		post(ADMIN_1, "punch.csv", csv, mappings, 200);

		assertThat(query("SELECT employee_code FROM employees WHERE id = " + EMPLOYEE_2)
				.get(0).get("employee_code")).isEqualTo("555002");
	}

	/** {@code create} with a shift from another company resolves to null and creates nothing. */
	@Test
	void aCreateMappingWithAForeignShiftCreatesNothing() {
		byte[] csv = csv("code,datetime", "555003,26/04/2026 08:03", "555003,26/04/2026 17:11");
		String mappings = "{\"555003\":{\"type\":\"create\",\"shift_id\":" + SHIFT_OTHER_CO + "}}";

		post(ADMIN_1, "punch.csv", csv, mappings, 400);

		assertThat(query("SELECT id FROM employees WHERE employee_code = '555003'")).isEmpty();
		assertThat(query("SELECT id FROM attendance")).isEmpty();
	}

	/**
	 * A mapping whose {@code type} is not recognised is not an error: the code
	 * falls through to the ordinary lookup, which finds the employee whose code
	 * it actually is.
	 */
	@Test
	void anUnknownMappingTypeFallsThroughToTheCodeLookup() {
		String mappings = "{\"" + EMPLOYEE_1 + "\":{\"type\":\"nonsense\"}}";

		Map<String, Object> data = dataOf(post(ADMIN_1, "punch.csv", punchCsv(), mappings, 200));

		assertThat(number(data.get("inserted"))).isEqualTo(2L);
	}

	/**
	 * {@code create} with a shift the company owns. What the database makes of
	 * it is the point: {@code employees.branch_id} is {@code NOT NULL} with no
	 * default and PHP's INSERT passes it NULL, so this endpoint's create path
	 * fails at the database in legacy too. Pinned as measured rather than as
	 * hoped -- the whole import rolls back and answers D-084's generic 500.
	 */
	@Test
	void aCreateMappingFailsOnTheNotNullBranchAndRollsTheImportBack() {
		byte[] csv = csv("code,datetime", "555004,26/04/2026 08:03", "555004,26/04/2026 17:11");
		String mappings = "{\"555004\":{\"type\":\"create\",\"shift_id\":" + SHIFT_1 + "}}";

		Map<String, Object> body = post(ADMIN_1, "punch.csv", csv, mappings, 500);

		// D-084: one deterministic body, nothing about the failure on the wire.
		assertThat(body.get("message")).isEqualTo("Internal server error");
		assertThat(body).doesNotContainKey("data");
		assertThat(query("SELECT id FROM employees WHERE employee_code = '555004'")).isEmpty();
		assertThat(query("SELECT id FROM employee_shift_assignments")).isEmpty();
		assertThat(query("SELECT id FROM attendance")).isEmpty();
	}

	// ------------------------------------------------------------------
	// The template format
	// ------------------------------------------------------------------

	/**
	 * The other import path: a wider sheet with recognisable column names goes
	 * through {@code import_fingerprint_attendance_rows()}, which has its own
	 * normalizers, its own row numbering ({@code $i + 2}) and its own
	 * {@code skipped} arithmetic ({@code count($errors)}, with no unmatched
	 * counter added).
	 */
	@Test
	void aTemplateSheetTakesTheOtherImportPath() {
		byte[] csv = csv(
				"employee_code,check_in_date,check_in_time,check_out_time,note",
				EMPLOYEE_1 + ",26/04/2026,08:03:00,17:11:00,x",
				"777777,26/04/2026,08:00:00,16:00:00,x");

		Map<String, Object> data = dataOf(post(ADMIN_1, "template.csv", csv, null, 200));

		assertThat(number(data.get("inserted"))).isEqualTo(1L);
		// count($errors) only -- one error, one skipped.
		assertThat(number(data.get("skipped"))).isEqualTo(1L);
		List<?> errors = (List<?>) data.get("errors");
		// $i + 2, and the message names the company id.
		assertThat(errors).hasSize(1);
		assertThat(errors.get(0))
				.isEqualTo("Row 3: employee_code '777777' not found in company " + COMPANY_1);

		List<Map<String, Object>> rows = query("SELECT check_in, check_out FROM attendance");
		assertThat(rows).hasSize(1);
		assertThat(timestamp(rows.get(0).get("check_in"))).isEqualTo("2026-04-26 08:03:00");
		// The check-out borrows the check-in date: there is a check-out time
		// column and no check-out date column.
		assertThat(timestamp(rows.get(0).get("check_out"))).isEqualTo("2026-04-26 17:11:00");
	}

	// ------------------------------------------------------------------
	// The reproduced BOM defect
	// ------------------------------------------------------------------

	/**
	 * <b>A reproduced defect, not a corrected one.</b>
	 * {@code attendance_import_load_rows()}'s CSV branch consumes three bytes
	 * when there is <em>no</em> BOM and keeps the BOM when there is one -- the
	 * same inversion D-085 corrected, in a function D-085 does not cover.
	 *
	 * <p>On the punch-log path it is almost invisible, because a two-column
	 * sheet is recognised by column count and its header names are then ignored
	 * -- which is what this proves: the header {@code code,datetime} arrives
	 * mangled to {@code e,datetime} and the import still works. Both files
	 * below import identically despite one header being three bytes shorter
	 * than the other believes.
	 */
	@Test
	void theInvertedBomHandlingIsReproducedAndTheImportSurvivesIt() {
		byte[] plain = punchCsv();
		byte[] withBom = new byte[plain.length + 3];
		withBom[0] = (byte) 0xEF;
		withBom[1] = (byte) 0xBB;
		withBom[2] = (byte) 0xBF;
		System.arraycopy(plain, 0, withBom, 3, plain.length);

		assertThat(number(dataOf(post(ADMIN_1, "punch.csv", plain, null, 200)).get("inserted")))
				.isEqualTo(2L);
		execute("DELETE FROM attendance");
		assertThat(number(dataOf(post(ADMIN_1, "bom.csv", withBom, null, 200)).get("inserted")))
				.isEqualTo(2L);
	}

	// ------------------------------------------------------------------
	// Atomicity
	// ------------------------------------------------------------------

	/**
	 * The whole import is one transaction, and the employee writes a mapping
	 * triggers are inside it. A {@code sync_code} followed by a failure must
	 * leave the employee's original code in place.
	 *
	 * <p>The failure is provoked by a code that is not digits appearing after a
	 * valid one: {@code resolve_punch_columns()} throws on it, which happens
	 * before any insert -- so this proves the ordering as much as the rollback.
	 */
	@Test
	void aFailedImportLeavesNothingBehind() {
		byte[] csv = csv("code,datetime",
				EMPLOYEE_1 + ",26/04/2026 08:03",
				"AB12,26/04/2026 09:00");

		post(ADMIN_1, "punch.csv", csv, null, 400);

		assertThat(query("SELECT id FROM attendance")).isEmpty();
		assertThat(query("SELECT employee_code FROM employees WHERE id = " + EMPLOYEE_1)
				.get(0).get("employee_code")).isEqualTo(String.valueOf(EMPLOYEE_1));
	}

	// ------------------------------------------------------------------
	// The XLS gap
	// ------------------------------------------------------------------

	/**
	 * <b>A reported divergence, not a reproduction.</b> Legacy reads Excel
	 * 97-2003 through the vendored {@code SimpleXLS} BIFF parser; there is no
	 * Java equivalent in this repository and porting one is outside this slice.
	 * An OLE2 upload therefore takes the same path PHP takes for a workbook
	 * {@code SimpleXLS} cannot read: {@code invalid_file_type} 400 with the
	 * message in {@code data}. A <em>valid</em> {@code .xls} imports in PHP and
	 * is refused here.
	 */
	@Test
	void anXlsUploadIsRefusedRatherThanParsed() {
		byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1,
			0x1A, (byte) 0xE1, 0x00, 0x00};

		Map<String, Object> body = post(ADMIN_1, "punch.xls", ole2, null, 400);

		assertThat(body.get("message")).isEqualTo("Invalid file type");
		assertThat(body.get("data")).isEqualTo("Cannot read XLS file. Invalid or corrupted file");
	}

	// ------------------------------------------------------------------
	// Fixtures
	// ------------------------------------------------------------------

	/** Two employees, three punches: a full day for one, a lone punch for the other. */
	private static byte[] punchCsv() {
		return csv("code,datetime",
				EMPLOYEE_1 + ",26/04/2026 08:03",
				EMPLOYEE_1 + ",26/04/2026 17:11",
				EMPLOYEE_2 + ",26/04/2026 09:00");
	}

	private static byte[] csv(String... lines) {
		return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
	}

	/** The same rows as {@link #punchCsv()}, as a minimal single-sheet workbook. */
	private static byte[] punchXlsx() {
		List<List<String>> rows = new ArrayList<>();
		rows.add(List.of("code", "datetime"));
		rows.add(List.of(String.valueOf(EMPLOYEE_1), "26/04/2026 08:03"));
		rows.add(List.of(String.valueOf(EMPLOYEE_1), "26/04/2026 17:11"));
		rows.add(List.of(String.valueOf(EMPLOYEE_2), "26/04/2026 09:00"));
		return workbook(rows);
	}

	/** A minimal .xlsx: inline strings only, so no shared-string table is needed. */
	private static byte[] workbook(List<List<String>> rows) {
		StringBuilder sheet = new StringBuilder(
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
				+ "<sheetData>");
		for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
			sheet.append("<row r=\"").append(rowIndex + 1).append("\">");
			List<String> row = rows.get(rowIndex);
			for (int cellIndex = 0; cellIndex < row.size(); cellIndex++) {
				String reference = (char) ('A' + cellIndex) + String.valueOf(rowIndex + 1);
				sheet.append("<c r=\"").append(reference).append("\" t=\"inlineStr\"><is><t>")
						.append(row.get(cellIndex)).append("</t></is></c>");
			}
			sheet.append("</row>");
		}
		sheet.append("</sheetData></worksheet>");

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			put(zip, "[Content_Types].xml",
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
					+ "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");
			put(zip, "xl/_rels/workbook.xml.rels",
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
					+ "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
					+ "<Relationship Id=\"rId1\""
					+ " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
					+ " Target=\"worksheets/sheet1.xml\"/></Relationships>");
			put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		return bytes.toByteArray();
	}

	private static void put(ZipOutputStream zip, String name, String content) throws Exception {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Map<String, Object> post(
			long employeeId, String filename, byte[] content, String mappings, int expectedStatus) {
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		ByteArrayResource file = new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		};
		parts.add("file", file);
		if (mappings != null) {
			parts.add("mappings", mappings);
		}
		return send(employeeId, parts, expectedStatus);
	}

	private Map<String, Object> postWithoutFile(long employeeId, int expectedStatus) {
		return send(employeeId, new LinkedMultiValueMap<>(), expectedStatus);
	}

	private Map<String, Object> send(
			long employeeId, MultiValueMap<String, Object> parts, int expectedStatus) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(employeeId));
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + IMPORT), HttpMethod.POST,
				new HttpEntity<>(parts, headers), mapType());
		assertThat(response.getStatusCode().value()).as("%s", response.getBody())
				.isEqualTo(expectedStatus);
		return response.getBody();
	}

	private static ParameterizedTypeReference<Map<String, Object>> mapType() {
		return new ParameterizedTypeReference<Map<String, Object>>() { };
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	/** A DATETIME column comes back as a Timestamp, whose toString appends {@code .0}. */
	private static String timestamp(Object value) {
		return value == null ? null : ((java.sql.Timestamp) value).toLocalDateTime()
				.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_1 ? "manager"
				: employeeId == HR_1 ? "hr"
				: employeeId == EMPLOYEE_1 || employeeId == EMPLOYEE_2
						|| employeeId == EMPLOYEE_OTHER_CO ? "employee"
				: "company_admin";
		long companyId = employeeId == ADMIN_2 || employeeId == EMPLOYEE_OTHER_CO
				? COMPANY_2 : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session",
				Map.of("role", role, "token_version", 1L));
	}

	private static void availableFrom(String value) {
		execute("DELETE FROM configs WHERE config_key = '" + CONFIG_KEY + "'");
		execute("INSERT INTO configs (config_key, config_value) VALUES ('" + CONFIG_KEY
				+ "', '" + value + "')");
	}

	private static void execute(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute(sql);
		} catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static List<Map<String, Object>> query(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			List<Map<String, Object>> rows = new ArrayList<>();
			while (rs.next()) {
				Map<String, Object> row = new LinkedHashMap<>();
				for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
					row.put(rs.getMetaData().getColumnLabel(column), rs.getObject(column));
				}
				rows.add(row);
			}
			return rows;
		} catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (20901, 'Import Co', '+201000020901', 'active', '2025-01-15 09:00:00'),
					  (20902, 'Import Other Co', '+201000020902', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (20911, 20901, 'Main', 1, '2025-03-01 10:00:00'),
					  (20912, 20902, 'Other', 1, '2025-03-01 10:00:00')
					""");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, is_active,"
					+ " created_at) VALUES (" + SHIFT_1 + ", " + COMPANY_1
					+ ", 'Day', '09:00:00', '17:00:00', 1, '2025-03-02 10:00:00')");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, is_active,"
					+ " created_at) VALUES (" + SHIFT_OTHER_CO + ", " + COMPANY_2
					+ ", 'Day', '09:00:00', '17:00:00', 1, '2025-03-02 10:00:00')");

			employee(st, ADMIN_1, COMPANY_1, BRANCH_1, "company_admin", "+201000209011", "Adam", "Admin");
			employee(st, HR_1, COMPANY_1, BRANCH_1, "hr", "+201000209012", "Hana", "Hr");
			employee(st, MANAGER_1, COMPANY_1, BRANCH_1, "manager", "+201000209013", "Maged", "Manager");
			employee(st, EMPLOYEE_1, COMPANY_1, BRANCH_1, "employee", "+201000209014", "Ellie", "One");
			employee(st, EMPLOYEE_2, COMPANY_1, BRANCH_1, "employee", "+201000209015", "Emad", "Two");
			employee(st, ADMIN_2, COMPANY_2, BRANCH_2, "company_admin", "+201000209021", "Other", "Admin");
			employee(st, EMPLOYEE_OTHER_CO, COMPANY_2, BRANCH_2, "employee", "+201000209022",
					"Other", "Staff");
		}
	}

	private static void employee(
			Statement st, long id, long companyId, long branchId, String role, String phone,
			String first, String last) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, role, is_active,"
				+ " join_request_status, phone, first_name, last_name, created_at) VALUES ("
				+ id + ", " + companyId + ", " + branchId + ", '" + id + "', '" + role + "', 1,"
				+ " 'accepted', '" + phone + "', '" + first + "', '" + last + "', '2025-01-20 09:00:00')");
	}

	private static void applySchema(String resourceName) throws Exception {
		String schema = readResource(resourceName);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			for (String statement : schema.split(";\\s*\\R")) {
				if (!statement.isBlank()) {
					st.execute(statement);
				}
			}
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream = LegacyAttendanceImportEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
