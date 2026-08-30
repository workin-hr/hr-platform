package com.workin.legacy.reference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The queries behind Wave 13.5's reference modules: {@code app_content},
 * {@code banners} and the two {@code faqs} tables.
 *
 * <p>None of them is company-scoped, and none should be: no table here has a
 * {@code company_id} column and no legacy query adds one. This is platform
 * content authored in the dashboard and read by every tenant, which is why the
 * absence of a tenant filter is stated rather than left to be re-derived.
 */
@Repository
public class LegacyReferenceStore {

	private final JdbcTemplate jdbcTemplate;

	public LegacyReferenceStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** One {@code app_content} row's two language values, or null when absent. */
	public record ContentValues(String arabic, String english) {
	}

	/**
	 * {@code app_content/one.php}'s lookup. The endpoint 404s on a miss, so
	 * null here is "no such key" rather than an empty value.
	 */
	public ContentValues content(String contentKey) {
		return jdbcTemplate.query(
				"SELECT content_value_ar, content_value_en FROM app_content WHERE content_key=?",
				rs -> rs.next()
						? new ContentValues(rs.getString("content_value_ar"), rs.getString("content_value_en"))
						: null,
				contentKey);
	}

	/**
	 * {@code banners/list.php}'s query, including its platform filter.
	 *
	 * <p>The filter is applied only for the two recognised values; anything
	 * else -- including a missing parameter, an empty one, or {@code ?platform=web}
	 * -- leaves it off and returns <b>every</b> active banner. That is legacy's
	 * {@code if/elseif} with no {@code else}, and it means an unrecognised
	 * platform is wider than a recognised one, not narrower.
	 *
	 * <p>Rows are returned as ordered maps of the exact selected columns,
	 * because PHP hands {@code get_all()}'s associative rows straight to
	 * {@code ok()} with no shaping -- so the column list <em>is</em> the wire
	 * contract, and its order is the order of the JSON object's keys.
	 */
	public List<Map<String, Object>> banners(String platform) {
		String filter = switch (platform) {
			case "desktop" -> " AND (app_platform IN ('desktop','both'))";
			case "mobile" -> " AND (app_platform IN ('mobile','both'))";
			default -> "";
		};

		return jdbcTemplate.query(
				"""
				SELECT id, image_url, app_platform, title_ar, title_en, description_ar, description_en,
				       button_label_ar, button_label_en, button_action_type, button_action_value
				FROM banners
				WHERE is_active = 1%s
				ORDER BY sort_order ASC""".formatted(filter),
				(rs, rowNum) -> {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("id", rs.getLong("id"));
					row.put("image_url", rs.getString("image_url"));
					row.put("app_platform", rs.getString("app_platform"));
					row.put("title_ar", rs.getString("title_ar"));
					row.put("title_en", rs.getString("title_en"));
					row.put("description_ar", rs.getString("description_ar"));
					row.put("description_en", rs.getString("description_en"));
					row.put("button_label_ar", rs.getString("button_label_ar"));
					row.put("button_label_en", rs.getString("button_label_en"));
					row.put("button_action_type", rs.getString("button_action_type"));
					row.put("button_action_value", rs.getString("button_action_value"));
					return row;
				});
	}

	/** One {@code faq_categories} row, before its items are attached. */
	public record FaqCategory(long id, String nameAr, String nameEn, int sortOrder) {
	}

	/** One {@code faq_items} row. */
	public record FaqItem(
			long id, long categoryId, String questionAr, String questionEn,
			String answerAr, String answerEn, String appPlatform, int sortOrder) {
	}

	public List<FaqCategory> faqCategories() {
		return jdbcTemplate.query(
				"""
				SELECT c.id, c.name_ar, c.name_en, c.sort_order
				FROM faq_categories c
				WHERE c.is_active = 1
				ORDER BY c.sort_order ASC, c.id ASC""",
				(rs, rowNum) -> new FaqCategory(
						rs.getLong("id"), rs.getString("name_ar"), rs.getString("name_en"),
						rs.getInt("sort_order")));
	}

	/**
	 * The items query joins back to {@code faq_categories} and re-checks
	 * {@code c.is_active = 1}, so an item under a deactivated category is
	 * excluded here as well as by the category list -- belt and braces in the
	 * legacy, reproduced rather than simplified away.
	 */
	public List<FaqItem> faqItems(String platform) {
		String filter = switch (platform) {
			case "desktop" -> " AND i.app_platform IN ('desktop','both')";
			case "mobile" -> " AND i.app_platform IN ('mobile','both')";
			default -> "";
		};

		return jdbcTemplate.query(
				"""
				SELECT i.id, i.faq_category_id, i.question_ar, i.question_en,
				       i.answer_ar, i.answer_en, i.app_platform, i.sort_order
				FROM faq_items i
				INNER JOIN faq_categories c ON c.id = i.faq_category_id
				WHERE i.is_active = 1 AND c.is_active = 1%s
				ORDER BY i.sort_order ASC, i.id ASC""".formatted(filter),
				(rs, rowNum) -> new FaqItem(
						rs.getLong("id"), rs.getLong("faq_category_id"),
						rs.getString("question_ar"), rs.getString("question_en"),
						rs.getString("answer_ar"), rs.getString("answer_en"),
						rs.getString("app_platform"), rs.getInt("sort_order")));
	}
}
