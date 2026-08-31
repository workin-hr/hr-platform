package com.workin.legacy.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * Wave 13.5's four reference endpoints at the request level.
 *
 * <p>The two public ones are exercised <b>without a token</b>, which is the
 * assertion: a client needs the dial-code list and the pre-login copy before it
 * can authenticate, so a 401 on either would be a regression it could not work
 * around.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyReferenceEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String COUNTRIES = "/apis/api/phone_countries/list.php";
	private static final String CONTENT = "/apis/api/app_content/one.php";
	private static final String BANNERS = "/apis/api/banners/list.php";
	private static final String FAQS = "/apis/api/faqs/list.php";

	private static final long COMPANY = 24001L;
	private static final long EMPLOYEE = 240011L;
	private static final long BRANCH = 24011L;

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
			throw new IllegalStateException("could not prepare the reference fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	// ---------------- legacy router path form ----------------

	/**
	 * The URL form the Flutter clients actually use.
	 *
	 * <p>Every controller here maps a <em>file</em> path
	 * ({@code .../list.php}) because the endpoint inventory was built from the
	 * PHP source tree. No client calls that. {@code api_constants.dart} joins
	 * {@code https://workin.company/apis/api/} with {@code phone_countries/list},
	 * and none of its 266 endpoint constants ends in {@code .php}. Production
	 * agrees: {@code /apis/api/configs/get} answers 200 and
	 * {@code /apis/api/configs/get.php} answers 500, because the direct file
	 * assumes a bootstrap only {@code index.php} performs.
	 *
	 * <p>Measured before {@link com.workin.legacy.wire.LegacyPhpRouterFilter}
	 * existed: Java answered the client's form for 9 of the 190 endpoints the
	 * clients call. This is the case that pins the other 181.
	 */
	@Test
	void theRouterPathFormAnswersIdenticallyToTheFilePath() {
		ResponseEntity<Map> viaFile = restTemplate.getForEntity(COUNTRIES, Map.class);
		ResponseEntity<Map> viaRoute = restTemplate.getForEntity(
				COUNTRIES.substring(0, COUNTRIES.length() - ".php".length()), Map.class);

		assertThat(viaRoute.getStatusCode())
				.as("the URL the clients actually call must be served")
				.isEqualTo(viaFile.getStatusCode());
		assertThat(viaRoute.getBody())
				.as("and must return the same payload, not merely the same status")
				.isEqualTo(viaFile.getBody());
	}

	/**
	 * The rewrite must not fire twice. A path already naming the file is left
	 * alone -- otherwise it would become {@code list.php.php} and 404.
	 */
	@Test
	void theFilePathFormStillWorks() {
		assertThat(restTemplate.getForEntity(COUNTRIES, Map.class).getStatusCode().value()).isEqualTo(200);
	}

	/**
	 * A public route stays public through the rewrite. This is the ordering
	 * the filter exists to get right: the security permit-list is written in
	 * {@code .php} paths, so if authorization saw the client's form it would
	 * fall through to {@code anyRequest().authenticated()} and 401 an endpoint
	 * legacy serves anonymously.
	 */
	@Test
	void aPublicRouteIsStillPublicWhenCalledInTheClientForm() {
		ResponseEntity<Map> response = restTemplate.getForEntity(
				"/apis/api/phone_countries/list", Map.class);

		assertThat(response.getStatusCode().value())
				.as("no Authorization header, and legacy calls no requireAuth() here")
				.isEqualTo(200);
		assertThat(response.getBody().get("success")).isEqualTo(true);
	}

	/**
	 * Legacy's router reads the two segments after {@code api} and <b>ignores
	 * the rest</b>, so a deeper path serves the same endpoint.
	 *
	 * <p>This test previously asserted the opposite — that a deeper path is
	 * refused — which was wrong, and wrong against this repository's own
	 * description of the router. Verified against the running PHP:
	 * {@code /apis/api/phone_countries/list/extra} answers 200, as does
	 * {@code .../list/a/b}. A client appending anything to a route would have
	 * received a 401 or 404 from Java where PHP serves the endpoint.
	 */
	@Test
	void aDeeperPathResolvesToTheSameEndpointAsLegacy() {
		ResponseEntity<Map> plain = restTemplate.getForEntity("/apis/api/phone_countries/list", Map.class);
		ResponseEntity<Map> deeper = restTemplate.getForEntity(
				"/apis/api/phone_countries/list/extra", Map.class);

		assertThat(deeper.getStatusCode())
				.as("legacy ignores the trailing segments rather than refusing them")
				.isEqualTo(plain.getStatusCode());
		assertThat(deeper.getBody()).isEqualTo(plain.getBody());
	}

	/**
	 * The query string has to survive the rewrite -- legacy reads its
	 * parameters from it, so losing it would turn a filtered request into an
	 * unfiltered one rather than into an error.
	 */
	@Test
	void theQueryStringSurvivesTheRewrite() {
		ResponseEntity<Map> viaRoute = restTemplate.getForEntity(
				"/apis/api/phone_countries/list?lang=en", Map.class);
		ResponseEntity<Map> viaFile = restTemplate.getForEntity(
				"/apis/api/phone_countries/list.php?lang=en", Map.class);

		assertThat(viaRoute.getStatusCode()).isEqualTo(viaFile.getStatusCode());
		assertThat(viaRoute.getBody()).isEqualTo(viaFile.getBody());
	}

	// ---------------- phone_countries ----------------

	@Test
	@SuppressWarnings("unchecked")
	void phoneCountriesAreServedWithoutATokenAndCarryTheFullPublicRow() {
		List<Map<String, Object>> rows = (List<Map<String, Object>>) data(get(COUNTRIES, null, null));

		assertThat(rows).isNotEmpty();
		Map<String, Object> first = rows.get(0);
		assertThat(first.keySet()).containsExactly(
				"id", "country_code", "name", "name_ar", "name_en", "flag_emoji",
				"phone_length", "phone_prefixes", "example_number", "sort_order", "is_active");
		assertThat(first).containsEntry("country_code", "+20").containsEntry("phone_length", 11);
		assertThat((List<String>) first.get("phone_prefixes")).containsExactly("010", "011", "012", "015");
		// First prefix padded with zeroes to phone_length.
		assertThat(first).containsEntry("example_number", "01000000000");
	}

	/**
	 * The name follows the <b>raw</b> {@code Accept-Language} header with
	 * {@code str_starts_with(..., 'en')} -- not {@code app_locale()}. So
	 * {@code ar,en;q=0.8} is Arabic here even though it names English second.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void theCountryNameFollowsTheRawHeaderPrefixNotTheSharedLocaleRule() {
		List<Map<String, Object>> english =
				(List<Map<String, Object>>) data(get(COUNTRIES, null, "en-US,en;q=0.9"));
		List<Map<String, Object>> arabicFirst =
				(List<Map<String, Object>>) data(get(COUNTRIES, null, "ar,en;q=0.8"));
		List<Map<String, Object>> noHeader = (List<Map<String, Object>>) data(get(COUNTRIES, null, null));

		assertThat(english.get(0)).containsEntry("name", "Egypt");
		assertThat(arabicFirst.get(0))
				.as("starts with 'ar', so Arabic -- app_locale() would also say Arabic here, "
						+ "but for a different reason")
				.containsEntry("name", "مصر");
		assertThat(noHeader.get(0)).as("absent header defaults to Arabic, not English")
				.containsEntry("name", "مصر");
	}

	// ---------------- app_content ----------------

	@Test
	@SuppressWarnings("unchecked")
	void appContentServesTheLocalizedValueWithoutAToken() {
		Map<String, Object> arabic =
				(Map<String, Object>) data(get(CONTENT + "?content_key=terms", null, "ar"));
		Map<String, Object> english =
				(Map<String, Object>) data(get(CONTENT + "?content_key=terms", null, "en"));

		assertThat(arabic).containsExactly(Map.entry("value", "الشروط"));
		assertThat(english).containsExactly(Map.entry("value", "Terms"));
	}

	/**
	 * <b>The two public endpoints in this wave disagree about the default
	 * language, and both are correct.</b>
	 *
	 * <p>{@code app_content/one.php} goes through {@code app_locale()}, which
	 * looks for {@code \bar\b} in the header and returns {@code 'en'} when it
	 * finds nothing -- so a client sending no header gets <b>English</b>.
	 * {@code phone_countries/list.php} bypasses that helper entirely and
	 * defaults its own {@code $lang} to the literal {@code 'ar'} -- so the same
	 * headerless client gets <b>Arabic</b>.
	 *
	 * <p>Asserted side by side because either one alone looks like a bug in the
	 * other's light, and "harmonising" them would change what a real client
	 * renders on its first screen.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void theTwoPublicEndpointsDefaultToOppositeLanguagesWithNoHeader() {
		Map<String, Object> content =
				(Map<String, Object>) data(get(CONTENT + "?content_key=terms", null, null));
		List<Map<String, Object>> countries = (List<Map<String, Object>>) data(get(COUNTRIES, null, null));

		assertThat(content).as("app_locale() falls through to English").containsEntry("value", "Terms");
		assertThat(countries.get(0)).as("phone_countries defaults to Arabic")
				.containsEntry("name", "مصر");
	}

	/** {@code if (!$content_key)} is falsy, so "0" is rejected as missing. */
	@Test
	void aFalsyContentKeyIsRejectedAsMissingRatherThanLookedUp() {
		assertThat(get(CONTENT, null, null).getStatusCode().value()).isEqualTo(400);
		assertThat(get(CONTENT + "?content_key=", null, null).getStatusCode().value()).isEqualTo(400);
		assertThat(get(CONTENT + "?content_key=0", null, null).getStatusCode().value())
				.as("PHP's falsy guard rejects the string \"0\" before any query runs")
				.isEqualTo(400);
	}

	@Test
	void anUnknownContentKeyIsFourZeroFourUnlikeConfigsWhichAnswersNull() {
		assertThat(get(CONTENT + "?content_key=no_such_key", null, null).getStatusCode().value())
				.isEqualTo(404);
	}

	// ---------------- banners ----------------

	@Test
	void bannersRequireAuthenticationUnlikeTheTwoPublicEndpoints() {
		assertThat(get(BANNERS, null, null).getStatusCode().value()).isEqualTo(401);
		assertThat(get(FAQS, null, null).getStatusCode().value()).isEqualTo(401);
	}

	@Test
	@SuppressWarnings("unchecked")
	void bannersFilterByPlatformAndAnUnknownPlatformWidensRatherThanNarrows() {
		List<Map<String, Object>> all = (List<Map<String, Object>>) data(get(BANNERS, token(), null));
		List<Map<String, Object>> desktop =
				(List<Map<String, Object>>) data(get(BANNERS + "?platform=desktop", token(), null));
		List<Map<String, Object>> mobile =
				(List<Map<String, Object>>) data(get(BANNERS + "?platform=mobile", token(), null));
		List<Map<String, Object>> unknown =
				(List<Map<String, Object>>) data(get(BANNERS + "?platform=web", token(), null));

		assertThat(all).hasSize(3);
		assertThat(desktop).as("desktop + both").hasSize(2);
		assertThat(mobile).as("mobile + both").hasSize(2);
		assertThat(unknown)
				.as("an unrecognised platform leaves the filter off entirely, so it returns MORE than a "
						+ "recognised one -- the if/elseif has no else")
				.hasSize(3);
		// An inactive banner never appears under any platform.
		assertThat(all).noneMatch(row -> "Hidden".equals(row.get("title_en")));
	}

	@Test
	@SuppressWarnings("unchecked")
	void bannerRowsCarryTheSelectedColumnsInTheSelectedOrder() {
		List<Map<String, Object>> rows = (List<Map<String, Object>>) data(get(BANNERS, token(), null));

		assertThat(rows.get(0).keySet()).containsExactly(
				"id", "image_url", "app_platform", "title_ar", "title_en",
				"description_ar", "description_en", "button_label_ar", "button_label_en",
				"button_action_type", "button_action_value");
	}

	// ---------------- faqs ----------------

	@Test
	@SuppressWarnings("unchecked")
	void faqsAreGroupedUnderACategoriesKeyAndLocalized() {
		Map<String, Object> body = (Map<String, Object>) data(get(FAQS, token(), null));
		List<Map<String, Object>> categories = (List<Map<String, Object>>) body.get("categories");

		assertThat(body.keySet()).as("wrapped, unlike banners which returns rows bare")
				.containsExactly("categories");
		// Two active categories have items; the inactive third and the category
		// whose only item is inactive are both absent.
		assertThat(categories).hasSize(2);
		assertThat(categories).extracting(category -> category.get("name_en"))
				.containsExactly("General", "Desktop only");
		assertThat(categories.get(0)).containsEntry("name", "General")
				.as("no Accept-Language header, so app_locale() resolves English")
				.containsEntry("name_en", "General");

		List<Map<String, Object>> items = (List<Map<String, Object>>) categories.get(0).get("items");
		assertThat(items).as("the inactive item is excluded").hasSize(2);
		assertThat(items.get(0))
				.containsEntry("question", "Desktop question")
				.containsEntry("question_ar", "سؤال ديسك توب")
				.containsEntry("question_en", "Desktop question");
	}

	/**
	 * A category whose every item is filtered out <b>disappears</b> rather than
	 * appearing with an empty list -- so the set of section headers a client
	 * renders differs per platform.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void aCategoryWithNoMatchingItemsIsOmittedEntirely() {
		Map<String, Object> mobile = (Map<String, Object>) data(get(FAQS + "?platform=mobile", token(), null));
		List<Map<String, Object>> categories = (List<Map<String, Object>>) mobile.get("categories");

		assertThat(categories)
				.as("the only mobile item belongs to the 'General' category, and the desktop-only "
						+ "category vanishes rather than arriving empty")
				.hasSize(1);
		assertThat((List<Map<String, Object>>) categories.get(0).get("items")).hasSize(1);
	}

	@Test
	void everyReferenceEndpointRejectsANonGetMethod() {
		for (String route : List.of(COUNTRIES, CONTENT, BANNERS, FAQS)) {
			ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
					URI.create(restTemplate.getRootUri() + route), HttpMethod.POST,
					new HttpEntity<>(new HttpHeaders()),
					new ParameterizedTypeReference<Map<String, Object>>() { });
			assertThat(response.getStatusCode().value())
					.as("%s must answer 405 before authenticating, as PHP checks the method first", route)
					.isEqualTo(405);
		}
	}

	// ---------------- fixture ----------------

	private static Object data(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		assertThat(response.getBody()).containsEntry("success", true);
		return response.getBody().get("data");
	}

	private ResponseEntity<Map<String, Object>> get(String path, String token, String acceptLanguage) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		if (acceptLanguage != null) {
			headers.set("Accept-Language", acceptLanguage);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.GET,
				new HttpEntity<>(headers), new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private String token() {
		return jwtService.issueAccessToken(EMPLOYEE, EMPLOYEE, COMPANY, "test-session",
				Map.of("role", "employee", "token_version", 1L));
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (" + COMPANY + ", 'Reference Co', '+201000024001', 'active', '2019-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH + ", " + COMPANY + ", 'Main', 1, '2019-03-01 10:00:00')");
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
					+ " last_name, phone, role, is_active, created_at) VALUES (" + EMPLOYEE + ", " + COMPANY
					+ ", " + BRANCH + ", '2401', 'Emp', 'One', '+201000240011', 'employee', 1,"
					+ " '2019-04-01 08:00:00')");

			st.execute("INSERT INTO phone_countries (id, country_code, name_ar, name_en, flag_emoji,"
					+ " phone_length, phone_prefixes, is_active, sort_order) VALUES"
					+ " (1, '+20', 'مصر', 'Egypt', '🇪🇬', 11, '[\"010\",\"011\",\"012\",\"015\"]', 1, 1),"
					+ " (2, '+966', 'السعودية', 'Saudi Arabia', '🇸🇦', 10, '[\"05\"]', 1, 2),"
					+ " (3, '+218', 'ليبيا', 'Libya', '🇱🇾', 10, '[\"091\"]', 0, 3)");

			st.execute("INSERT INTO app_content (id, content_key, content_value_ar, content_value_en)"
					+ " VALUES (1, 'terms', 'الشروط', 'Terms')");

			st.execute("INSERT INTO banners (id, image_url, is_active, sort_order, app_platform,"
					+ " title_ar, title_en, button_action_type) VALUES"
					+ " (1, 'a.png', 1, 1, 'both', 'كل', 'Both', 'none'),"
					+ " (2, 'b.png', 1, 2, 'desktop', 'مكتب', 'Desktop', 'none'),"
					+ " (3, 'c.png', 1, 3, 'mobile', 'موبايل', 'Mobile', 'none'),"
					+ " (4, 'd.png', 0, 4, 'both', 'مخفي', 'Hidden', 'none')");

			st.execute("INSERT INTO faq_categories (id, name_ar, name_en, sort_order, is_active) VALUES"
					+ " (1, 'عام', 'General', 1, 1),"
					+ " (2, 'مكتب فقط', 'Desktop only', 2, 1),"
					+ " (3, 'معطل', 'Disabled', 3, 0)");
			st.execute("INSERT INTO faq_items (id, faq_category_id, question_ar, question_en, answer_ar,"
					+ " answer_en, app_platform, sort_order, is_active) VALUES"
					+ " (1, 1, 'سؤال ديسك توب', 'Desktop question', 'ج', 'A', 'desktop', 1, 1),"
					+ " (2, 1, 'سؤال موبايل', 'Mobile question', 'ج', 'A', 'mobile', 2, 1),"
					+ " (3, 2, 'سؤال ٣', 'Q3', 'ج', 'A', 'desktop', 1, 1),"
					+ " (4, 3, 'مخفي', 'Hidden', 'ج', 'A', 'both', 1, 1),"
					+ " (5, 1, 'غير نشط', 'Inactive', 'ج', 'A', 'both', 3, 0)");
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

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream in = LegacyReferenceEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
