package com.workin.legacy.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@code t()} and {@code app_locale()} ({@code helpers/i18n.php}), proven
 * against the vendored catalogs without a database or a Spring context.
 */
class LegacyMessagesTest {

	private final LegacyMessages messages = new LegacyMessages();

	@Test
	void translatesFromTheVendoredLegacyCatalogs() {
		assertThat(messages.translate("en", "employee_not_found", null)).isEqualTo("Employee not found");
		assertThat(messages.translate("ar", "employees", null)).isEqualTo("الموظفون");
	}

	@Test
	void anUnknownKeyPassesThroughUnchanged() {
		// $cache[$loc][$key] ?? $key -- legacy returns dynamic error text verbatim.
		assertThat(messages.translate("en", "a_key_no_catalog_has", null)).isEqualTo("a_key_no_catalog_has");
		assertThat(messages.translate("ar", "a_key_no_catalog_has", null)).isEqualTo("a_key_no_catalog_has");
	}

	@Test
	void arabicIsEnglishMergedUnderTheLocaleFile() {
		// t(): array_merge($en, $locArr). Today ar.php is a superset -- it carries
		// three keys en.php does not (payslip_created/_deleted/_not_found) and no
		// key of its own is missing -- so the merge is proven in the direction that
		// exists: the Arabic file wins for every shared key, and an Arabic-only key
		// resolves under 'ar' while falling through to itself under 'en'.
		assertThat(messages.translate("ar", "ok", null)).isNotEqualTo(messages.translate("en", "ok", null));
		assertThat(messages.translate("ar", "payslip_not_found", null)).isNotEqualTo("payslip_not_found");
		assertThat(messages.translate("en", "payslip_not_found", null)).isEqualTo("payslip_not_found");
	}

	@Test
	void placeholdersAreSubstitutedTheWayPhpDoes() {
		assertThat(messages.translate("en", "field_required", Map.of("field", "id")))
				.isEqualTo("Field 'id' is required");
		// An array value joins with ', '...
		assertThat(messages.translate("en", "field_required", Map.of("field", List.of("id", "name"))))
				.isEqualTo("Field 'id, name' is required");
		// ...and null renders as the empty string, not the text "null".
		Map<String, Object> nullValue = new LinkedHashMap<>();
		nullValue.put("field", null);
		assertThat(messages.translate("en", "field_required", nullValue)).isEqualTo("Field '' is required");
	}

	@Test
	void appLocalePrefersTheLangParameterThenTheHeader() {
		assertThat(messages.resolveLocale(request("lang=ar", null))).isEqualTo("ar");
		// strpos($l, 'ar') === 0
		assertThat(messages.resolveLocale(request("lang=ar-EG", null))).isEqualTo("ar");
		assertThat(messages.resolveLocale(request("lang=AR", null))).isEqualTo("ar");
		assertThat(messages.resolveLocale(request("lang=fr", null))).isEqualTo("en");
		// !empty($_GET['lang']) -- '0' and '' are empty in PHP, so both fall through.
		assertThat(messages.resolveLocale(request("lang=0", "ar"))).isEqualTo("ar");
		assertThat(messages.resolveLocale(request("lang=", "ar"))).isEqualTo("ar");
		// preg_match('/\bar\b/i', $hdr) -- a word boundary, so "hungarian" is not Arabic.
		assertThat(messages.resolveLocale(request(null, "ar-EG,ar;q=0.9"))).isEqualTo("ar");
		assertThat(messages.resolveLocale(request(null, "hungarian"))).isEqualTo("en");
		assertThat(messages.resolveLocale(request(null, null))).isEqualTo("en");
	}

	private static MockHttpServletRequest request(String queryString, String acceptLanguage) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setQueryString(queryString);
		if (acceptLanguage != null) {
			request.addHeader("Accept-Language", acceptLanguage);
		}
		return request;
	}

}
