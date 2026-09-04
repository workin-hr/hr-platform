package com.workin.legacy.guide;

import java.util.Map;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code /apis/api/guide_videos/list.php} -- the desktop client's how-to
 * clips, read through its {@code getGuideVideosEndpoint}.
 *
 * <p>Added to legacy after this port's first sweep. The desktop client at the
 * pinned submodule commit does not call it; the build five commits later
 * does, which is why the client repositories had to be read rather than
 * assumed unchanged.
 */
@RestController
@RequestMapping("/apis/api/guide_videos")
public class LegacyGuideVideoController {

	private final LegacyGuideVideoService service;

	private final LegacyMessages messages;

	private final LegacyRequestGuard requestGuard;

	public LegacyGuideVideoController(
			LegacyGuideVideoService service, LegacyMessages messages, LegacyRequestGuard requestGuard) {
		this.service = service;
		this.messages = messages;
		this.requestGuard = requestGuard;
	}

	@AuthenticatedUseCase(reason = "The platform's guide videos. PHP calls requireAuth() with no "
			+ "role check, and the content is the same for every tenant -- there is nothing to scope.")
	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		if (!"GET".equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
		// Method first, then auth with no role list -- PHP's order, so an
		// unauthenticated POST answers 405 rather than 401.
		this.requestGuard.requireAuth();

		String locale = this.messages.resolveLocale(request);
		return LegacyApiResponse.ok(
				this.messages.translate(locale, "success", null),
				Map.of("videos", this.service.list(LegacyGuideVideoService.isEnglish(locale))));
	}

}
