package com.workin.backend.companysettings;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;
import com.workin.backend.tenancy.AuthorizationContext;

/**
 * Thin delegation, module template. Deliberately no path variables:
 * a company can only ever address its own settings row, so the
 * cross-tenant probe surface is structurally absent.
 */
@RestController
@RequestMapping("/api/tenant/company-settings")
public class CompanySettingsController {

	private final CompanySettingsService companySettingsService;

	public CompanySettingsController(CompanySettingsService companySettingsService) {
		this.companySettingsService = companySettingsService;
	}

	@RequiresPermission(PermissionKeys.COMPANY_SETTINGS_READ)
	@GetMapping
	public CompanySettingsView view(HttpServletRequest request) {
		return companySettingsService.view(contextFrom(request));
	}

	@RequiresPermission(PermissionKeys.COMPANY_SETTINGS_MANAGE)
	@PutMapping
	public CompanySettingsView upsert(
			HttpServletRequest request, @Valid @RequestBody UpdateCompanySettingsRequest body) {
		return companySettingsService.upsert(contextFrom(request), body);
	}

	private static AuthorizationContext contextFrom(HttpServletRequest request) {
		Object context = request.getAttribute(AuthorizationContext.class.getName());
		if (context == null) {
			throw new IllegalStateException("AuthorizationContext missing -- authorization interceptor not applied");
		}
		return (AuthorizationContext) context;
	}

}
