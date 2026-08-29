package com.workin.legacy.auth.otp;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.whatsapp.LegacyWhatsAppSender;
import com.workin.legacy.phone.LegacyPhoneNumbers;
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
	private static final String DEFAULT_COUNTRY_CODE = "+20";

	private final LegacyOtpStore store;
	private final LegacyOtpRateLimit rateLimit;
	private final LegacyWhatsAppSender whatsApp;
	private final LegacyMessages messages;
	private final JdbcTemplate jdbcTemplate;

	public LegacyOtpService(
			LegacyOtpStore store, LegacyOtpRateLimit rateLimit, LegacyWhatsAppSender whatsApp,
			LegacyMessages messages, DataSource legacyDataSource) {
		this.store = store;
		this.rateLimit = rateLimit;
		this.whatsApp = whatsApp;
		this.messages = messages;
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** {@code otp_normalize_phone()}: trim, then digits only. */
	public static String normalizePhone(Object phone) {
		return LegacyPhoneNumbers.digitsOnly(
				LegacyValues.phpTrim(phone == null ? "" : LegacyValues.toPhpString(phone)));
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
	 * @param rawPhone normalised internally, as PHP does
	 * @param messageLangKey one of the three {@code SMS_OTP_*} keys
	 * @param countryCode may be null or blank -- then it is resolved from the
	 *        companies and employees tables
	 * @return the issued code, which callers must <b>not</b> put on the wire
	 *         (PMR-05, {@code hr-legacy#4})
	 */
	public String issueAndSendWhatsApp(
			HttpServletRequest request, String rawPhone, String messageLangKey,
			String countryCode, int expiresMinutes, String locale) {
		String phone = normalizePhone(rawPhone);
		String purpose = purposeFromMessageKey(messageLangKey);
		String ip = LegacyClientAddress.clientIp(request);

		rateLimit.assertCanSend(phone, purpose, ip);
		String code = issueForPhone(request, phone, expiresMinutes, purpose, ip);

		if (!sendWhatsApp(phone, code, messageLangKey, countryCode, locale)) {
			throw new LegacyApiException(503, "otp_delivery_failed");
		}
		return code;
	}

	/**
	 * {@code otp_issue_for_phone()} ({@code otp_helper.php:180-227}).
	 *
	 * <p>An empty phone is rejected here and not by the caller, so a request
	 * whose phone reduced to no digits gets {@code invalid_phone_number} even
	 * though it passed {@code required()}.
	 */
	private String issueForPhone(
			HttpServletRequest request, String phone, int expiresMinutes, String purpose, String ip) {
		if (phone.isEmpty()) {
			throw new LegacyApiException(400, "invalid_phone_number");
		}
		String userAgent = LegacyClientAddress.userAgent(request);
		store.clearForPhone(phone);
		String code = generateCode();
		store.insert(phone, code, expiresMinutes, ip, userAgent, purpose);
		store.logRequest(phone, purpose, ip, userAgent);
		return code;
	}

	/** {@code otp_has_recent_for_phone($phone, 60)}. */
	public boolean hasRecentForPhone(String rawPhone, long withinSeconds) {
		String phone = normalizePhone(rawPhone);
		return !phone.isEmpty() && rateLimit.countRecentSends(phone, null, "", withinSeconds) > 0;
	}

	/** {@code otp_verify_latest_for_phone()}. */
	public Long verifyLatestForPhone(String rawPhone, Object rawCode) {
		return store.verifyLatest(normalizePhone(rawPhone),
				LegacyValues.phpTrim(rawCode == null ? "" : LegacyValues.toPhpString(rawCode)));
	}

	/** {@code otp_clear_for_phone()}. */
	public void clearForPhone(String rawPhone) {
		store.clearForPhone(normalizePhone(rawPhone));
	}

	/**
	 * {@code otp_resolve_country_code_for_phone()}: companies first, then
	 * employees, then {@code +20}.
	 *
	 * <p>Both lookups match the phone <b>exactly</b> against the stored column
	 * -- no {@code phone_sql_match_clause()} here, unlike the callers around
	 * it -- so a company whose stored phone carries a {@code +} or a space is
	 * not found and the default is used. Preserved.
	 */
	public String resolveCountryCodeForPhone(String rawPhone) {
		String phone = normalizePhone(rawPhone);
		if (phone.isEmpty()) {
			return DEFAULT_COUNTRY_CODE;
		}
		String company = firstNonEmpty(
				"SELECT country_code FROM companies WHERE phone = ? LIMIT 1", phone);
		if (company != null) {
			return company;
		}
		String employee = firstNonEmpty(
				"SELECT country_code FROM employees WHERE phone = ? LIMIT 1", phone);
		return employee != null ? employee : DEFAULT_COUNTRY_CODE;
	}

	/** {@code $row && !empty($row[COUNTRY_CODE])} -- PHP emptiness, so {@code "0"} does not count. */
	private String firstNonEmpty(String sql, String phone) {
		List<String> values = jdbcTemplate.queryForList(sql, String.class, phone);
		if (values.isEmpty()) {
			return null;
		}
		String value = values.get(0);
		return LegacyValues.isPhpEmpty(value) ? null : value;
	}

	/**
	 * {@code otp_send_whatsapp()} plus {@code otp_whatsapp_message_body()}.
	 *
	 * <p>The body is <b>hard-coded Arabic</b> for the verify and resend
	 * templates, whatever the request's language: legacy comments that company
	 * registration and verification "always in Arabic (exact template)". Only
	 * the password-reset key goes through {@code t()} and can come out in
	 * English.
	 */
	private boolean sendWhatsApp(
			String phone, String code, String messageLangKey, String countryCode, String locale) {
		String resolved = countryCode == null || LegacyValues.phpTrim(countryCode).isEmpty()
				? resolveCountryCodeForPhone(phone)
				: countryCode;
		return whatsApp.sendText(phone, messageBody(messageLangKey, code, locale), resolved);
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
