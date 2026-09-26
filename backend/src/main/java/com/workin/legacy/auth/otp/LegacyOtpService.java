package com.workin.legacy.auth.otp;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.whatsapp.LegacyWhatsAppSender;
import com.workin.legacy.phone.CanonicalPhone;
import com.workin.legacy.phone.CanonicalPhones;
import com.workin.legacy.phone.LegacyPhoneNumbers;
import com.workin.legacy.phone.PhoneLookup;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code helpers/otp_helper.php}'s public surface: issue, deliver and verify.
 *
 * <p>Every OTP-issuing endpoint funnels through
 * {@link #issueAndSendWhatsApp}, and the order inside it is the contract:
 * rate-limit check, then <b>write the row</b>, then attempt delivery, then
 * fail 503 if delivery failed. The row is durable before the send, so a
 * failed delivery still consumed the caller's cooldown -- and, under R-014,
 * still counts toward the platform-wide hourly total.
 */
@Service
public class LegacyOtpService {

	/** {@code LangKey::SMS_OTP_*}, the three message templates. */
	public static final String SMS_OTP_VERIFY = "sms_otp_verify";
	public static final String SMS_OTP_RESEND = "sms_otp_resend";
	public static final String SMS_OTP_PASSWORD_RESET = "sms_otp_password_reset";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final LegacyOtpStore store;
	private final LegacyOtpRateLimit rateLimit;
	private final LegacyWhatsAppSender whatsApp;
	private final LegacyMessages messages;
	private final JdbcTemplate jdbcTemplate;
	private final LegacyPhoneNumbers phoneNumbers;

	public LegacyOtpService(
			LegacyOtpStore store, LegacyOtpRateLimit rateLimit, LegacyWhatsAppSender whatsApp,
			LegacyMessages messages, DataSource legacyDataSource, LegacyPhoneNumbers phoneNumbers) {
		this.store = store;
		this.rateLimit = rateLimit;
		this.whatsApp = whatsApp;
		this.messages = messages;
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.phoneNumbers = phoneNumbers;
	}

	/**
	 * The number an OTP route's {@code phone} names, for a route that has no
	 * stored row to take it from ({@code resend_otp}, {@code verify_otp},
	 * {@code reset_password}) -- PHP's {@code otp_normalize_phone()} plus
	 * {@code otp_resolve_country_code_for_phone()}, through the one
	 * normalizer (ADR-0020).
	 *
	 * <p>Written internationally, or read the same in every country the
	 * product offers, it has one reading and that is the answer. A national
	 * number with several readings takes the country of the stored account it
	 * belongs to -- companies first, then employees, as PHP's country lookup
	 * orders them -- and with no account, Egypt's. Anything else is empty.
	 */
	public Optional<CanonicalPhone> resolvePhone(Object rawPhone) {
		PhoneLookup lookup = phoneNumbers.lookup(rawPhone);
		if (lookup.isEmpty()) {
			return Optional.empty();
		}
		Optional<CanonicalPhone> only = lookup.only();
		if (only.isPresent()) {
			return only;
		}
		for (String table : new String[] {"companies", "employees"}) {
			PhoneLookup.Clause match = lookup.clause("phone");
			List<Map<String, Object>> rows = lookup.verified(jdbcTemplate.queryForList(
					"SELECT phone, country_code FROM " + table + " WHERE " + match.sql() + " ORDER BY id ASC",
					match.binds().toArray()));
			if (!rows.isEmpty()) {
				Map<String, Object> row = rows.get(0);
				return CanonicalPhones.parse(row.get("phone"),
						row.get("country_code") == null ? null : LegacyValues.toPhpString(row.get("country_code")));
			}
		}
		return lookup.readings().stream()
				.filter(phone -> CanonicalPhones.DEFAULT_REGION.equals(phone.region()))
				.findFirst();
	}

	/**
	 * {@code otp_generate_code(4)} -- four digits, zero-padded, so
	 * {@code "0007"} is a legitimate code and the range really is 0000-9999.
	 *
	 * <p>PHP uses {@code random_int()}, which is cryptographically secure;
	 * {@link SecureRandom} is the equivalent and {@code java.util.Random} would
	 * not be. Ten thousand possibilities is weak either way, which is what the
	 * rate limiter is for.
	 */
	static String generateCode() {
		// Locale.ROOT is load-bearing, not decoration: under a default locale
		// with non-ASCII digits (ar_EG among them) String.format would render
		// 7 as "٠٠٠٧". That value would be stored and delivered, and a client
		// submitting the ordinary "0007" could never verify it. PHP's integer
		// conversion and str_pad always produce ASCII.
		return String.format(Locale.ROOT, "%04d", RANDOM.nextInt(10000));
	}

	/**
	 * {@code otp_issue_and_send_whatsapp()} ({@code otp_helper.php:337-359}).
	 *
	 * <p>The code, its cooldowns and its hourly caps are keyed on the number's
	 * E.164 form, so every spelling of one number shares them (D-291), and it
	 * is delivered to that number -- its country comes with it, where PHP had
	 * to look a country code up.
	 *
	 * @param messageLangKey one of the three {@code SMS_OTP_*} keys
	 * @return the issued code, which callers must <b>not</b> put on the wire
	 *         (PMR-05, {@code hr-legacy#4})
	 */
	public String issueAndSendWhatsApp(
			HttpServletRequest request, CanonicalPhone phone, String messageLangKey,
			int expiresMinutes, String locale) {
		String purpose = purposeFromMessageKey(messageLangKey);
		String ip = LegacyClientAddress.clientIp(request);

		rateLimit.assertCanSend(phone.e164(), purpose, ip);
		String code = issueForPhone(request, phone.e164(), expiresMinutes, purpose, ip);

		if (!whatsApp.sendText(phone.nationalDigits(), messageBody(messageLangKey, code, locale), phone.dialCode())) {
			throw new LegacyApiException(503, "otp_delivery_failed");
		}
		return code;
	}

	/** {@code otp_issue_for_phone()} ({@code otp_helper.php:180-227}). */
	private String issueForPhone(
			HttpServletRequest request, String phone, int expiresMinutes, String purpose, String ip) {
		String userAgent = LegacyClientAddress.userAgent(request);
		store.clearForPhone(phone);
		String code = generateCode();
		store.insert(phone, code, expiresMinutes, ip, userAgent, purpose);
		store.logRequest(phone, purpose, ip, userAgent);
		return code;
	}

	/** {@code otp_has_recent_for_phone($phone, 60)}. */
	public boolean hasRecentForPhone(CanonicalPhone phone, long withinSeconds) {
		return rateLimit.countRecentSends(phone.e164(), null, "", withinSeconds) > 0;
	}

	/** {@code otp_verify_latest_for_phone()}. */
	public Long verifyLatestForPhone(CanonicalPhone phone, Object rawCode) {
		return store.verifyLatest(phone.e164(),
				LegacyValues.phpTrim(rawCode == null ? "" : LegacyValues.toPhpString(rawCode)));
	}

	/** {@code otp_clear_for_phone()}. */
	public void clearForPhone(CanonicalPhone phone) {
		store.clearForPhone(phone.e164());
	}

	/** {@code otp_whatsapp_message_body()} ({@code otp_helper.php:291-302}). */
	String messageBody(String messageLangKey, String code, String locale) {
		if (SMS_OTP_VERIFY.equals(messageLangKey) || SMS_OTP_RESEND.equals(messageLangKey)) {
			return "رمز التحقق الخاص بك هو (" + code + ")\nلا تشاركه مع أي شخص";
		}
		return messages.translate(locale, messageLangKey, Map.of("otp", code));
	}

	/** {@code otp_purpose_from_message_key()}. */
	static String purposeFromMessageKey(String messageLangKey) {
		return switch (messageLangKey) {
			case SMS_OTP_PASSWORD_RESET -> "password_reset";
			case SMS_OTP_RESEND -> "resend";
			case SMS_OTP_VERIFY -> "verify";
			default -> "generic";
		};
	}
}
