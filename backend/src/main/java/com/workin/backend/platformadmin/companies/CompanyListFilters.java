package com.workin.backend.platformadmin.companies;

import java.util.LinkedHashMap;
import java.util.SequencedMap;

import jakarta.servlet.http.HttpServletRequest;

import com.workin.backend.platformadmin.web.DashboardPage;
import com.workin.legacy.PhpCast;

/**
 * {@code company_filter_query_params()} plus the paging the same page reads.
 *
 * <p>Separate from {@code DashboardListFilters} because the two pages filter on
 * different things. The org pages filter by branch and department inside one
 * company; this page is platform-wide -- it has no company scope at all -- and
 * filters by activity, title and size, with a status whose values are the
 * company lifecycle ({@code active}/{@code pending}/{@code rejected}) rather
 * than the {@code is_active} flag every other list uses.
 *
 * @param status {@code all} or a {@code companies.status} value; an unknown
 *     value narrows nothing, as PHP's {@code $filter !== 'all'} comparison
 *     against the enum column does
 */
public record CompanyListFilters(
		String search, String status, long activityId, long titleId, long sizeId,
		int page, int perPage) {

	public static CompanyListFilters read(HttpServletRequest request) {
		return new CompanyListFilters(
				trimmed(request.getParameter("search")),
				request.getParameter("filter") == null ? "all" : request.getParameter("filter"),
				positive(request.getParameter("filter_activity")),
				positive(request.getParameter("filter_title")),
				positive(request.getParameter("filter_size")),
				Math.clamp(intOr(request.getParameter("page"), 1), 1, Integer.MAX_VALUE),
				Math.clamp(intOr(request.getParameter("per_page"), DashboardPage.SIZE_DEFAULT),
						1, DashboardPage.SIZE_MAX));
	}

	/** The filters worth carrying in a page link, in link order. */
	public SequencedMap<String, String> asQueryParameters() {
		SequencedMap<String, String> parameters = new LinkedHashMap<>();
		put(parameters, "search", this.search.isEmpty() ? null : this.search);
		put(parameters, "filter", "all".equals(this.status) || this.status.isEmpty() ? null : this.status);
		put(parameters, "filter_activity", this.activityId > 0 ? String.valueOf(this.activityId) : null);
		put(parameters, "filter_title", this.titleId > 0 ? String.valueOf(this.titleId) : null);
		put(parameters, "filter_size", this.sizeId > 0 ? String.valueOf(this.sizeId) : null);
		return parameters;
	}

	private static void put(SequencedMap<String, String> parameters, String name, String value) {
		if (value != null) {
			parameters.put(name, value);
		}
	}

	private static String trimmed(String raw) {
		return raw == null ? "" : raw.trim();
	}

	private static long positive(String raw) {
		long value = intOr(raw, 0);
		return value > 0 ? value : 0L;
	}

	/** PHP's {@code (int)} cast ({@link PhpCast#intval}), with a default when absent. */
	private static long intOr(String raw, long fallback) {
		return raw == null ? fallback : PhpCast.intval(raw);
	}

}
