package com.workin.backend.platformadmin.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.hr.PayrollAdminService;
import com.workin.backend.platformadmin.hr.PayrollRecord;
import com.workin.backend.platformadmin.hr.PayrollStore;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.payroll.LegacyPayslipService;
import com.workin.legacy.wire.LegacyMessages;

/** {@code dashboard/pages/payroll/page.php}. */
@Controller
public class AdminPayrollController {

	private static final String VIEW = "admin/payroll";

	private static final String PATH = PlatformAdminWebSecurityConfig.PAYROLL_PATH;

	private final PayrollStore store;

	private final PayrollAdminService service;

	private final LegacyPayslipService payslipService;

	private final LegacyClock clock;

	private final LegacyMessages messages;

	public AdminPayrollController(
			PayrollStore store, PayrollAdminService service,
			LegacyPayslipService payslipService, LegacyClock clock, LegacyMessages messages) {
		this.store = store;
		this.service = service;
		this.payslipService = payslipService;
		this.clock = clock;
		this.messages = messages;
	}

	@AuthenticatedUseCase(reason = "One company's payroll batches and the payslips inside them. "
			+ "An administrator reaches every company's through the session filter; a "
			+ "company-scoped session is held to its own by DashboardOrgScope.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		int month = positiveOr(request.getParameter("month"), 0);
		int year = positiveOr(request.getParameter("year"), 0);
		long runId = longOr(request.getParameter("run_id"), 0);

		long scopedCompanyId = current.isScopedToOneCompany() ? current.companyId() : 0;
		PayrollRecord.CurrentBatch currentBatch = runId > 0
				? this.store.currentBatch(runId, scopedCompanyId) : null;
		// `else { $viewRunId = 0; }` -- an unreachable batch falls back to the list.
		long viewRunId = currentBatch == null ? 0 : runId;

		model.addAttribute("filters", filters);
		model.addAttribute("month", month);
		model.addAttribute("year", year);
		model.addAttribute("viewRunId", viewRunId);
		model.addAttribute("currentBatch", currentBatch);
		model.addAttribute("showCompanyColumn",
				DashboardOrgScope.showsCompanyColumn(current, filters.companyId()));

		if (viewRunId > 0) {
			model.addAttribute("detailResult",
					enrichedPayslips(request, currentBatch, filters));
			model.addAttribute("batchTotals", this.store.batchTotals(viewRunId));
			model.addAttribute("batchResult", null);
		}
		else {
			model.addAttribute("detailResult", null);
			model.addAttribute("batchTotals", null);
			model.addAttribute("batchResult", this.store.paginateBatches(
					filters.companyId(), month, year, filters.page(), filters.perPage()));
		}

		model.addAttribute("editDetail", editDetailOrNull(request, current));
		model.addAttribute("companyOptions",
				current.isScopedToOneCompany() ? java.util.List.of() : this.store.activeCompanies());
		model.addAttribute("today", this.clock.today());
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "payroll"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	/**
	 * {@code payroll_paginate_payslips()} and then
	 * {@code payroll_enrich_payslip_rows()}, which is what PHP does with the
	 * same rows.
	 *
	 * <p>The enrichment is the API's, and reusing it here is the faithful
	 * reading rather than a convenience: {@code payroll_enrich_payslip_row()} is
	 * one function with callers on both sides. Contrast {@code finalize}, where
	 * the two sides genuinely differ and the port keeps them apart.
	 */
	private DashboardPage<java.util.Map<String, Object>> enrichedPayslips(
			HttpServletRequest request, PayrollRecord.CurrentBatch batch,
			DashboardListFilters filters) {

		// `$periodTo = (string) ($currentRun['period_to'] ?? date('Y-m-d'))`.
		String periodTo = batch.periodTo() == null || batch.periodTo().isEmpty()
				? this.clock.today().toString() : batch.periodTo();
		DashboardPage<java.util.Map<String, Object>> raw = this.store.paginatePayslips(
				batch.id(), periodTo, filters.page(), filters.perPage());
		if (raw.isEmpty()) {
			return raw;
		}
		String locale = this.messages.resolveLocale(request);
		java.util.List<java.util.Map<String, Object>> enriched = this.payslipService.enrichRows(
				raw.data(), batch.companyId(),
				// The same three keys LegacyPayslipController passes. They feed
				// `present_details` only, which this table does not render, but
				// there is no reason for the two callers to disagree.
				this.messages.translate(locale, "csv_attendance_present_day", null),
				this.messages.translate(locale, "schedule_weekly_rest", null),
				this.messages.translate(locale, "csv_official_holiday_days", null));
		return new DashboardPage<>(enriched, raw.total(), raw.page(), raw.perPage(),
				raw.pages(), raw.from(), raw.to());
	}

	/**
	 * {@code ?action=edit_detail&id=} opens one payslip's form.
	 *
	 * <p>PHP loads it with an unscoped {@code WHERE pd.id = ?}, so a filtered
	 * session can read another company's payslip into the form. The row is
	 * resolved the same way and then held to the session's own company, which
	 * is the read-side half of the R-064 divergence: refusing the write while
	 * still rendering the victim's salary would close nothing.
	 */
	private PayrollRecord.PayslipRow editDetailOrNull(
			HttpServletRequest request, DashboardSession session) {
		if (!"edit_detail".equals(request.getParameter("action"))) {
			return null;
		}
		long id = longOr(request.getParameter("id"), 0);
		if (id <= 0) {
			return null;
		}
		Long owner = this.store.companyOfPayslip(id);
		if (owner == null) {
			return null;
		}
		if (session.isScopedToOneCompany() && owner != session.companyId()) {
			return null;
		}
		if (session.companyId() > 0 && owner != session.companyId()) {
			return null;
		}
		return this.store.payslipForEdit(id);
	}

	@AuthenticatedUseCase(reason = "Creates, calculates, finalizes, reopens or deletes a payroll "
			+ "batch, or rewrites one payslip's amounts. Gated by the surface flag and a bound "
			+ "second factor, tenant-checked against the batch's own company (R-064), and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "company_id", required = false, defaultValue = "0") long companyId,
			@RequestParam(required = false, defaultValue = "0") int month,
			@RequestParam(required = false, defaultValue = "0") int year) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();

		try {
			if ("edit_detail".equals(action)) {
				PayrollAdminService.DetailEdit edited = this.service.editDetail(
						session, adminId, id, payslipEditFrom(request));
				// `header('Location: payroll.php?run_id=...')` -- back to the batch,
				// and this one does not go through payroll_redirect() at all.
				return "redirect:" + PATH + "?run_id=" + edited.batchId();
			}

			switch (action) {
				case "create_run" -> this.service.createRun(
						session, adminId, companyId, month, year);
				case "calculate" -> this.service.calculate(
						session, adminId, id, weeklyRestLabel(request));
				case "finalize" -> this.service.finalizeRun(session, adminId, id);
				case "reopen" -> this.service.reopenRun(session, adminId, id);
				case "delete_run" -> this.service.deleteRun(session, adminId, id);
				default -> throw new PayrollAdminService.RefusedException(
						PayrollAdminService.Refusal.INVALID);
			}
			// `payroll_redirect('payroll', $cidFilter)` passes the filter already
			// in force, not the company just written to. An unfiltered
			// administrator stays unfiltered after finalizing one company's batch
			// and can go straight on to another's -- which is R-044's reach and
			// is what AdminPayrollTenantIsolationTest asserts.
			return "redirect:" + PATH;
		}
		catch (PayrollAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?error=" + messageKey(refused);
		}
	}

	/**
	 * The twelve posted values, with PHP's own split between the six it
	 * defaults to zero and the six it reads raw. See
	 * {@code PayrollStore.PayslipEdit}.
	 */
	private static PayrollStore.PayslipEdit payslipEditFrom(HttpServletRequest request) {
		return new PayrollStore.PayslipEdit(
				defaulted(request, "days_present"),
				defaulted(request, "days_absent"),
				defaulted(request, "days_leave"),
				defaulted(request, "overtime_hours"),
				raw(request, "basic_salary"),
				raw(request, "allowances"),
				raw(request, "overtime_pay"),
				raw(request, "penalties_total"),
				raw(request, "advance_deduction"),
				defaulted(request, "advances_deduction"),
				defaulted(request, "other_deductions"),
				raw(request, "net_salary"));
	}

	/** `$_POST['x'] ?? 0`. */
	private static java.math.BigDecimal defaulted(HttpServletRequest request, String field) {
		return PayrollAdminService.decimalOrDefault(
				request.getParameter(field), java.math.BigDecimal.ZERO);
	}

	/** `$_POST['x']` with no coalesce: absent means NULL, not zero. */
	private static java.math.BigDecimal raw(HttpServletRequest request, String field) {
		return PayrollAdminService.decimalOrDefault(request.getParameter(field), null);
	}

	private String weeklyRestLabel(HttpServletRequest request) {
		return this.messages.translate(
				this.messages.resolveLocale(request), "schedule_weekly_rest", null);
	}

	private static String messageKey(PayrollAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FOREIGN_ROW -> "error_db";
			case INVALID -> "error_required";
			case DUPLICATE_PERIOD -> "payroll_batch_exists";
		};
	}

	private static int positiveOr(String raw, int fallback) {
		return (int) longOr(raw, fallback);
	}

	private static long longOr(String raw, long fallback) {
		if (raw == null || raw.isEmpty()) {
			return fallback;
		}
		try {
			return Math.max(0, Long.parseLong(raw.trim()));
		}
		catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

}
