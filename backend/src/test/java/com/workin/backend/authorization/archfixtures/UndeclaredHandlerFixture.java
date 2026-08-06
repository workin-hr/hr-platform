package com.workin.backend.authorization.archfixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately violates F-23's completeness rule: a handler method with
 * no authorization-policy declaration. Test sources only -- never
 * component-scanned (Spring scans main sources' package tree at
 * runtime, and the production arch rules import with
 * DO_NOT_INCLUDE_TESTS), so this exists purely as proven-to-fail
 * evidence for AuthorizationPolicyArchTest.
 */
@RestController
public class UndeclaredHandlerFixture {

	@GetMapping("/archfixture/undeclared")
	public String get() {
		return "fixture";
	}

}
