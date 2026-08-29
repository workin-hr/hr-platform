package com.workin.legacy.reference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * {@code faq_list_grouped_for_platform()} ({@code helpers/faq_helper.php}).
 *
 * <p>Two queries and an in-memory join, exactly as legacy does it -- not a
 * single grouped query. The distinction is observable: the category list is
 * ordered by {@code (sort_order, id)} and the items by their own
 * {@code (sort_order, id)}, and the output follows the <em>category</em>
 * ordering with each category's items in <em>item</em> order.
 *
 * <p><b>A category with no matching items disappears.</b> The final loop skips
 * any category whose bucket is empty, so filtering by platform can remove whole
 * categories rather than leaving them present-but-empty. A client rendering
 * section headers therefore sees a different set of sections per platform.
 */
@Service
public class LegacyFaqCatalog {

	private final LegacyReferenceStore referenceStore;

	public LegacyFaqCatalog(LegacyReferenceStore referenceStore) {
		this.referenceStore = referenceStore;
	}

	/**
	 * @param platform already lowercased and trimmed by the caller, as
	 *     {@code strtolower(trim(...))} does at the endpoint
	 * @param english whether {@code app_locale()} resolved to English
	 */
	public List<Map<String, Object>> grouped(String platform, boolean english) {
		Map<Long, List<Map<String, Object>>> byCategory = new LinkedHashMap<>();
		for (LegacyReferenceStore.FaqItem item : referenceStore.faqItems(platform)) {
			// PHP guards `$cid < 1`, which the INNER JOIN already makes
			// unreachable -- kept because dropping it would be a behaviour
			// claim about the join rather than about this loop.
			if (item.categoryId() < 1) {
				continue;
			}
			Map<String, Object> shaped = new LinkedHashMap<>();
			shaped.put("id", item.id());
			shaped.put("faq_category_id", item.categoryId());
			shaped.put("question", text(english ? item.questionEn() : item.questionAr()));
			shaped.put("answer", text(english ? item.answerEn() : item.answerAr()));
			shaped.put("question_ar", text(item.questionAr()));
			shaped.put("question_en", text(item.questionEn()));
			shaped.put("answer_ar", text(item.answerAr()));
			shaped.put("answer_en", text(item.answerEn()));
			// `?? 'both'` fires only on a NULL column, not on an empty string.
			shaped.put("app_platform", item.appPlatform() == null ? "both" : item.appPlatform());
			shaped.put("sort_order", item.sortOrder());
			byCategory.computeIfAbsent(item.categoryId(), key -> new ArrayList<>()).add(shaped);
		}

		List<Map<String, Object>> out = new ArrayList<>();
		for (LegacyReferenceStore.FaqCategory category : referenceStore.faqCategories()) {
			List<Map<String, Object>> items = byCategory.get(category.id());
			if (items == null || items.isEmpty()) {
				continue;
			}
			Map<String, Object> shaped = new LinkedHashMap<>();
			shaped.put("id", category.id());
			shaped.put("name", text(english ? category.nameEn() : category.nameAr()));
			shaped.put("name_ar", text(category.nameAr()));
			shaped.put("name_en", text(category.nameEn()));
			shaped.put("sort_order", category.sortOrder());
			shaped.put("items", items);
			out.add(shaped);
		}
		return out;
	}

	/** {@code (string) ($x ?? '')} -- a NULL column becomes "", never null. */
	private static String text(String value) {
		return value == null ? "" : value;
	}
}
