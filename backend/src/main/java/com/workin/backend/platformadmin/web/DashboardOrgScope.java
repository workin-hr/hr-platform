package com.workin.backend.platformadmin.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * {@code org_helper.php}: which company the dashboard is acting on.
 *
 * <p>For an owner or an HR employee this is not a question -- it is their own
 * company and nothing can change it. For the platform administrator it is a
 * <b>filter</b> held in the session, set from {@code ?company_id=}, where
 * {@code 0} means "all companies at once". That filter is the cross-tenant
 * mode <b>R-044</b> was filed for, and it is why this is a named component
 * with its own tests rather than a parameter read at each call site: the one
 * place a tenant boundary is deliberately crossed should be the one place
 * that is easy to audit.
 *
 * <h2>It is session state, not a query parameter</h2>
 * <p>{@code ?company_id=9} sets the filter and it then applies to every later
 * request until changed -- so a link with no {@code company_id} does not reset
 * it. {@code ?company_id=} (present and empty) clears it; a value that is not
 * a positive integer clears it too. A request that does not mention
 * {@code company_id} at all leaves it alone. Those three cases are different
 * and PHP distinguishes them with {@code array_key_exists}, not
 * {@code isset} -- which would treat present-and-empty as absent and make the
 * filter impossible to clear.
 */
public final class DashboardOrgScope {

	/** {@code ORG_FILTER_SESSION_KEY}. */
	static final String SESSION_KEY = "org_filter_company_id";

	private DashboardOrgScope() {
	}

	/**
	 * {@code org_apply_company_filter_from_request()} then
	 * {@code org_resolve_company_id()}: the company id to act on, having first
	 * applied whatever the request said about the filter.
	 *
	 * <p>A scoped session returns its own company and never touches the filter,
	 * so a company owner appending {@code ?company_id=9} to a URL changes
	 * nothing at all. That is the containment: the parameter is not "ignored
	 * for safety", it is never read on a path where it could mean anything.
	 */
	public static long resolve(DashboardSession session, HttpServletRequest request) {
		if (session.isScopedToOneCompany()) {
			return session.companyId();
		}
		applyFilterFromRequest(request);
		return current(request.getSession(false));
	}

	/** {@code org_apply_company_filter_from_request()}, for an administrator only. */
	private static void applyFilterFromRequest(HttpServletRequest request) {
		// `array_key_exists('company_id', $_GET)`: absent leaves the filter
		// alone, present-and-empty clears it.
		if (request.getParameter("company_id") == null) {
			return;
		}
		HttpSession session = request.getSession(true);
		String raw = request.getParameter("company_id");
		long id = parsePositive(raw);
		if (id > 0) {
			session.setAttribute(SESSION_KEY, id);
		} else {
			session.removeAttribute(SESSION_KEY);
		}
	}

	/** The filter as it stands, without reading the request. {@code 0} is "all companies". */
	public static long current(HttpSession session) {
		if (session == null) {
			return 0L;
		}
		Object value = session.getAttribute(SESSION_KEY);
		return value instanceof Long id ? id : 0L;
	}

	/**
	 * {@code org_redirect()}: sets the filter on the way out, so an
	 * administrator who has just created a row in company 9 lands filtered to
	 * company 9 and can see what they made. A no-op for a scoped session.
	 */
	public static void rememberAfterWrite(
			DashboardSession session, HttpServletRequest request, long companyId) {
		if (session.isScopedToOneCompany() || companyId <= 0) {
			return;
		}
		request.getSession(true).setAttribute(SESSION_KEY, companyId);
	}

	/**
	 * {@code org_show_company_column()}: the company column appears only in the
	 * administrator's <em>unfiltered</em> view, where rows from different
	 * companies are on screen together and the column is the only thing telling
	 * them apart.
	 */
	public static boolean showsCompanyColumn(DashboardSession session, long companyId) {
		return session.isAdmin() && companyId <= 0;
	}

	/**
	 * Whether a row of {@code rowCompanyId} may be opened by this session under
	 * the filter currently in force -- the rule every org and HR page applies
	 * to a row id that arrived in a URL.
	 *
	 * <p>The two halves are not the same test, and the difference is the point:
	 *
	 * <ul>
	 * <li>a <b>scoped</b> session may open only its own company's rows, full
	 *     stop. This is the check that stops a crafted id in a link from
	 *     reaching another company's data;</li>
	 * <li>an <b>administrator</b> may open any row, <em>except</em> one outside
	 *     the company they have filtered to. Filtered to company 3 and
	 *     following an edit link for company 9's row shows nothing -- not
	 *     because it is forbidden, but because silently editing a company the
	 *     operator is not looking at is how the wrong row gets changed. With no
	 *     filter set, every row is reachable, which is what "all companies"
	 *     means.</li>
	 * </ul>
	 *
	 * <p>One method rather than one per page. It was four identical copies
	 * across the org controllers and would have been twenty by the time the
	 * remaining pages landed; a tenant rule with twenty copies is a tenant rule
	 * with nineteen chances to drift, and the direction it drifts is another
	 * company's row on screen.
	 */
	public static boolean canOpenRow(
			DashboardSession session, DashboardListFilters filters, long rowCompanyId) {
		if (session.isScopedToOneCompany()) {
			return rowCompanyId == session.companyId();
		}
		return filters.companyId() <= 0 || rowCompanyId == filters.companyId();
	}

	/**
	 * {@code (int) $raw} for the shapes a query parameter arrives in, then
	 * {@code > 0}.
	 *
	 * <p>PHP's cast takes the leading integer and yields 0 for anything that
	 * does not start with one, so {@code "9abc"} is 9 and {@code "abc"} is 0 --
	 * which clears the filter rather than erroring. Reproduced, because the
	 * alternative is a 400 on a URL legacy accepts.
	 */
	private static long parsePositive(String raw) {
		String trimmed = raw == null ? "" : raw.trim();
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
			// Longer than a long: PHP saturates rather than throwing, and
			// either way the id matches no company.
			return 0L;
		}
	}

}
