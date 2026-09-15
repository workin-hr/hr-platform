package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import com.workin.backend.platformadmin.content.BroadcastAdminService;

/**
 * The notifications page's writes flash legacy's messages (D-253). No end-to-end test sends a
 * broadcast, so this drives the controller directly, with the service answering as it would.
 */
class AdminNotificationsFlashTest {

	private static final String REDIRECT = "redirect:" + PlatformAdminWebSecurityConfig.NOTIFICATIONS_PATH;

	private static final PlatformAdminWebPrincipal ADMIN = new PlatformAdminWebPrincipal(1L, "admin");

	@Test
	void aSendFlashesLegacysSentMessageWithItsAudienceAndReach() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		String view = controller(new BroadcastAdminService.Result(true, 7, null))
				.send(ADMIN, "all_employees", "Title", "Body", null, "1", model(), redirect);

		assertThat(view).as("the count travels in the flash now, not the query").isEqualTo(REDIRECT);
		assertThat(redirect.getFlashAttributes().get("flash")).isEqualTo("sent_ok — notif_audience_all_employees (7)");
		assertThat(redirect.getFlashAttributes().get("flashType")).isEqualTo("success");
	}

	@Test
	void aRefusedSendKeepsItsErrorAndFlashesNothing() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		String view = controller(new BroadcastAdminService.Result(false, 0, "confirm_broadcast"))
				.send(ADMIN, "all_employees", "Title", "Body", null, null, model(), redirect);

		assertThat(view).isEqualTo(REDIRECT + "?error=confirm_broadcast");
		assertThat(redirect.getFlashAttributes()).isEmpty();
	}

	@Test
	void aDeleteFlashesLegacysDeletedMessageAsAnError() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		String view = controller(new BroadcastAdminService.Result(true, 0, null))
				.delete(ADMIN, 9L, model(), redirect);

		assertThat(view).isEqualTo(REDIRECT);
		assertThat(redirect.getFlashAttributes().get("flash")).isEqualTo("deleted_ok");
		assertThat(redirect.getFlashAttributes().get("flashType")).isEqualTo("error");
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
			public Result send(long adminId, String audienceValue, String title, String body,
					Long companyId, boolean confirmed) {
				return answer;
			}

			@Override
			public Result delete(long adminId, long id) {
				return answer;
			}
		});
	}

}
