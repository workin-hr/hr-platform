package com.workin.legacy.reference;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.phone.LegacyPhoneCountries;
import com.workin.legacy.phone.LegacyPhoneCountry;
import com.workin.legacy.phone.LegacyPhoneCountryPublicRow;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Wave 13.5's four single-endpoint reference modules:
 * {@code phone_countries/list.php}, {@code app_content/one.php},
 * {@code banners/list.php} and {@code faqs/list.php}.
 *
 * <h2>Four endpoints, three different authority levels</h2>
 * <ul>
 * <li>{@code phone_countries/list.php} -- <b>unauthenticated</b>. Its own
 *     comment says "public — used by mobile/desktop before login", and it must
 *     stay so: a client needs the dial-code list to render the login form.</li>
 * <li>{@code app_content/one.php} -- <b>unauthenticated</b>. No
 *     {@code requireAuth()} anywhere in the file; it serves marketing and
 *     legal copy that the apps show before sign-in.</li>
 * <li>{@code banners/list.php} and {@code faqs/list.php} -- a bare
 *     {@code requireAuth()}, so <b>any</b> authenticated role reads them, and
 *     neither calls {@code requireCompanyActive()}. An employee of a suspended
 *     company still sees banners and FAQs, which is deliberate: this is
 *     platform content, not company data.</li>
 * </ul>
 *
 * <p>None of the four is company-scoped, and none of the underlying tables has
 * a {@code company_id} column. That is stated rather than left implicit because
 * "no tenant filter" normally signals a defect (D-075) and here it does not.
 *
 * <h2>The {@code platform} filter widens on an unknown value</h2>
 * <p>{@code banners} and {@code faqs} both apply an {@code if/elseif} with no
 * {@code else}, so {@code ?platform=web} -- or any typo -- returns
 * <em>everything</em> rather than nothing. Reproduced; a client sending a
 * platform this code does not know receives more rows, not fewer.
 */
@RestController
public class LegacyReferenceController {

	private final LegacyReferenceStore referenceStore;
	private final LegacyFaqCatalog faqCatalog;
	private final LegacyPhoneCountries phoneCountries;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyReferenceController(
			LegacyReferenceStore referenceStore, LegacyFaqCatalog faqCatalog,
			LegacyPhoneCountries phoneCountries, LegacyRequestGuard requestGuard,
			LegacyMessages messages) {
		this.referenceStore = referenceStore;
		this.faqCatalog = faqCatalog;
		this.phoneCountries = phoneCountries;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	/** {@code phone_countries/list.php}: {@code ok(OK, phone_countries_public_rows(null))}. */
	@RequestMapping("/apis/api/phone_countries/list.php")
	public LegacyApiResponse phoneCountries(HttpServletRequest request) {
		requireGet(request);
		String acceptLanguage = request.getHeader("Accept-Language");
		List<Map<String, Object>> rows = phoneCountries.allActive().stream()
				.map((LegacyPhoneCountry row) -> LegacyPhoneCountryPublicRow.of(row, acceptLanguage))
				.toList();
		return LegacyApiResponse.ok(message(request, "ok"), rows);
	}

	/**
	 * {@code app_content/one.php}: {@code ok(CONTENT, ['value' => $value])}.
	 *
	 * <p>Two different failures, two different statuses: a missing or empty
	 * {@code content_key} is {@code key_required} at the default status, while
	 * a key that matches no row is {@code not_found} at 404. The guard is
	 * {@code if (!$content_key)}, which is falsy rather than null -- so
	 * {@code ?content_key=0} is rejected as missing.
	 */
	@RequestMapping("/apis/api/app_content/one.php")
	public LegacyApiResponse appContent(HttpServletRequest request) {
		requireGet(request);
		Object raw = LegacyQueryParameters.parse(request.getQueryString()).value("content_key");
		if (LegacyValues.isPhpEmpty(raw)) {
			throw new LegacyApiException(400, "key_required");
		}
		LegacyReferenceStore.ContentValues values = referenceStore.content(LegacyValues.toPhpString(raw));
		if (values == null) {
			throw new LegacyApiException(404, "not_found");
		}
		String locale = messages.resolveLocale(request);
		String value = "en".equals(locale) ? values.english() : values.arabic();
		return LegacyApiResponse.ok(
				message(request, "content"), java.util.Collections.singletonMap("value", value));
	}

	/** {@code banners/list.php}: {@code ok(SUCCESS, $banner_rows)}. */
	@RequestMapping("/apis/api/banners/list.php")
	public LegacyApiResponse banners(HttpServletRequest request) {
		requireGet(request);
		requestGuard.requireAuth();
		return LegacyApiResponse.ok(message(request, "success"), referenceStore.banners(platform(request)));
	}

	/**
	 * {@code faqs/list.php}: {@code ok(SUCCESS, ['categories' => $categories])}.
	 *
	 * <p>Wrapped in a {@code categories} key, unlike {@code banners}, which
	 * returns its rows bare. The two endpoints are otherwise near-identical in
	 * shape, so the difference is easy to normalise away by accident.
	 */
	@RequestMapping("/apis/api/faqs/list.php")
	public LegacyApiResponse faqs(HttpServletRequest request) {
		requireGet(request);
		requestGuard.requireAuth();
		boolean english = "en".equals(messages.resolveLocale(request));
		return LegacyApiResponse.ok(message(request, "success"),
				java.util.Collections.singletonMap("categories",
						faqCatalog.grouped(platform(request), english)));
	}

	/**
	 * {@code strtolower(trim((string) ($_GET['platform'] ?? '')))}.
	 *
	 * <p>{@link LegacyValues#phpTrim}, not {@link String#trim}: PHP strips only
	 * {@code " \t\n\r\0\x0B"}, while Java strips every character at or below
	 * U+0020. So {@code ?platform=%1Cdesktop} keeps its leading control
	 * character in legacy and matches neither branch -- returning <em>every</em>
	 * row -- where Java would have trimmed it to {@code desktop} and filtered.
	 */
	private static String platform(HttpServletRequest request) {
		Object raw = LegacyQueryParameters.parse(request.getQueryString()).value("platform");
		return LegacyValues.mbStrToLower(
				LegacyValues.phpTrim(LegacyValues.toPhpString(raw == null ? "" : raw)));
	}

	private static void requireGet(HttpServletRequest request) {
		if (!"GET".equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}
}
