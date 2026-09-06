package com.workin.legacy.employees;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
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
import com.workin.legacy.employees.spreadsheet.LegacyEmployeeSpreadsheetColumns;
import com.workin.legacy.spreadsheet.LegacyCsvWriter;
import com.workin.legacy.spreadsheet.LegacyXlsxWriter;

/**
 * Wave 12.4, slice 7: {@code employees/analyze_excel.php} over real HTTP.
 *
 * <h2>The D-085 gate</h2>
 * <p>Four of these tests are the decision's acceptance criteria, and they are
 * deliberately end-to-end through <em>both</em> endpoints: the template is
 * downloaded from {@code template_excel.php} and posted back to
 * {@code analyze_excel.php} unchanged. A blank template of either format must
 * analyse to {@code total: 0}, and the same template plus one valid row must
 * analyse to {@code total: 1, valid: 1}. Against the real PHP those four cases
 * produce 15, 1, 1 and 2 rows respectively, none of them valid -- which is what
 * D-085 exists to correct.
 *
 * <p>The rest is the variant matrix: the ways a real customer's file differs
 * from the pristine template, each asserted against what legacy does with it
 * rather than what would be convenient.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyEmployeeAnalyzeExcelEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String ANALYZE = "/apis/api/employees/analyze_excel.php";
	private static final String TEMPLATE = "/apis/api/employees/template_excel.php";

	private static final long COMPANY_1 = 19701L;
	private static final long COMPANY_SUSPENDED = 19702L;

	private static final long ADMIN_1 = 197011L;
	private static final long HR_1 = 197012L;
	private static final long MANAGER_1 = 197013L;
	private static final long PLAIN_EMPLOYEE = 197014L;
	private static final long ADMIN_SUSPENDED = 197021L;

	private static final long BRANCH_MAIN = 19711L;
	private static final long DEPARTMENT_FIELD = 19721L;
	private static final long JOB_TITLE_SENIOR = 19731L;
	private static final long SHIFT_NIGHT = 19741L;

	/** Lookup names are unique within each table: J2 is unresolved and nothing here depends on it. */
	private static final String SHIFT_NAME = "Night Watch";
	private static final String BRANCH_NAME = "Riverside Branch";
	private static final String DEPARTMENT_NAME = "Field Operations";
	private static final String JOB_TITLE_NAME = "Senior Agent";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the analyze_excel fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	/**
	 * The other direction the byte-count guard got wrong: {@code filename=""}
	 * <em>with</em> content is {@code UPLOAD_ERR_NO_FILE} in PHP, so it is
	 * {@code no_file_uploaded} — but {@code isEmpty()} is false for it, so Java
	 * let it through to format validation and answered
	 * {@code File rejected: it does not match the expected template} instead.
	 */
	@Test
	void anEmptyFilenameIsNoFileUploadedEvenWithContent() {
		ResponseEntity<Map<String, Object>> response =
				post("employee_code,first_name\n1,Test\n".getBytes(StandardCharsets.UTF_8), "", ADMIN_1, "en");

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().get("message")).isEqualTo("No file uploaded");
	}

	// ------------------------------------------------------------------
	// D-085: the four round trips
	// ------------------------------------------------------------------

	@Test
	void aBlankGeneratedCsvAnalysesAsNoRowsAtAll() {
		byte[] template = download(TEMPLATE + "?format=csv");
		Map<String, Object> summary = summaryOf(analyze(template, "employees_template.csv", ADMIN_1));
		assertThat(summary).containsEntry("total", 0).containsEntry("valid", 0).containsEntry("invalid", 0);
	}

	@Test
	void aBlankGeneratedXlsxAnalysesAsNoRowsAtAll() {
		byte[] template = download(TEMPLATE);
		Map<String, Object> summary = summaryOf(analyze(template, "employees_template.xlsx", ADMIN_1));
		assertThat(summary).containsEntry("total", 0).containsEntry("valid", 0).containsEntry("invalid", 0);
	}

	@Test
	void aGeneratedCsvWithOneValidRowAnalysesAsOneValidEmployee() {
		byte[] file = csvWithRows(download(TEMPLATE + "?format=csv"), List.of(validRow("7101", "01012340101")));
		Map<String, Object> body = analyze(file, "filled.csv", ADMIN_1);

		assertThat(summaryOf(body))
				.containsEntry("total", 1).containsEntry("valid", 1).containsEntry("invalid", 0);
		assertResolvedPayload(rowsOf(body).get(0));
	}

	@Test
	void aGeneratedXlsxWithOneValidRowAnalysesAsOneValidEmployee() {
		byte[] file = xlsxWithRows(List.of(validRow("7102", "01012340102")));
		Map<String, Object> body = analyze(file, "filled.xlsx", ADMIN_1);

		assertThat(summaryOf(body))
				.containsEntry("total", 1).containsEntry("valid", 1).containsEntry("invalid", 0);
		assertResolvedPayload(rowsOf(body).get(0));
	}

	/** The valid row's payload has to carry the resolved ids, not just a clean status. */
	private static void assertResolvedPayload(Map<String, Object> row) {
		assertThat(row.get("status")).isEqualTo("valid");
		assertThat(row.get("errors")).isEqualTo(List.of());
		assertThat(row.get("row_index")).isEqualTo(1);

		@SuppressWarnings("unchecked")
		Map<String, Object> payload = (Map<String, Object>) row.get("payload");
		assertThat(number(payload.get("shift_id"))).isEqualTo(SHIFT_NIGHT);
		assertThat(number(payload.get("branch_id"))).isEqualTo(BRANCH_MAIN);
		assertThat(number(payload.get("department_id"))).isEqualTo(DEPARTMENT_FIELD);
		assertThat(number(payload.get("job_title_id"))).isEqualTo(JOB_TITLE_SENIOR);

		// The spreadsheet's phone rules: a bare local number resolves against
		// the configured default country.
		assertThat(payload.get("country_code")).isEqualTo("+20");
		assertThat((String) payload.get("phone")).isNotBlank();
		// Dates are normalized, and shift_effective_from follows hire_date.
		assertThat(payload.get("hire_date")).isEqualTo("2024-03-01");
		assertThat(payload.get("birth_date")).isEqualTo("1990-01-15");
		assertThat(payload.get("shift_effective_from")).isEqualTo(payload.get("hire_date"));
		// Years on the sheet, months in the payload.
		assertThat(number(payload.get("contract_duration_months"))).isEqualTo(24L);
		// Fixed by the spreadsheet path, never read from the file.
		assertThat(number(payload.get("can_check_in_any_branch"))).isZero();
		assertThat(number(payload.get("is_mobile_attendance_enabled"))).isEqualTo(1L);
		assertThat(number(payload.get("expected_daily_hours"))).isEqualTo(8L);
		assertThat(payload.get("gender")).isEqualTo("male");

		@SuppressWarnings("unchecked")
		Map<String, Object> salary = (Map<String, Object>) payload.get("salary");
		assertThat(number(salary.get("basic"))).isEqualTo(5000L);
		assertThat(number(salary.get("transport"))).isEqualTo(200L);
		assertThat(number(salary.get("insurance_deduction"))).isEqualTo(75L);
		// Only the cells that were filled appear.
		assertThat(salary).doesNotContainKeys("risk_allowance", "fund_deduction");
	}

	// ------------------------------------------------------------------
	// The response's own shape
	// ------------------------------------------------------------------

	@Test
	void theEnvelopeAndTheAnalysisKeysAreLegacys() {
		byte[] file = csvWithRows(
				download(TEMPLATE + "?format=csv"), List.of(validRow("7103", "01012340103")));
		Map<String, Object> body = analyze(file, "filled.csv", ADMIN_1);

		assertThat(body.keySet()).containsExactly("success", "message", "data");
		assertThat(body.get("success")).isEqualTo(true);
		// The envelope's message goes through t(), so it follows the request
		// locale -- unlike the helper's own error strings, which stay Arabic
		// whatever the locale is.
		assertThat(body.get("message")).isEqualTo("تم تحليل ملف الموظفين");
		assertThat(post(file, "filled.csv", ADMIN_1, "en").getBody().get("message"))
				.isEqualTo("Employee file analyzed");

		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) body.get("data");
		assertThat(data.keySet()).containsExactly("columns", "lookups", "rows", "summary");

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> columns = (List<Map<String, Object>>) data.get("columns");
		assertThat(columns).hasSize(28);
		// Four keys and no more: aliases and salary groups stay internal even
		// though the metadata this is built from carries them.
		assertThat(columns.get(0).keySet()).containsExactly("key", "required", "label_ar", "label_en");
		assertThat(columns.get(0).get("required")).isEqualTo(true);

		@SuppressWarnings("unchecked")
		Map<String, Object> lookups = (Map<String, Object>) data.get("lookups");
		assertThat(lookups.keySet()).containsExactly("branches", "departments", "job_titles", "shifts");
		// The values are the map *keys*: normalized, lower-cased names.
		assertThat(lookups.get("shifts")).isEqualTo(List.of("night watch", "day watch"));
		assertThat(lookups.get("branches")).isEqualTo(List.of("riverside branch"));

		Map<String, Object> row = rowsOf(body).get(0);
		assertThat(row.keySet()).containsExactly(
				"row_index", "status", "errors", "error_messages", "field_errors", "data", "payload");
		// `warnings` is returned by row_to_payload() and dropped by analyze().
		assertThat(row).doesNotContainKey("warnings");
	}

	/**
	 * {@code employee_excel_field_errors()} returns a bare PHP array, so a row
	 * with nothing wrong reaches the wire as {@code "field_errors":[]} -- not
	 * <code>{}</code>, which is what a Java {@code Map} would have written.
	 *
	 * <p>Read from the bytes on purpose: the assertions elsewhere in this class
	 * deserialise into a {@code Map} first, and both shapes survive that. The
	 * populated case is asserted alongside so the rule cannot be satisfied by
	 * always writing an array. Measured against the running legacy PHP. D-156.
	 */
	@Test
	void fieldErrorsIsAnEmptyArrayOnTheWireAndAnObjectWhenPopulated() {
		JsonNode clean = rawRow(List.of(validRow("990100", "01012340199")));
		assertThat(clean.get("status").asString()).isEqualTo("valid");
		assertThat(clean.get("field_errors").isArray())
				.as("field_errors: %s", clean.get("field_errors")).isTrue();
		assertThat(clean.get("field_errors")).isEmpty();

		Map<String, String> broken = validRow("990101", "01012340198");
		broken.put("first_name", "");
		JsonNode invalid = rawRow(List.of(broken));
		assertThat(invalid.get("field_errors").isObject()).isTrue();
		assertThat(invalid.get("field_errors").get("first_name").asString()).isNotEmpty();
	}

	@Test
	void anInvalidRowCarriesItsCodesMessagesAndFieldErrorsInOrder() {
		Map<String, String> broken = validRow("not-a-number", "01012340104");
		broken.put("first_name", "");
		broken.put("shift_name", "No Such Shift");
		broken.put("is_mobile_attendance_enabled", "maybe");
		broken.put("salary_basic", "");

		Map<String, Object> body = analyze(
				csvWithRows(download(TEMPLATE + "?format=csv"), List.of(broken)), "broken.csv", ADMIN_1);
		Map<String, Object> row = rowsOf(body).get(0);

		assertThat(row.get("status")).isEqualTo("invalid");
		// Source order, which is what the client renders.
		assertThat(row.get("errors")).isEqualTo(List.of(
				"first_name_required", "employee_code_invalid", "shift_not_found",
				"mobile_attendance_invalid", "salary_basic_required"));

		@SuppressWarnings("unchecked")
		List<String> messages = (List<String>) row.get("error_messages");
		assertThat(messages).hasSize(5);
		assertThat(messages.get(0)).isEqualTo("الاسم الأول مطلوب");
		assertThat(messages.get(1)).contains("(not-a-number)");
		assertThat(messages.get(2)).contains("(No Such Shift)");

		@SuppressWarnings("unchecked")
		Map<String, String> fieldErrors = (Map<String, String>) row.get("field_errors");
		assertThat(fieldErrors.keySet()).containsExactly(
				"first_name", "employee_code", "shift_name", "is_mobile_attendance_enabled", "salary_basic");

		// `data` is the display row: the four rewritten cells, not the payload.
		@SuppressWarnings("unchecked")
		Map<String, Object> display = (Map<String, Object>) row.get("data");
		assertThat(display.get("hire_date")).isEqualTo("2024-03-01");
		assertThat(display.get("gender")).isEqualTo("ذكر");
		assertThat(display.get("is_mobile_attendance_enabled")).isEqualTo("maybe");
		assertThat(summaryOf(body)).containsEntry("valid", 0).containsEntry("invalid", 1);
	}

	@Test
	void aDuplicateEmployeeCodeIsCheckedAgainstTheCompany() {
		// 1001 belongs to the seeded admin.
		Map<String, Object> body = analyze(
				csvWithRows(download(TEMPLATE + "?format=csv"), List.of(validRow("1001", "01012340105"))),
				"dupe.csv", ADMIN_1);
		assertThat(rowsOf(body).get(0).get("errors")).isEqualTo(List.of("employee_code_exists"));
	}

	@Test
	void rowIndexCountsSurvivingRowsNotFileLines() {
		// A blank line and a hint row sit between the header and the employees,
		// and neither shifts the reported index.
		Map<String, String> blank = new LinkedHashMap<>();
		Map<String, String> hint = validRow("", "");
		hint.put("first_name", "الاسم الأول (اجباري)");

		byte[] file = csvWithRows(download(TEMPLATE + "?format=csv"),
				List.of(blank, hint, validRow("7106", "01012340106"), validRow("7107", "01012340107")));
		Map<String, Object> body = analyze(file, "spaced.csv", ADMIN_1);

		assertThat(summaryOf(body)).containsEntry("total", 2);
		assertThat(rowsOf(body).get(0).get("row_index")).isEqualTo(1);
		assertThat(rowsOf(body).get(1).get("row_index")).isEqualTo(2);
	}

	// ------------------------------------------------------------------
	// The variant matrix
	// ------------------------------------------------------------------

	@Test
	void aFileWithNoBomAndNoGroupRowIsStillRead() {
		byte[] file = csv(false, false, ',', canonicalHeaders(), List.of(validRow("7110", "01012340110")));
		assertThat(summaryOf(analyze(file, "plain.csv", ADMIN_1)))
				.containsEntry("total", 1).containsEntry("valid", 1);
	}

	@Test
	void aBomWithoutAGroupRowIsConsumedExactlyOnce() {
		// The half of D-085 that shifted every row: with the BOM kept, the
		// header row's first cell would not normalize and the file would be
		// rejected outright.
		byte[] file = csv(true, false, ',', canonicalHeaders(), List.of(validRow("7111", "01012340111")));
		assertThat(summaryOf(analyze(file, "bom.csv", ADMIN_1)))
				.containsEntry("total", 1).containsEntry("valid", 1);
	}

	@Test
	void aSemicolonDelimitedFileIsRead() {
		byte[] file = csv(true, true, ';', canonicalHeaders(), List.of(validRow("7112", "01012340112")));
		assertThat(summaryOf(analyze(file, "semi.csv", ADMIN_1)))
				.containsEntry("total", 1).containsEntry("valid", 1);
	}

	@Test
	void reorderedCanonicalHeadersAreAccepted() {
		// The structure check is a set, not a sequence.
		List<String> reordered = new ArrayList<>(canonicalHeaders());
		java.util.Collections.reverse(reordered);
		byte[] file = csv(true, false, ',', reordered, List.of(validRow("7113", "01012340113")));
		assertThat(summaryOf(analyze(file, "reordered.csv", ADMIN_1)))
				.containsEntry("total", 1).containsEntry("valid", 1);
	}

	@Test
	void trailingEmptyColumnsAreIgnored() {
		List<String> padded = new ArrayList<>(canonicalHeaders());
		padded.add("");
		padded.add("   ");
		byte[] file = csv(true, false, ',', padded, List.of(validRow("7114", "01012340114")));
		assertThat(summaryOf(analyze(file, "padded.csv", ADMIN_1)))
				.containsEntry("total", 1).containsEntry("valid", 1);
	}

	@Test
	void anAliasThatResolvesToAnOccupiedColumnIsADuplicate() {
		// "mobile_attendance" normalizes to `phone` through the alias prefix
		// rule, so the file has two phone columns and no mobile-attendance one.
		List<String> headers = new ArrayList<>(canonicalHeaders());
		headers.set(11, "mobile_attendance");
		assertRejected(csv(true, false, ',', headers, List.of()),
				"أعمدة ناقصة: 1", "أعمدة مكررة: 1");
	}

	@Test
	void theEnglishMobileAttendanceLabelCollidesTheSameWay() {
		// The known collision, reached through the label rather than the alias.
		// Preserved, not fixed: D-085 puts it out of scope.
		List<String> headers = new ArrayList<>();
		for (LegacyEmployeeSpreadsheetColumns.Column column : LegacyEmployeeSpreadsheetColumns.columns()) {
			headers.add(column.labelEn());
		}
		assertRejected(csv(true, false, ',', headers, List.of()),
				"أعمدة ناقصة: 1", "أعمدة مكررة: 1");
	}

	@Test
	void anExplicitDuplicateCanonicalColumnIsRejected() {
		List<String> headers = new ArrayList<>(canonicalHeaders());
		headers.set(3, "employee_code");
		assertRejected(csv(true, false, ',', headers, List.of()),
				"أعمدة ناقصة: 1", "أعمدة مكررة: 1");
	}

	@Test
	void anUnknownTwentyNinthColumnIsRejected() {
		List<String> headers = new ArrayList<>(canonicalHeaders());
		headers.add("Favourite colour");
		assertRejected(csv(true, false, ',', headers, List.of()),
				"أعمدة غير معروفة أو تم تعديل اسمها: 1");
	}

	@Test
	void oneMissingCanonicalColumnIsRejected() {
		List<String> headers = new ArrayList<>(canonicalHeaders());
		headers.remove(5);
		assertRejected(csv(true, false, ',', headers, List.of()), "أعمدة ناقصة: 1");
	}

	@Test
	void theRejectionIsLocalizedByTheRequestsOwnLocale() {
		List<String> headers = new ArrayList<>(canonicalHeaders());
		headers.remove(5);
		ResponseEntity<Map<String, Object>> english = post(
				csv(true, false, ',', headers, List.of()), "missing.csv", ADMIN_1, "en");
		assertThat(english.getStatusCode().value()).isEqualTo(400);
		assertThat((String) english.getBody().get("message"))
				.startsWith("File rejected: it does not match the employee template.")
				.contains("Missing columns: 1")
				.contains("Download a fresh template");
	}

	// ------------------------------------------------------------------
	// Formats this flow does not support
	// ------------------------------------------------------------------

	@Test
	void anOle2XlsUploadIsUnreadableRatherThanParsed() {
		// The attendance module reads .xls; this flow never did. Neither of
		// legacy's two branches matches OLE2, so read_headers() throws before
		// any row is looked at -- and that is the whole outcome.
		byte[] ole2 = new byte[512];
		byte[] signature = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
				(byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
		System.arraycopy(signature, 0, ole2, 0, signature.length);

		ResponseEntity<Map<String, Object>> response = post(ole2, "employees.xls", ADMIN_1, "ar");
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().get("message")).isEqualTo("Empty or unreadable file");
		assertThat(response.getBody().get("success")).isEqualTo(false);
	}

	@Test
	void aMalformedXlsxContainerIsRejectedWithTheHelpersOwnReasonAndNeverAnalysed() {
		// Worth being precise here, because two different fallbacks look alike.
		//
		// D-085 removes the one where a workbook parsed fine and merely held no
		// data rows, and legacy re-read the ZIP as CSV anyway. That is the
		// defect, and it is gone.
		//
		// This is the other one: the container cannot be opened at all. Both
		// read_headers() and load_rows() have carried
		// `catch (RuntimeException) { $format = 'csv'; }` since long before
		// D-085, so the decision's two corrections do not reach it. The bytes
		// are read as CSV, produce a header row that is not the template's, and
		// the file is rejected on structure -- which is the helper's actual 400
		// reason for a malformed workbook. Removing that fallback would be a
		// third correction D-085 does not authorize.
		//
		// What holds either way: a 400 carrying a rejection reason, and not one
		// row of deflate noise analysed as an employee.
		byte[] broken = "PK-not-really-a-zip-at-all".getBytes(StandardCharsets.UTF_8);
		ResponseEntity<Map<String, Object>> response = post(broken, "broken.xlsx", ADMIN_1, "ar");
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().get("success")).isEqualTo(false);
		assertThat((String) response.getBody().get("message"))
				.startsWith("تم رفض الملف لأنه غير مطابق لقالب الموظفين.");
		assertThat(response.getBody()).doesNotContainKey("data");
	}

	@Test
	void aZipThatOpensButHoldsNoWorksheetIsAlsoRejected() {
		// A well-formed container missing the parts a workbook needs takes the
		// same route, and likewise never reaches row analysis.
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
			zip.putNextEntry(new java.util.zip.ZipEntry("docProps/app.xml"));
			zip.write("<Properties/>".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		} catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
		ResponseEntity<Map<String, Object>> response =
				post(bytes.toByteArray(), "no-sheet.xlsx", ADMIN_1, "ar");
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody()).doesNotContainKey("data");
	}

	/**
	 * <b>The empty-file assertion here was wrong, and the code matched it.</b>
	 * A zero-byte file with a real filename is {@code UPLOAD_ERR_OK} in PHP --
	 * the user did choose a file -- so legacy falls through to the format check
	 * and answers {@code Empty or unreadable file}. This test asserted
	 * {@code No file uploaded}, which is what Java's {@code file.isEmpty()}
	 * guard produced, so the divergence was pinned as if it were the contract.
	 * Measured against the running PHP; the guard is now the same three-part
	 * filename test {@code LegacyAttendanceImportService} uses.
	 */
	@Test
	void anEmptyUploadAndAMissingPartAreBothNoFileUploaded() {
		ResponseEntity<Map<String, Object>> empty = post(new byte[0], "empty.csv", ADMIN_1, "en");
		assertThat(empty.getStatusCode().value()).isEqualTo(400);
		assertThat(empty.getBody().get("message"))
				.as("zero bytes with a real filename is UPLOAD_ERR_OK in PHP, so it reaches "
						+ "the format check rather than being refused as a missing upload")
				.isEqualTo("Empty or unreadable file");

		// A multipart request carrying a differently named part.
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		parts.add("spreadsheet", filePart(
				"spreadsheet", "x,y\n1,2\n".getBytes(StandardCharsets.UTF_8), "other.csv"));
		ResponseEntity<Map<String, Object>> wrongName = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + ANALYZE), HttpMethod.POST,
				new HttpEntity<>(parts, multipartHeaders(tokenFor(ADMIN_1), "ar")),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(wrongName.getStatusCode().value()).isEqualTo(400);
		// Posted with Accept-Language: ar, so the same key resolves in Arabic.
		assertThat(wrongName.getBody().get("message")).isEqualTo("لم يُرفع ملف");
	}

	// ------------------------------------------------------------------
	// Guards
	// ------------------------------------------------------------------

	@Test
	void theGuardStackRunsInPhpsOrder() {
		byte[] file = csv(true, false, ',', canonicalHeaders(), List.of(validRow("7120", "01012340120")));

		// The method guard is the opening line, before auth.
		ResponseEntity<Map<String, Object>> wrongMethod = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + ANALYZE), HttpMethod.GET,
				new HttpEntity<>(headersFor(tokenFor(ADMIN_1))),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(wrongMethod.getStatusCode().value()).isEqualTo(405);
		assertThat(wrongMethod.getBody().get("message")).isEqualTo("Invalid method");

		assertThat(post(file, "f.csv", HR_1, "ar").getStatusCode().value()).isEqualTo(200);
		// admin/HR only: a manager is not on this endpoint's list.
		assertThat(post(file, "f.csv", MANAGER_1, "ar").getStatusCode().value()).isEqualTo(403);
		assertThat(post(file, "f.csv", PLAIN_EMPLOYEE, "ar").getStatusCode().value()).isEqualTo(403);
		assertThat(post(file, "f.csv", ADMIN_SUSPENDED, "ar").getStatusCode().value()).isEqualTo(403);
	}

	@Test
	void analysisWritesNothing() {
		long before = employeeCount();
		analyze(csvWithRows(download(TEMPLATE + "?format=csv"), List.of(validRow("7130", "01012340130"))),
				"f.csv", ADMIN_1);
		assertThat(employeeCount()).isEqualTo(before);
	}

	// ------------------------------------------------------------------
	// Fixtures and helpers
	// ------------------------------------------------------------------

	/** One row that resolves cleanly against the seeded lookups. */
	private static Map<String, String> validRow(String code, String phone) {
		Map<String, String> row = new LinkedHashMap<>();
		row.put("employee_code", code);
		row.put("first_name", "Nour");
		row.put("last_name", "Adel");
		row.put("country_code", "");
		row.put("phone", phone);
		row.put("password", "secret123");
		row.put("shift_name", SHIFT_NAME);
		row.put("national_id", "29801011234567");
		row.put("birth_date", "1990-01-15");
		row.put("gender", "ذكر");
		row.put("address", "Cairo");
		row.put("is_mobile_attendance_enabled", "نعم");
		row.put("hire_date", "2024-03-01");
		row.put("branch_name", BRANCH_NAME);
		row.put("department_name", DEPARTMENT_NAME);
		row.put("job_title_name", JOB_TITLE_NAME);
		row.put("expected_daily_hours", "8");
		row.put("contract_duration_years", "2");
		row.put("salary_basic", "5000");
		row.put("salary_transport", "200");
		row.put("salary_insurance_deduction", "75");
		return row;
	}

	private static List<String> canonicalHeaders() {
		return LegacyEmployeeSpreadsheetColumns.columns().stream()
				.map(LegacyEmployeeSpreadsheetColumns.Column::key).toList();
	}

	/** The cells of one row, in the order the given headers ask for. */
	private static List<String> cellsFor(List<String> headers, Map<String, String> row) {
		List<String> cells = new ArrayList<>(headers.size());
		for (String header : headers) {
			cells.add(row.getOrDefault(header, ""));
		}
		return cells;
	}

	private static byte[] csv(boolean bom, boolean groupRow, char delimiter,
			List<String> headers, List<Map<String, String>> rows) {
		List<List<String>> records = new ArrayList<>();
		if (groupRow) {
			records.add(LegacyEmployeeSpreadsheetColumns.csvGroupRow());
		}
		records.add(headers);
		for (Map<String, String> row : rows) {
			records.add(cellsFor(headers, row));
		}

		StringBuilder text = new StringBuilder();
		if (bom) {
			text.append('﻿');
		}
		for (List<String> record : records) {
			// LegacyCsvWriter is comma-only, as fputcsv's default is; the
			// semicolon variant is written here so the reader's delimiter
			// detection has something to detect.
			text.append(delimiter == ','
					? LegacyCsvWriter.record(record)
					: String.join(String.valueOf(delimiter), record.stream().map(
							cell -> cell == null ? "" : cell).toList()) + "\n");
		}
		return text.toString().getBytes(StandardCharsets.UTF_8);
	}

	/** Append data rows to a template the endpoint itself produced. */
	private static byte[] csvWithRows(byte[] template, List<Map<String, String>> rows) {
		String text = new String(template, StandardCharsets.UTF_8);
		List<String> headers = LegacyEmployeeSpreadsheetColumns.columns().stream()
				.map(LegacyEmployeeSpreadsheetColumns.Column::key).toList();
		StringBuilder appended = new StringBuilder(text);
		for (Map<String, String> row : rows) {
			appended.append(LegacyCsvWriter.record(cellsFor(headers, row)));
		}
		return appended.toString().getBytes(StandardCharsets.UTF_8);
	}

	/** A workbook shaped exactly like the template, plus data rows. */
	private static byte[] xlsxWithRows(List<Map<String, String>> rows) {
		List<String> headers = LegacyEmployeeSpreadsheetColumns.columns().stream()
				.map(LegacyEmployeeSpreadsheetColumns.Column::key).toList();
		List<List<String>> dataRows = new ArrayList<>();
		for (Map<String, String> row : rows) {
			dataRows.add(cellsFor(headers, row));
		}
		return LegacyXlsxWriter.build(headers, dataRows, "Employees",
				List.of(LegacyEmployeeSpreadsheetColumns.templateGroupRow()),
				LegacyEmployeeSpreadsheetColumns.templateGroupMerges(), 2,
				LegacyEmployeeSpreadsheetColumns.templateCellStyles());
	}

	private void assertRejected(byte[] file, String... fragments) {
		ResponseEntity<Map<String, Object>> response = post(file, "variant.csv", ADMIN_1, "ar");
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		String message = (String) response.getBody().get("message");
		assertThat(message).startsWith("تم رفض الملف لأنه غير مطابق لقالب الموظفين.");
		for (String fragment : fragments) {
			assertThat(message).contains(fragment);
		}
	}

	private Map<String, Object> analyze(byte[] file, String filename, long employeeId) {
		ResponseEntity<Map<String, Object>> response = post(file, filename, employeeId, "ar");
		assertThat(response.getStatusCode().value())
				.as("analyze %s: %s", filename, response.getBody())
				.isEqualTo(200);
		return response.getBody();
	}

	/** The first analysed row as parsed JSON rather than a deserialised Map, for shape assertions. */
	private JsonNode rawRow(List<Map<String, String>> rows) {
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		parts.add("file", filePart(
				"file", csvWithRows(download(TEMPLATE + "?format=csv"), rows), "shape.csv"));
		ResponseEntity<String> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + ANALYZE), HttpMethod.POST,
				new HttpEntity<>(parts, multipartHeaders(tokenFor(ADMIN_1), "ar")), String.class);
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		return JsonMapper.builder().build().readTree(response.getBody()).get("data").get("rows").get(0);
	}

	private ResponseEntity<Map<String, Object>> post(
			byte[] file, String filename, long employeeId, String locale) {
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		parts.add("file", filePart("file", file, filename));
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + ANALYZE), HttpMethod.POST,
				new HttpEntity<>(parts, multipartHeaders(tokenFor(employeeId), locale)),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private static HttpEntity<ByteArrayResource> filePart(String partName, byte[] content, String filename) {
		HttpHeaders partHeaders = new HttpHeaders();
		partHeaders.setContentDispositionFormData(partName, filename);
		return new HttpEntity<>(new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		}, partHeaders);
	}

	private static HttpHeaders multipartHeaders(String token, String locale) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		headers.set("Accept-Language", locale);
		return headers;
	}

	private static HttpHeaders headersFor(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

	private byte[] download(String path) {
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.GET,
				new HttpEntity<>(headersFor(tokenFor(ADMIN_1))), byte[].class).getBody();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> summaryOf(Map<String, Object> body) {
		return (Map<String, Object>) ((Map<String, Object>) body.get("data")).get("summary");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> rowsOf(Map<String, Object> body) {
		return (List<Map<String, Object>>) ((Map<String, Object>) body.get("data")).get("rows");
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	private long employeeCount() {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			var rs = st.executeQuery("SELECT COUNT(*) FROM employees WHERE company_id = " + COMPANY_1);
			rs.next();
			return rs.getLong(1);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_1 ? "manager"
				: employeeId == PLAIN_EMPLOYEE ? "employee"
				: employeeId == HR_1 ? "hr" : "company_admin";
		long companyId = employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (19701, 'Analyze Co', '+201000019701', 'active', '2025-01-15 09:00:00'),
					  (19702, 'Analyze Suspended Co', '+201000019702', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (19711, 19701, 'Riverside Branch', 1, '2025-03-01 10:00:00'),
					  (19712, 19702, 'Suspended Branch', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
					  (19721, 19701, 'Field Operations', 1, '2025-04-10 10:00:00'),
					  (19722, 19702, 'Suspended Department', 1, '2025-04-10 10:00:00')
					""");
			st.execute("""
					INSERT INTO department_branches (department_id, branch_id) VALUES
					  (19721, 19711), (19722, 19712)
					""");
			st.execute("""
					INSERT INTO job_titles (id, company_id, department_id, name, is_active, created_at) VALUES
					  (19731, 19701, 19721, 'Senior Agent', 1, '2025-04-11 10:00:00'),
					  (19732, 19702, 19722, 'Suspended Title', 1, '2025-04-11 10:00:00')
					""");
			st.execute("""
					INSERT INTO shifts (id, company_id, name, start_time, end_time, created_at) VALUES
					  (19741, 19701, 'Night Watch', '22:00:00', '06:00:00', '2025-04-12 10:00:00'),
					  (19742, 19701, 'Day Watch', '09:00:00', '17:00:00', '2025-04-12 10:00:00')
					""");

			insertEmployee(st, ADMIN_1, COMPANY_1, 19711L, "'1001'", "company_admin", "+201000197011", "Rana");
			insertEmployee(st, HR_1, COMPANY_1, 19711L, "'1002'", "hr", "+201000197012", "Mona");
			insertEmployee(st, MANAGER_1, COMPANY_1, 19711L, "'1003'", "manager", "+201000197013", "Mostafa");
			insertEmployee(st, PLAIN_EMPLOYEE, COMPANY_1, 19711L, "'1004'", "employee",
					"+201000197014", "Omar");
			insertEmployee(st, ADMIN_SUSPENDED, COMPANY_SUSPENDED, 19712L, "'3001'", "company_admin",
					"+201000197021", "Tarek");
		}
	}

	private static void insertEmployee(
			Statement st, long id, long companyId, long branchId, String code, String role,
			String phone, String firstName) throws Exception {
		st.execute("""
				INSERT INTO employees
				  (id, company_id, branch_id, department_id, job_title_id, employee_code, expected_daily_hours,
				   first_name, last_name, phone, country_code, password_hash, token_version, role, national_id,
				   birth_date, gender, address, photo_url, hire_date, contract_duration_months, is_active,
				   is_mobile_attendance_enabled, can_check_in_any_branch, join_request_status, created_at)
				VALUES (%d, %d, %d, NULL, NULL, %s, 8.00, '%s', 'Adel', '%s', '+20',
				   '$2y$10$abcdefghijklmnopqrstuv', 1, '%s', '29001011200011', '0000-00-00', 'female',
				   'Cairo', NULL, '2024-01-01', 12, 1, 1, 0, 'accepted', '2025-05-01 09:00:00')
				""".formatted(id, companyId, branchId, code, firstName, phone, role));
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
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream =
				LegacyEmployeeAnalyzeExcelEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
