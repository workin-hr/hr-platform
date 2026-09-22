package com.workin.backend.platformadmin.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.workin.backend.platformadmin.PlatformAdmin;
import com.workin.backend.platformadmin.PlatformAdminRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The absolute cap and the active-admin revalidation, at the unit level.
 *
 * <p>The cap is tested here rather than through HTTP because proving it end to
 * end means waiting eight hours or making the cap configurable so a test can
 * shrink it -- and a cap that a property can shrink is a cap that a
 * misconfiguration can remove. The integration test proves the login stamp is
 * written; this proves what the filter does with it.
 */
class PlatformAdminSessionRevalidationFilterTest {

	private final PlatformAdminRepository repository = mock(PlatformAdminRepository.class);

	private final PlatformAdminSessionRevalidationFilter filter =
			new PlatformAdminSessionRevalidationFilter(this.repository);

	@Test
	void aFreshSessionForAnActiveAdministratorSurvives() throws Exception {
		givenAdministrator(true);
		MockHttpSession session = sessionEstablished(Duration.ofMinutes(5));

		doFilter(session);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
		assertThat(session.isInvalid()).isFalse();
	}

	@Test
	void aDeactivatedAdministratorLosesTheSessionOnTheNextRequest() throws Exception {
		givenAdministrator(false);
		MockHttpSession session = sessionEstablished(Duration.ofMinutes(5));

		doFilter(session);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertThat(session.isInvalid())
			.as("clearing the context but leaving the session alive means the cookie gets retried")
			.isTrue();
	}

	@Test
	void aDeletedAdministratorIsTreatedAsInactive() throws Exception {
		when(this.repository.findById(anyLong())).thenReturn(Optional.empty());
		MockHttpSession session = sessionEstablished(Duration.ofMinutes(5));

		doFilter(session);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void aSessionPastTheAbsoluteCapIsRefusedEvenWhileActive() throws Exception {
		givenAdministrator(true);
		MockHttpSession session = sessionEstablished(
				PlatformAdminWebSecurityConfig.ABSOLUTE_CAP.plusMinutes(1));

		doFilter(session);

		assertThat(SecurityContextHolder.getContext().getAuthentication())
			.as("the cap is non-renewable; activity must not extend it")
			.isNull();
		assertThat(session.isInvalid()).isTrue();
	}

	@Test
	void aSessionJustInsideTheCapSurvives() throws Exception {
		givenAdministrator(true);
		MockHttpSession session = sessionEstablished(
				PlatformAdminWebSecurityConfig.ABSOLUTE_CAP.minusMinutes(1));

		doFilter(session);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
	}

	@Test
	void aSessionWithNoEstablishmentStampIsRefused() throws Exception {
		givenAdministrator(true);
		MockHttpSession session = new MockHttpSession();

		doFilter(session);

		assertThat(SecurityContextHolder.getContext().getAuthentication())
			.as("a session that cannot be shown to be within the cap is not within it")
			.isNull();
	}

	@Test
	void aStaticAssetIsServedWithoutRevalidating() throws Exception {
		givenAdministrator(true);
		MockHttpSession session = sessionEstablished(Duration.ofMinutes(5));

		doFilter(session, "/admin/_assets/app-ui.css");

		verifyNoInteractions(this.repository);
		assertThat(session.isInvalid()).as("nothing about the session changes either").isFalse();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
	}

	@Test
	void anAssetRequestPastTheAbsoluteCapIsStillRefused() throws Exception {
		givenAdministrator(true);
		MockHttpSession session = sessionEstablished(
				PlatformAdminWebSecurityConfig.ABSOLUTE_CAP.plusMinutes(1));

		doFilter(session, "/admin/_assets/app-ui.css");

		assertThat(SecurityContextHolder.getContext().getAuthentication())
			.as("an asset request carries the session cookie, so a page fetching assets must not "
					+ "carry a session past a cap that is meant to be non-renewable")
			.isNull();
		assertThat(session.isInvalid()).isTrue();
		verifyNoInteractions(this.repository);
	}

	@Test
	void anAssetRequestWithNoEstablishmentStampIsRefused() throws Exception {
		givenAdministrator(true);

		doFilter(new MockHttpSession(), "/admin/_assets/app-ui.css");

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	/**
	 * The skip reads the raw path, so anything that could still resolve
	 * elsewhere -- a traversal segment, a double slash, any percent-encoding --
	 * is revalidated rather than skipped. Spring's firewall rejects most of
	 * these before they reach a filter; this does not depend on that.
	 */
	@Test
	void aPathThatMerelyBeginsLikeAnAssetIsStillRevalidated() throws Exception {
		for (String path : List.of("/admin/_assets/../companies", "/admin/_assets//../companies",
				"/admin/_assets/%2e%2e/companies", "/admin/_assetsx/app.css", "/admin/companies")) {
			reset(this.repository);
			givenAdministrator(false);

			doFilter(sessionEstablished(Duration.ofMinutes(5)), path);

			assertThat(SecurityContextHolder.getContext().getAuthentication())
				.as("revalidated, and refused: " + path).isNull();
		}
	}

	// --- helpers ------------------------------------------------------------

	private void givenAdministrator(boolean active) {
		PlatformAdmin admin = mock(PlatformAdmin.class);
		when(admin.isActive()).thenReturn(active);
		when(this.repository.findById(anyLong())).thenReturn(Optional.of(admin));
	}

	private static MockHttpSession sessionEstablished(Duration ago) {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(PlatformAdminSessionRevalidationFilter.ESTABLISHED_AT,
				Instant.now().minus(ago).toEpochMilli());
		return session;
	}

	private void doFilter(MockHttpSession session) throws Exception {
		doFilter(session, "/admin");
	}

	private void doFilter(MockHttpSession session, String path) throws Exception {
		SecurityContextHolder.clearContext();
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				new PlatformAdminWebPrincipal(7L, "admin"), null, List.of()));

		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.setSession(session);
		this.filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
	}

}
