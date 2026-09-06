package com.workin.backend.platformadmin.content;

import java.util.Locale;
import java.util.regex.Pattern;

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
				action, value), null);
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

	/**
	 * {@code banner_whatsapp_from_parts()}: the dial code without its plus,
	 * followed by the local number stripped to digits. An empty local number
	 * is no number, not a bare dial code.
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
		return NON_DIGITS.matcher(dial).replaceAll("") + local;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static int parseInt(String value) {
		try {
			return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim());
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

}
