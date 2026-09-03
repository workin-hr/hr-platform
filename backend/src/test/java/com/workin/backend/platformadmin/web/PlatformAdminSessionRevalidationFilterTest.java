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
		SecurityContextHolder.clearContext();
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				new PlatformAdminWebPrincipal(7L, "+201000000000", true), null, List.of()));

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin");
		request.setSession(session);
		this.filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
	}

}
