package com.workin.legacy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code hr-legacy/apis/helpers/pagination.php}, shared by every legacy module
 * that paginates.
 *
 * <h2>Why this is not a per-module copy</h2>
 * <p>Two of these rules are easy to reimplement slightly differently, and a
 * module that did would diverge from PHP only for particular query strings --
 * the kind of defect no module's own tests would show, because each module's
 * fixtures would agree with its own copy. Wave 12.4 wrote them once inside
 * {@code LegacyEmployeeService}; Wave 12.5 needs the same rules, so they moved
 * here rather than being duplicated.
 *
 * <ul>
 * <li>{@code $raw ?: $defaultLimit} -- a limit that <em>casts</em> to zero
 *     ({@code ?limit=0}, {@code ?limit=abc}) becomes the default, not 1. The
 *     {@code max(1, ...)} that follows never sees the zero.</li>
 * <li>{@code $_GET['limit'] ?? $_GET['per_page'] ?? $default} -- a null
 *     coalesce, so {@code ?limit=} (present but empty) wins over
 *     {@code per_page} and then casts to 0, which is the default again.</li>
 * </ul>
 */
public final class LegacyPagination {

	/** {@code AppConfig::DEFAULT_LIMIT} and the {@code pagination_params($default, 100)} cap. */
	private static final long DEFAULT_LIMIT = 20;
	private static final long MAX_LIMIT = 100;

	private LegacyPagination() {
	}

	/** {@code array{page:int, limit:int, offset:int}}. */
	public record Params(long page, long limit, long offset) {
	}

	/**
	 * {@code pagination_params(AppConfig::DEFAULT_LIMIT, 100)}
	 * ({@code helpers/pagination.php:12-23}).
	 */
	public static Params params(LegacyQueryParameters query) {
		long page = Math.max(1, LegacyValues.toPhpLong(query.value("page") == null ? 1 : query.value("page")));
		Object rawLimit = query.value("limit");
		if (rawLimit == null) {
			rawLimit = query.value("per_page");
		}
		long raw = LegacyValues.toPhpLong(rawLimit == null ? DEFAULT_LIMIT : rawLimit);
		long limit = Math.min(Math.max(1, raw == 0 ? DEFAULT_LIMIT : raw), MAX_LIMIT);
		return new Params(page, limit, (page - 1) * limit);
	}

	/**
	 * {@code pagination_meta()} ({@code helpers/pagination.php:28-39}), key
	 * order included -- the meta object is part of the D-074 wire contract.
	 */
	public static Map<String, Object> meta(long total, Params pagination) {
		long pages = pagination.limit() > 0 ? (long) Math.ceil((double) total / pagination.limit()) : 0;
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("page", pagination.page());
		meta.put("limit", pagination.limit());
		meta.put("total", total);
		meta.put("total_pages", pages);
		meta.put("has_next", pagination.page() < pages);
		meta.put("has_previous", pagination.page() > 1);
		return meta;
	}

	/**
	 * {@code search_query_param()} ({@code helpers/pagination.php:44-47}):
	 * {@code trim((string) ($_GET[$key] ?? ''))}, and the empty string becomes
	 * null -- so {@code ?search=} filters nothing rather than matching
	 * {@code LIKE '%%'}.
	 */
	public static String searchQueryParam(LegacyQueryParameters query) {
		Object raw = query.value("search");
		String value = LegacyValues.phpTrim(raw == null ? "" : LegacyValues.toPhpString(raw));
		return value.isEmpty() ? null : value;
	}

}
