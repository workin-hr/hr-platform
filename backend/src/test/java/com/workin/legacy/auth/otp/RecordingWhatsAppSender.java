package com.workin.legacy.auth.otp;

import java.util.ArrayList;
import java.util.List;

import com.workin.legacy.auth.whatsapp.LegacyWhatsAppSender;

/**
 * A {@link LegacyWhatsAppSender} that records instead of sending, so the OTP
 * flows can be exercised end to end without an outbound socket.
 *
 * <p>It records the <b>message body</b>, which is the only place the issued
 * code exists once the DEBUG response branch is gone -- the tests read the code
 * out of the message the way a real recipient would, rather than being handed
 * it through a back door the production path does not have.
 *
 * <p>{@link #failNext} makes the next send fail, which is how the 503
 * {@code otp_delivery_failed} path is reached. That path matters: the OTP row
 * is already written when it fires.
 */
public class RecordingWhatsAppSender implements LegacyWhatsAppSender {

	public record Sent(String phone, String message, String countryCode) {
	}

	private final List<Sent> sent = new ArrayList<>();
	private boolean failNext;

	@Override
	public synchronized boolean sendText(String localPhone, String message, String countryCode) {
		if (failNext) {
			failNext = false;
			return false;
		}
		sent.add(new Sent(localPhone, message, countryCode));
		return true;
	}

	public synchronized void failNext() {
		this.failNext = true;
	}

	public synchronized List<Sent> sent() {
		return List.copyOf(sent);
	}

	public synchronized Sent last() {
		return sent.isEmpty() ? null : sent.get(sent.size() - 1);
	}

	public synchronized void clear() {
		sent.clear();
		failNext = false;
	}

	/**
	 * The four-digit code in the last message body.
	 *
	 * <p>The two templates render it differently -- the verify/resend one wraps
	 * it in parentheses, the password-reset one does not -- so this matches a
	 * standalone run of exactly four digits rather than either template's
	 * punctuation. The only other number in any template is the "10 minutes"
	 * validity, which is two digits and therefore cannot collide.
	 */
	public synchronized String lastCode() {
		Sent last = last();
		if (last == null) {
			return null;
		}
		java.util.regex.Matcher matcher =
				java.util.regex.Pattern.compile("(?<!\\d)(\\d{4})(?!\\d)").matcher(last.message());
		return matcher.find() ? matcher.group(1) : null;
	}
}
