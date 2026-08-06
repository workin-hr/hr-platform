package com.workin.backend.authorization.archfixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.authorization.PublicUseCase;

/**
 * Deliberately violates F-23's exactly-one rule: two policy
 * declarations on one handler. See UndeclaredHandlerFixture for why
 * fixtures are safe to keep in test sources.
 */
@RestController
public class DoublyDeclaredHandlerFixture {

	@PublicUseCase(reason = "fixture")
	@AuthenticatedUseCase(reason = "fixture")
	@GetMapping("/archfixture/doubly-declared")
	public String get() {
		return "fixture";
	}

}
