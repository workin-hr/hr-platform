package com.workin.backend.platformadmin.content;

import java.util.List;

/**
 * A row of {@code banners} -- the promotional cards the mobile and desktop
 * clients show on their home screen, read through {@code banners/list}.
 *
 * <p>{@link Action} is the load-bearing part. The clients open
 * {@code buttonActionValue} when the banner's button is tapped, and
 * {@code banners/list.php} returns it <b>unsanitised</b> -- the API's own
 * {@code sanitize_banner_internal_route()} and
 * {@code sanitize_banner_external_url()} exist but are called from nowhere.
 * Validation at write time is therefore the only control there is, which is
 * why {@link BannerForm} reproduces it rather than trusting the column.
 */
public record Banner(
		long id,
		String imageUrl,
		boolean active,
		int sortOrder,
		Faq.Platform platform,
		String titleAr,
		String titleEn,
		String descriptionAr,
		String descriptionEn,
		String buttonLabelAr,
		String buttonLabelEn,
		Banner.Action buttonActionType,
		String buttonActionValue,
		String createdAt) {

	/** {@code substr((string) $row['created_at'], 0, 10)}. */
	public String createdDate() {
		return this.createdAt == null || this.createdAt.isBlank()
				? "—" : this.createdAt.substring(0, Math.min(10, this.createdAt.length()));
	}

	/**
	 * {@code home_banner_title()}: the viewer's language, falling back to
	 * Arabic and then English -- a banner with only one of the two still shows
	 * rather than rendering an empty heading.
	 */
	public String title(String lang) {
		return firstNonBlank("en".equals(lang) ? this.titleEn : this.titleAr,
				this.titleAr, this.titleEn);
	}

	public String description(String lang) {
		return firstNonBlank("en".equals(lang) ? this.descriptionEn : this.descriptionAr,
				this.descriptionAr, this.descriptionEn);
	}

	/**
	 * {@code home_banner_cta()}: the button, or null when this banner has none.
	 *
	 * <p>Null rather than a disabled button for every case legacy refuses: no
	 * action type, no label, an external URL that is not {@code http(s)}, or a
	 * WhatsApp action with no number. A button that goes nowhere is worse than
	 * no button, and the stored value is unsanitised on read (see above), so
	 * the check happens here as well as at write time.
	 *
	 * @param lang the viewer's language, for the label
	 */
	public Cta cta(String lang) {
		String label = firstNonBlank("en".equals(lang) ? this.buttonLabelEn : this.buttonLabelAr,
				this.buttonLabelAr, this.buttonLabelEn);
		if (this.buttonActionType == null || this.buttonActionType == Action.NONE || label.isEmpty()) {
			return null;
		}
		String value = this.buttonActionValue == null ? "" : this.buttonActionValue.trim();
		return switch (this.buttonActionType) {
			case EXTERNAL_URL -> value.regionMatches(true, 0, "http://", 0, 7)
					|| value.regionMatches(true, 0, "https://", 0, 8)
					? new Cta(label, value, true) : null;
			case INTERNAL_ROUTE -> INTERNAL_ROUTES.contains(value)
					? new Cta(label, routeHref(value), false) : null;
			case WHATSAPP -> {
				String digits = value.replaceAll("\\D", "");
				yield digits.isEmpty() ? null : new Cta(label, "https://wa.me/" + digits, true);
			}
			case NONE -> null;
		};
	}

	/**
	 * {@code home_internal_route_href()} against this surface's own paths.
	 *
	 * <p>The three {@code app_*} keys name client screens with no page here, so
	 * they land on the home page rather than on a 404 -- which is what legacy's
	 * map does with a key it has no entry for.
	 */
	private static String routeHref(String key) {
		return switch (key) {
			case "home", "dashboard", "app_how_to_use", "app_terms", "app_compliance" -> "/admin";
			default -> "/admin/" + key;
		};
	}

	private static String firstNonBlank(String... candidates) {
		for (String candidate : candidates) {
			if (candidate != null && !candidate.isBlank()) {
				return candidate.trim();
			}
		}
		return "";
	}

	/** @param external whether the link leaves this application */
	public record Cta(String label, String href, boolean external) {
	}

	/** {@code banners.button_action_type}. */
	public enum Action {
		NONE("none"),
		EXTERNAL_URL("external_url"),
		INTERNAL_ROUTE("internal_route"),
		WHATSAPP("whatsapp");

		private final String stored;

		Action(String stored) {
			this.stored = stored;
		}

		public String stored() {
			return this.stored;
		}

		/** Anything unrecognised becomes {@code none}, as the dashboard's own check does. */
		public static Action of(String value) {
			for (Action action : values()) {
				if (action.stored.equals(value)) {
					return action;
				}
			}
			return NONE;
		}
	}

	/**
	 * {@code banner_internal_route_whitelist()}
	 * ({@code apis/helpers/banner_routes.php}) -- the screen keys the desktop
	 * client knows how to open.
	 *
	 * <p>Copied rather than referenced because it is the allowlist an
	 * {@code internal_route} value is checked against, and there is nowhere
	 * else in this application that holds it. Its own source says it "must
	 * stay in sync with desktop app sidebar bannerRouteKey values"; adding a
	 * key here that the client does not know produces a button that does
	 * nothing.
	 */
	public static final List<String> INTERNAL_ROUTES = List.of(
			"home", "dashboard", "branches", "departments", "job_titles", "shifts",
			"employees", "requests", "leave_balances", "penalties", "assets", "advances",
			"workforce_planning", "salary_calculator", "attendance", "payroll", "settings",
			"app_how_to_use", "app_terms", "app_compliance");
}
