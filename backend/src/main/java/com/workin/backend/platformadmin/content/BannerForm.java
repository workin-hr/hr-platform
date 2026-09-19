package com.workin.backend.platformadmin.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.workin.legacy.PhpCast;

/**
 * Validates a submitted banner, reproducing
 * {@code banner_fields_from_post()}.
 *
 * <p>The action rules are a security control, not formatting. The clients
 * open {@code button_action_value} directly and {@code banners/list.php}
 * hands it over unsanitised, so what this method rejects is what never
 * reaches a customer's device. An action whose value fails its own rule
 * keeps the type and stores a <b>null</b> value, exactly as the dashboard
 * does -- a button with nothing behind it, rather than a button pointing
 * somewhere unchecked.
 */
public final class BannerForm {

	/** {@code #^https?://#i} -- and the reason a {@code javascript:} URL cannot be stored. */
	private static final Pattern EXTERNAL_URL = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);

	private static final Pattern NON_DIGITS = Pattern.compile("\\D+");

	/** PHP's {@code ltrim($code, '+')}, which strips every leading plus. */
	private static final Pattern LEADING_PLUSES = Pattern.compile("^\\++");

	/** @param errorKey a message key, or null when {@link #banner} is present */
	public record Result(Banner banner, String errorKey) {

		public boolean ok() {
			return this.banner != null;
		}
	}

	private BannerForm() {
	}

	/**
	 * @param imageUrl the stored URL, either just uploaded or carried forward
	 *                 from the row being edited; a banner with no image is
	 *                 rejected, as the dashboard's own
	 *                 {@code banner_image_required} does
	 */
	public static Result validate(String imageUrl, String titleAr, String titleEn,
			String descriptionAr, String descriptionEn, String buttonLabelAr, String buttonLabelEn,
			String platform, String actionType, String actionValue,
			String whatsappCountryCode, String whatsappPhone, boolean active, String sortOrder) {

		String image = trimToNull(imageUrl);
		if (image == null) {
			return new Result(null, "banner_image_required");
		}

		Banner.Action action = Banner.Action.of(actionType);
		String value = resolveActionValue(action, actionValue, whatsappCountryCode, whatsappPhone);

		return new Result(new Banner(0L, image, active, parseInt(sortOrder),
				Faq.Platform.of(platform),
				trimToNull(titleAr), trimToNull(titleEn),
				trimToNull(descriptionAr), trimToNull(descriptionEn),
				trimToNull(buttonLabelAr), trimToNull(buttonLabelEn),
				// A row being written has no created_at yet; the database sets it.
				action, value, null), null);
	}

	/** @return the value to store, or null when the submitted one fails its type's rule */
	static String resolveActionValue(Banner.Action action, String rawValue,
			String whatsappCountryCode, String whatsappPhone) {
		String value = rawValue == null ? "" : rawValue.trim();
		return switch (action) {
			case NONE -> null;
			case EXTERNAL_URL -> EXTERNAL_URL.matcher(value).find() ? value : null;
			// Lower-cased before the check, as the dashboard does, so "Home"
			// and "home" are the same key rather than the first being dropped.
			case INTERNAL_ROUTE -> {
				String key = value.toLowerCase(Locale.ROOT);
				yield Banner.INTERNAL_ROUTES.contains(key) ? key : null;
			}
			case WHATSAPP -> whatsappNumber(whatsappCountryCode, whatsappPhone);
		};
	}

	/** A stored WhatsApp number as the edit form's two inputs show it. */
	public record WhatsappParts(String countryCode, String local) {
	}

	/**
	 * {@code banner_split_whatsapp_digits()}: the stored digits split on the
	 * longest active dial code they start with, or {@code +20} with every digit
	 * as the local number when none matches -- legacy's literal default, not
	 * the first active code.
	 *
	 * @param dialCodes the active dial codes, as {@code company_country_codes()} lists them
	 */
	public static WhatsappParts splitWhatsapp(String storedDigits, List<String> dialCodes) {
		String digits = NON_DIGITS.matcher(storedDigits == null ? "" : storedDigits.trim()).replaceAll("");
		if (digits.isEmpty()) {
			return new WhatsappParts("+20", "");
		}
		// PHP's usort is stable, and so is List.sort: codes of equal length keep
		// the order the active list gives them.
		List<String> longestFirst = new ArrayList<>(dialCodes);
		longestFirst.sort(Comparator.comparingInt(String::length).reversed());
		for (String code : longestFirst) {
			String dial = LEADING_PLUSES.matcher(code).replaceFirst("");
			if (!dial.isEmpty() && digits.startsWith(dial)) {
				return new WhatsappParts(code, digits.substring(dial.length()));
			}
		}
		return new WhatsappParts("+20", digits);
	}

	/**
	 * {@code banner_normalize_whatsapp_phone()}'s rule for a value it stores
	 * unchanged: eight to fifteen digits and nothing else, the length of a full
	 * international number. An edit keeps such a stored value; any other one it
	 * rebuilds, so it cannot carry an unchecked value past the WhatsApp rule to
	 * a client.
	 */
	static boolean isStorableWhatsappNumber(String value) {
		return value != null && value.length() >= 8 && value.length() <= 15
				&& !NON_DIGITS.matcher(value).find();
	}

	/**
	 * {@code banner_whatsapp_from_parts()}: the dial code without its plus,
	 * followed by the local number stripped to digits, through
	 * {@code banner_normalize_whatsapp_phone()}. An empty local number is no
	 * number, not a bare dial code; so is a result outside eight to fifteen
	 * digits.
	 */
	static String whatsappNumber(String countryCode, String localPhone) {
		String local = NON_DIGITS.matcher(localPhone == null ? "" : localPhone.trim()).replaceAll("");
		if (local.isEmpty()) {
			return null;
		}
		String dial = countryCode == null ? "" : countryCode.trim();
		while (dial.startsWith("+")) {
			dial = dial.substring(1);
		}
		String number = NON_DIGITS.matcher(dial).replaceAll("") + local;
		return isStorableWhatsappNumber(number) ? number : null;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** {@code (int) ($post['sort_order'] ?? 0)} ({@link PhpCast#intval}), bounded to the {@code int} column. */
	private static int parseInt(String value) {
		return Math.clamp(PhpCast.intval(value), Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

}
