package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

/**
 * {@link DashboardOrgScope} against {@code org_helper.php}.
 *
 * <p>Weighted towards the tenant boundary. The administrator's filter is a
 * deliberate cross-tenant widening (<b>R-044</b>), so the cases that matter
 * are the ones where a scoped session tries to use it and the ones where the
 * filter is set, cleared, or left alone -- three outcomes that one careless
 * {@code isset()} would collapse into two.
 */
class DashboardOrgScopeTest {

	private static final DashboardSession ADMIN = DashboardSession.admin(0L);
	private static final DashboardSession OWNER = DashboardSession.company(7L);
	private static final DashboardSession HR = DashboardSession.hr(70L, 7L, "hr", java.util.Set.of());

	private static MockHttpServletRequest request(MockHttpSession session, String companyId) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/branches");
		request.setSession(session);
		if (companyId != null) {
			request.setParameter("company_id", companyId);
		}
		return request;
	}

	@Test
	void aScopedSessionAlwaysGetsItsOwnCompanyAndTheParameterIsNeverRead() {
		// The containment that matters: an owner appending ?company_id=9 does
		// not get company 9, and does not get a filter set on their session
		// either -- the parameter is not reached on this path at all.
		MockHttpSession session = new MockHttpSession();
		for (DashboardSession scoped : List.of(OWNER, HR)) {
			assertThat(DashboardOrgScope.resolve(scoped, request(session, "9"))).isEqualTo(7L);
			assertThat(session.getAttribute(DashboardOrgScope.SESSION_KEY)).isNull();
		}
	}

	@Test
	void anAdministratorStartsUnfilteredWhichMeansEveryCompany() {
		MockHttpSession session = new MockHttpSession();
		assertThat(DashboardOrgScope.resolve(ADMIN, request(session, null))).isZero();
		assertThat(DashboardOrgScope.showsCompanyColumn(ADMIN, 0L))
				.as("and the company column is shown, because rows from many companies are on screen")
				.isTrue();
	}

	@Test
	void theFilterIsSetFromTheParameterAndThenOutlivesIt() {
		// This is the half a stateless reading gets wrong: a later request with
		// no company_id at all keeps the filter, it does not reset it.
		MockHttpSession session = new MockHttpSession();
		assertThat(DashboardOrgScope.resolve(ADMIN, request(session, "9"))).isEqualTo(9L);
		assertThat(DashboardOrgScope.resolve(ADMIN, request(session, null))).isEqualTo(9L);
		assertThat(DashboardOrgScope.showsCompanyColumn(ADMIN, 9L))
				.as("filtered to one company, the column is redundant").isFalse();
	}

	@Test
	void anEmptyParameterClearsTheFilterAndAnAbsentOneDoesNot() {
		// array_key_exists, not isset. With isset the empty string would look
		// absent and "show me all companies again" would be unreachable.
		MockHttpSession session = new MockHttpSession();
		DashboardOrgScope.resolve(ADMIN, request(session, "9"));
		assertThat(DashboardOrgScope.resolve(ADMIN, request(session, ""))).isZero();

		DashboardOrgScope.resolve(ADMIN, request(session, "9"));
		assertThat(DashboardOrgScope.resolve(ADMIN, request(session, null))).isEqualTo(9L);
	}

	@Test
	void aNonNumericOrNonPositiveValueClearsRatherThanFails() {
		// `(int) $raw` takes the leading integer and yields 0 otherwise, and 0
		// clears. A 400 here would refuse a URL legacy serves.
		for (String value : List.of("0", "-1", "abc", " ", "+", "-")) {
			MockHttpSession session = new MockHttpSession();
			DashboardOrgScope.resolve(ADMIN, request(session, "9"));
			assertThat(DashboardOrgScope.resolve(ADMIN, request(session, value)))
					.as("value '%s'", value).isZero();
		}
	}

	@Test
	void aLeadingIntegerWinsTheWayThePhpCastDoes() {
		MockHttpSession session = new MockHttpSession();
		assertThat(DashboardOrgScope.resolve(ADMIN, request(session, "9abc"))).isEqualTo(9L);
		assertThat(DashboardOrgScope.resolve(ADMIN, request(session, " 12 "))).isEqualTo(12L);
	}

	@Test
	void anIdTooLargeForALongClearsRatherThanThrowing() {
		MockHttpSession session = new MockHttpSession();
		DashboardOrgScope.resolve(ADMIN, request(session, "9"));
		assertThat(DashboardOrgScope.resolve(ADMIN, request(session, "99999999999999999999999")))
				.isZero();
	}

	@Test
	void aWriteRemembersItsCompanySoTheAdministratorSeesWhatTheyJustMade() {
		// org_redirect(): create a branch in company 9 while unfiltered and the
		// next page is filtered to 9, rather than showing every company and
		// leaving the new row to be hunted for.
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest post = request(session, null);
		DashboardOrgScope.rememberAfterWrite(ADMIN, post, 9L);
		assertThat(DashboardOrgScope.current(session)).isEqualTo(9L);
	}

	@Test
	void aWriteByAScopedSessionRemembersNothing() {
		MockHttpSession session = new MockHttpSession();
		DashboardOrgScope.rememberAfterWrite(OWNER, request(session, null), 9L);
		DashboardOrgScope.rememberAfterWrite(HR, request(session, null), 9L);
		assertThat(session.getAttribute(DashboardOrgScope.SESSION_KEY)).isNull();
	}

	@Test
	void aNonPositiveCompanyIdIsNotRemembered() {
		MockHttpSession session = new MockHttpSession();
		DashboardOrgScope.rememberAfterWrite(ADMIN, request(session, null), 0L);
		assertThat(session.getAttribute(DashboardOrgScope.SESSION_KEY)).isNull();
	}

	@Test
	void theCompanyColumnIsTheAdministratorsAloneEvenUnfiltered() {
		// A scoped session is always looking at exactly one company, so the
		// column would be one repeated value.
		assertThat(DashboardOrgScope.showsCompanyColumn(OWNER, 0L)).isFalse();
		assertThat(DashboardOrgScope.showsCompanyColumn(HR, 0L)).isFalse();
	}

	@Test
	void readingTheFilterNeverCreatesASession() {
		// current() is called while rendering, on requests that may be
		// anonymous; creating a session there would issue a cookie to a
		// visitor who has not logged in.
		assertThat(DashboardOrgScope.current(null)).isZero();
	}

}
