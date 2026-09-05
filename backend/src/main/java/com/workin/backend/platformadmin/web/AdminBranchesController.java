package com.workin.backend.platformadmin.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.org.Branch;
import com.workin.backend.platformadmin.org.BranchAdminService;
import com.workin.backend.platformadmin.org.BranchStore;
import com.workin.legacy.LegacyClock;

/**
 * {@code dashboard/pages/branches/page.php}.
 *
 * <p>One GET serving three states -- the list, the add form, the edit form and
 * the QR panel -- selected by {@code ?action=}, because that is how the page
 * being reproduced works and every link in it points that way.
 *
 * <p>The POST answers with a redirect in every case, legacy's included: the
 * dashboard is a form-post application and a rendered response to a POST would
 * put a resubmission prompt in front of the operator on every refresh.
 */
@Controller
@Profile("phase1-mysql")
public class AdminBranchesController {

	private static final String VIEW = "admin/branches";

	private static final String PATH = PlatformAdminWebSecurityConfig.BRANCHES_PATH;

	private final BranchStore store;

	private final BranchAdminService service;

	private final LegacyClock clock;

	public AdminBranchesController(BranchStore store, BranchAdminService service, LegacyClock clock) {
		this.store = store;
		this.service = service;
		this.clock = clock;
	}

	@AuthenticatedUseCase(reason = "One company's branches. An administrator reaches every "
			+ "company's through the session filter, which is R-044's cross-tenant mode.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) Long id,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		// resolve() may have just changed the filter, so the session in the
		// model -- built before this handler ran -- can be one request stale.
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		boolean showCompany = DashboardOrgScope.showsCompanyColumn(current, filters.companyId());
		model.addAttribute("showCompanyColumn", showCompany);
		model.addAttribute("filters", filters);
		model.addAttribute("result", this.store.paginate(filters, showCompany));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "branches"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		model.addAttribute("addOpen", "add".equals(action));
		model.addAttribute("editRow", "edit".equals(action) ? visible(current, filters, id) : null);
		model.addAttribute("qrRow", "qr".equals(action) ? visible(current, filters, id) : null);
		model.addAttribute("now", this.clock.now());
		return VIEW;
	}

	/**
	 * {@code dbFind()} then the visibility test the page performs on the row.
	 *
	 * <p>Legacy's two tests are not the same. For a scoped session the row must
	 * belong to that company. For an administrator the row must match the
	 * <em>current filter</em> when one is set -- so filtering to company 3 and
	 * then following an edit link for a branch of company 9 shows nothing,
	 * rather than silently editing a company the operator is not looking at.
	 * With no filter set, any row is reachable, which is what "all companies"
	 * means.
	 */
	private Branch visible(DashboardSession session, DashboardListFilters filters, Long id) {
		if (id == null || id <= 0) {
			return null;
		}
		Branch row = this.store.find(id);
		if (row == null) {
			return null;
		}
		if (session.isScopedToOneCompany()) {
			return row.companyId() == session.companyId() ? row : null;
		}
		return filters.companyId() > 0 && row.companyId() != filters.companyId() ? null : row;
	}

	@AuthenticatedUseCase(reason = "Creates, edits, deactivates a branch or regenerates its "
			+ "check-in code. Gated in the service by the surface flag and a bound second "
			+ "factor, tenant-checked, and audited in the same transaction.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "company_id", required = false, defaultValue = "0") long companyId,
			@RequestParam(required = false, defaultValue = "") String name,
			@RequestParam(required = false, defaultValue = "") String address,
			@RequestParam(required = false, defaultValue = "") String lat,
			@RequestParam(required = false, defaultValue = "") String lng,
			@RequestParam(name = "radius_meters", required = false, defaultValue = "") String radius,
			@RequestParam(name = "is_active", required = false) String isActive,
			@RequestParam(name = "expires_at", required = false, defaultValue = "") String expiresAt) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();

		try {
			long wrote = switch (action) {
				case "add" -> this.service.add(
						session, adminId, bound, companyId, name, address, lat, lng, radius);
				case "save_edit" -> this.service.saveEdit(
						session, adminId, bound, id, companyId, name, address, lat, lng, radius,
						// An unchecked checkbox is absent from the post, and
						// `(int) ($_POST['is_active'] ?? 1)` reads absent as 1.
						// So the edit form must always send it, and this
						// mirrors the same default.
						isActive == null || !"0".equals(isActive.trim()));
				case "delete" -> this.service.delete(session, adminId, bound, id, companyId);
				case "generate_qr" -> this.service.generateQr(
						session, adminId, bound, id, companyId, expiresAt, this.clock.now());
				default -> throw new BranchAdminService.RefusedException(
						BranchAdminService.Refusal.NO_COMPANY);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "generate_qr".equals(action)
					? "redirect:" + PATH + "?action=qr&id=" + id
					: "redirect:" + PATH;
		} catch (BranchAdminService.RefusedException refused) {
			return "redirect:" + PATH + failureTail(action, id) + "&error=" + messageKey(refused);
		}
	}

	/**
	 * Legacy returns the operator to the form they were in, so a rejected add
	 * reopens the add form rather than dropping them on the list with their
	 * typing gone.
	 */
	private static String failureTail(String action, long id) {
		return switch (action) {
			case "add" -> "?action=add";
			case "save_edit" -> "?action=edit&id=" + id;
			case "generate_qr" -> id > 0 ? "?action=qr&id=" + id : "?";
			default -> "?";
		};
	}

	private static String messageKey(BranchAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			case NO_COMPANY -> "select_company_first";
			case FOREIGN_ROW -> "error_db";
			case BAD_EXPIRY -> "branch_qr_invalid_expiry";
		};
	}

}
