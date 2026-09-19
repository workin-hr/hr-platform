package com.workin.backend.platformadmin.content;

import com.workin.legacy.PhpCast;

/**
 * Validates a submitted FAQ category or item, reproducing
 * {@code faq_category_validate_post()} and {@code faq_item_validate_post()}.
 *
 * <p>Both require every text field in <b>both</b> languages. That is the
 * dashboard's rule and it is worth keeping: an item with only an Arabic
 * answer renders as an empty panel to an English client rather than being
 * hidden, because the clients read whichever column matches their locale
 * and do not fall back.
 */
public final class FaqForm {

	/** @param errorKey a message key, or null when the value is present */
	public record CategoryResult(Faq.Category category, String errorKey) {

		public boolean ok() {
			return this.category != null;
		}
	}

	/** @param errorKey a message key, or null when the value is present */
	public record ItemResult(Faq.Item item, String errorKey) {

		public boolean ok() {
			return this.item != null;
		}
	}

	private FaqForm() {
	}

	public static CategoryResult validateCategory(String nameAr, String nameEn,
			String sortOrder, boolean active) {
		String ar = trim(nameAr);
		String en = trim(nameEn);
		if (ar.isEmpty() || en.isEmpty()) {
			return new CategoryResult(null, "error_required");
		}
		return new CategoryResult(
				new Faq.Category(0L, ar, en, parseInt(sortOrder), active, 0), null);
	}

	public static ItemResult validateItem(String categoryId, String questionAr, String questionEn,
			String answerAr, String answerEn, String platform, String sortOrder, boolean active) {

		long category = parseLong(categoryId);
		String qAr = trim(questionAr);
		String qEn = trim(questionEn);
		String aAr = trim(answerAr);
		String aEn = trim(answerEn);

		if (category < 1 || qAr.isEmpty() || qEn.isEmpty() || aAr.isEmpty() || aEn.isEmpty()) {
			return new ItemResult(null, "error_required");
		}

		return new ItemResult(new Faq.Item(0L, category, null, null, qAr, qEn, aAr, aEn,
				Faq.Platform.of(platform), parseInt(sortOrder), active), null);
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}

	/** {@code (int) ($post['sort_order'] ?? 0)} ({@link PhpCast#intval}), bounded to the {@code int} column. */
	private static int parseInt(String value) {
		return Math.clamp(PhpCast.intval(value), Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	/** {@code (int) ($post['faq_category_id'] ?? 0)}. */
	private static long parseLong(String value) {
		return PhpCast.intval(value);
	}

}
