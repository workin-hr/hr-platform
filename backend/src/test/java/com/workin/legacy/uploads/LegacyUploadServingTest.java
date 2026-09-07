package com.workin.legacy.uploads;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;

/**
 * The stored uploads answer over HTTP.
 *
 * <p>End to end rather than as a unit test of the resolver, because the defect
 * this covers was <b>the absence of a route</b>: {@code LegacyFileUploads}
 * stored the file, returned {@code /uploads/<area>/<name>} to the caller, and
 * every one of those URLs answered 404 -- the file was on disk and the mapping
 * was correct and nothing served it. A test below the HTTP layer would have
 * passed throughout.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyUploadServingTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	@TempDir
	static Path uploads;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
		}
		catch (Exception ex) {
			throw new IllegalStateException("could not apply the legacy schema", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.platform-admin.mfa.encryption-key", () -> {
			byte[] key = new byte[32];
			new java.security.SecureRandom().nextBytes(key);
			return java.util.Base64.getEncoder().encodeToString(key);
		});
		registry.add("app.legacy-uploads.path", () -> uploads.toString());
	}

	@Autowired
	private TestRestTemplate restTemplate;

	@BeforeEach
	void doNotFollowRedirects() {
		// A redirect followed to the login page would turn "protected" and
		// "not served at all" into the same 200 -- the failure this suite
		// already learned to guard against elsewhere.
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
	}

	private void put(String relative, String content) throws Exception {
		Path file = uploads.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	@Test
	void aStoredFileIsServedWithoutASession() throws Exception {
		put("employees/photo.png", "not-really-a-png");

		ResponseEntity<String> response = this.restTemplate.getForEntity("/uploads/employees/photo.png",
				String.class);

		assertThat(response.getStatusCode())
			.as("the clients fetch this URL with no session, straight out of an API "
					+ "response body -- D-111 forbids changing that")
			.isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo("not-really-a-png");
	}

	@Test
	void anExtensionThisSystemNeverWritesIsNotServed() throws Exception {
		// Frozen PHP names a stored file from the client-supplied filename, so
		// its /uploads tree can hold one whose extension has nothing to do with
		// its bytes. Serving that inline from this origin would make a planted
		// .html into script on the admin's own origin.
		put("employees/planted.html", "<script>alert(1)</script>");

		assertThat(this.restTemplate.getForEntity("/uploads/employees/planted.html", String.class)
			.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void aTraversalDoesNotReachOutsideTheUploadRoot() throws Exception {
		Files.writeString(uploads.getParent().resolve("outside.png"), "secret");

		for (String attempt : new String[] {
			"/uploads/../outside.png",
			"/uploads/employees/../../outside.png",
			"/uploads/%2e%2e%2foutside.png",
		}) {
			assertThat(this.restTemplate.getForEntity(attempt, String.class).getBody())
				.as("%s must not read outside the upload root", attempt)
				.isNotEqualTo("secret");
		}
	}

	@Test
	void aMissingFileIsNotFoundRatherThanAnError() {
		assertThat(this.restTemplate.getForEntity("/uploads/employees/absent.png", String.class)
			.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	private static void applySchema(String resource) throws Exception {
		String sql = new String(LegacyUploadServingTest.class.getClassLoader()
				.getResourceAsStream(resource).readAllBytes(), StandardCharsets.UTF_8);
		try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
				java.sql.Statement statement = connection.createStatement()) {
			statement.execute("SET SESSION sql_mode = \'\'");
			for (String piece : sql.split(";\\R")) {
				if (!piece.isBlank()) {
					statement.execute(piece);
				}
			}
		}
	}

}
