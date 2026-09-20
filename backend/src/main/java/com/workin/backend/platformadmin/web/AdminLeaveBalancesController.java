package com.workin.backend.platformadmin.web;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.hr.LeaveBalanceAdminService;
import com.workin.backend.platformadmin.hr.LeaveBalanceStore;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.PhpCast;
import com.workin.legacy.spreadsheet.LegacyXlsxWriter;

/**
 * {@code dashboard/pages/leave_balances/page.php}, the first of the HR pages.
 *
 * <p>Two things differ from the org pages it reuses the machinery of. The list
 * is always narrowed to one {@code year}, defaulting to the current one rather
 * than to "all"; and the rows reach a company through their employee, so the
 * company column and every tenant check go through a join.
 */
@Controller
public class AdminLeaveBalancesController {

	private static final String VIEW = "admin/leave-balances";

	private static final String PATH = PlatformAdminWebSecurityConfig.LEAVE_BALANCES_PATH;

	/**
	 * What {@code csv_export_send()} actually sends ({@code query.php:375-405}).
	 *
	 * <p>The button says CSV and the file is a spreadsheet: the helper rewrites
	 * a {@code .csv} name to {@code .xlsx} and sends the OOXML type
	 * ({@code hr-legacy#23}). Reproduced rather than corrected, because what
	 * #23 asks for -- a content type and an extension that match the bytes --
	 * is already true here; only the button's wording is legacy's, and changing
	 * a label is a decision for the owner, not for this port.
	 */
	private static final MediaType XLSX = MediaType.parseMediaType(
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

	private final LeaveBalanceStore store;

	private final LeaveBalanceAdminService service;

	private final LegacyClock clock;

	public AdminLeaveBalancesController(
			LeaveBalanceStore store, LeaveBalanceAdminService service, LegacyClock clock) {
		this.store = store;
		this.service = service;
		this.clock = clock;
	}

	@AuthenticatedUseCase(reason = "One company's annual leave balances. An administrator "
			+ "reaches every company's through the session filter.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		int thisYear = this.clock.now().getYear();
		int year = year(request.getParameter("year"), thisYear);
		boolean showCompany = DashboardOrgScope.showsCompanyColumn(current, filters.companyId());

		model.addAttribute("showCompanyColumn", showCompany);
		model.addAttribute("filters", filters);
		model.addAttribute("year", year);
		// The dashboard offers next year through five years back.
		model.addAttribute("yearOptions", java.util.stream.IntStream
				.rangeClosed(thisYear - 5, thisYear + 1).boxed()
				.sorted(java.util.Comparator.reverseOrder()).toList());
		model.addAttribute("result", this.store.paginate(filters, year, showCompany));
		model.addAttribute("employeeOptions", this.store.employeeOptions(filters.companyId()));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "leave_balances"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	/**
	 * {@code hr_export_leave_balances_csv()} ({@code leave_balances/page.php:7-9}):
	 * the list this page is showing, as a spreadsheet, before anything is
	 * rendered.
	 *
	 * <p>Legacy exports every row the filter admits, not the page on the
	 * screen, and orders them by employee name rather than newest first. Both
	 * are reproduced. The scoping is not relaxed for it: the same
	 * {@link DashboardListFilters} the page reads, so an administrator filtered
	 * to one company exports that company, and a session bound to one company
	 * can export no other.
	 *
	 * <p>If it fails, the operator sees the dashboard's error page rather than
	 * a download, and the request appears in the access log as a 500 on
	 * {@code /admin/leave_balances?export=csv}; nothing is written either way.
	 */
	@AuthenticatedUseCase(reason = "One company's annual leave balances, or every "
			+ "company's, as a spreadsheet. Read-only, and narrowed by exactly the "
			+ "filter that narrows the page.")
	@GetMapping(value = PATH, params = "export=csv")
	public ResponseEntity<byte[]> export(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		int year = year(request.getParameter("year"), this.clock.now().getYear());
		Function<String, String> t = AdminFlash.t(model);

		byte[] body = LegacyXlsxWriter.build(
				List.of(t.apply("emp_code"), t.apply("employee_name"), t.apply("year"),
						t.apply("total_days"), t.apply("used_days"), t.apply("remaining_days")),
				this.store.exportRows(filters, year),
				// csv_export_send()'s own sheet name and default options.
				"Export", List.of(), List.of(), 1, Map.of());

		return ResponseEntity.ok()
				.contentType(XLSX)
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
						+ LegacyXlsxWriter.sanitizeFilename("leave_balances_" + year + ".xlsx") + "\"")
				.body(body);
	}

	/**
	 * {@code (int) ($_GET['year'] ?? date('Y'))}, read with {@link PhpCast#intval}.
	 *
	 * <p>A blank, unreadable or non-positive year is this one. Legacy's cast gives 0, or the negative
	 * number, and lists and writes that year (D-263).
	 */
	private static int year(String raw, int fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		int parsed = Math.clamp(PhpCast.intval(raw), Integer.MIN_VALUE, Integer.MAX_VALUE);
		return parsed > 0 ? parsed : fallback;
	}

	@AuthenticatedUseCase(reason = "Creates, edits or deletes one employee's annual leave "
			+ "balance. Gated by the surface flag and a bound second factor, tenant-checked "
			+ "through the employee join (R-046), and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "employee_id", required = false, defaultValue = "0") long employeeId,
			@RequestParam(required = false, defaultValue = "") String year,
			@RequestParam(name = "total_days", required = false, defaultValue = "") String totalDays,
			@RequestParam(name = "used_days", required = false, defaultValue = "") String usedDays,
			Model model, RedirectAttributes redirect) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();

		try {
			long wrote = switch (action) {
				case "add_leave" -> this.service.add(session, adminId, employeeId,
						year(year, this.clock.now().getYear()), totalDays);
				case "edit_leave" -> this.service.saveEdit(
						session, adminId, id, totalDays, usedDays);
				case "delete_leave" -> this.service.delete(session, adminId, id);
				default -> throw new LeaveBalanceAdminService.RefusedException(
						LeaveBalanceAdminService.Refusal.NO_COMPANY);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			AdminFlash.saved(redirect, model);
			return "redirect:" + PATH;
		} catch (LeaveBalanceAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?error=" + messageKey(refused);
		}
	}

	private static String messageKey(LeaveBalanceAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case NO_COMPANY -> "select_company_first";
			case NO_EMPLOYEE -> "error_required";
			case FOREIGN_ROW -> "error_db";
		};
	}

}
