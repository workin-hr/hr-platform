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
		String buttonActionValue) {

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
