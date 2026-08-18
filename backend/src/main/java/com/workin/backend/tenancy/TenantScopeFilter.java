package com.workin.backend.tenancy;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Function;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the authenticated tenant to {@link TenantScope} for the life of
 * one request, and unbinds it unconditionally (ADR-0012 / D-041).
 *
 * <p><b>The {@code finally} is the control, not hygiene.</b> Under
 * PostgreSQL, {@code SET LOCAL} expired with its transaction whatever
 * happened. {@link TenantScope} is thread-local and the container serves
 * requests from a pool, so a scope left behind is the next request on
 * that thread reading the previous tenant's data — a cross-tenant read
 * caused by an exception on an unrelated path. Every exit route from
 * this filter must clear the scope, including the ones that throw, and
 * that is asserted rather than assumed.
 *
 * <p>The tenant id comes from a resolver over the request, and the
 * resolver's contract is that it derives the id from the
 * <em>authenticated principal</em> — never from a header, path variable
 * or body field. That is ADR-0012's second mandatory part; this filter
 * enforces the lifetime, the resolver is responsible for the trust.
 *
 * <p>An unresolvable tenant leaves the scope unestablished rather than
 * defaulting to anything. Downstream, a tenant-scoped read then fails
 * closed via {@link TenantScope#current()} instead of quietly reading
 * across tenants — which is why this filter never needs to reject a
 * request itself.
 */
public class TenantScopeFilter extends OncePerRequestFilter {

	private final TenantScope tenantScope;
	private final Function<HttpServletRequest, Optional<Long>> authenticatedTenantResolver;

	public TenantScopeFilter(
			TenantScope tenantScope,
			Function<HttpServletRequest, Optional<Long>> authenticatedTenantResolver) {
		this.tenantScope = tenantScope;
		this.authenticatedTenantResolver = authenticatedTenantResolver;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		Optional<Long> tenant = authenticatedTenantResolver.apply(request);
		tenant.ifPresent(tenantScope::enter);
		try {
			chain.doFilter(request, response);
		} finally {
			// Unconditional: not "if we entered". A scope established
			// deeper in the request -- or left behind by a pooled thread
			// that failed to clean up -- must not survive this frame
			// either.
			tenantScope.exit();
		}
	}

}
