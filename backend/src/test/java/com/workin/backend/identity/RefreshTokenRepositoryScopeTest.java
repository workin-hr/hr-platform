package com.workin.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The tripwire behind issue #74.
 *
 * <p>{@code refresh_tokens} is the only table carrying a
 * {@code company_id} that has no row-level security, and it cannot get
 * one: the token-hash lookup necessarily runs before any tenant context
 * exists. What keeps that safe is a property of this interface rather
 * than of the database — every finder is keyed on something
 * unguessable or already proven, and none accepts a company.
 *
 * <p>A property nothing checks is a property that decays. This test
 * fails the build the moment a new query method appears, so whoever
 * adds it has to state why it cannot read across tenants. It is
 * deliberately a plain reflection test with no Spring context: the
 * point is the shape of the interface, not its runtime behaviour.
 */
class RefreshTokenRepositoryScopeTest {

	/**
	 * Every declared method, with the reason each is safe without RLS.
	 * Adding an entry is the moment to think, not a formality.
	 */
	private static final Set<String> REVIEWED_QUERY_METHODS = Set.of(
			// SHA-256 of an opaque random token -- unguessable, and the
			// only identifier a caller has before it is authenticated.
			"findByTokenHash",
			// Primary key, on a row the caller has already resolved.
			"transitionIfStatus",
			// A family UUID, reachable only from a token whose hash the
			// caller presented.
			"setStatusForFamily",
			// The authenticated identity. Spans that identity's companies
			// on purpose -- logout-everywhere and password change must not
			// leave a session alive in another tenant.
			"setStatusForIdentity");

	@Test
	void everyQueryMethodHasBeenReviewedForCrossTenantReach() {
		Set<String> declared = Arrays.stream(RefreshTokenRepository.class.getDeclaredMethods())
				.map(Method::getName)
				.collect(Collectors.toSet());

		assertThat(declared)
				.as("A new RefreshTokenRepository method must be reviewed for cross-tenant reach and "
						+ "listed in REVIEWED_QUERY_METHODS -- this table has no RLS backstop (issue #74)")
				.isEqualTo(REVIEWED_QUERY_METHODS);
	}

	@Test
	void noFinderIsKeyedOnCompany() {
		Set<String> companyKeyed = Arrays.stream(RefreshTokenRepository.class.getDeclaredMethods())
				.map(Method::getName)
				.filter(name -> name.contains("CompanyId") || name.contains("ByCompany"))
				.collect(Collectors.toSet());

		assertThat(companyKeyed)
				.as("A company-keyed query on an RLS-less table is the exact shape that would read "
						+ "across tenants -- scope it through the membership instead")
				.isEmpty();
	}

}
