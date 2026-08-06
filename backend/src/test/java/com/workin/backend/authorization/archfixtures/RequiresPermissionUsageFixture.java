package com.workin.backend.authorization.archfixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.authorization.RequiresPermission;

/**
 * Deliberately violates the @RequiresPermission freeze tripwire: usage
 * before runtime enforcement exists (F-15/F-17). See
 * UndeclaredHandlerFixture for why fixtures are safe in test sources.
 */
@RestController
public class RequiresPermissionUsageFixture {

	@RequiresPermission("employees.read")
	@GetMapping("/archfixture/requires-permission")
	public String get() {
		return "fixture";
	}

}
