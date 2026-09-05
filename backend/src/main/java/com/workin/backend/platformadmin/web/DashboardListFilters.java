package com.workin.backend.platformadmin.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code org_list_read_filters()}: the query bundle every org list page reads.
 *
 * <p>One record for four pages, because it is one function in PHP. Two of the
 * five filters are used by only some of them -- {@code filterBranch} by
 * departments and employees, {@code filterDepartment} by employees -- and
 * they are read on every page regardless, so a link that carries them between
 * pages does not silently lose one.
 *
 * @param companyId        the resolved company, {@code 0} meaning every company
 *                         for an administrator. Reading this <b>applies</b>
 *                         the request's {@code ?company_id=} to the session
 *                         filter first, which is why construction takes the
 *                         request rather than the session alone
 * @param search           the free-text box, trimmed, empty when unused
 * @param status           {@code all}, {@code active} or {@code inactive}
 * @param filterBranch     branch id, {@code 0} for none
 * @param filterDepartment department id, {@code 0} for none
 * @param page             1-based, never below 1
 * @param perPage          clamped to {@code [1, PAGE_SIZE_MAX]}
 * @param scoped           whether the session is bound to one company, which
 *                         decides only whether {@code company_id} is carried
 *                         in links -- {@code org_filter_query_params()} omits
 *                         it for a scoped session, because there it names the
 *                         only company the session can ever see and putting it
 *                         in every URL would suggest otherwise
 */
public record DashboardListFilters(
		long companyId, String search, String status, long filterBranch, long filterDepartment,
		int page, int perPage, boolean scoped) {

	public static DashboardListFilters read(DashboardSession session, HttpServletRequest request) {
		return new DashboardListFilters(
				DashboardOrgScope.resolve(session, request),
				trimmed(request.getParameter("search")),
				// `(string) ($_GET['filter'] ?? 'all')` -- not trimmed, and not
				// validated: an unknown value falls through the status filter's
				// two tests and behaves as `all`.
				request.getParameter("filter") == null ? "all" : request.getParameter("filter"),
				positive(request.getParameter("filter_branch")),
				positive(request.getParameter("filter_department")),
				Math.max(1, (int) positive(request.getParameter("page"))),
				Math.max(1, Math.min(
						(int) intOr(request.getParameter("per_page"), DashboardPage.SIZE_DEFAULT),
						DashboardPage.SIZE_MAX)),
				session.isScopedToOneCompany());
	}

	private static String trimmed(String raw) {
		return raw == null ? "" : raw.trim();
	}

	/** {@code (int) $raw}, then {@code max(0, ...)}: a negative or absent id is none. */
	private static long positive(String raw) {
		long value = intOr(raw, 0);
		return value > 0 ? value : 0L;
	}

	/**
	 * {@code (int) $raw} with a default when absent.
	 *
	 * <p>PHP's cast takes the leading integer and yields 0 otherwise, so
	 * {@code ?page=abc} is page 0 -- which {@code max(1, ...)} then makes page
	 * 1. An exception here would refuse a URL legacy serves.
	 */
	private static long intOr(String raw, long fallback) {
		if (raw == null) {
			return fallback;
		}
		String trimmed = raw.trim();
		int end = 0;
		if (end < trimmed.length() && (trimmed.charAt(end) == '+' || trimmed.charAt(end) == '-')) {
			end++;
		}
		while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
			end++;
		}
		String digits = trimmed.substring(0, end);
		if (digits.isEmpty() || "+".equals(digits) || "-".equals(digits)) {
			return 0L;
		}
		try {
			return Long.parseLong(digits);
		} catch (NumberFormatException ex) {
			return 0L;
		}
	}

	/** {@code org_apply_status_filter()}: only these two values narrow anything. */
	public String statusClause(String alias) {
		return switch (this.status) {
			case "active" -> " AND " + alias + ".is_active = 1";
			case "inactive" -> " AND " + alias + ".is_active = 0";
			default -> "";
		};
	}

	/**
	 * {@code org_filter_query_params()}: the filters worth carrying in a link,
	 * as a query string with a leading {@code &} when non-empty.
	 *
	 * <p>Empty and zero values are dropped, and {@code filter=all} with them --
	 * a pager link should read {@code ?page=2}, not
	 * {@code ?page=2&search=&filter=all&filter_branch=0}.
	 */
	public String asQueryTail() {
		StringBuilder tail = new StringBuilder();
		append(tail, "search", this.search.isEmpty() ? null : this.search);
		append(tail, "filter", "all".equals(this.status) || this.status.isEmpty() ? null : this.status);
		append(tail, "filter_branch", this.filterBranch > 0 ? String.valueOf(this.filterBranch) : null);
		append(tail, "filter_department",
				this.filterDepartment > 0 ? String.valueOf(this.filterDepartment) : null);
		// `if (!org_is_scoped_company())`: only the administrator's filter is a
		// choice worth carrying.
		append(tail, "company_id",
				!this.scoped && this.companyId > 0 ? String.valueOf(this.companyId) : null);
		return tail.toString();
	}

	private static void append(StringBuilder tail, String name, String value) {
		if (value == null) {
			return;
		}
		tail.append('&').append(name).append('=')
				.append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
	}

}
