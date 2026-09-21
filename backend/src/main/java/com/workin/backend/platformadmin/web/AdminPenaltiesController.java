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
import com.workin.backend.platformadmin.hr.PenaltyAdminService;
import com.workin.backend.platformadmin.hr.PenaltyStore;
import com.workin.legacy.spreadsheet.LegacyXlsxWriter;

/** {@code dashboard/pages/penalties/page.php}. */
@Controller
public class AdminPenaltiesController {

	private static final String VIEW = "admin/penalties";

	private static final String PATH = PlatformAdminWebSecurityConfig.PENALTIES_PATH;

	/**
	 * What {@code csv_export_send()} actually sends ({@code query.php:375-405}): the button
	 * says CSV and the file is a spreadsheet (D-269, {@code hr-legacy#23}), reproduced rather
	 * than corrected here too.
	 */
	private static final MediaType XLSX = MediaType.parseMediaType(
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

	private final PenaltyStore store;

	private final PenaltyAdminService service;

	public AdminPenaltiesController(PenaltyStore store, PenaltyAdminService service) {
		this.store = store;
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "One company's employee penalties, which payroll deducts "
			+ "against. An administrator reaches every company's through the session filter.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		String applied = request.getParameter("applied") == null
				? "all" : request.getParameter("applied");
		String dateFrom = request.getParameter("date_from");
		String dateTo = request.getParameter("date_to");
		boolean showCompany = DashboardOrgScope.showsCompanyColumn(current, filters.companyId());

		model.addAttribute("showCompanyColumn", showCompany);
		model.addAttribute("filters", filters);
		model.addAttribute("applied", applied);
		model.addAttribute("dateFrom", dateFrom == null ? "" : dateFrom);
		model.addAttribute("dateTo", dateTo == null ? "" : dateTo);
		model.addAttribute("result", this.store.paginate(
				filters, applied, dateFrom, dateTo, showCompany));
		model.addAttribute("employeeOptions", this.store.employeeOptions(filters.companyId()));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "penalties"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	/**
	 * {@code hr_export_penalties_csv()} ({@code penalties/page.php:12-14}): the list this page
	 * is showing, as a spreadsheet, before anything is rendered.
	 *
	 * <p>Legacy exports every row the filter admits, not the page on the screen, in the same
	 * order the table uses. Both are reproduced. The scoping is not relaxed for it: the same
	 * {@link DashboardListFilters} the page reads, so an administrator filtered to one company
	 * exports that company, and a session bound to one company can export no other. This is a
	 * read, so it is not behind the actions switch that gates the row actions -- a working
	 * export must not disappear because writes are turned off.
	 *
	 * <p>If it fails, the operator sees the dashboard's error page rather than a download, and
	 * the request appears in the access log as a 500 on {@code /admin/penalties?export=csv};
	 * nothing is written either way.
	 */
	@AuthenticatedUseCase(reason = "One company's employee penalties, or every company's, as a "
			+ "spreadsheet. Read-only, and narrowed by exactly the filter that narrows the page.")
	@GetMapping(value = PATH, params = "export=csv")
	public ResponseEntity<byte[]> export(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		String applied = request.getParameter("applied") == null
				? "all" : request.getParameter("applied");
		String dateFrom = request.getParameter("date_from");
		String dateTo = request.getParameter("date_to");
		Function<String, String> t = AdminFlash.t(model);

		byte[] body = LegacyXlsxWriter.build(
				List.of(t.apply("emp_code"), t.apply("employee_name"), t.apply("penalty_type"),
						t.apply("penalty_days"), t.apply("penalty_reason"), t.apply("penalty_date"),
						t.apply("applied_payroll")),
				this.store.exportRows(filters, applied, dateFrom, dateTo),
				// csv_export_send()'s own sheet name and default options.
				"Export", List.of(), List.of(), 1, Map.of());

		String from = dateFrom == null || dateFrom.isBlank() ? "all" : dateFrom;
		String to = dateTo == null || dateTo.isBlank() ? "all" : dateTo;
		return ResponseEntity.ok()
				.contentType(XLSX)
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
						+ LegacyXlsxWriter.sanitizeFilename("penalties_" + from + "_" + to + ".xlsx")
						+ "\"")
				.body(body);
	}

	@AuthenticatedUseCase(reason = "Creates, edits, marks applied or deletes one penalty. "
			+ "A penalty already applied to payroll cannot be edited. Gated by the surface "
			+ "flag and a bound second factor, tenant-checked through the employee join (R-046), "
			+ "and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "employee_id", required = false, defaultValue = "0") long employeeId,
			@RequestParam(name = "penalty_type", required = false, defaultValue = "") String penaltyType,
			@RequestParam(name = "penalty_days", required = false, defaultValue = "") String penaltyDays,
			@RequestParam(required = false, defaultValue = "") String reason,
			@RequestParam(name = "penalty_date", required = false, defaultValue = "") String penaltyDate,
			Model model, RedirectAttributes redirect) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();

		try {
			long wrote = switch (action) {
				case "add_penalty" -> this.service.add(session, adminId, employeeId,
						penaltyType, penaltyDays, reason, penaltyDate);
				case "edit_penalty" -> this.service.saveEdit(session, adminId, id,
						employeeId, penaltyType, penaltyDays, reason, penaltyDate);
				case "mark_applied" -> this.service.markApplied(session, adminId, id);
				case "delete_penalty" -> this.service.delete(session, adminId, id);
				default -> throw new PenaltyAdminService.RefusedException(
						PenaltyAdminService.Refusal.INVALID);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			AdminFlash.saved(redirect, model);
			return "redirect:" + PATH;
		} catch (PenaltyAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?error=" + messageKey(refused);
		}
	}

	private static String messageKey(PenaltyAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FOREIGN_ROW -> "error_db";
			case INVALID -> "error_required";
			case BAD_DAYS -> "penalty_days_invalid";
		};
	}

}
