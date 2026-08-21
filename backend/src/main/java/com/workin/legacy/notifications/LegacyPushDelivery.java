package com.workin.legacy.notifications;

import java.util.Map;

/**
 * The seam for legacy's {@code sendPushToEmployee()}
 * ({@code helpers/notifications.php:104-116}), called immediately after a
 * notification row is inserted and wrapped by PHP in
 * {@code try { ... } catch (Throwable $ignored) { }}.
 *
 * <p>This interface exists so the call site and its ordering survive in Java
 * even though the delivery itself does not ship in Wave 12.4. Deleting the call
 * would erase evidence of a behaviour Phase 1 still owes; faking it as
 * "delivered" would be worse.
 *
 * <h2>Wave 12.4 ships {@link LegacyPushDeliveryUnavailable}, and that is not parity</h2>
 * <p>Real Firebase delivery is <b>hr-platform#22</b>, a Phase 1 cross-cutting
 * exit requirement and a hard release/cutover blocker. It is deliberately
 * outside this wave: implementing it here would pull Firebase Admin SDK
 * dependencies, FCM credentials and {@code push_tokens} registration
 * infrastructure into an employee-module PR. Until #22 lands, an employee whose
 * account is deactivated gets the notification row and no device push --
 * an accepted, recorded gap, not a completed port.
 *
 * @see LegacyPushDeliveryUnavailable
 */
public interface LegacyPushDelivery {

	/**
	 * Attempt delivery. Implementations may throw: every call site wraps this
	 * the way PHP does, so a failure never reaches the client.
	 *
	 * @param employeeId the recipient, matching PHP's {@code $to_employee_id}
	 * @param title the already-translated title stored on the notification row
	 * @param body the already-translated body, empty rather than null in PHP's
	 *        call ({@code $body ?? ''})
	 * @param data PHP's payload map -- {@code notification_id} and
	 *        {@code notification_type}, both as strings. Carried through the
	 *        seam now so #22 does not have to widen it later.
	 */
	void sendToEmployee(long employeeId, String title, String body, Map<String, String> data);

}
