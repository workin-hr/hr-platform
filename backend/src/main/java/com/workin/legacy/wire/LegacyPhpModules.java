package com.workin.legacy.wire;

import java.util.List;
import java.util.Set;

/**
 * {@code ApiModule::allowedList()}, ported literally.
 *
 * <p>{@code apis/api/index.php} resolves the first path segment against this
 * list <em>before</em> it looks for an action file and before any
 * authentication runs. A module that is not on it is a 404 that names the list
 * back to the caller, which is why the order matters as much as the contents:
 * PHP emits {@code implode(', ', $allowedModules)}, so this is
 * {@code allowedList()}'s own order, not alphabetical.
 *
 * <p><b>{@code reports} is on the list and has no directory.</b> That is not a
 * transcription slip -- it is the C4 anomaly recorded in
 * {@code docs/migration/2026-08-23-phase1-completion-plan.md}: an advertised
 * module on which every action answers 501. It is included because PHP includes
 * it, and dropping it would change the {@code module_not_found} body.
 */
public final class LegacyPhpModules {

	/** Ported from {@code apis/config/http_api.php}; order is PHP's. */
	public static final List<String> ALLOWED = List.of(
			"administrative_decisions", "advances", "app_content", "faqs", "attendance", "auth",
			"banners", "branches", "company", "company_settings", "company_official_holidays",
			"setting_definitions", "setting_allowed_values", "complaints", "configs", "departments",
			"employee_docs", "assets", "employees", "hr_employees", "company_join_requests",
			"job_titles", "workforce_planning", "leave_balances", "notifications",
			"payroll_batches", "payslips", "penalties", "phone_countries", "profile", "reports",
			"request_types", "requests", "salary_contracts", "shifts", "schedules",
			"attendance_exception_types", "dashboard");

	private static final Set<String> ALLOWED_SET = Set.copyOf(ALLOWED);

	/** The {@code {list}} placeholder in {@code module_not_found}. */
	public static final String ALLOWED_CSV = String.join(", ", ALLOWED);

	public static boolean isAllowed(String module) {
		return ALLOWED_SET.contains(module);
	}

	private LegacyPhpModules() {
	}

}
