package com.workin.backend.platformadmin.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyMariaDb;

/**
 * {@code /admin/banners}'s edit form over real HTTP against a real MariaDB.
 *
 * <p>A banner's WhatsApp button stores one string of digits, the dial code and
 * the local number run together, and the form edits it as two inputs. So the
 * edit has to split the stored digits back into those two parts, or an
 * unchanged save sends an empty number -- which the form reads as "no number"
 * and stores as null, deleting the link. Each case here reads the rendered form
 * the way a browser submits it and posts it back as multipart, as the page does.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminBannersEndToEndTest {

	/** A database of this class's own, inside the shared container. */
	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String PASSWORD = "correct horse battery staple";

	private static final String PATH = "/admin/banners";

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
		this.jdbc.update("DELETE FROM banners");
		this.jdbc.update("DELETE FROM phone_countries");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");
		// The dial codes the page's split can match. "+96" is not a real code;
		// it is here so a shorter prefix of "+966" exists to be passed over.
		dialCode("+20", 11, 1);
		dialCode("+96", 9, 2);
		dialCode("+966", 9, 3);

		Page login = page("/admin/login", null);
		this.cookie = cookieOf(post("/admin/login", login.cookie(), login.csrf(), "password", PASSWORD));
	}

	@Test
	void anUnchangedSaveKeepsAWhatsappBannersNumber() {
		long id = seedBanner("whatsapp", "201012345678");
		Map<String, Object> before = bannerRow(id);

		assertSaved(postMultipart(formFields(body(PATH + "?edit=" + id))));

		assertThat(bannerRow(id)).isEqualTo(before);
	}

	@Test
	void theEditFormSplitsTheStoredNumberOnTheLongestActiveDialCode() {
		long id = seedBanner("whatsapp", "966501234567");

		Map<String, String> form = formFields(body(PATH + "?edit=" + id));

		assertThat(form.get("whatsappCountryCode")).isEqualTo("+966");
		assertThat(form.get("whatsappPhone")).isEqualTo("501234567");
		assertThat(form.get("actionValue"))
				.as("legacy leaves the action value empty for a WhatsApp button")
				.isEmpty();
	}

	@Test
	void anUnchangedSaveKeepsANumberNoActiveDialCodeMatches() {
		// No active code starts "44", so the split falls back to +20 with every
		// digit as the local number, and recombining them would add a 20.
		long id = seedBanner("whatsapp", "4412345678");
		Map<String, Object> before = bannerRow(id);

		assertSaved(postMultipart(formFields(body(PATH + "?edit=" + id))));

		assertThat(bannerRow(id)).isEqualTo(before);
	}

	@Test
	void aChangedWhatsappNumberIsWrittenFromItsParts() {
		long id = seedBanner("whatsapp", "201012345678");
		Map<String, String> form = formFields(body(PATH + "?edit=" + id));
		form.put("whatsappPhone", "1099999999");

		assertSaved(postMultipart(form));

		assertThat(this.jdbc.queryForObject(
				"SELECT button_action_value FROM banners WHERE id = ?", String.class, id))
				.isEqualTo("201099999999");
	}

	@Test
	void aFormattedStoredNumberIsRebuiltToDigitsNotKept() {
		// Only digits are kept as stored, which is what both systems write. A
		// value in any other shape is rebuilt from its parts, as legacy does.
		long id = seedBanner("whatsapp", "+966 50-123-4567");

		assertSaved(postMultipart(formFields(body(PATH + "?edit=" + id))));

		assertThat(this.jdbc.queryForObject(
				"SELECT button_action_value FROM banners WHERE id = ?", String.class, id))
				.isEqualTo("966501234567");
	}

	@Test
	void aStoredWhatsappValueThatIsNotANumberIsNotKept() {
		// banners/list serves the value to the clients unsanitised, so an edit
		// must not carry a stored value past the WhatsApp rule because its parts
		// came back as shown. The one digit here splits into +20 and "1", and
		// rebuilt that is three digits, which is no number.
		long id = seedBanner("whatsapp", "javascript:alert(1)");

		assertSaved(postMultipart(formFields(body(PATH + "?edit=" + id))));

		assertThat(this.jdbc.queryForObject(
				"SELECT button_action_value FROM banners WHERE id = ?", String.class, id))
				.isNull();
	}

	@Test
	void aStoredNumberOutsideEightToFifteenDigitsIsNotKept() {
		// Legacy stores a WhatsApp number only when it has eight to fifteen
		// digits, so a save through it, unchanged or not, clears one that does not.
		long id = seedBanner("whatsapp", "2012345");

		assertSaved(postMultipart(formFields(body(PATH + "?edit=" + id))));

		assertThat(this.jdbc.queryForObject(
				"SELECT button_action_value FROM banners WHERE id = ?", String.class, id))
				.isNull();
	}

	@Test
	void aBannerChangedToWhatsappWithNoNumberStoresNoNumber() {
		// The keep rule applies only to a banner that was already a WhatsApp
		// button. Without that, an internal route changed to WhatsApp would keep
		// its route under the WhatsApp type: both split to +20 and nothing.
		long id = seedBanner("internal_route", "home");
		Map<String, String> form = formFields(body(PATH + "?edit=" + id));
		form.put("actionType", "whatsapp");
		form.put("whatsappCountryCode", "+20");
		form.put("whatsappPhone", "");

		assertSaved(postMultipart(form));

		assertThat(this.jdbc.queryForMap(
				"SELECT button_action_type, button_action_value FROM banners WHERE id = ?", id))
				.containsEntry("button_action_type", "whatsapp")
				.containsEntry("button_action_value", null);
	}

	@Test
	void aBannerChangedToWhatsappIsBuiltFromItsPartsEvenOverStoredDigits() {
		// The first guard on its own. Only a stored WhatsApp number is kept: a
		// digits-only value left behind under another type is not, once the
		// banner becomes a WhatsApp button -- the number is built from what was
		// typed, even when the typed parts match that leftover value's split.
		long id = seedBanner("none", "4412345678");
		Map<String, String> form = formFields(body(PATH + "?edit=" + id));
		form.put("actionType", "whatsapp");
		form.put("whatsappCountryCode", "+20");
		form.put("whatsappPhone", "4412345678");

		assertSaved(postMultipart(form));

		assertThat(this.jdbc.queryForObject(
				"SELECT button_action_value FROM banners WHERE id = ?", String.class, id))
				.isEqualTo("204412345678");
	}

	@Test
	void anUnchangedSaveKeepsAnExternalUrlBanner() {
		long id = seedBanner("external_url", "https://example.com/offer");
		Map<String, Object> before = bannerRow(id);

		assertSaved(postMultipart(formFields(body(PATH + "?edit=" + id))));

		assertThat(bannerRow(id)).isEqualTo(before);
	}

	// ------------------------------------------------------------------
	// Harness
	// ------------------------------------------------------------------

	@Test
	void theListAndTheFormShowLegacysPlatformAndActionLabelsInItsOrder() {
		// banners/page.php:95, :140-142 and :182-185: the label, never the stored value.
		seedBanner("external_url", "https://example.com/offer");

		String html = body(PATH + "?lang=en");
		assertThat(html).as("the list's platform badge, then its action cell before the stored value")
				.containsPattern("Mobile app</span></td>\\s*<td>\\s*External URL\\s*"
						+ "<span class=\"badge badge-gray\">https://example.com/offer</span>");
		assertThat(optionTexts(html, "banner_platform")).containsExactly("Desktop & mobile", "Desktop app", "Mobile app");
		assertThat(optionTexts(html, "banner_action_type"))
				.containsExactly("None", "External URL", "WhatsApp", "In-app screen");
	}

	private static List<String> optionTexts(String html, String selectId) {
		Matcher select = Pattern.compile("(?s)<select[^>]*\\bid=\"" + selectId + "\"[^>]*>(.*?)</select>").matcher(html);
		assertThat(select.find()).as("the %s select", selectId).isTrue();
		return Pattern.compile("(?s)<option[^>]*>(.*?)</option>").matcher(select.group(1)).results()
				.map(option -> option.group(1).trim().replace("&amp;", "&")).toList();
	}

	private void dialCode(String code, int phoneLength, int sortOrder) {
		this.jdbc.update("INSERT INTO phone_countries (country_code, name_ar, name_en, phone_length,"
				+ " is_active, sort_order) VALUES (?, ?, ?, ?, 1, ?)",
				code, "دولة " + code, "Country " + code, phoneLength, sortOrder);
	}

	private long seedBanner(String actionType, String actionValue) {
		this.jdbc.update("INSERT INTO banners (image_url, is_active, sort_order, app_platform,"
				+ " title_ar, title_en, description_ar, button_label_en, button_action_type,"
				+ " button_action_value) VALUES ('/uploads/banners/offer.png', 1, 2, 'mobile',"
				+ " 'عرض الصيف', 'Summer offer', 'وصف', 'Chat', ?, ?)", actionType, actionValue);
		return this.jdbc.queryForObject("SELECT MAX(id) FROM banners", Long.class);
	}

	/** Every column an edit writes. */
	private Map<String, Object> bannerRow(long id) {
		return new LinkedHashMap<>(this.jdbc.queryForMap(
				"SELECT image_url, is_active, sort_order, app_platform, title_ar, title_en,"
						+ " description_ar, description_en, button_label_ar, button_label_en,"
						+ " button_action_type, button_action_value FROM banners WHERE id = ?",
				id));
	}

	/**
	 * A refused post also redirects and leaves the row as it was, so an
	 * unchanged-row assertion alone would pass on a save that never ran.
	 */
	private void assertSaved(ResponseEntity<String> response) {
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString()
				.as("accepted, not refused with an error")
				.endsWith(PATH);
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_admin_audit_events WHERE target_type = 'BANNER'",
				Integer.class))
				.as("and audited, which only a completed write is")
				.isEqualTo(1);
	}

	/**
	 * The edit form's fields as a browser submits them: text-like inputs with
	 * their values, textareas with their content, a checkbox only when ticked,
	 * and each select's selected option or else its first. The file input and
	 * the CSRF field are left to the post.
	 */
	private static Map<String, String> formFields(String html) {
		int marker = html.indexOf("name=\"action\" value=\"edit\"");
		assertThat(marker).as("the edit form renders").isPositive();
		String form = html.substring(html.lastIndexOf("<form", marker), html.indexOf("</form>", marker));

		Map<String, String> fields = new LinkedHashMap<>();
		Matcher inputs = Pattern.compile("<input\\b([^>]*)>").matcher(form);
		while (inputs.find()) {
			String attributes = inputs.group(1);
			String name = attribute(attributes, "name");
			String type = attribute(attributes, "type");
			if (name == null || name.contains("_csrf") || "file".equals(type)) {
				continue;
			}
			String value = attribute(attributes, "value");
			if ("checkbox".equals(type)) {
				if (hasAttribute(attributes, "checked")) {
					fields.put(name, value == null ? "on" : value);
				}
				continue;
			}
			fields.put(name, value == null ? "" : value);
		}
		Matcher textareas = Pattern.compile("<textarea\\b([^>]*)>(.*?)</textarea>", Pattern.DOTALL).matcher(form);
		while (textareas.find()) {
			fields.put(attribute(textareas.group(1), "name"), unescape(textareas.group(2)));
		}
		Matcher selects = Pattern.compile("<select\\b([^>]*)>(.*?)</select>", Pattern.DOTALL).matcher(form);
		while (selects.find()) {
			String first = null;
			String chosen = null;
			Matcher options = Pattern.compile("<option\\b([^>]*)>").matcher(selects.group(2));
			while (options.find()) {
				String value = attribute(options.group(1), "value");
				value = value == null ? "" : value;
				if (first == null) {
					first = value;
				}
				if (hasAttribute(options.group(1), "selected")) {
					chosen = value;
				}
			}
			fields.put(attribute(selects.group(1), "name"), chosen != null ? chosen : first == null ? "" : first);
		}
		return fields;
	}

	private static String attribute(String attributes, String name) {
		Matcher matcher = Pattern.compile("(?:^|\\s)" + name + "=\"([^\"]*)\"").matcher(attributes);
		return matcher.find() ? unescape(matcher.group(1)) : null;
	}

	private static boolean hasAttribute(String attributes, String name) {
		return Pattern.compile("(?:^|\\s)" + name + "(?:\\s|=|$)").matcher(attributes).find();
	}

	private static String unescape(String text) {
		return text.replace("&quot;", "\"").replace("&#34;", "\"").replace("&#39;", "'")
				.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
	}

	/**
	 * Posts the form as a browser posts a multipart form: each field a part
	 * with no content type, its value as UTF-8 bytes, and the file input left
	 * empty still sent, as a part with a blank filename. Built by hand for the
	 * same reason the guide-videos harness builds its body by hand: a client
	 * converter's default charset would test the client, not the server.
	 */
	private ResponseEntity<String> postMultipart(Map<String, String> fields) {
		String boundary = "----WorkinBannerBoundary" + System.nanoTime();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Csrf csrf = page(PATH, this.cookie).csrf();
		Map<String, String> all = new LinkedHashMap<>(fields);
		all.put(csrf.name(), csrf.value());
		all.forEach((name, value) -> part(out, boundary,
				"Content-Disposition: form-data; name=\"" + name + "\"",
				(value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
		part(out, boundary, "Content-Disposition: form-data; name=\"image\"; filename=\"\"\r\n"
				+ "Content-Type: application/octet-stream", new byte[0]);
		out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary));
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + this.cookie);
		return this.restTemplate.exchange(PATH, HttpMethod.POST,
				new HttpEntity<>(out.toByteArray(), headers), String.class);
	}

	private static void part(ByteArrayOutputStream out, String boundary, String partHeaders, byte[] content) {
		out.writeBytes(("--" + boundary + "\r\n" + partHeaders + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		out.writeBytes(content);
		out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
	}

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

	/** The login form, as a browser posts it: UTF-8 bytes, urlencoded. */
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
