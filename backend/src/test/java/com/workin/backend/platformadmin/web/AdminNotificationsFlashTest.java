package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import com.workin.backend.platformadmin.content.BroadcastAdminService;

/**
 * The notifications page's writes flash legacy's messages (D-253). No end-to-end test sends a
 * broadcast, so this drives the controller directly, with the service answering as it would.
 *
 * <p>Both cases also pin that the controller builds the session it hands the service, rather than the
 * service inventing one: the scoped checks live there, and a controller that passed no session could
 * not compile. What those checks then do is {@code AdminNotificationsTenantScopeTest}'s subject,
 * against a real database.
 */
class AdminNotificationsFlashTest {

	private static final String REDIRECT = "redirect:" + PlatformAdminWebSecurityConfig.NOTIFICATIONS_PATH;

	private static final PlatformAdminWebPrincipal ADMIN = new PlatformAdminWebPrincipal(1L, "admin");

	@Test
	void aSendFlashesLegacysSentMessageWithItsAudienceAndReach() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		String view = controller(new BroadcastAdminService.Result(true, 7, null))
				.send(ADMIN, "all_employees", "Title", "Body", null, "1", new MockHttpServletRequest(), model(), redirect);

		assertThat(view).as("the count travels in the flash now, not the query").isEqualTo(REDIRECT);
		assertThat(redirect.getFlashAttributes().get("flash")).isEqualTo("sent_ok — send_all_employees_system (7)");
		assertThat(redirect.getFlashAttributes().get("flashType")).isEqualTo("success");
	}

	@Test
	void aCompanySendCarriesLegacysLabelForThatAudience() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller(new BroadcastAdminService.Result(true, 3, null))
				.send(ADMIN, "company_employees", "Title", "Body", 5L, null, new MockHttpServletRequest(), model(), redirect);

		assertThat(redirect.getFlashAttributes().get("flash")).isEqualTo("sent_ok — send_to_all (3)");
	}

	/** Legacy's dispatch answers `'ok' => $count > 0`, and the page flashes error_required. */
	@Test
	void aSendThatReachesNobodyIsLegacysErrorNotASuccess() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		String view = controller(new BroadcastAdminService.Result(true, 0, null))
				.send(ADMIN, "all_employees", "Title", "Body", null, "1", new MockHttpServletRequest(), model(), redirect);

		assertThat(view).isEqualTo(REDIRECT + "?error=error_required");
		assertThat(redirect.getFlashAttributes()).isEmpty();
	}

	@Test
	void aRefusedSendKeepsItsErrorAndFlashesNothing() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		String view = controller(new BroadcastAdminService.Result(false, 0, "confirm_broadcast"))
				.send(ADMIN, "all_employees", "Title", "Body", null, null, new MockHttpServletRequest(), model(), redirect);

		assertThat(view).isEqualTo(REDIRECT + "?error=confirm_broadcast");
		assertThat(redirect.getFlashAttributes()).isEmpty();
	}

	@Test
	void aDeleteFlashesLegacysDeletedMessageAsAnError() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		String view = controller(new BroadcastAdminService.Result(true, 0, null))
				.delete(ADMIN, 9L, new MockHttpServletRequest(), model(), redirect);

		assertThat(view).isEqualTo(REDIRECT);
		assertThat(redirect.getFlashAttributes().get("flash")).isEqualTo("deleted_ok");
		assertThat(redirect.getFlashAttributes().get("flashType")).isEqualTo("error");
	}

	@Test
	void aDeleteReturnsToThePageAndFiltersItWasMadeFrom() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Referer", "https://admin.example/admin/notifications?page=3&search=pay");

		assertThat(controller(new BroadcastAdminService.Result(true, 0, null))
				.delete(ADMIN, 9L, request, model(), new RedirectAttributesModelMap()))
				.isEqualTo(REDIRECT + "?page=3&search=pay");
		assertThat(controller(new BroadcastAdminService.Result(false, 0, "error_not_found"))
				.delete(ADMIN, 9L, request, model(), new RedirectAttributesModelMap()))
				.as("a refusal keeps them too, with its own message")
				.isEqualTo(REDIRECT + "?page=3&search=pay&error=error_not_found");
	}

	/** The advice's translator, answering with the key, as it does for a missing message. */
	private static ExtendedModelMap model() {
		ExtendedModelMap model = new ExtendedModelMap();
		model.addAttribute("t", (Function<String, String>) key -> key);
		return model;
	}

	private static AdminNotificationsController controller(BroadcastAdminService.Result answer) {
		return new AdminNotificationsController(new BroadcastAdminService(null, null, true) {
			@Override
			public Result send(DashboardSession session, long adminId, String audienceValue,
					String title, String body, Long companyId, boolean confirmed) {
				return answer;
			}

			@Override
			public Result delete(DashboardSession session, long adminId, long id) {
				return answer;
			}
		});
	}

}
