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
import com.workin.backend.platformadmin.hr.AdvanceAdminService;
import com.workin.backend.platformadmin.hr.AdvanceStore;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.spreadsheet.LegacyXlsxWriter;

/** {@code dashboard/pages/advances/page.php}. */
@Controller
public class AdminAdvancesController {

	private static final String VIEW = "admin/advances";

	private static final String PATH = PlatformAdminWebSecurityConfig.ADVANCES_PATH;

	/**
	 * What {@code csv_export_send()} actually sends ({@code query.php:375-405}): the button
	 * says CSV and the file is a spreadsheet (D-269, {@code hr-legacy#23}), reproduced rather
	 * than corrected here too.
	 */
	private static final MediaType XLSX = MediaType.parseMediaType(
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

	private final AdvanceStore store;

	private final AdvanceAdminService service;

	private final LegacyClock clock;

	public AdminAdvancesController(AdvanceStore store, AdvanceAdminService service, LegacyClock clock) {
		this.store = store;
		this.service = service;
		this.clock = clock;
	}

	@AuthenticatedUseCase(reason = "One company's salary advances and what remains outstanding "
			+ "on them. An administrator reaches every company's through the session filter.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		String status = request.getParameter("status") == null
				? "all" : request.getParameter("status");
		String dateFrom = request.getParameter("date_from");
		String dateTo = request.getParameter("date_to");
		boolean showCompany = DashboardOrgScope.showsCompanyColumn(current, filters.companyId());

		model.addAttribute("showCompanyColumn", showCompany);
		model.addAttribute("filters", filters);
		model.addAttribute("status", status);
		model.addAttribute("dateFrom", dateFrom == null ? "" : dateFrom);
		model.addAttribute("dateTo", dateTo == null ? "" : dateTo);
		model.addAttribute("result", this.store.paginate(
				filters, status, dateFrom, dateTo, showCompany));
		model.addAttribute("employeeOptions", this.store.employeeOptions(filters.companyId()));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "advances"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	/**
	 * {@code hr_export_advances_csv()} ({@code advances/page.php:11-13}): the list this page is
	 * showing, as a spreadsheet, before anything is rendered.
	 *
	 * <p>Legacy exports every row the filter admits, not the page on the screen, ordered by
	 * {@code created_at DESC, id DESC} -- the order of the list above it, which
	 * {@code hr_paginate_advances()} uses too. Both are reproduced. The scoping is not relaxed for it: the same
	 * {@link DashboardListFilters} the page reads, so an administrator filtered to one company
	 * exports that company, and a session bound to one company can export no other. This is a
	 * read, so it is not behind the actions switch that gates the row actions -- a working
	 * export must not disappear because writes are turned off.
	 *
	 * <p>If it fails, the operator sees the dashboard's error page rather than a download, and
	 * the request appears in the access log as a 500 on {@code /admin/advances?export=csv};
	 * nothing is written either way.
	 */
	@AuthenticatedUseCase(reason = "One company's salary advances, or every company's, as a "
			+ "spreadsheet. Read-only, and narrowed by exactly the filter that narrows the page.")
	@GetMapping(value = PATH, params = "export=csv")
	public ResponseEntity<byte[]> export(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		String status = request.getParameter("status") == null
				? "all" : request.getParameter("status");
		String dateFrom = request.getParameter("date_from");
		String dateTo = request.getParameter("date_to");
		Function<String, String> t = AdminFlash.t(model);

		byte[] body = LegacyXlsxWriter.build(
				List.of(t.apply("emp_code"), t.apply("employee_name"), t.apply("advance_amount"),
						t.apply("remaining"), t.apply("request_date"), t.apply("advance_reason"),
						t.apply("rejection_reason"), t.apply("status")),
				this.store.exportRows(filters, status, dateFrom, dateTo),
				// csv_export_send()'s own sheet name and default options.
				"Export", List.of(), List.of(), 1, Map.of());

		return ResponseEntity.ok()
				.contentType(XLSX)
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
						+ LegacyXlsxWriter.sanitizeFilename("advances_" + this.clock.todayAsString() + ".xlsx")
						+ "\"")
				.body(body);
	}

	@AuthenticatedUseCase(reason = "Creates, edits, approves, rejects, marks repaid or deletes "
			+ "one salary advance. An edit adjusts the outstanding balance rather than "
			+ "overwriting it. Gated by the surface flag and a bound second factor, "
			+ "tenant-checked through the employee join (R-046, D-176), and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "employee_id", required = false, defaultValue = "0") long employeeId,
			@RequestParam(required = false, defaultValue = "") String amount,
			@RequestParam(required = false, defaultValue = "") String reason,
			@RequestParam(name = "request_date", required = false, defaultValue = "") String requestDate,
			@RequestParam(name = "rejection_reason", required = false, defaultValue = "") String rejectionReason,
			Model model, RedirectAttributes redirect) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();

		try {
			long wrote = switch (action) {
				case "add_advance" -> this.service.add(
						session, adminId, employeeId, amount, reason, requestDate);
				case "edit_advance" -> this.service.saveEdit(
						session, adminId, id, employeeId, amount, reason, requestDate);
				case "approve" -> this.service.approve(session, adminId, id);
				case "reject" -> this.service.reject(session, adminId, id, rejectionReason);
				case "mark_paid" -> this.service.markPaid(session, adminId, id);
				case "delete_advance" -> this.service.delete(session, adminId, id);
				default -> throw new AdvanceAdminService.RefusedException(
						AdvanceAdminService.Refusal.INVALID);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			switch (action) {
				case "approve" -> AdminFlash.approved(redirect, model);
				case "reject" -> AdminFlash.rejected(redirect, model);
				default -> AdminFlash.saved(redirect, model);
			}
			return "redirect:" + PATH + AdminReturnTo.query(request, PATH, wrote);
		} catch (AdvanceAdminService.RefusedException refused) {
			return "redirect:" + PATH + AdminReturnTo.queryWithError(request, PATH, messageKey(refused));
		}
	}

	private static String messageKey(AdvanceAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FOREIGN_ROW -> "error_db";
			case INVALID -> "error_required";
			case REASON_REQUIRED -> "rejection_reason_required";
		};
	}

}
