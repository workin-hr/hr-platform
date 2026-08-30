package com.workin.legacy.notifications;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Wave 13.2: {@code apis/api/notifications/*.php}.
 *
 * <p>Five of the six routes take a bare {@code requireAuth()} with no role
 * list and <b>no {@code requireCompanyActive()}</b> -- a suspended company's
 * users keep reading and clearing their inbox. Only {@code send.php}, the one
 * route that creates notifications, restricts roles and requires an active
 * company. That asymmetry is legacy's and is preserved.
 *
 * <p>The tenant scope for all six is {@link LegacyNotificationInbox}, derived
 * from the auth type rather than from a request parameter.
 */
@RestController
public class LegacyNotificationController {

	private final LegacyNotificationService service;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyNotificationController(
			LegacyNotificationService service, LegacyRequestGuard requestGuard, LegacyMessages messages) {
		this.service = service;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	@RequestMapping("/apis/api/notifications/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyNotificationService.Page page = service.list(
				requestGuard.requireAuth(), LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "notifications"), page.rows(), page.meta());
	}

	@RequestMapping("/apis/api/notifications/unread_count.php")
	public LegacyApiResponse unreadCount(HttpServletRequest request) {
		requireMethod(request, "GET");
		return LegacyApiResponse.ok(
				message(request, "unread_count"), service.unreadCount(requestGuard.requireAuth()));
	}

	/** A GET with a side effect: it marks the notification read. */
	@RequestMapping("/apis/api/notifications/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = requestGuard.requireAuth();
		return LegacyApiResponse.ok(message(request, "notification"),
				service.oneAndMarkRead(context, requiredId(request)));
	}

	@RequestMapping("/apis/api/notifications/mark_read.php")
	public LegacyApiResponse markRead(HttpServletRequest request) {
		requireMethod(request, "PUT");
		service.markRead(requestGuard.requireAuth(), optionalId(request));
		return LegacyApiResponse.ok(message(request, "marked_as_read"), null);
	}

	@RequestMapping("/apis/api/notifications/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		service.delete(requestGuard.requireAuth(), optionalId(request));
		return LegacyApiResponse.ok(message(request, "notification_deleted"), null);
	}

	@RequestMapping("/apis/api/notifications/send.php")
	public ResponseEntity<LegacyApiResponse> send(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		requestGuard.requireCompanyActive(context.companyId());

		LegacyNotificationService.SendResult result = service.send(context, LegacyJsonBody.read(request));
		if (result instanceof LegacyNotificationService.SendResult.Broadcast broadcast) {
			return ResponseEntity.ok(LegacyApiResponse.ok(
					message(request, "broadcast_sent"), Map.of("count", broadcast.count())));
		}
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "notification_sent"),
				((LegacyNotificationService.SendResult.Sent) result).row()));
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	/** {@code required($_GET, [ID]); $id = (int) $_GET[ID];} -- {@code one.php} only. */
	private static long requiredId(HttpServletRequest request) {
		Object id = LegacyQueryParameters.parse(request.getQueryString()).value("id");
		if (id == null || "".equals(id)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
		}
		return LegacyValues.toPhpLong(id);
	}

	/**
	 * {@code $id = isset($_GET[ID]) ? (int) $_GET[ID] : null;} -- no
	 * {@code required()}, so an absent id is legal and means "all". Present but
	 * unparseable is <em>also</em> "all", because the cast makes it 0 and the
	 * caller's {@code if ($id)} is a truthiness test.
	 */
	private static Long optionalId(HttpServletRequest request) {
		Object id = LegacyQueryParameters.parse(request.getQueryString()).value("id");
		return id == null ? null : LegacyValues.toPhpLong(id);
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}
}
