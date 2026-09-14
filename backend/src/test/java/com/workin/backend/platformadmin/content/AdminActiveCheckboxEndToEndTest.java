package com.workin.backend.platformadmin.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyMariaDb;

/**
 * The Active checkbox in the faqs and phone-countries edit dialogs, over real
 * HTTP against a real MariaDB.
 *
 * <p>{@code row-dialog.js} fills those dialogs from the row's
 * {@code data-dialog-*} attributes. The checkbox was left out and rendered
 * ticked, so saving an inactive row from its dialog re-activated it. Legacy's
 * edit modals set the box from the row ({@code faqs/page.php:260} and
 * {@code :274}, {@code phone_countries/page.php:198}).
 *
 * <p>Each case reads the rendered trigger and dialog, fills the dialog by the
 * script's rules and posts it as a browser would. Those rules are held to the
 * real script in a browser by {@code deploy/e2e/tests/row-dialog.spec.js}.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminActiveCheckboxEndToEndTest {

	/** A database of this class's own, inside the shared container. */
	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String PASSWORD = "correct horse battery staple";

	private static final String FAQS = "/admin/faqs";

	private static final String PHONE_COUNTRIES = "/admin/phone_countries";

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
	private javax.sql.DataSource legacyDataSource;

	private JdbcTemplate jdbc;

	private String cookie;

	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM faq_items");
		this.jdbc.update("DELETE FROM faq_categories");
		this.jdbc.update("DELETE FROM phone_countries WHERE country_code IN ('+881', '+882')");

		Page login = page("/admin/login", null);
		this.cookie = cookieOf(post("/admin/login", login.cookie(), login.csrf(), List.of("password", PASSWORD)));
	}

	@Test
	void aCategorySavedUnchangedFromItsDialogKeepsItsActiveState() {
		long open = seedCategory("مفتوحة", "Open", true);
		long closed = seedCategory("مغلقة", "Closed", false);

		saveFromDialog(FAQS, "faq-category-edit", open);
		saveFromDialog(FAQS, "faq-category-edit", closed);

		assertThat(active("faq_categories", open)).as("an active category stays active").isTrue();
		assertThat(active("faq_categories", closed))
				.as("an inactive category is not re-activated by saving it unchanged").isFalse();
		assertThat(this.jdbc.queryForObject("SELECT name_en FROM faq_categories WHERE id = ?", String.class, closed))
				.as("the dialog was filled from the row").isEqualTo("Closed");
	}

	@Test
	void aQuestionSavedUnchangedFromItsDialogKeepsItsActiveState() {
		long category = seedCategory("فئة", "Category", true);
		long open = seedItem(category, "Open question", true);
		long closed = seedItem(category, "Closed question", false);

		saveFromDialog(FAQS, "faq-item-edit", open);
		saveFromDialog(FAQS, "faq-item-edit", closed);

		assertThat(active("faq_items", open)).as("an active question stays active").isTrue();
		assertThat(active("faq_items", closed))
				.as("an inactive question is not re-activated by saving it unchanged").isFalse();
		assertThat(this.jdbc.queryForObject("SELECT question_en FROM faq_items WHERE id = ?", String.class, closed))
				.as("the dialog was filled from the row").isEqualTo("Closed question");
	}

	@Test
	void aCountrySavedUnchangedFromItsDialogKeepsItsActiveState() {
		long open = seedCountry("+881", "Open country", true);
		long closed = seedCountry("+882", "Closed country", false);

		saveFromDialog(PHONE_COUNTRIES, "pc-edit", open);
		saveFromDialog(PHONE_COUNTRIES, "pc-edit", closed);

		assertThat(active("phone_countries", open)).as("an active country stays active").isTrue();
		assertThat(active("phone_countries", closed))
				.as("an inactive country is not re-activated by saving it unchanged").isFalse();
		assertThat(this.jdbc.queryForObject("SELECT name_en FROM phone_countries WHERE id = ?", String.class, closed))
				.as("the dialog was filled from the row").isEqualTo("Closed country");
	}

	@Test
	void theAddFormsStillStartTicked() {
		// Legacy's add modals are ticked (faqs/page.php:163 and :213,
		// phone_countries/page.php:131). Only the edit dialogs follow the row.
		seedCategory("فئة", "Category", true);
		String faqs = body(FAQS);

		assertThat(addFormTicked(faqs, "add_category")).as("the add-category form").isTrue();
		assertThat(addFormTicked(faqs, "add_item")).as("the add-question form").isTrue();
		assertThat(addFormTicked(body(PHONE_COUNTRIES), "add")).as("the add-country form").isTrue();
	}

	// ------------------------------------------------------------------
	// Seeds
	// ------------------------------------------------------------------

	private long seedCategory(String nameAr, String nameEn, boolean active) {
		this.jdbc.update("INSERT INTO faq_categories (name_ar, name_en, sort_order, is_active) VALUES (?, ?, 1, ?)",
				nameAr, nameEn, active ? 1 : 0);
		return this.jdbc.queryForObject("SELECT MAX(id) FROM faq_categories", Long.class);
	}

	private long seedItem(long categoryId, String questionEn, boolean active) {
		this.jdbc.update("""
				INSERT INTO faq_items
				  (faq_category_id, question_ar, question_en, answer_ar, answer_en, app_platform, sort_order, is_active)
				VALUES (?, 'سؤال', ?, 'جواب', 'An answer', 'both', 1, ?)""",
				categoryId, questionEn, active ? 1 : 0);
		return this.jdbc.queryForObject("SELECT MAX(id) FROM faq_items", Long.class);
	}

	/** Through the page's own add form, so the prefixes are stored as the page stores them. */
	private long seedCountry(String countryCode, String nameEn, boolean active) {
		List<String> fields = new ArrayList<>(List.of("action", "add", "countryCode", countryCode,
				"nameAr", "دولة", "nameEn", nameEn, "flagEmoji", "", "phoneLength", "9",
				"prefixes", "10, 11", "sortOrder", "0"));
		if (active) {
			fields.addAll(List.of("isActive", "1"));
		}
		ResponseEntity<String> response = post(PHONE_COUNTRIES, this.cookie, page(PHONE_COUNTRIES, this.cookie).csrf(),
				fields);
		assertThat(response.getHeaders().getLocation()).as("seeding %s", countryCode).asString()
				.doesNotContain("error");
		return this.jdbc.queryForObject("SELECT id FROM phone_countries WHERE country_code = ?", Long.class,
				countryCode);
	}

	private boolean active(String table, long id) {
		return this.jdbc.queryForObject("SELECT is_active FROM " + table + " WHERE id = ?", Boolean.class, id);
	}

	// ------------------------------------------------------------------
	// The dialog, as the script fills it and a browser submits it
	// ------------------------------------------------------------------

	private void saveFromDialog(String path, String dialogId, long rowId) {
		Page page = page(path, this.cookie);
		List<String> fields = dialogSubmission(page.response().getBody(), dialogId, rowId);

		ResponseEntity<String> response = post(path, this.cookie, page.csrf(), fields);
		assertThat(response.getStatusCode().is3xxRedirection()).as("the save redirects").isTrue();
		assertThat(response.getHeaders().getLocation()).as("the save was accepted").asString()
				.doesNotContain("error");
	}

	/**
	 * What the dialog posts for one row. It starts from the form as rendered,
	 * which is what {@code form.reset()} restores. Each field whose
	 * {@code data-dialog-field} the row's trigger carries then takes the
	 * trigger's value; a checkbox is ticked when that value is {@code "1"} and
	 * unticked otherwise. A browser submits a checkbox only when ticked, and
	 * will not submit a required field left empty, so that fails the test.
	 */
	private static List<String> dialogSubmission(String html, String dialogId, long rowId) {
		Map<String, String> row = triggerValues(html, dialogId, rowId);
		int start = html.indexOf("<dialog class=\"row-dialog\" id=\"" + dialogId + "\"");
		assertThat(start).as("the %s dialog renders", dialogId).isPositive();
		String dialog = html.substring(start, html.indexOf("</dialog>", start));

		List<String> fields = new ArrayList<>();
		Matcher inputs = Pattern.compile("<input\\b([^>]*)>").matcher(dialog);
		while (inputs.find()) {
			String attributes = inputs.group(1);
			String name = attribute(attributes, "name");
			if (name == null || name.contains("_csrf")) {
				continue;
			}
			String filled = filledValue(row, attributes);
			if ("checkbox".equals(attribute(attributes, "type"))) {
				boolean ticked = filled != null ? "1".equals(filled) : hasAttribute(attributes, "checked");
				if (ticked) {
					String value = attribute(attributes, "value");
					fields.addAll(List.of(name, value == null ? "on" : value));
				}
				continue;
			}
			String value = filled != null ? filled : attribute(attributes, "value");
			addField(fields, name, value == null ? "" : value, hasAttribute(attributes, "required"));
		}

		Matcher textareas = Pattern.compile("<textarea\\b([^>]*)>(.*?)</textarea>", Pattern.DOTALL).matcher(dialog);
		while (textareas.find()) {
			String filled = filledValue(row, textareas.group(1));
			addField(fields, attribute(textareas.group(1), "name"),
					filled != null ? filled : decode(textareas.group(2)),
					hasAttribute(textareas.group(1), "required"));
		}

		Matcher selects = Pattern.compile("<select\\b([^>]*)>(.*?)</select>", Pattern.DOTALL).matcher(dialog);
		while (selects.find()) {
			List<String> options = new ArrayList<>();
			String selected = null;
			Matcher option = Pattern.compile("<option\\b([^>]*)>").matcher(selects.group(2));
			while (option.find()) {
				String value = attribute(option.group(1), "value");
				options.add(value == null ? "" : value);
				if (hasAttribute(option.group(1), "selected")) {
					selected = options.get(options.size() - 1);
				}
			}
			String filled = filledValue(row, selects.group(1));
			String value = filled != null ? filled : selected != null ? selected : options.isEmpty() ? "" : options.get(0);
			assertThat(options).as("the row's %s matches one of the select's options", filled).contains(value);
			addField(fields, attribute(selects.group(1), "name"), value, hasAttribute(selects.group(1), "required"));
		}
		return fields;
	}

	/** The trigger's {@code data-dialog-*} values, keyed as the browser reads them: without regard to case. */
	private static Map<String, String> triggerValues(String html, String dialogId, long rowId) {
		Matcher buttons = Pattern.compile("<button\\b([^>]*)>").matcher(html);
		while (buttons.find()) {
			String attributes = buttons.group(1);
			if (dialogId.equals(attribute(attributes, "data-dialog"))
					&& String.valueOf(rowId).equals(attribute(attributes, "data-dialog-id"))) {
				Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
				Matcher data = Pattern.compile("(?:^|\\s)data-dialog-([A-Za-z]+)=\"([^\"]*)\"").matcher(attributes);
				while (data.find()) {
					values.put(data.group(1), decode(data.group(2)));
				}
				return values;
			}
		}
		throw new AssertionError("no " + dialogId + " trigger for row " + rowId);
	}

	private static String filledValue(Map<String, String> row, String attributes) {
		String key = attribute(attributes, "data-dialog-field");
		return key == null ? null : row.get(key);
	}

	private static void addField(List<String> fields, String name, String value, boolean required) {
		assertThat(!required || !value.isEmpty())
				.as("a browser would not submit the dialog with %s empty", name).isTrue();
		fields.addAll(List.of(name, value));
	}

	private static boolean addFormTicked(String html, String action) {
		int marker = html.indexOf("name=\"action\" value=\"" + action + "\"");
		assertThat(marker).as("the %s form renders", action).isPositive();
		String form = html.substring(html.lastIndexOf("<form", marker), html.indexOf("</form>", marker));
		Matcher inputs = Pattern.compile("<input\\b([^>]*)>").matcher(form);
		while (inputs.find()) {
			if ("isActive".equals(attribute(inputs.group(1), "name"))) {
				return hasAttribute(inputs.group(1), "checked");
			}
		}
		throw new AssertionError("the " + action + " form has no isActive checkbox");
	}

	private static String attribute(String attributes, String name) {
		Matcher matcher = Pattern.compile("(?:^|\\s)" + name + "=\"([^\"]*)\"").matcher(attributes);
		return matcher.find() ? decode(matcher.group(1)) : null;
	}

	private static boolean hasAttribute(String attributes, String name) {
		return Pattern.compile("(?:^|\\s)" + name + "(?:\\s|=|$)").matcher(attributes).find();
	}

	private static String decode(String escaped) {
		return escaped.replace("&quot;", "\"").replace("&#34;", "\"").replace("&#39;", "'")
				.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
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
	 * Posts what a browser posts: UTF-8 percent-encoding under a bare
	 * {@code application/x-www-form-urlencoded}, as
	 * {@code AdminGuideVideosEndToEndTest} explains.
	 */
	private ResponseEntity<String> post(String path, String sessionCookie, Csrf csrf, List<String> fields) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);

		StringBuilder body = new StringBuilder();
		for (int index = 0; index < fields.size(); index += 2) {
			append(body, fields.get(index), fields.get(index + 1));
		}
		append(body, csrf.name(), csrf.value());

		return this.restTemplate.exchange(path, HttpMethod.POST,
				new HttpEntity<>(body.toString().getBytes(StandardCharsets.UTF_8), headers), String.class);
	}

	private static void append(StringBuilder body, String name, String value) {
		if (body.length() > 0) {
			body.append('&');
		}
		body.append(java.net.URLEncoder.encode(name, StandardCharsets.UTF_8))
				.append('=')
				.append(java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
	}

	private static Csrf csrfOf(ResponseEntity<String> response) {
		Matcher matcher = CSRF.matcher(response.getBody());
		assertThat(matcher.find()).as("expected a CSRF token").isTrue();
		return new Csrf(matcher.group(1), matcher.group(2));
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
