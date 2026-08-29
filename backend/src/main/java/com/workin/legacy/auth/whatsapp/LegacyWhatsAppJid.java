package com.workin.legacy.auth.whatsapp;

import com.workin.legacy.phone.LegacyPhoneNumbers;

/** {@code phone_to_whatsapp_jid()} ({@code whatsapp_helper.php:9-18}). */
public final class LegacyWhatsAppJid {

	private LegacyWhatsAppJid() {
	}

	/**
	 * {@code $cc . $local . '@s.whatsapp.net'}, where the local part has its
	 * digits extracted, and then a single <b>leading zero stripped</b> -- the
	 * trunk prefix Egyptian and Saudi local numbers carry and international
	 * dialling does not.
	 *
	 * <p>Only one zero is removed, and only from the front. A number that is
	 * all zeros, or empty, is left as whatever remains: PHP does not guard
	 * either case and neither does this.
	 */
	public static String of(String localPhone, String countryCode) {
		String local = LegacyPhoneNumbers.digitsOnly(localPhone);
		String code = countryCode == null ? "" : countryCode.trim();
		while (code.startsWith("+")) {
			code = code.substring(1);
		}
		if (!local.isEmpty() && local.charAt(0) == '0') {
			local = local.substring(1);
		}
		return code + local + "@s.whatsapp.net";
	}
}
