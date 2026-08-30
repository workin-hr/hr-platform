package com.workin.legacy.auth.whatsapp;

/**
 * The seam for legacy's {@code sendWhatsAppText()}
 * ({@code helpers/whatsapp_helper.php:183-215}) -- the outbound leg of every
 * OTP the platform issues.
 *
 * <h2>Why this is a seam and not an inline HTTP call</h2>
 * <p>Unlike {@code LegacyPushDelivery}, a failure here is <b>not</b> swallowed:
 * {@code otp_issue_and_send_whatsapp()} answers <b>503
 * {@code otp_delivery_failed}</b> when this returns false, so the return value
 * decides whether registration, password reset and phone verification work at
 * all. It therefore needs to be substitutable in tests without an outbound
 * socket, and it needs one place that owns the timeout and the
 * credential-absent behaviour.
 *
 * <p><b>The OTP row is already written by the time this is called.</b>
 * {@code otp_issue_for_phone()} runs first, so a delivery failure still leaves
 * a used-up rate-limit slot behind -- and, under R-014, still counts toward the
 * platform-wide hourly total. A 503 here is not free.
 */
public interface LegacyWhatsAppSender {

	/**
	 * {@code sendWhatsAppText($localPhone, $message, $countryCode)}.
	 *
	 * <p>Implementations must not throw for a transport failure: legacy logs
	 * and returns false, and the caller turns false into the 503. Throwing
	 * would produce a 500 where legacy produces a 503.
	 *
	 * @param localPhone the local-format digits, already normalised
	 * @param message the fully rendered message body
	 * @param countryCode the dial code, {@code "+20"} by default in PHP
	 * @return true when the gateway accepted the message
	 */
	boolean sendText(String localPhone, String message, String countryCode);
}
