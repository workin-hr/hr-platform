package com.workin.legacy.employees;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * {@code employees/upload_photo.php} over real HTTP against real MariaDB, with
 * a throwaway upload directory.
 *
 * <p>The endpoint stores the file before it touches the database and never
 * checks that the target exists, so the interesting cases are the ones where
 * that ordering shows: a foreign target leaves a file on disk and updates
 * nothing, and a database failure after the move leaves it orphaned. Those are
 * asserted rather than fixed.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyEmployeeUploadPhotoEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String UPLOAD = "/apis/api/employees/upload_photo.php";

	private static final long COMPANY_1 = 20001L;
	private static final long COMPANY_2 = 20002L;
	private static final long BRANCH = 20011L;
	private static final long ADMIN = 200011L;
	private static final long HR = 200012L;
	private static final long MANAGER = 200013L;
	private static final long STAFF = 200014L;
	private static final long OTHER_STAFF = 200015L;
	private static final long OTHER_COMPANY_EMPLOYEE = 200021L;

	/** A disposable directory: no test may write anywhere a deployment would. */
	private static final Path UPLOAD_ROOT;

	static {
		try {
			UPLOAD_ROOT = Files.createTempDirectory("legacy-uploads-test");
		} catch (Exception ex) {
			throw new IllegalStateException("could not create the test upload directory", ex);
		}
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the Wave 12.4 upload fixture", ex);
		}
	}

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	@MockitoSpyBean
	private LegacyEmployeeStore storeSpy;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.legacy-uploads.path", () -> UPLOAD_ROOT.toString());
		registry.add("app.legacy-uploads.url", () -> "/uploads/");
	}

	@Test
	void everyManagementRoleMayUploadForAnyoneInItsCompany() throws Exception {
		for (long actor : List.of(ADMIN, HR, MANAGER)) {
			ResponseEntity<Map<String, Object>> response = upload(
					UPLOAD + "?id=" + STAFF, actor, "photo", "portrait.jpg", jpegBytes());
			assertThat(response.getStatusCode().value()).describedAs("actor %s", actor).isEqualTo(200);
			assertThat(response.getBody().get("message")).isEqualTo("Photo uploaded");

			@SuppressWarnings("unchecked")
			Map<String, Object> employee = (Map<String, Object>) response.getBody().get("data");
			String url = (String) employee.get("photo_url");
			// /uploads/photos/<generated>.<client extension>
			assertThat(url).matches("^/uploads/photos/[0-9a-f]+\\.[0-9]+\\.jpg$");
			assertThat(employee).doesNotContainKeys("password_hash", "token_version");
			assertThat(employee.get("branch_name")).isEqualTo("Main Branch");

			// The row and the file both exist, and they agree.
			assertThat(single("SELECT photo_url FROM employees WHERE id = " + STAFF).get("photo_url"))
					.isEqualTo(url);
			assertThat(storedFile(url)).exists();
		}
		// A manager is not limited to its own branch here, unlike employees/one.php.
		assertThat(upload(UPLOAD + "?id=" + OTHER_STAFF, MANAGER, "photo", "portrait.jpg", jpegBytes())
				.getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void anEmployeeMayOnlyUploadItsOwnPhotoAndDefaultsToIt() throws Exception {
		// No id at all: the target is the authenticated employee.
		ResponseEntity<Map<String, Object>> implicit = upload(UPLOAD, STAFF, "photo", "me.png", pngBytes());
		assertThat(implicit.getStatusCode().value()).isEqualTo(200);
		@SuppressWarnings("unchecked")
		Map<String, Object> employee = (Map<String, Object>) implicit.getBody().get("data");
		assertThat(((Number) employee.get("id")).longValue()).isEqualTo(STAFF);
		assertThat((String) employee.get("photo_url")).endsWith(".png");

		// The same id given explicitly is equally fine.
		assertThat(upload(UPLOAD + "?id=" + STAFF, STAFF, "photo", "me.png", pngBytes())
				.getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void anEmployeeTargetingSomebodyElseIsRejectedBeforeAnyFileIsWritten() throws Exception {
		long filesBefore = storedFileCount();
		ResponseEntity<Map<String, Object>> response = upload(
				UPLOAD + "?id=" + OTHER_STAFF, STAFF, "photo", "portrait.jpg", jpegBytes());

		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody().get("message")).isEqualTo("Forbidden");
		// The role check runs before the upload, so nothing reached the disk.
		assertThat(storedFileCount()).isEqualTo(filesBefore);
		assertThat(single("SELECT photo_url FROM employees WHERE id = " + OTHER_STAFF).get("photo_url"))
				.isNotEqualTo(null);
	}

	@Test
	void theMultipartFieldIsPhotoAndItsAbsenceIsNoFileUploaded() throws Exception {
		// upload_photo.php reads $_FILES['photo']; 'file' belongs to
		// analyze_excel.php and is not this endpoint's part.
		ResponseEntity<Map<String, Object>> wrongField = upload(
				UPLOAD + "?id=" + STAFF, ADMIN, "file", "portrait.jpg", jpegBytes());
		assertThat(wrongField.getStatusCode().value()).isEqualTo(400);
		assertThat(wrongField.getBody().get("message")).isEqualTo("No file uploaded");

		// A request with no multipart body at all is the same "no file".
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(ADMIN));
		ResponseEntity<Map<String, Object>> empty = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + UPLOAD + "?id=" + STAFF), HttpMethod.POST,
				new HttpEntity<>(headers), new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(empty.getStatusCode().value()).isEqualTo(400);
		assertThat(empty.getBody().get("message")).isEqualTo("No file uploaded");
	}

	@Test
	void theMimeAllowlistIsTheSourcesIncludingPdf() throws Exception {
		// uploadFile()'s allowlist is image/jpeg, image/png, image/webp and
		// application/pdf -- the endpoint's name does not narrow it.
		assertThat(upload(UPLOAD + "?id=" + STAFF, ADMIN, "photo", "scan.pdf", pdfBytes())
				.getStatusCode().value()).isEqualTo(200);
		assertThat((String) single("SELECT photo_url FROM employees WHERE id = " + STAFF).get("photo_url"))
				.endsWith(".pdf");
		assertThat(upload(UPLOAD + "?id=" + STAFF, ADMIN, "photo", "shot.webp", webpBytes())
				.getStatusCode().value()).isEqualTo(200);

		// Anything else is rejected on its bytes, whatever it is called.
		long filesBefore = storedFileCount();
		ResponseEntity<Map<String, Object>> rejected = upload(
				UPLOAD + "?id=" + STAFF, ADMIN, "photo", "notes.txt", "plain text".getBytes(StandardCharsets.UTF_8));
		assertThat(rejected.getStatusCode().value()).isEqualTo(400);
		assertThat(rejected.getBody().get("message")).isEqualTo("Invalid file type");
		assertThat(storedFileCount()).isEqualTo(filesBefore);
	}

	@Test
	void theExtensionComesFromTheDetectedTypeNotFromTheClientFilename() throws Exception {
		// Regression: frozen PHP's pathinfo($_FILES[...]['name'], PATHINFO_EXTENSION)
		// trusts the client-supplied filename, so a file whose bytes are sniffed
		// as one type but named with another extension (or an executable one,
		// e.g. "shell.php" holding PNG bytes) is stored under the client's
		// chosen extension on the same webroot /uploads is served from -- a
		// real upload-based RCE/XSS path, not reproduced. A PNG uploaded as
		// "MISNAMED.JPG" is stored as .png, matching its detected content.
		ResponseEntity<Map<String, Object>> response = upload(
				UPLOAD + "?id=" + STAFF, ADMIN, "photo", "MISNAMED.JPG", pngBytes());
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		@SuppressWarnings("unchecked")
		Map<String, Object> employee = (Map<String, Object>) response.getBody().get("data");
		assertThat((String) employee.get("photo_url")).endsWith(".png");
	}

	@Test
	void anExecutableExtensionOnTheClientFilenameIsIgnored() throws Exception {
		ResponseEntity<Map<String, Object>> response = upload(
				UPLOAD + "?id=" + STAFF, ADMIN, "photo", "shell.php", pngBytes());
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		@SuppressWarnings("unchecked")
		Map<String, Object> employee = (Map<String, Object>) response.getBody().get("data");
		assertThat((String) employee.get("photo_url")).endsWith(".png").doesNotContain(".php");
	}

	@Test
	void aForeignTargetLeavesTheFileOnDiskAndUpdatesNothing() throws Exception {
		String before = (String) single(
				"SELECT photo_url FROM employees WHERE id = " + OTHER_COMPANY_EMPLOYEE).get("photo_url");
		long filesBefore = storedFileCount();

		ResponseEntity<Map<String, Object>> response = upload(
				UPLOAD + "?id=" + OTHER_COMPANY_EMPLOYEE, ADMIN, "photo", "portrait.jpg", jpegBytes());

		// The file was written before the database was consulted, the scoped
		// update matched zero rows, and the re-read returned nothing -- which in
		// PHP is public_row(null), an uncaught TypeError. D-084 renders it.
		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody()).containsExactly(
				Map.entry("success", false), Map.entry("message", "Internal server error"));

		// The other company's row is untouched, and the orphan is still there:
		// legacy does not clean it up, and neither does this.
		assertThat(single("SELECT photo_url FROM employees WHERE id = " + OTHER_COMPANY_EMPLOYEE)
				.get("photo_url")).isEqualTo(before);
		assertThat(storedFileCount()).isEqualTo(filesBefore + 1);
	}

	@Test
	void aDatabaseFailureAfterTheMoveAlsoLeavesTheFileBehind() throws Exception {
		long filesBefore = storedFileCount();
		Mockito.doThrow(new IllegalStateException("photo_url update failed"))
				.when(storeSpy).updatePhotoUrl(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
		try {
			ResponseEntity<Map<String, Object>> response = upload(
					UPLOAD + "?id=" + STAFF, ADMIN, "photo", "portrait.jpg", jpegBytes());
			assertThat(response.getStatusCode().value()).isEqualTo(500);
			assertThat(response.getBody().get("message")).isEqualTo("Internal server error");
		} finally {
			Mockito.reset(storeSpy);
		}
		// No compensating filesystem rollback exists in the source, so the file
		// stays. Asserted so that adding one later is a deliberate change.
		assertThat(storedFileCount()).isEqualTo(filesBefore + 1);
	}

	@Test
	void aFalsyTargetIsRejectedAndTheMethodGuardComesFirst() throws Exception {
		long filesBefore = storedFileCount();
		ResponseEntity<Map<String, Object>> zero = upload(
				UPLOAD + "?id=0", ADMIN, "photo", "portrait.jpg", jpegBytes());
		assertThat(zero.getStatusCode().value()).isEqualTo(400);
		assertThat(zero.getBody().get("message")).isEqualTo("Employee id required");
		assertThat(storedFileCount()).isEqualTo(filesBefore);

		HttpHeaders headers = new HttpHeaders();
		ResponseEntity<Map<String, Object>> wrongMethod = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + UPLOAD), HttpMethod.GET,
				new HttpEntity<>(headers), new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(wrongMethod.getStatusCode().value()).isEqualTo(405);
		assertThat(wrongMethod.getBody().get("message")).isEqualTo("Invalid method");
	}

	@Test
	void anOldPhotoIsNeverDeletedWhenANewOneReplacesIt() throws Exception {
		String first = (String) uploadedUrl(UPLOAD + "?id=" + STAFF, ADMIN, "one.jpg", jpegBytes());
		String second = (String) uploadedUrl(UPLOAD + "?id=" + STAFF, ADMIN, "two.jpg", jpegBytes());
		assertThat(first).isNotEqualTo(second);
		// The column moved on; the previous file is still on disk.
		assertThat(storedFile(first)).exists();
		assertThat(storedFile(second)).exists();
		assertThat(single("SELECT photo_url FROM employees WHERE id = " + STAFF).get("photo_url"))
				.isEqualTo(second);
	}

	private Object uploadedUrl(String path, long actor, String filename, byte[] content) throws Exception {
		ResponseEntity<Map<String, Object>> response = upload(path, actor, "photo", filename, content);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		@SuppressWarnings("unchecked")
		Map<String, Object> employee = (Map<String, Object>) response.getBody().get("data");
		return employee.get("photo_url");
	}

	private ResponseEntity<Map<String, Object>> upload(
			String path, long actor, String partName, String filename, byte[] content) {
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		ByteArrayResource resource = new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		};
		parts.add(partName, resource);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		headers.setBearerAuth(tokenFor(actor));
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.POST,
				new HttpEntity<>(parts, headers), new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == ADMIN ? "company_admin"
				: employeeId == HR ? "hr"
				: employeeId == MANAGER ? "manager" : "employee";
		return jwtService.issueAccessToken(
				employeeId, employeeId, COMPANY_1, "test-session", Map.of("role", role, "token_version", 1L));
	}

	/** The four signatures {@code mime_content_type()} would report. */
	private static byte[] jpegBytes() {
		return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};
	}

	private static byte[] pngBytes() {
		return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
	}

	private static byte[] webpBytes() {
		return new byte[] {'R', 'I', 'F', 'F', 0x1A, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
	}

	private static byte[] pdfBytes() {
		return "%PDF-1.4\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);
	}

	private static Path storedFile(String photoUrl) {
		return UPLOAD_ROOT.resolve(photoUrl.replaceFirst("^/uploads/", ""));
	}

	private static long storedFileCount() throws Exception {
		Path photos = UPLOAD_ROOT.resolve("photos");
		if (!Files.isDirectory(photos)) {
			return 0L;
		}
		try (java.util.stream.Stream<Path> files = Files.list(photos)) {
			return files.count();
		}
	}

	private static Map<String, Object> single(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			assertThat(rs.next()).withFailMessage("no row for %s", sql).isTrue();
			Map<String, Object> row = new LinkedHashMap<>();
			for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
				row.put(rs.getMetaData().getColumnLabel(column), rs.getString(column));
			}
			return row;
		}
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

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (20001, 'Upload Co 1', '+201000020001', 'active', '2025-01-15 09:00:00'),
					  (20002, 'Upload Co 2', '+201000020002', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (20011, 20001, 'Main Branch', 1, '2025-03-01 10:00:00'),
					  (20021, 20002, 'Other Company Branch', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, employee_code, first_name, last_name, phone, country_code,
					   password_hash, token_version, role, is_active, join_request_status, photo_url, created_at)
					VALUES
					  (200011, 20001, 20011, '1001', 'Upload', 'Admin', '+201000200011', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'company_admin', 1, 'accepted', NULL,
					   '2025-05-01 09:00:00'),
					  (200012, 20001, 20011, '1002', 'Upload', 'Hr', '+201000200012', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'hr', 1, 'accepted', NULL, '2025-05-01 09:00:00'),
					  (200013, 20001, 20011, '1003', 'Upload', 'Manager', '+201000200013', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'manager', 1, 'accepted', NULL, '2025-05-01 09:00:00'),
					  (200014, 20001, 20011, '1004', 'Upload', 'Staff', '+201000200014', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'employee', 1, 'accepted', NULL, '2025-05-01 09:00:00'),
					  (200015, 20001, 20011, '1005', 'Other', 'Staff', '+201000200015', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'employee', 1, 'accepted', '/uploads/photos/keep.jpg',
					   '2025-05-01 09:00:00'),
					  (200021, 20002, 20021, '2001', 'Other', 'Company', '+201000200021', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'employee', 1, 'accepted', '/uploads/photos/theirs.jpg',
					   '2025-05-01 09:00:00')
					""");
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream =
				LegacyEmployeeUploadPhotoEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
