package com.workin.legacy.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * Wave 13.3's eight endpoints. Ordered, because the write tests build on each
 * other exactly as a client would.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacySettingsEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String LIST = "/apis/api/company_settings/list.php";
	private static final String ONE = "/apis/api/company_settings/one.php";
	private static final String OPTIONS = "/apis/api/company_settings/options.php";
	private static final String CREATE = "/apis/api/company_settings/create.php";
	private static final String UPDATE = "/apis/api/company_settings/update.php";
	private static final String DELETE = "/apis/api/company_settings/delete.php";
	private static final String DEFINITIONS = "/apis/api/setting_definitions/list.php";
	private static final String ALLOWED = "/apis/api/setting_allowed_values/list.php";

	private static final long COMPANY = 26001L;
	private static final long ADMIN = 260011L;
	private static final long NO_PERMISSION = 260012L;
	private static final long BRANCH = 26011L;

	/** Single-valued, not required. */
	private static final long DEF_THEME = 1L;
	/** Multi-valued, not required. */
	private static final long DEF_MODULES = 2L;
	/** Single-valued and required -- cannot be deleted. */
	private static final long DEF_CURRENCY = 3L;

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
			throw new IllegalStateException("could not prepare the settings fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	// ---------------- authority ----------------

	@Test
	@Order(1)
	void theAllowedValuesCatalogueIsPublicWhileTheDefinitionsCatalogueIsNot() {
		assertThat(send(ALLOWED + "?setting_definition_id=" + DEF_THEME, HttpMethod.GET, null, null)
				.getStatusCode().value())
				.as("no requireAuth() in legacy at all")
				.isEqualTo(200);
		assertThat(send(DEFINITIONS, HttpMethod.GET, null, null).getStatusCode().value())
				.as("the definitions that name those same values need a role")
				.isEqualTo(401);
	}

	@Test
	@Order(2)
	void theCompanySettingsRoutesNeedTheCanCompanySettingsPermission() {
		assertThat(send(LIST, HttpMethod.GET, token(NO_PERMISSION), null).getStatusCode().value())
				.isEqualTo(403);
		assertThat(send(LIST, HttpMethod.GET, token(ADMIN), null).getStatusCode().value())
				.isEqualTo(200);
	}

	/**
	 * The permission refusal must carry legacy's {@code forbidden} message, not
	 * the platform's {@code error.forbidden} key. {@code LegacyMessages} loads
	 * only {@code legacy/lang/*.properties}, which has no {@code error.}
	 * namespace, and its lookup falls back to returning the key -- so throwing
	 * the platform exception put the literal string on the wire.
	 */
	@Test
	@Order(3)
	void thePermissionRefusalCarriesLegacysForbiddenMessageNotThePlatformKey() {
		ResponseEntity<Map<String, Object>> response =
				send(LIST, HttpMethod.GET, token(NO_PERMISSION), null);

		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody()).containsEntry("message", "Forbidden");
		assertThat(response.getBody().get("message")).asString()
				.as("never the raw key")
				.doesNotContain("error.");
	}

	/**
	 * {@code json_decode(..., true)} turns a JSON <em>object</em> into an
	 * associative array, so {@code is_array()} is true and PHP iterates its
	 * values. Treating only a JSON array as array-like would stringify the map
	 * to {@code "Array"} and reject a value legacy accepts.
	 */
	@Test
	@Order(4)
	@SuppressWarnings("unchecked")
	void aJsonObjectOfValuesIsTreatedAsAnArrayJustAsPhpDoes() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, token(ADMIN),
				"{\"setting_key\":\"theme\",\"values\":{\"any_key\":\"light\"}}");

		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
		Map<String, Object> item = (Map<String, Object>) response.getBody().get("data");
		assertThat((List<Map<String, Object>>) item.get("selected"))
				.extracting(row -> row.get("value")).containsExactly("light");

		// Leave the fixture as the later ordered tests expect it.
		send(DELETE + "?setting_key=theme&setting_definition_id=" + DEF_THEME, HttpMethod.DELETE,
				token(ADMIN), null);
	}

	@Test
	@Order(5)
	void everyRouteChecksItsMethodBeforeAuthenticating() {
		assertThat(send(LIST, HttpMethod.POST, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(CREATE, HttpMethod.GET, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(UPDATE, HttpMethod.POST, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(DELETE, HttpMethod.GET, null, null).getStatusCode().value()).isEqualTo(405);
	}

	// ---------------- read shapes ----------------

	@Test
	@Order(6)
	@SuppressWarnings("unchecked")
	void listCarriesEveryDefinitionWithTheSeventeenKeyItemShape() {
		List<Map<String, Object>> rows = (List<Map<String, Object>>) data(
				send(LIST, HttpMethod.GET, token(ADMIN), null));

		assertThat(rows).hasSize(3);
		assertThat(rows.get(0).keySet()).containsExactly(
				"setting_definition_id", "company_setting_id", "setting_key", "label",
				"label_ar", "label_en", "description_ar", "description_en", "description",
				"icon_data", "is_multi", "is_required", "sort_order", "options", "selected",
				"updated_at");
		// Nothing set yet: a zero id and a null timestamp side by side.
		assertThat(rows.get(0)).containsEntry("company_setting_id", 0)
				.containsEntry("updated_at", null);
		assertThat((List<Object>) rows.get(0).get("selected")).isEmpty();
		assertThat((List<Object>) rows.get(0).get("options")).hasSize(2);
	}

	/** The label falls back across languages before it reaches the setting key. */
	@Test
	@Order(7)
	@SuppressWarnings("unchecked")
	void labelsFallBackToTheOtherLanguageThenToTheKey() {
		List<Map<String, Object>> arabic = (List<Map<String, Object>>) data(
				send(LIST, HttpMethod.GET, token(ADMIN), null, "ar"));

		Map<String, Object> theme = arabic.stream()
				.filter(row -> "theme".equals(row.get("setting_key"))).findFirst().orElseThrow();
		Map<String, Object> modules = arabic.stream()
				.filter(row -> "modules".equals(row.get("setting_key"))).findFirst().orElseThrow();

		assertThat(theme).containsEntry("label", "المظهر");
		assertThat(modules)
				.as("no Arabic label, so the English one is used before the key")
				.containsEntry("label", "Modules");
	}

	/**
	 * {@code is_array($raw_values)} is the branch, and a <b>scalar</b> takes the
	 * else branch to be wrapped into a one-element list. So
	 * {@code "values": "dark"} stores {@code dark} rather than normalizing to
	 * nothing.
	 */
	@Test
	@Order(8)
	@SuppressWarnings("unchecked")
	void aScalarValueIsWrappedIntoAOneElementListJustAsPhpDoes() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, token(ADMIN),
				"{\"setting_key\":\"theme\",\"values\":\"dark\"}");

		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
		Map<String, Object> item = (Map<String, Object>) response.getBody().get("data");
		assertThat((List<Map<String, Object>>) item.get("selected"))
				.as("a bare string is one value, not zero")
				.extracting(row -> row.get("value")).containsExactly("dark");

		// Leave the fixture as the later ordered tests expect it.
		send(DELETE + "?setting_definition_id=" + DEF_THEME, HttpMethod.DELETE, token(ADMIN), null);
	}

	@Test
	@Order(9)
	@SuppressWarnings("unchecked")
	void optionsAnswersTwoShapesAndEchoesAnUnknownKeyRatherThanTruncating() {
		Map<String, Object> single = (Map<String, Object>) data(
				send(OPTIONS + "?setting_key=theme", HttpMethod.GET, token(ADMIN), null));
		assertThat(single.keySet()).containsExactly("setting_key", "options");
		assertThat((List<Object>) single.get("options")).hasSize(2);

		Map<String, Object> unknown = (Map<String, Object>) data(
				send(OPTIONS + "?setting_key=nope", HttpMethod.GET, token(ADMIN), null));
		assertThat(unknown).containsEntry("setting_key", "nope");
		assertThat((List<Object>) unknown.get("options"))
				.as("an unknown key is an empty option list, not a 404")
				.isEmpty();

		Map<String, Object> all = (Map<String, Object>) data(
				send(OPTIONS, HttpMethod.GET, token(ADMIN), null));
		assertThat(all.keySet())
				.as("the map form is ordered by setting_key, the only place definitions are "
						+ "ordered alphabetically rather than by sort_order")
				.containsExactly("currency", "modules", "theme");
	}

	// ---------------- writes ----------------

	@Test
	@Order(10)
	@SuppressWarnings("unchecked")
	void createStoresTheSelectionAndAnswersTwoZeroOne() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, token(ADMIN),
				"{\"setting_definition_id\":" + DEF_THEME + ",\"values\":[\"dark\"]}");

		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
		Map<String, Object> item = (Map<String, Object>) response.getBody().get("data");
		assertThat((List<Map<String, Object>>) item.get("selected"))
				.extracting(row -> row.get("value")).containsExactly("dark");
		assertThat(((Number) item.get("company_setting_id")).longValue()).isPositive();
	}

	@Test
	@Order(11)
	void creatingTheSameSettingTwiceIsAlreadyExistsRatherThanAnUpsert() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, token(ADMIN),
				"{\"setting_definition_id\":" + DEF_THEME + ",\"values\":[\"light\"]}");
		assertThat(response.getStatusCode().value()).isEqualTo(400);
	}

	/**
	 * A value outside the definition's allowed list is rejected with 400 -- not
	 * the 500 the surrounding {@code catch (Throwable)} would produce, because
	 * PHP's {@code fail()} exits rather than throwing. The already-inserted
	 * parent row must not survive.
	 */
	@Test
	@Order(12)
	@SuppressWarnings("unchecked")
	void aDisallowedValueIsFourHundredAndRollsBackTheParentRow() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, token(ADMIN),
				"{\"setting_definition_id\":" + DEF_MODULES + ",\"values\":[\"payroll\",\"nope\"]}");

		assertThat(response.getStatusCode().value())
				.as("a validation failure, not the catch block's 500")
				.isEqualTo(400);

		// Asserted against the table, not through list.php: the endpoint reports
		// 0 for "no row" and for "row exists but has no values" alike, so it
		// cannot tell a rollback from a partially-written setting.
		assertThat(countRows("SELECT COUNT(*) FROM company_settings WHERE company_id=" + COMPANY
				+ " AND setting_definition_id=" + DEF_MODULES))
				.as("the company_settings row inserted before validation must be rolled back")
				.isZero();
		assertThat(countRows("SELECT COUNT(*) FROM company_setting_values"))
				.as("and no child value may survive either")
				.isEqualTo(1);
	}

	@Test
	@Order(13)
	void aSingleValuedDefinitionRejectsMoreThanOneValue() {
		assertThat(send(CREATE, HttpMethod.POST, token(ADMIN),
				"{\"setting_definition_id\":" + DEF_CURRENCY + ",\"values\":[\"egp\",\"usd\"]}")
				.getStatusCode().value()).isEqualTo(400);
	}

	@Test
	@Order(14)
	void aRequiredDefinitionRejectsAnEmptyValueList() {
		assertThat(send(CREATE, HttpMethod.POST, token(ADMIN),
				"{\"setting_definition_id\":" + DEF_CURRENCY + ",\"values\":[]}")
				.getStatusCode().value()).isEqualTo(400);
	}

	@Test
	@Order(15)
	void aMissingValuesKeyIsFieldRequired() {
		assertThat(send(CREATE, HttpMethod.POST, token(ADMIN),
				"{\"setting_definition_id\":" + DEF_MODULES + "}")
				.getStatusCode().value()).isEqualTo(400);
	}

	@Test
	@Order(16)
	@SuppressWarnings("unchecked")
	void updateUpsertsAndReplacesTheWholeSelection() {
		Map<String, Object> item = (Map<String, Object>) data(send(UPDATE, HttpMethod.PUT, token(ADMIN),
				"{\"setting_key\":\"modules\",\"values\":[\"payroll\",\"attendance\"]}"));

		assertThat((List<Map<String, Object>>) item.get("selected"))
				.extracting(row -> row.get("value"))
				.containsExactly("attendance", "payroll");

		Map<String, Object> replaced = (Map<String, Object>) data(send(UPDATE, HttpMethod.PUT,
				token(ADMIN), "{\"setting_key\":\"modules\",\"values\":[\"payroll\"]}"));
		assertThat((List<Map<String, Object>>) replaced.get("selected"))
				.as("the previous selection is replaced wholesale, not merged")
				.extracting(row -> row.get("value")).containsExactly("payroll");
	}

	/**
	 * {@code update} with an empty list <b>deletes the whole setting</b>, while
	 * {@code create} with an empty list would have inserted a row with no
	 * values. The two endpoints reach opposite end states from the same input.
	 */
	@Test
	@Order(17)
	@SuppressWarnings("unchecked")
	void updateWithAnEmptyListDeletesTheSettingEntirely() {
		Map<String, Object> item = (Map<String, Object>) data(send(UPDATE, HttpMethod.PUT, token(ADMIN),
				"{\"setting_key\":\"modules\",\"values\":[]}"));

		assertThat(item).containsEntry("company_setting_id", 0);
		assertThat((List<Object>) item.get("selected")).isEmpty();
	}

	/**
	 * <b>{@code create} and {@code one} report different ids for the same
	 * row.</b> A setting created with an empty value list has a real
	 * {@code company_settings} row, and {@code create.php}'s
	 * {@code build_company_setting_item()} looks that row up directly -- while
	 * {@code one.php} derives the id from the selection join, which returns
	 * nothing, and answers 0.
	 *
	 * <p>So a client creates a setting, gets an id back, re-reads it, and the
	 * id is 0 without anything having been deleted. Legacy's, and pinned here
	 * because a single shared item-builder would silently unify the two.
	 */
	@Test
	@Order(18)
	@SuppressWarnings("unchecked")
	void createAndOneDisagreeAboutTheIdOfAValuelessSetting() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST,
				token(ADMIN), "{\"setting_definition_id\":" + DEF_MODULES + ",\"values\":[]}");
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
		Map<String, Object> created = (Map<String, Object>) response.getBody().get("data");
		long createdId = ((Number) created.get("company_setting_id")).longValue();
		assertThat(createdId).as("create.php looks the parent row up directly").isPositive();

		Map<String, Object> read = (Map<String, Object>) data(send(
				ONE + "?setting_definition_id=" + DEF_MODULES, HttpMethod.GET, token(ADMIN), null));
		assertThat(read).as("one.php derives it from the join, which has no rows")
				.containsEntry("company_setting_id", 0)
				.containsEntry("updated_at", null);

		assertThat(countRows("SELECT COUNT(*) FROM company_settings WHERE id=" + createdId))
				.as("the row is really there; only the reported id differs")
				.isEqualTo(1);

		// Leave the fixture as the later tests expect it.
		data(send(UPDATE, HttpMethod.PUT, token(ADMIN),
				"{\"setting_key\":\"modules\",\"values\":[]}"));
	}

	@Test
	@Order(19)
	void deletingARequiredSettingIsRejected() {
		data(send(UPDATE, HttpMethod.PUT, token(ADMIN),
				"{\"setting_definition_id\":" + DEF_CURRENCY + ",\"values\":[\"egp\"]}"));

		assertThat(send(DELETE + "?setting_definition_id=" + DEF_CURRENCY, HttpMethod.DELETE,
				token(ADMIN), null).getStatusCode().value())
				.as("is_required = 1 blocks deletion")
				.isEqualTo(400);
	}

	@Test
	@Order(20)
	void deletingASettingThatWasNeverSetIsOkRatherThanNotFound() {
		assertThat(send(DELETE + "?setting_definition_id=" + DEF_MODULES, HttpMethod.DELETE,
				token(ADMIN), null).getStatusCode().value())
				.isEqualTo(200);
	}

	@Test
	@Order(21)
	void deletingWithNeitherIdentifierIsFieldRequired() {
		assertThat(send(DELETE, HttpMethod.DELETE, token(ADMIN), null).getStatusCode().value())
				.isEqualTo(400);
	}

	// ---------------- catalogue endpoints ----------------

	@Test
	@Order(22)
	@SuppressWarnings("unchecked")
	void definitionsListIsPaginatedAndCarriesLabelAndDescriptionKeys() {
		ResponseEntity<Map<String, Object>> response =
				send(DEFINITIONS, HttpMethod.GET, token(ADMIN), null);
		List<Map<String, Object>> rows = (List<Map<String, Object>>) data(response);

		assertThat(rows).hasSize(3);
		assertThat(rows.get(0)).containsKeys("id", "setting_key", "label_ar", "label_en",
				"label", "description", "description_ar", "description_en", "sort_order");
		assertThat(response.getBody()).containsKey("meta");
	}

	@Test
	@Order(23)
	@SuppressWarnings("unchecked")
	void definitionsSearchMatchesKeyAndBothLabels() {
		assertThat((List<Map<String, Object>>) data(
				send(DEFINITIONS + "?search=theme", HttpMethod.GET, token(ADMIN), null))).hasSize(1);
		assertThat((List<Map<String, Object>>) data(
				send(DEFINITIONS + "?search=Modules", HttpMethod.GET, token(ADMIN), null))).hasSize(1);
		assertThat((List<Map<String, Object>>) data(
				send(DEFINITIONS + "?search=%20", HttpMethod.GET, token(ADMIN), null)))
				.as("a whitespace-only search filters nothing")
				.hasSize(3);
	}

	/**
	 * A genuine persistence failure answers legacy's {@code error_with_message}
	 * contract, not D-084's generic 500.
	 *
	 * <p>PHP wraps its writes in {@code catch (Throwable)}; validation failures
	 * never reach it, because {@code fail()} exits first. So the catch is
	 * reachable only by a real database error, and the only honest way to test
	 * it is to cause one — here a {@code BEFORE DELETE} trigger that signals.
	 */
	@Test
	@Order(24)
	void aRealDeleteFailureAnswersLegacysErrorWithMessageContract() {
		data(send(UPDATE, HttpMethod.PUT, token(ADMIN),
				"{\"setting_key\":\"theme\",\"values\":[\"dark\"]}"));
		execute("CREATE TRIGGER settings_delete_boom BEFORE DELETE ON company_settings"
				+ " FOR EACH ROW SIGNAL SQLSTATE '45000'"
				+ " SET MESSAGE_TEXT = 'forced failure for the persistence-contract test'");
		try {
			ResponseEntity<Map<String, Object>> response = send(
					DELETE + "?setting_definition_id=" + DEF_THEME, HttpMethod.DELETE, token(ADMIN), null);

			assertThat(response.getStatusCode().value())
					.as("PHP's catch answers 500 with its own keyed contract")
					.isEqualTo(500);
			assertThat(response.getBody()).containsEntry("success", false);
			assertThat(response.getBody().get("message")).asString()
					.as("the localized error_with_message text, not \"Internal server error\"")
					.isNotEqualTo("Internal server error");
		} finally {
			execute("DROP TRIGGER IF EXISTS settings_delete_boom");
			send(DELETE + "?setting_definition_id=" + DEF_THEME, HttpMethod.DELETE, token(ADMIN), null);
		}
	}

	/**
	 * The same contract for a failure in the <b>allowed-values lookup</b>, which
	 * PHP performs at {@code update.php:190} — inside the {@code try} that opens
	 * at {@code :178}, not before it. Running it outside the translation
	 * boundary would answer D-084's generic 500 for a timeout or a lost
	 * connection where legacy answers {@code error_with_message}.
	 *
	 * <p>A trigger cannot force a {@code SELECT} to fail, so the table is
	 * renamed out from under the query instead.
	 *
	 * <p>Deliberately not extended to {@code create.php} or {@code delete.php}:
	 * create's definition and existence lookups sit <em>above</em> its
	 * {@code try} at {@code :181}, and delete has no {@code try} at all, so
	 * their Java counterparts are correct outside the wrapper.
	 */
	@Test
	@Order(24)
	void aFailedAllowedValuesLookupAlsoAnswersErrorWithMessage() {
		execute("RENAME TABLE setting_allowed_values TO setting_allowed_values_hidden");
		try {
			ResponseEntity<Map<String, Object>> response = send(UPDATE, HttpMethod.PUT, token(ADMIN),
					"{\"setting_key\":\"modules\",\"values\":[\"payroll\"]}");

			assertThat(response.getStatusCode().value()).isEqualTo(500);
			assertThat(response.getBody()).containsEntry("success", false);
			assertThat(response.getBody().get("message")).asString()
					.as("PHP's error_with_message, not \"Internal server error\"")
					.isNotEqualTo("Internal server error");
		} finally {
			execute("RENAME TABLE setting_allowed_values_hidden TO setting_allowed_values");
		}
	}

	@Test
	@Order(25)
	void allowedValuesRequiresItsDefinitionIdAndFourZeroFoursAnUnknownOne() {
		assertThat(send(ALLOWED, HttpMethod.GET, null, null).getStatusCode().value())
				.as("required() rejects a missing parameter")
				.isEqualTo(400);
		assertThat(send(ALLOWED + "?setting_definition_id=999", HttpMethod.GET, null, null)
				.getStatusCode().value()).isEqualTo(404);
		assertThat(send(ALLOWED + "?setting_definition_id=abc", HttpMethod.GET, null, null)
				.getStatusCode().value())
				.as("\"abc\" passes required() and casts to 0, which matches no definition")
				.isEqualTo(404);
	}

	/**
	 * A page whose offset exceeds {@link Integer#MAX_VALUE}. PHP computes the
	 * offset in 64-bit and returns a successful empty page; narrowing it to
	 * {@code int} wraps it negative, which MariaDB rejects outright, so the
	 * divergence shows up as a 500 rather than as wrong rows.
	 *
	 * <p>At the maximum limit of 100, {@code page=21474838} is the first page
	 * past the boundary: {@code 21474837 * 100 == 2147483700}, which is
	 * {@code Integer.MAX_VALUE + 53}.
	 */
	@Test
	@Order(26)
	void aPageOffsetBeyondIntRangeIsAnEmptyPageRatherThanFiveHundred() {
		assertThat(send(DEFINITIONS + "?page=21474838&limit=100", HttpMethod.GET, token(ADMIN), null)
				.getStatusCode().value())
				.as("setting_definitions/list.php")
				.isEqualTo(200);
		assertThat(send(ALLOWED + "?setting_definition_id=" + DEF_THEME + "&page=21474838&limit=100",
				HttpMethod.GET, null, null).getStatusCode().value())
				.as("setting_allowed_values/list.php")
				.isEqualTo(200);
	}

	/**
	 * {@code options.php} orders by {@code setting_key} in the <b>database's</b>
	 * collation, which is {@code utf8mb4_unicode_ci} and therefore
	 * case-insensitive. A Java string comparator is binary and would sort every
	 * uppercase key ahead of every lowercase one.
	 *
	 * <p>Inserted and removed inside the test rather than added to the shared
	 * fixture, which two other assertions pin at three definitions.
	 */
	@Test
	@Order(27)
	@SuppressWarnings("unchecked")
	void theOptionsMapFollowsTheDatabaseCollationNotJavaStringOrder() {
		execute("INSERT INTO setting_definitions (id, setting_key, label_ar, label_en,"
				+ " description_ar, description_en, icon_data, is_multi, is_required, sort_order)"
				+ " VALUES (90, 'MONTHLY_ACCRUAL', NULL, 'Monthly', NULL, NULL, NULL, 0, 0, 9)");
		try {
			Map<String, Object> all = (Map<String, Object>) data(
					send(OPTIONS, HttpMethod.GET, token(ADMIN), null));
			assertThat(all.keySet())
					.as("case-insensitive: MONTHLY_ACCRUAL sorts among the lowercase keys, "
							+ "after `modules` because `mod` < `mon`")
					.containsExactly("currency", "modules", "MONTHLY_ACCRUAL", "theme");
		} finally {
			execute("DELETE FROM setting_definitions WHERE id = 90");
		}
	}

	/**
	 * PHP builds {@code $map = []} and {@code json_encode()} emits {@code []}
	 * for it when no definition exists -- a bare array, where a Java map would
	 * always serialize as {@code {}}.
	 */
	@Test
	@Order(28)
	void anEmptyDefinitionCatalogueAnswersAJsonArrayNotAnObject() {
		// Plain tables, not TEMPORARY: execute() opens a fresh connection per
		// statement, and a temporary table would not survive between them.
		execute("CREATE TABLE _defs_backup AS SELECT * FROM setting_definitions");
		execute("CREATE TABLE _vals_backup AS SELECT * FROM setting_allowed_values");
		execute("DELETE FROM setting_allowed_values");
		execute("DELETE FROM setting_definitions");
		try {
			ResponseEntity<Map<String, Object>> response =
					send(OPTIONS, HttpMethod.GET, token(ADMIN), null);
			assertThat(response.getStatusCode().value()).isEqualTo(200);
			assertThat(response.getBody().get("data"))
					.as("PHP's bare array encodes as [], not {}")
					.isInstanceOf(List.class);
		} finally {
			execute("INSERT INTO setting_definitions SELECT * FROM _defs_backup");
			execute("INSERT INTO setting_allowed_values SELECT * FROM _vals_backup");
			execute("DROP TABLE _defs_backup");
			execute("DROP TABLE _vals_backup");
		}
	}

	// ---------------- fixture ----------------

	private static Object data(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		assertThat(response.getBody()).containsEntry("success", true);
		return response.getBody().get("data");
	}

	private ResponseEntity<Map<String, Object>> send(
			String path, HttpMethod method, String token, String body) {
		return send(path, method, token, body, null);
	}

	private ResponseEntity<Map<String, Object>> send(
			String path, HttpMethod method, String token, String body, String acceptLanguage) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		if (acceptLanguage != null) {
			headers.set("Accept-Language", acceptLanguage);
		}
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method,
				new HttpEntity<>(body, headers), new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private String token(long employeeId) {
		return jwtService.issueAccessToken(employeeId, employeeId, COMPANY, "test-session",
				Map.of("role", "company_admin", "token_version", 1L));
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (" + COMPANY + ", 'Settings Co', '+201000026001', 'active', '2019-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH + ", " + COMPANY + ", 'Main', 1, '2019-03-01 10:00:00')");
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
					+ " last_name, phone, role, is_active, created_at) VALUES"
					+ " (" + ADMIN + ", " + COMPANY + ", " + BRANCH + ", '2601', 'A', 'One',"
					+ " '+201000260011', 'company_admin', 1, '2019-04-01 08:00:00'),"
					+ " (" + NO_PERMISSION + ", " + COMPANY + ", " + BRANCH + ", '2602', 'B', 'Two',"
					+ " '+201000260012', 'company_admin', 1, '2019-04-01 08:00:00')");
			// The permission matrix: ADMIN may manage settings, NO_PERMISSION may not.
			st.execute("INSERT INTO hr_permissions (employee_id, can_company_settings) VALUES"
					+ " (" + ADMIN + ", 1), (" + NO_PERMISSION + ", 0)");

			st.execute("INSERT INTO setting_definitions (id, setting_key, label_ar, label_en,"
					+ " description_ar, description_en, icon_data, is_multi, is_required, sort_order)"
					+ " VALUES"
					+ " (" + DEF_THEME + ", 'theme', 'المظهر', 'Theme', 'وصف', 'Desc', NULL, 0, 0, 1),"
					+ " (" + DEF_MODULES + ", 'modules', NULL, 'Modules', NULL, NULL, NULL, 1, 0, 2),"
					+ " (" + DEF_CURRENCY + ", 'currency', 'العملة', 'Currency', NULL, NULL, NULL, 0, 1, 3)");

			st.execute("INSERT INTO setting_allowed_values (id, setting_definition_id, value,"
					+ " label_ar, label_en, sort_order) VALUES"
					+ " (1, " + DEF_THEME + ", 'dark', 'داكن', 'Dark', 1),"
					+ " (2, " + DEF_THEME + ", 'light', 'فاتح', 'Light', 2),"
					+ " (3, " + DEF_MODULES + ", 'attendance', NULL, 'Attendance', 1),"
					+ " (4, " + DEF_MODULES + ", 'payroll', NULL, 'Payroll', 2),"
					+ " (5, " + DEF_CURRENCY + ", 'egp', NULL, 'EGP', 1),"
					+ " (6, " + DEF_CURRENCY + ", 'usd', NULL, 'USD', 2)");
		}
	}

	private static void execute(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute(sql);
		} catch (Exception ex) {
			throw new IllegalStateException("could not adjust the settings fixture", ex);
		}
	}

	private static long countRows(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				java.sql.ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
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
		try (InputStream in = LegacySettingsEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
