package com.workin.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The request-boundary half of ADR-0012: scope is bound for exactly one
 * request and released whatever happens.
 *
 * <p>The release matters more than the bind. {@code SET LOCAL} expired
 * with its transaction unconditionally; a thread-local does not, and the
 * container serves requests from a pool — so a scope surviving a failed
 * request is the <em>next</em> request on that thread reading the
 * previous tenant's data. That is a cross-tenant read produced by an
 * exception on an unrelated code path, which is exactly the kind of bug
 * that does not show up in the test for the path that threw.
 */
class TenantScopeFilterTest {

	private final TenantScope tenantScope = new TenantScope();

	@AfterEach
	void clear() {
		tenantScope.exit();
	}

	private TenantScopeFilter filterResolving(Long tenantId) {
		return new TenantScopeFilter(tenantScope, request -> Optional.ofNullable(tenantId));
	}

	@Test
	void theAuthenticatedTenantIsVisibleForTheDurationOfTheRequest() throws Exception {
		AtomicReference<Long> seenDownstream = new AtomicReference<>();
		FilterChain chain = (req, res) -> seenDownstream.set(tenantScope.current());

		filterResolving(42L).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

		assertThat(seenDownstream.get()).isEqualTo(42L);
	}

	@Test
	void theScopeIsReleasedAfterANormalRequest() throws Exception {
		filterResolving(42L).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> { });

		assertThat(tenantScope.isEstablished()).isFalse();
	}

	/**
	 * The assertion this whole class exists for. A downstream failure
	 * must not leave the thread scoped for whoever gets it next.
	 */
	@Test
	void theScopeIsReleasedEvenWhenTheRequestFails() {
		FilterChain exploding = (req, res) -> {
			throw new IllegalStateException("downstream blew up");
		};

		assertThatThrownBy(() -> filterResolving(42L).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(), exploding))
				.isInstanceOf(IllegalStateException.class);

		assertThat(tenantScope.isEstablished())
				.describedAs("a failed request must not leave the pooled thread scoped")
				.isFalse();
	}

	/**
	 * An unauthenticated request establishes nothing and defaults to
	 * nothing. Downstream then fails closed on
	 * {@link TenantScope#current()} rather than reading across tenants,
	 * which is why this filter never has to reject a request itself.
	 */
	@Test
	void anUnresolvableTenantLeavesTheScopeUnestablishedRatherThanDefaulted() throws Exception {
		AtomicReference<Boolean> establishedDownstream = new AtomicReference<>();
		FilterChain chain = (req, res) -> establishedDownstream.set(tenantScope.isEstablished());

		filterResolving(null).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

		assertThat(establishedDownstream.get()).isFalse();
	}

	/**
	 * A scope established deeper in the request — or leaked onto this
	 * thread by an earlier one that failed to clean up — must not
	 * survive either. The cleanup is unconditional, not conditional on
	 * this filter having been the one to bind.
	 */
	@Test
	void aScopeEstablishedDownstreamIsAlsoReleased() throws Exception {
		FilterChain scopesLate = (req, res) -> tenantScope.enter(99L);

		filterResolving(null).doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(), scopesLate);

		assertThat(tenantScope.isEstablished()).isFalse();
	}

}
