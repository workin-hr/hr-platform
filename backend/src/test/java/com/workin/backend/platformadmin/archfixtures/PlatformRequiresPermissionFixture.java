package com.workin.backend.platformadmin.archfixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.authorization.RequiresPermission;

/**
 * Deliberately violates the platform-domain confinement rule:
 * a @RequiresPermission usage inside the platformadmin package, where
 * no permission-evaluation path exists yet. Test sources only -- never
 * component-scanned, and the production arch rules import with
 * DO_NOT_INCLUDE_TESTS; this exists purely as proven-to-fail evidence
 * for AuthorizationPolicyArchTest.
 */
@RestController
public class PlatformRequiresPermissionFixture {

	@RequiresPermission("platform.companies.read")
	@GetMapping("/archfixture/platform-requires-permission")
	public String get() {
		return "fixture";
	}

}
