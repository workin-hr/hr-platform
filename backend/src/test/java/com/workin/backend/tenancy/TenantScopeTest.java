package com.workin.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The Phase 1 fail-closed contract (ADR-0012 / D-041).
 *
 * <p>PostgreSQL RLS failed closed for free: with no
 * {@code app.current_company_id} set, an unscoped read returned zero
 * rows ({@code RlsFailClosedTest}). A Hibernate filter does the
 * opposite — a filter that was never enabled does not restrict
 * anything, so the same query returns <em>every tenant's</em> rows.
 * Fail-open is the whole risk of the mechanism ADR-0012 adopts.
 *
 * <p>So this contract is deliberately stricter than the RLS behaviour it
 * replaces: no scope established is an <b>error</b>, not an empty
 * result. Returning empty would be safe but would hide the bug;
 * returning rows would be a cross-tenant breach. Raising is the only
 * option that is both safe and loud.
 *
 * <p>Runs without a database on purpose. The property being asserted is
 * the one a missing container would otherwise leave untested, and it is
 * the property the whole Phase 1 isolation posture rests on.
 */
class TenantScopeTest {

	private final TenantScope scope = new TenantScope();

	@AfterEach
	void clearScope() {
		scope.exit();
	}

	@Test
	void withNoScopeEstablishedReadingTheTenantRaisesRatherThanReturningAnything() {
		assertThat(scope.isEstablished()).isFalse();

		assertThatThrownBy(scope::current)
				.isInstanceOf(NoTenantScopeException.class)
				.hasMessageContaining("no tenant scope");
	}

	@Test
	void anEstablishedScopeReadsBack() {
		scope.enter(42L);

		assertThat(scope.isEstablished()).isTrue();
		assertThat(scope.current()).isEqualTo(42L);
	}

	/**
	 * The scope must not survive the unit of work that established it.
	 * {@code SET LOCAL} died with its transaction automatically; a
	 * thread-local does not, and a leaked scope is a request reading the
	 * previous request's tenant — on a pooled thread, someone else's
	 * company.
	 */
	@Test
	void exitingRemovesTheScopeCompletely() {
		scope.enter(42L);
		scope.exit();

		assertThat(scope.isEstablished()).isFalse();
		assertThatThrownBy(scope::current).isInstanceOf(NoTenantScopeException.class);
	}

	@Test
	void exitingWithoutHavingEnteredIsHarmless() {
		assertThatCode(scope::exit).doesNotThrowAnyException();
	}

	/**
	 * Re-entering with a different tenant inside one unit of work is
	 * rejected. Legitimate tenant changes happen by starting a new unit
	 * of work; a second {@code enter} on an already-scoped thread means
	 * either a leak or an attempt to widen reach mid-request, and Phase
	 * 1 has no database backstop to catch which.
	 */
	@Test
	void enteringASecondDifferentTenantWithoutExitingIsRejected() {
		scope.enter(42L);

		assertThatThrownBy(() -> scope.enter(43L))
				.isInstanceOf(NoTenantScopeException.class)
				.hasMessageContaining("42");

		// The original scope is intact -- a rejected widening must not
		// leave the thread unscoped, which would fail the next read.
		assertThat(scope.current()).isEqualTo(42L);
	}

	/** Re-entering the same tenant is a no-op, not an error. */
	@Test
	void reEnteringTheSameTenantIsAllowed() {
		scope.enter(42L);

		assertThatCode(() -> scope.enter(42L)).doesNotThrowAnyException();
		assertThat(scope.current()).isEqualTo(42L);
	}

	@Test
	void aNullTenantIsRejectedRatherThanStoredAsAbsent() {
		assertThatThrownBy(() -> scope.enter(null))
				.isInstanceOf(NoTenantScopeException.class);
	}

	/**
	 * One thread's scope must be invisible to another. The application
	 * serves concurrent requests from a pool, so a scope shared across
	 * threads is a cross-tenant read by construction.
	 */
	@Test
	void aScopeOnOneThreadIsInvisibleToAnother() throws Exception {
		scope.enter(42L);
		AtomicReference<Boolean> otherThreadSawAScope = new AtomicReference<>();

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			executor.submit(() -> otherThreadSawAScope.set(scope.isEstablished()))
					.get(5, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		assertThat(otherThreadSawAScope.get()).isFalse();
		assertThat(scope.current()).isEqualTo(42L);
	}

}
