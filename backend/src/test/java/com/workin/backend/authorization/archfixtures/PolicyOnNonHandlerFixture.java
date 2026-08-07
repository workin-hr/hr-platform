package com.workin.backend.authorization.archfixtures;

import com.workin.backend.authorization.PublicUseCase;

/**
 * Deliberately violates F-23's placement rule: a policy annotation on
 * a method that is not a controller handler -- nothing would ever
 * enforce it there. See UndeclaredHandlerFixture for why fixtures are
 * safe in test sources.
 */
public class PolicyOnNonHandlerFixture {

	@PublicUseCase(reason = "fixture")
	public String notAHandler() {
		return "fixture";
	}

}
