package com.workin.backend.platformadmin.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.hr.JoinRequestAdminService;
import com.workin.backend.platformadmin.hr.JoinRequestStore;

/** {@code dashboard/pages/join_requests/page.php}. */
@Controller
@Profile("phase1-mysql")
public class AdminJoinRequestsController {

	private static final String VIEW = "admin/join-requests";

	private static final String PATH = PlatformAdminWebSecurityConfig.JOIN_REQUESTS_PATH;

	/**
	 * The filter's four options, and the fallback for anything else.
	 *
	 * <p>{@code rejected} is offered and will always be empty: rejecting
	 * deletes the employee row rather than setting that status, on this surface
	 * and in the API alike. Kept because the column allows the value and the
	 * page offers the option -- removing it would be a change to the page, not
	 * a fix to it.
	 */
	private static final List<String> STATUSES = List.of("pending", "accepted", "rejected", "all");

	private final JoinRequestStore store;

	private final JoinRequestAdminService service;

	public AdminJoinRequestsController(JoinRequestStore store, JoinRequestAdminService service) {
		this.store = store;
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "Employees waiting to be admitted to a company, filtered by "
			+ "status. Gated by the employees permission, and scoped to one company for any "
			+ "session that is scoped to one.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false, defaultValue = "pending") String status,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		if (!DashboardAccess.canViewHrSection(current, "employees")) {
			return "redirect:" + PlatformAdminWebSecurityConfig.PATH_PREFIX;
		}

		String selected = STATUSES.contains(status) ? status : "pending";
		model.addAttribute("filters", filters);
		model.addAttribute("status", selected);
		model.addAttribute("statuses", STATUSES);
		model.addAttribute("items", this.store.list(filters.companyId(), selected));
		model.addAttribute("canManage", true);
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	@AuthenticatedUseCase(reason = "Admits one employee to a company, or refuses them. Refusing "
			+ "deletes the employee row, which is what legacy does on both surfaces, so the "
			+ "action is confined to a request that is still pending.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "redirect_status", required = false, defaultValue = "pending")
					String redirectStatus) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();
		String back = STATUSES.contains(redirectStatus) ? redirectStatus : "pending";

		try {
			switch (action) {
				case "accept_join" -> this.service.accept(session, adminId, bound, id);
				case "reject_join" -> this.service.reject(session, adminId, bound, id);
				default -> throw new JoinRequestAdminService.RefusedException(
						JoinRequestAdminService.Refusal.FOREIGN_ROW);
			}
		}
		catch (JoinRequestAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?status=" + back + "&error=" + messageFor(refused);
		}
		return "redirect:" + PATH + "?status=" + back;
	}

	/**
	 * Legacy flashes {@code join_accept_failed} or {@code join_reject_failed}
	 * for every refusal on this page, without saying which rule refused. That
	 * is the right shape for a tenant check -- telling a caller that a row
	 * exists but belongs to someone else is the disclosure the check exists to
	 * prevent -- so the distinction stays in the audit log and the refusal
	 * enum, not in the message.
	 */
	private static String messageFor(JoinRequestAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			default -> "join_accept_failed";
		};
	}

}
