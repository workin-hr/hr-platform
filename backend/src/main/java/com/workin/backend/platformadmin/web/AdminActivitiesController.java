package com.workin.backend.platformadmin.web;

import java.time.LocalDate;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.legacy.LegacyClock;
import com.workin.backend.platformadmin.hr.ActivityStore;

/**
 * {@code dashboard/pages/activities/page.php}.
 *
 * <p>Read-only: it writes nothing and takes no row id, so D-176 has nothing to
 * anchor. What it does have is three separate permission decisions -- one to
 * open the page at all, and one per half of the feed -- and a company filter
 * that scopes both halves.
 */
@Controller
@Profile("phase1-mysql")
public class AdminActivitiesController {

	private static final String VIEW = "admin/activities";

	private static final String PATH = PlatformAdminWebSecurityConfig.ACTIVITIES_PATH;

	private static final List<String> KINDS = List.of("all", "attendance", "request");

	private final ActivityStore store;

	/** Legacy's clock, not the JVM's -- see {@link AdminViewModelAdvice#today()}. */
	private final LegacyClock clock;

	public AdminActivitiesController(ActivityStore store, LegacyClock clock) {
		this.store = store;
		this.clock = clock;
	}

	@AuthenticatedUseCase(reason = "A read-only feed of recent attendance punches and requests, "
			+ "scoped to one company for a scoped session. Gated by the recent-activities "
			+ "permission, and each half gated again by the permission for the page it comes "
			+ "from, so a session with one and not the other sees half the feed.")
	@GetMapping(PATH)
	public String page(
			HttpServletRequest request, Model model,
			@RequestParam(required = false, defaultValue = "all") String kind,
			@RequestParam(name = "date_from", required = false) String dateFrom,
			@RequestParam(name = "date_to", required = false) String dateTo,
			@RequestParam(required = false, defaultValue = "1") String page,
			@RequestParam(name = "per_page", required = false, defaultValue = "0") String perPage) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		if (!DashboardAccess.can(current, DashboardAccess.PERM_RECENT_ACTIVITIES)) {
			// Legacy flashes unauthorized and returns to the dashboard root.
			return "redirect:" + PlatformAdminWebSecurityConfig.PATH_PREFIX;
		}

		String selectedKind = KINDS.contains(kind) ? kind : "all";
		LocalDate today = this.clock.today();
		String from = blank(dateFrom) ? today.withDayOfMonth(1).toString() : dateFrom.trim();
		String to = blank(dateTo) ? today.toString() : dateTo.trim();

		// This page's own pager arithmetic, which is not dbPaginate's: the page
		// size floors at 10 and caps at 100, and the page count floors at 1 even
		// with no rows, where dbPaginate reports 0.
		int size = Math.max(10, Math.min(100, positive(perPage, 10)));
		int requested = Math.max(1, positive(page, 1));
		int offset = (requested - 1) * size;

		boolean canAttendance = DashboardAccess.canViewPayrollSection(current, "attendance");
		boolean canRequests = DashboardAccess.canViewHrSection(current, "requests");

		ActivityStore.Result result = this.store.list(
				filters.companyId(), selectedKind, from, to, size, offset,
				canAttendance, canRequests);

		int total = result.total();
		int pages = Math.max(1, (int) Math.ceil(total / (double) size));
		// Clamped after the offset was taken, as legacy does, so asking for a
		// page past the end reports the last page above an empty table.
		int clamped = Math.min(requested, pages);

		model.addAttribute("filters", filters);
		model.addAttribute("kind", selectedKind);
		model.addAttribute("kinds", KINDS);
		model.addAttribute("canAttendance", canAttendance);
		model.addAttribute("canRequests", canRequests);
		model.addAttribute("dateFrom", from);
		model.addAttribute("dateTo", to);
		model.addAttribute("rows", result.rows());
		model.addAttribute("total", total);
		model.addAttribute("pageNumber", clamped);
		model.addAttribute("pages", pages);
		model.addAttribute("perPage", size);
		model.addAttribute("rangeFrom", total > 0 ? offset + 1 : 0);
		model.addAttribute("rangeTo", Math.min(offset + size, total));
		return VIEW;
	}

	private static boolean blank(String value) {
		return value == null || value.trim().isEmpty();
	}

	/** PHP's {@code (int)} cast: a leading numeric prefix, or zero. */
	private static int positive(String raw, int fallback) {
		if (blank(raw)) {
			return fallback;
		}
		int end = 0;
		String trimmed = raw.trim();
		while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
			end++;
		}
		if (end == 0) {
			return 0;
		}
		try {
			return Integer.parseInt(trimmed.substring(0, end));
		}
		catch (NumberFormatException ex) {
			return 0;
		}
	}

}
