package com.workin.backend.platformadmin.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyMariaDb;

/**
 * {@code /admin/guide_videos} over real HTTP against a real MariaDB.
 *
 * <p>The page has no tenant dimension — {@code guide_videos} carries no
 * {@code company_id} and PHP gates the whole page on {@code isAdmin()} — so
 * there is no cross-company case to cover here, unlike the HR pages. What
 * there is instead is a filename that reaches a filesystem path, and the case
 * below that drives a traversal attempt through the real form is the one worth
 * having end to end rather than only in {@link GuideVideoFormTest}.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminGuideVideosEndToEndTest {

	/** A database of this class's own, inside the shared container. */
	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String PASSWORD = "correct horse battery staple";

	private static final String PATH = "/admin/guide_videos";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.platform-admin.password", () -> PASSWORD);
		registry.add("app.platform-admin.actions.enabled", () -> "true");
	}

	@Autowired
	private TestRestTemplate restTemplate;


	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private javax.sql.DataSource legacyDataSource;

	private JdbcTemplate jdbc;

	private String cookie;

	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM guide_videos");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		// One administrator, one password (ADR-0018): the bootstrap provisioned
		// the row from the configured password when the context started.
		long adminId = this.jdbc.queryForObject(
				"SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
		Page login = page("/admin/login", null);
		this.cookie = cookieOf(post("/admin/login", login.cookie(), login.csrf(), "password", PASSWORD));
	}

	private long seed(String titleAr, String titleEn, String video, int sortOrder, boolean active) {
		this.jdbc.update("INSERT INTO guide_videos (title_ar, title_en, video, sort_order, is_active)"
				+ " VALUES (?, ?, ?, ?, ?)", titleAr, titleEn, video, sortOrder, active ? 1 : 0);
		return this.jdbc.queryForObject("SELECT MAX(id) FROM guide_videos", Long.class);
	}

	private ResponseEntity<String> submit(String... fields) {
		return post(PATH, this.cookie, page(PATH, this.cookie).csrf(), fields);
	}

	@Test
	void thePageListsEveryRowIncludingTheInactiveOnes() {
		seed("نشط", "Active clip", "a.mp4", 1, true);
		seed("متوقف", "Hidden clip", "b.mp4", 2, false);

		String html = body(PATH);
		assertThat(html).contains("Active clip");
		assertThat(html).as("the client's read filters is_active; this page must not")
				.contains("Hidden clip");
	}

	@Test
	void theListIsOrderedBySortOrderThenId() {
		seed("ثالث", "Third", "c.mp4", 9, true);
		seed("أول", "First", "a.mp4", 1, true);
		seed("ثانٍ", "Second", "b.mp4", 5, true);

		String html = body(PATH);
		assertThat(html.indexOf("First")).isLessThan(html.indexOf("Second"));
		assertThat(html.indexOf("Second")).isLessThan(html.indexOf("Third"));
	}

	@Test
	void theConnectionStoresArabicIntact() {
		// Isolates the storage layer from the HTTP layer: if this passes and the
		// form test does not, the corruption is in request decoding, not in the
		// column, the connection charset or the driver.
		long id = seed("دليل المستخدم", "User guide", "a.mp4", 1, true);
		assertThat(this.jdbc.queryForObject(
				"SELECT title_ar FROM guide_videos WHERE id = " + id, String.class))
				.isEqualTo("دليل المستخدم");
	}

	@Test
	void addingWritesTheFiveColumnsTheFormCarries() {
		submit("action", "add", "title_ar", "دليل", "title_en", "Guide",
				"video", "intro.mp4", "sort_order", "3", "is_active", "1");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT title_ar, title_en, video, sort_order, is_active FROM guide_videos");
		assertThat(row.get("title_ar")).isEqualTo("دليل");
		assertThat(row.get("title_en")).isEqualTo("Guide");
		assertThat(row.get("video")).isEqualTo("intro.mp4");
		assertThat(row.get("sort_order").toString()).isEqualTo("3");
		// tinyint(1) with the driver's default tinyInt1isBit: a Boolean, not a 1.
		assertThat(row.get("is_active")).isEqualTo(Boolean.TRUE);
	}

	@Test
	void anUntickedActiveBoxStoresZero() {
		// `!empty($post['is_active'])`: the browser omits the field entirely.
		submit("action", "add", "title_ar", "د", "title_en", "G",
				"video", "intro.mp4", "sort_order", "0");

		assertThat(this.jdbc.queryForObject(
				"SELECT is_active FROM guide_videos", Boolean.class)).isFalse();
	}

	@Test
	void aTraversingFilenameIsRefusedAndNothingIsWritten() {
		ResponseEntity<String> response = submit("action", "add",
				"title_ar", "د", "title_en", "G",
				"video", "../../../etc/passwd", "sort_order", "0", "is_active", "1");

		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM guide_videos", Integer.class)).isZero();
	}

	@Test
	void aFilenameWithADirectoryPrefixIsStoredAsItsLastSegment() {
		submit("action", "add", "title_ar", "د", "title_en", "G",
				"video", "uploads/intro.mp4", "sort_order", "0", "is_active", "1");

		assertThat(this.jdbc.queryForObject("SELECT video FROM guide_videos", String.class))
				.as("basename() runs before the allow-list, so the prefix is stripped rather"
						+ " than making the whole value unusable")
				.isEqualTo("intro.mp4");
	}

	@Test
	void addingWithNoTitleWritesNothing() {
		ResponseEntity<String> response = submit("action", "add",
				"title_ar", "", "title_en", "G", "video", "intro.mp4", "sort_order", "0");

		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM guide_videos", Integer.class)).isZero();
	}

	@Test
	void editingRewritesTheFiveColumnsAndNothingElse() {
		long id = seed("قديم", "Old", "old.mp4", 1, true);
		Map<String, Object> before = this.jdbc.queryForMap(
				"SELECT * FROM guide_videos WHERE id = " + id);

		submit("action", "edit", "id", String.valueOf(id),
				"title_ar", "جديد", "title_en", "New", "video", "new.mp4",
				"sort_order", "8", "is_active", "1");

		Map<String, Object> after = this.jdbc.queryForMap(
				"SELECT * FROM guide_videos WHERE id = " + id);
		assertThat(after.get("title_en")).isEqualTo("New");
		assertThat(after.get("video")).isEqualTo("new.mp4");
		assertThat(after.get("sort_order").toString()).isEqualTo("8");
		assertThat(after.get("id")).as("the row keeps its identity").isEqualTo(before.get("id"));
		assertThat(after.get("created_at"))
				.as("created_at is not on the form and must not move")
				.isEqualTo(before.get("created_at"));
	}

	@Test
	void editingCanSwitchAClipOff() {
		long id = seed("نشط", "Active", "a.mp4", 1, true);

		submit("action", "edit", "id", String.valueOf(id),
				"title_ar", "نشط", "title_en", "Active", "video", "a.mp4", "sort_order", "1");

		assertThat(this.jdbc.queryForObject(
				"SELECT is_active FROM guide_videos WHERE id = " + id, Boolean.class))
				.as("turning a clip off is what this page is for").isFalse();
	}

	@Test
	void editingAnUnknownRowSaysSoRatherThanReportingSuccess() {
		// PHP updates by id with no existence check and still flashes saved_ok.
		// The port refuses instead; nothing is written either way.
		ResponseEntity<String> response = submit("action", "edit", "id", "987654",
				"title_ar", "د", "title_en", "G", "video", "a.mp4", "sort_order", "0");

		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_not_found");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM guide_videos", Integer.class)).isZero();
	}

	@Test
	void anEditWithATraversingFilenameLeavesTheStoredOneAlone() {
		long id = seed("د", "G", "safe.mp4", 1, true);

		ResponseEntity<String> response = submit("action", "edit", "id", String.valueOf(id),
				"title_ar", "د", "title_en", "G", "video", "../../evil.sh", "sort_order", "1");

		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT video FROM guide_videos WHERE id = " + id, String.class))
				.isEqualTo("safe.mp4");
	}

	@Test
	void deletingRemovesOnlyThatRow() {
		long kept = seed("أ", "Kept", "a.mp4", 1, true);
		long removed = seed("ب", "Removed", "b.mp4", 2, true);

		submit("action", "delete", "id", String.valueOf(removed));

		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM guide_videos", Integer.class)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject(
				"SELECT id FROM guide_videos", Long.class)).isEqualTo(kept);
	}

	@Test
	void deletingAnUnknownRowIsRefused() {
		ResponseEntity<String> response = submit("action", "delete", "id", "987654");
		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_not_found");
	}

	@Test
	void everyWriteIsAudited() {
		submit("action", "add", "title_ar", "د", "title_en", "Guide",
				"video", "a.mp4", "sort_order", "0", "is_active", "1");
		long id = this.jdbc.queryForObject("SELECT MAX(id) FROM guide_videos", Long.class);
		submit("action", "delete", "id", String.valueOf(id));

		// Filtered: signing in writes its own PLATFORM_ADMIN events, and they
		// land after the @BeforeEach truncation rather than before it.
		List<String> targets = this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events"
						+ " WHERE target_type = 'GUIDE_VIDEO' ORDER BY id", String.class);
		assertThat(targets).containsExactly("CONTENT_CREATED", "CONTENT_DELETED");
	}

	// ------------------------------------------------------------------
	// Harness
	// ------------------------------------------------------------------

	private record Csrf(String name, String value) {
	}

	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	private String body(String path) {
		return get(path, this.cookie).getBody();
	}

	private ResponseEntity<String> get(String path, String sessionCookie) {
		HttpHeaders headers = new HttpHeaders();
		if (sessionCookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		}
		return this.restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	private Page page(String path, String sessionCookie) {
		ResponseEntity<String> response = get(path, sessionCookie);
		String resolved = sessionCookie != null ? sessionCookie : tryCookieOf(response);
		return new Page(response, resolved, csrfOf(response));
	}

	/**
	 * Posts what a browser posts.
	 *
	 * <p>The body is percent-encoded as UTF-8 and sent as raw bytes under a
	 * bare {@code application/x-www-form-urlencoded} with <b>no charset
	 * parameter</b> — which is exactly what a browser does for a form on a
	 * UTF-8 page, since the form-urlencoded media type has no charset parameter
	 * to send. Spring's own form and string converters default to ISO-8859-1
	 * when the content type carries no charset, so building the body through
	 * them would have tested the client's default rather than the server's
	 * decoding, and Arabic would have arrived mangled for a reason no user
	 * would ever hit.
	 */
	private ResponseEntity<String> post(String path, String sessionCookie, Csrf csrf, String... fields) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);

		StringBuilder body = new StringBuilder();
		for (int index = 0; index < fields.length; index += 2) {
			append(body, fields[index], fields[index + 1]);
		}
		append(body, csrf.name(), csrf.value());

		return this.restTemplate.exchange(path, HttpMethod.POST,
				new HttpEntity<>(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
						headers),
				String.class);
	}

	private static void append(StringBuilder body, String name, String value) {
		if (body.length() > 0) {
			body.append('&');
		}
		body.append(java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8))
				.append('=')
				.append(java.net.URLEncoder.encode(
						value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8));
	}

	private static Csrf csrfOf(ResponseEntity<String> response) {
		Matcher matcher = CSRF.matcher(response.getBody());
		assertThat(matcher.find()).as("expected a CSRF token").isTrue();
		return new Csrf(matcher.group(1), matcher.group(2));
	}


	private static byte[] fromBase32(String seed) {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int buffer = 0;
		int bits = 0;
		for (char character : seed.toCharArray()) {
			int value = alphabet.indexOf(character);
			if (value < 0) {
				continue;
			}
			buffer = (buffer << 5) | value;
			bits += 5;
			if (bits >= 8) {
				out.write((buffer >> (bits - 8)) & 0xFF);
				bits -= 8;
			}
		}
		return out.toByteArray();
	}

	private static String cookieOf(ResponseEntity<String> response) {
		String value = tryCookieOf(response);
		assertThat(value).as("expected a session cookie").isNotNull();
		return value;
	}

	private static String tryCookieOf(ResponseEntity<String> response) {
		List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
		if (cookies == null) {
			return null;
		}
		return cookies.stream()
				.filter(value -> value.startsWith("WORKIN_ADMIN_SESSION="))
				.map(header -> {
					int start = header.indexOf('=') + 1;
					int end = header.indexOf(';', start);
					return end < 0 ? header.substring(start) : header.substring(start, end);
				})
				.findFirst().orElse(null);
	}

}
