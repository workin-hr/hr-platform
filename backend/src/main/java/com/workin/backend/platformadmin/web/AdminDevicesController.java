package com.workin.backend.platformadmin.web;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.devices.AdminDeviceActions;
import com.workin.backend.platformadmin.org.ActiveCompanies;
import com.workin.devices.agent.DeviceAgent;
import com.workin.devices.agent.DeviceAgentService;
import com.workin.devices.api.DeviceAdministrationService;
import com.workin.devices.ingest.DeviceFileImportService;
import com.workin.devices.registry.AttendanceDevice;
import com.workin.legacy.PhpCast;

/**
 * Attendance terminals across every company: what has reached the platform,
 * what each terminal delivered, and the agents that read the ones that cannot
 * push. No PHP counterpart -- the dashboard never had devices.
 *
 * <p>One path, selected by {@code ?device=} like the org pages select by
 * {@code ?action=}, so the sidebar's current-page rule and the company filter
 * behave here as they do everywhere else. {@code ?live=1} refreshes the page on
 * a timer, which is what watching a terminal during a site visit needs and all
 * it needs.
 *
 * <p>Administrator-only ({@link DashboardAccess}): these reads are not
 * tenant-scoped, and the unclaimed serials belong to nobody.
 */
@Controller
public class AdminDevicesController {

	private static final String VIEW = "admin/devices";

	private static final String PATH = PlatformAdminWebSecurityConfig.DEVICES_PATH;

	private static final int LIVE_REFRESH_SECONDS = 10;

	private final DeviceAdministrationService devices;

	private final AdminDeviceActions actions;

	private final ActiveCompanies companies;

	public AdminDevicesController(DeviceAdministrationService devices, AdminDeviceActions actions,
			ActiveCompanies companies) {
		this.devices = devices;
		this.actions = actions;
		this.companies = companies;
	}

	@AuthenticatedUseCase(reason = "Every company's attendance terminals, unclaimed serials, punches "
			+ "and on-premises agents. Administrator-only; nothing here is tenant-scoped.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false) Long device,
			@RequestParam(required = false) String live,
			@RequestParam(required = false) String serial,
			@RequestParam(required = false) String error) {
		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);
		if (!DashboardAccess.canViewPage(current, "devices")) {
			return "redirect:/admin";
		}
		Long companyId = filters.companyId() > 0 ? filters.companyId() : null;
		model.addAttribute("filters", filters);
		model.addAttribute("errorKey", error == null ? null : errorKey(error));
		model.addAttribute("actionsEnabled", actions.actionsEnabled());
		model.addAttribute("liveSeconds", "1".equals(live) ? LIVE_REFRESH_SECONDS : 0);
		model.addAttribute("issuedAgent", model.getAttribute("issuedAgent"));
		model.addAttribute("allocateSerial", serial == null ? "" : serial.strip());

		// One page number per list, because they are on one screen and paging
		// one must not move the others. `per_page` is shared, as it is on
		// attendance: one size control, whichever table it sits under.
		int perPage = filters.perPage();
		int devPage = pageOf(request, "dev_page");
		int sightPage = pageOf(request, "sight_page");
		int agentPage = pageOf(request, "agent_page");
		int punchPage = pageOf(request, "punch_page");
		int malPage = pageOf(request, "mal_page");
		// The requested numbers, not the clamped ones: a pager link carries the
		// other pagers' pages, and carrying a clamped value would rewrite a
		// bookmarked page the moment another table is turned (attendance.jte
		// records the same reason).
		model.addAttribute("devPage", devPage);
		model.addAttribute("sightPage", sightPage);
		model.addAttribute("agentPage", agentPage);
		model.addAttribute("punchPage", punchPage);
		model.addAttribute("malPage", malPage);

		Optional<AttendanceDevice> selected = device == null ? Optional.empty() : devices.device(device)
				.filter(found -> DashboardOrgScope.canOpenRow(current, filters, found.companyId()));
		model.addAttribute("selected", selected.orElse(null));
		if (selected.isPresent()) {
			long id = selected.get().id();
			model.addAttribute("selectedRow", devices.deviceRow(id));
			model.addAttribute("counts", devices.punchCounts(id));
			model.addAttribute("punchResult", DashboardPage.of(
					devices.punches(null, id, perPage, DashboardPage.offsetFor(punchPage, perPage)),
					devices.punchCount(null, id), punchPage, perPage));
			model.addAttribute("malformedResult", DashboardPage.of(
					devices.malformed(id, perPage, DashboardPage.offsetFor(malPage, perPage)),
					devices.malformedCount(id), malPage, perPage));
			// The lists this half of the page does not render. An empty page
			// rather than an empty list, so the template takes one type for a
			// table whichever half it is in, and the pager draws nothing at
			// total 0.
			model.addAttribute("deviceResult", emptyPage(perPage));
			model.addAttribute("sightingResult", emptyPage(perPage));
			model.addAttribute("agentResult", DashboardPage.<DeviceAgent>of(List.of(), 0, 1, perPage));
			model.addAttribute("branches", List.of());
			model.addAttribute("companyOptions", List.of());
		} else {
			model.addAttribute("selectedRow", java.util.Map.of());
			model.addAttribute("counts", List.of());
			model.addAttribute("punchResult", DashboardPage.of(
					devices.punches(companyId, null, perPage, DashboardPage.offsetFor(punchPage, perPage)),
					devices.punchCount(companyId, null), punchPage, perPage));
			model.addAttribute("malformedResult", emptyPage(perPage));
			model.addAttribute("deviceResult", DashboardPage.of(
					devices.devices(companyId, perPage, DashboardPage.offsetFor(devPage, perPage)),
					devices.deviceCount(companyId), devPage, perPage));
			model.addAttribute("sightingResult", DashboardPage.of(
					devices.sightings(perPage, DashboardPage.offsetFor(sightPage, perPage)),
					devices.sightingCount(), sightPage, perPage));
			model.addAttribute("agentResult", DashboardPage.of(
					devices.agents(companyId, perPage, DashboardPage.offsetFor(agentPage, perPage)),
					devices.agentCount(companyId), agentPage, perPage));
			model.addAttribute("branches", devices.branches(companyId));
			model.addAttribute("companyOptions", companyId == null ? companies.all() : List.of());
		}
		return VIEW;
	}

	private static DashboardPage<java.util.Map<String, Object>> emptyPage(int perPage) {
		return DashboardPage.of(List.of(), 0, 1, perPage);
	}

	/**
	 * {@code max(1, (int) ($_GET[$name] ?? 1))}, read with {@link PhpCast#intval}
	 * so that {@code ?dev_page=abc} is page 1 rather than a 400 -- the rule every
	 * other dashboard page number follows.
	 */
	private static int pageOf(HttpServletRequest request, String name) {
		String raw = request.getParameter(name);
		if (raw == null || raw.isEmpty()) {
			return 1;
		}
		return (int) Math.clamp(PhpCast.intval(raw), 1, Integer.MAX_VALUE);
	}

	@AuthenticatedUseCase(reason = "Allocates a terminal to a company, switches one on or off, imports "
			+ "a USB export, or issues or revokes an on-premises agent. Gated by the surface flag and "
			+ "audited with the write.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model, RedirectAttributes redirect,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "branch_id", required = false, defaultValue = "0") long branchId,
			@RequestParam(name = "company_id", required = false, defaultValue = "0") long companyId,
			@RequestParam(required = false, defaultValue = "") String vendor,
			@RequestParam(name = "serial_number", required = false, defaultValue = "") String serialNumber,
			@RequestParam(required = false, defaultValue = "") String name,
			@RequestParam(name = "device_time_zone", required = false, defaultValue = "") String zone,
			@RequestParam(required = false, defaultValue = "") String active) {
		long adminId = principal.platformAdminId();
		DashboardSession session = DashboardSession.admin(DashboardOrgScope.current(request.getSession(false)));
		String back = "device_active".equals(action) || "import".equals(action) ? PATH + "?device=" + id : PATH;
		try {
			switch (action) {
				case "allocate" -> {
					AttendanceDevice device = actions.allocate(adminId, branchId, vendor, serialNumber, name, zone);
					// The page it redirects to shows a device only inside the company filter, so the
					// filter follows the write, as every org page's does.
					DashboardOrgScope.rememberAfterWrite(session, request, device.companyId());
					AdminFlash.success(redirect, AdminFlash.t(model).apply("device_allocated_ok"));
					return "redirect:" + PATH + "?device=" + device.id();
				}
				case "device_active" -> {
					AttendanceDevice device = actions.setActive(adminId, id, "1".equals(active));
					DashboardOrgScope.rememberAfterWrite(session, request, device.companyId());
					AdminFlash.saved(redirect, model);
				}
				case "import" -> {
					DeviceFileImportService.Result result = actions.importAttlog(adminId, id, fileName(request), fileBytes(request));
					var t = AdminFlash.t(model);
					AdminFlash.success(redirect, t.apply("device_import_done") + " "
							+ t.apply("device_import_lines") + ": " + result.lines() + ", "
							+ t.apply("device_punches_stored") + ": " + result.stored() + ", "
							+ t.apply("device_punches_duplicate") + ": " + result.duplicates() + ", "
							+ t.apply("device_punches_unmatched") + ": " + result.unmatched() + ", "
							+ t.apply("device_punches_malformed") + ": " + result.malformed());
				}
				case "agent_issue" -> {
					// Rendered, not redirected: the token exists only in this
					// response, and a redirect would have to carry it through the
					// session store to reach the next page.
					DeviceAgentService.IssuedAgent issued = actions.issueAgent(adminId, companyId, name);
					model.addAttribute("issuedAgent", issued);
					return page(principal, request, model, null, null, null, null);
				}
				case "agent_active" -> {
					actions.setAgentActive(adminId, id, "1".equals(active));
					AdminFlash.saved(redirect, model);
				}
				default -> throw new AdminDeviceActions.RefusedException("devices.nothing_to_update");
			}
			return "redirect:" + back;
		} catch (AdminDeviceActions.RefusedException refused) {
			return "redirect:" + back + (back.contains("?") ? "&" : "?") + "error=" + refused.code();
		}
	}

	private static String fileName(HttpServletRequest request) {
		MultipartFile file = multipart(request);
		return file == null ? null : file.getOriginalFilename();
	}

	private static byte[] fileBytes(HttpServletRequest request) {
		MultipartFile file = multipart(request);
		if (file == null || file.isEmpty()) {
			throw new AdminDeviceActions.RefusedException("device_import_no_file");
		}
		try {
			return file.getBytes();
		} catch (IOException ex) {
			throw new AdminDeviceActions.RefusedException("device_import_no_file");
		}
	}

	/** Resolved here rather than bound, because resolution is lazy (application.properties) and an oversized file must become a message, not a 500. */
	private static MultipartFile multipart(HttpServletRequest request) {
		try {
			return request instanceof org.springframework.web.multipart.MultipartHttpServletRequest multipart
					? multipart.getFile("file") : null;
		} catch (MultipartException ex) {
			throw new AdminDeviceActions.RefusedException("device_import_too_large");
		}
	}

	/** A code from the query string becomes a message key only if it is one this page issues. */
	static String errorKey(String code) {
		String key = code.toLowerCase(Locale.ROOT);
		return switch (key) {
			case "admin_actions_disabled", "device_import_no_file", "device_import_too_large" -> key;
			default -> key.startsWith("devices.") && key.length() < 64 && key.matches("devices\\.[a-z_]+")
					? "device_error_" + key.substring("devices.".length())
					: "error_db";
		};
	}
}
