package com.workin.legacy.wire;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;

/**
 * Legacy's {@code t()} and {@code app_locale()}
 * ({@code hr-legacy/apis/helpers/i18n.php:8-56}), which together decide the
 * {@code message} every PHP response carries. D-074 makes that message part of
 * the wire contract, so it is reproduced rather than approximated.
 *
 * <p>The catalogs are the legacy ones, converted key-for-key by running
 * {@code json_encode(require 'lang/en.php')} under a real PHP 8.3 CLI --
 * {@code scripts/check_legacy_lang_drift.py} re-runs that conversion and fails
 * if the vendored copies drift, the same guarantee
 * {@code check_legacy_schema_drift.py} gives the schema.
 *
 * <p>Deliberately not routed through {@code com.workin.backend.i18n.Messages}:
 * that catalog is the platform's own, keyed differently, and enforced
 * key-for-key across locales by {@code MessageCatalogSyncTest}. Legacy's
 * {@code ar.php} intentionally carries keys {@code en.php} does not, and its
 * placeholder syntax is {@code {name}}, not {@code MessageFormat}'s
 * {@code {0}} -- merging them would corrupt both.
 */
@Component
public class LegacyMessages {

	/** {@code preg_match('/\bar\b/i', $hdr)} over {@code Accept-Language}. */
	private static final Pattern ARABIC_HEADER = Pattern.compile("\\bar\\b", Pattern.CASE_INSENSITIVE);

	private final Map<String, String> english;
	private final Map<String, String> arabic;

	public LegacyMessages() {
		this.english = load("legacy/lang/en.properties");
		// t(): $cache[$loc] = array_merge($en, $locArr) -- Arabic overrides
		// English, and English fills any key ar.php omits.
		Map<String, String> merged = new LinkedHashMap<>(this.english);
		merged.putAll(load("legacy/lang/ar.properties"));
		this.arabic = Map.copyOf(merged);
	}

	/**
	 * {@code app_locale()}: {@code ?lang=} wins when non-empty in PHP's sense,
	 * then {@code Accept-Language}, then English.
	 *
	 * <p>{@code !empty($_GET['lang'])} is the exact guard, so {@code ?lang=0}
	 * and {@code ?lang=} both fall through to the header -- {@code '0'} is empty
	 * in PHP. An array-valued {@code lang[]} stringifies to {@code "Array"},
	 * which is neither {@code ar} nor {@code ar*}, so it resolves to English.
	 */
	public String resolveLocale(HttpServletRequest request) {
		Object raw = LegacyQueryParameters.parse(request.getQueryString()).value("lang");
		if (!LegacyValues.isPhpEmpty(raw)) {
			String candidate = LegacyValues.toPhpString(raw).toLowerCase(java.util.Locale.ROOT);
			return candidate.startsWith("ar") ? "ar" : "en";
		}
		String header = request.getHeader("Accept-Language");
		return header != null && ARABIC_HEADER.matcher(header).find() ? "ar" : "en";
	}

	/**
	 * {@code t($key, $replace)}. Unknown keys pass through unchanged, exactly as
	 * PHP's {@code $cache[$loc][$key] ?? $key} does -- dynamic error strings
	 * reach the client verbatim in legacy and must here too.
	 */
	public String translate(String locale, String key, Map<String, Object> replace) {
		Map<String, String> catalog = "ar".equals(locale) ? arabic : english;
		String text = catalog.getOrDefault(key, key);
		if (replace == null || replace.isEmpty()) {
			return text;
		}
		for (Map.Entry<String, Object> entry : replace.entrySet()) {
			text = text.replace("{" + entry.getKey() + "}", render(entry.getValue()));
		}
		return text;
	}

	/**
	 * {@code t()}'s placeholder rendering: arrays join with {@code ', '}, other
	 * non-scalars become JSON, null becomes the empty string, scalars cast.
	 */
	private static String render(Object value) {
		if (value == null) {
			return "";
		}
		if (value instanceof Iterable<?> items) {
			StringBuilder joined = new StringBuilder();
			for (Object item : items) {
				if (joined.length() > 0) {
					joined.append(", ");
				}
				joined.append(item == null ? "" : LegacyValues.toPhpString(item));
			}
			return joined.toString();
		}
		return LegacyValues.toPhpString(value);
	}

	private static Map<String, String> load(String resource) {
		Properties properties = new Properties();
		try (InputStream stream = new ClassPathResource(resource).getInputStream();
				Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException ex) {
			throw new IllegalStateException("could not load the legacy language catalog " + resource, ex);
		}
		Map<String, String> catalog = new LinkedHashMap<>();
		properties.forEach((key, value) -> catalog.put(key.toString(), value.toString()));
		return catalog;
	}

}
