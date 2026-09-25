package com.workin.backend.platformadmin.web;

import java.time.LocalDate;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.hr.AttendanceAdminService;
import com.workin.backend.platformadmin.hr.AttendanceRecord;
import com.workin.backend.platformadmin.hr.AttendanceStore;
import com.workin.backend.platformadmin.hr.EmployeeStore;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.PhpCast;
import com.workin.legacy.wire.LegacyMessages;

/** {@code dashboard/pages/attendance/page.php}. */
@Controller
public class AdminAttendanceController {

	private static final String VIEW = "admin/attendance";

	private static final String PATH = PlatformAdminWebSecurityConfig.ATTENDANCE_PATH;

	private final AttendanceStore store;

	private final AttendanceAdminService service;

	private final EmployeeStore employeeStore;

	private final LegacyClock clock;

	private final LegacyMessages messages;

	public AdminAttendanceController(
			AttendanceStore store, AttendanceAdminService service, EmployeeStore employeeStore,
			LegacyClock clock, LegacyMessages messages) {
		this.store = store;
		this.service = service;
		this.employeeStore = employeeStore;
		this.clock = clock;
		this.messages = messages;
	}

	@AuthenticatedUseCase(reason = "One company's attendance punches, which payroll is computed "
			+ "from. An administrator reaches every company's through the session filter; a "
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

		// `$_GET['from'] ?? date('Y-m-01')` and `$_GET['to'] ?? date('Y-m-d')`.
		// Legacy passes these straight through into the range-delete button's
		// confirm() text (page.php:187) inside a json_encode() call, which
		// neutralises anything they hold; the port drops that value into a
		// single-quoted JS string by concatenation (attendance.jte), so an
		// unvalidated value could break out of it. Java-only: a malformed
		// ?from=/?to= falls back to the default range instead, the same way a
		// blank one already does.
		LocalDate today = this.clock.today();
		String from = validDateOr(request.getParameter("from"), today.withDayOfMonth(1).toString());
		String to = validDateOr(request.getParameter("to"), today.toString());
		int aggPage = positiveOr(request.getParameter("agg_page"), 1);

		long optionsCompanyId = filters.companyId() > 0
				? filters.companyId() : (current.isScopedToOneCompany() ? current.companyId() : 0);
		String weeklyRestLabel = this.messages.translate(
				this.messages.resolveLocale(request), "schedule_weekly_rest", null);

		model.addAttribute("showCompanyColumn",
				DashboardOrgScope.showsCompanyColumn(current, filters.companyId()));
		model.addAttribute("filters", filters);
		model.addAttribute("from", from);
		model.addAttribute("to", to);
		model.addAttribute("aggPage", aggPage);
		var result = this.store.paginateDetail(filters, from, to, weeklyRestLabel);
		model.addAttribute("result", result);
		// The types the listed rows carry: the edit dialog needs an option for each row's own type.
		java.util.List<Long> rowTypeIds = result.data().stream()
				.map(AttendanceRecord.Row::exceptionTypeId)
				.filter(java.util.Objects::nonNull)
				.distinct()
				.toList();
		model.addAttribute("aggregateResult", this.store.aggregate(
				filters, from, to, aggPage,
				current.isScopedToOneCompany() ? current.companyId() : 0,
				today.toString()));
		model.addAttribute("employeeOptions", this.store.employeeOptions(optionsCompanyId));
		model.addAttribute("exceptionTypes", this.store.exceptionTypeOptions(optionsCompanyId));
		// With no company chosen the table lists every company's rows and a type belongs to one
		// company, so the edit dialog offers no type choice: it carries each row's type back.
		model.addAttribute("editChoosesType", optionsCompanyId > 0);
		model.addAttribute("editExceptionTypes", optionsCompanyId > 0
				? this.store.editableExceptionTypeOptions(optionsCompanyId, rowTypeIds)
				: java.util.List.<AttendanceRecord.ExceptionTypeOption>of());
		model.addAttribute("branchOptions", this.employeeStore.branchOptions(optionsCompanyId));
		model.addAttribute("departmentOptions", this.employeeStore.departmentOptions(optionsCompanyId));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "attendance"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	@AuthenticatedUseCase(reason = "Adds, edits, deletes one attendance punch, or clears a whole "
			+ "date range for a company. Gated by the surface flag and a bound second factor, "
			+ "tenant-checked through the employee join (R-046/R-059), and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "employee_id", required = false, defaultValue = "0") long employeeId,
			@RequestParam(name = "company_id", required = false, defaultValue = "0") long companyId,
			@RequestParam(name = "check_in", required = false, defaultValue = "") String checkIn,
			@RequestParam(name = "check_out", required = false, defaultValue = "") String checkOut,
			@RequestParam(name = "exception_type_id", required = false, defaultValue = "0")
					long exceptionTypeId,
			@RequestParam(required = false, defaultValue = "") String from,
			@RequestParam(required = false, defaultValue = "") String to,
			Model model, RedirectAttributes redirect) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		Long exceptionType = AttendanceAdminService.exceptionTypeOrNull(exceptionTypeId);

		try {
			switch (action) {
				case "add_attendance" -> this.service.add(
						session, adminId, employeeId, checkIn, checkOut, exceptionType);
				case "edit_attendance" -> this.service.saveEdit(
						session, adminId, id, checkIn, checkOut, exceptionType);
				case "delete" -> this.service.delete(session, adminId, id);
				case "delete_range" -> {
					AttendanceAdminService.RangeDeletion range = this.service.deleteRange(
							session, adminId, companyId, from, to,
							this.clock.today().toString());
					// Legacy flashes the count it removed as an error, as it does a single delete.
					AdminFlash.error(redirect, AdminFlash.t(model).apply("att_range_deleted")
							.replace("{count}", String.valueOf(range.deleted())));
				}
				default -> throw new AttendanceAdminService.RefusedException(
						AttendanceAdminService.Refusal.INVALID);
			}
			switch (action) {
				case "add_attendance", "edit_attendance" -> AdminFlash.saved(redirect, model);
				case "delete" -> AdminFlash.deleted(redirect, model);
				default -> {
				}
			}
			// `payroll_redirect('attendance', $cid)` passes the filter already in
			// force, not the company just written to -- so unlike its sibling
			// pages, which pass `hr_post_company_id()`, this one never moves an
			// unfiltered administrator's filter. DashboardOrgScope.rememberAfterWrite
			// is therefore deliberately not called here.
			return "redirect:" + PATH + AdminReturnTo.query(request, PATH, 0L);
		} catch (AttendanceAdminService.RefusedException refused) {
			return "redirect:" + PATH + AdminReturnTo.queryWithError(request, PATH, messageKey(refused));
		}
	}

	private static String messageKey(AttendanceAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FOREIGN_ROW -> "error_db";
			case INACTIVE_TYPE -> "exception_type_inactive";
			case INVALID -> "error_required";
		};
	}

	/** {@code $_GET['from'] ?? $fallback}, but a value that is not a real {@code YYYY-MM-DD} date is blank too. */
	private static String validDateOr(String raw, String fallback) {
		if (raw == null || raw.isEmpty()) {
			return fallback;
		}
		try {
			return LocalDate.parse(raw).toString();
		} catch (java.time.format.DateTimeParseException malformed) {
			return fallback;
		}
	}

	/** {@code max(1, (int) ($_GET['agg_page'] ?? 1))}, read with {@link PhpCast#intval}. */
	private static int positiveOr(String raw, int fallback) {
		if (raw == null || raw.isEmpty()) {
			return fallback;
		}
		return Math.max(fallback, Math.clamp(PhpCast.intval(raw), Integer.MIN_VALUE, Integer.MAX_VALUE));
	}

}
