package com.workin.backend.platformadmin.content;

/**
 * The two rows behind {@code dashboard/pages/faqs/page.php}: categories,
 * and the question-and-answer items inside them. Both are read by the
 * mobile and desktop clients through {@code faqs/list} and, before
 * ADR-0016, written only from the PHP dashboard.
 */
public final class Faq {

	/** {@code faq_items.app_platform} -- which client shows the item. */
	public enum Platform {
		DESKTOP("desktop"),
		MOBILE("mobile"),
		BOTH("both");

		private final String stored;

		Platform(String stored) {
			this.stored = stored;
		}

		public String stored() {
			return this.stored;
		}

		/** Anything unrecognised becomes {@code both}, as {@code faq_platform_values()} does. */
		public static Platform of(String value) {
			for (Platform platform : values()) {
				if (platform.stored.equals(value)) {
					return platform;
				}
			}
			return BOTH;
		}
	}

	/** @param itemCount how many items the category holds, for the list view */
	public record Category(long id, String nameAr, String nameEn, int sortOrder, boolean active, int itemCount) {
	}

	public record Item(
			long id,
			long categoryId,
			String categoryNameAr,
			String categoryNameEn,
			String questionAr,
			String questionEn,
			String answerAr,
			String answerEn,
			Platform platform,
			int sortOrder,
			boolean active) {
	}

	private Faq() {
	}

}
