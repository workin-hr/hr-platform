package com.workin.backend.platformadmin.content;

import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code faq_categories} and {@code faq_items}.
 *
 * <p>Ordering follows {@code faq_categories_list()} and
 * {@code faq_items_list()} exactly -- categories by {@code sort_order} then
 * id, items by their category's sort order first, then their own. Clients
 * render the list in the order they receive it, so this is a visible
 * contract rather than an implementation detail.
 */
@Repository
@Profile("phase1-mysql")
public class FaqStore {

	private final JdbcTemplate jdbcTemplate;

	public FaqStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private final RowMapper<Faq.Category> categoryMapper = (rs, rowNum) -> new Faq.Category(
			rs.getLong("id"),
			rs.getString("name_ar"),
			rs.getString("name_en"),
			rs.getInt("sort_order"),
			rs.getInt("is_active") == 1,
			rs.getInt("items_count"));

	private final RowMapper<Faq.Item> itemMapper = (rs, rowNum) -> new Faq.Item(
			rs.getLong("id"),
			rs.getLong("faq_category_id"),
			rs.getString("category_name_ar"),
			rs.getString("category_name_en"),
			rs.getString("question_ar"),
			rs.getString("question_en"),
			rs.getString("answer_ar"),
			rs.getString("answer_en"),
			Faq.Platform.of(rs.getString("app_platform")),
			rs.getInt("sort_order"),
			rs.getInt("is_active") == 1);

	public List<Faq.Category> categories() {
		return this.jdbcTemplate.query("""
				SELECT c.*,
				       (SELECT COUNT(*) FROM faq_items i WHERE i.faq_category_id = c.id) AS items_count
				  FROM faq_categories c
				 ORDER BY c.sort_order ASC, c.id ASC""", this.categoryMapper);
	}

	public List<Faq.Item> items() {
		return this.jdbcTemplate.query("""
				SELECT i.*, c.name_ar AS category_name_ar, c.name_en AS category_name_en
				  FROM faq_items i
				 INNER JOIN faq_categories c ON c.id = i.faq_category_id
				 ORDER BY c.sort_order ASC, i.sort_order ASC, i.id ASC""", this.itemMapper);
	}

	public boolean categoryExists(long id) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM faq_categories WHERE id = ?", Integer.class, id);
		return count != null && count > 0;
	}

	public Optional<Long> findItem(long id) {
		return this.jdbcTemplate.query("SELECT id FROM faq_items WHERE id = ?",
						(rs, rowNum) -> rs.getLong("id"), id)
				.stream().findFirst();
	}

	public void insertCategory(Faq.Category category) {
		this.jdbcTemplate.update(
				"INSERT INTO faq_categories (name_ar, name_en, sort_order, is_active) VALUES (?, ?, ?, ?)",
				category.nameAr(), category.nameEn(), category.sortOrder(), category.active() ? 1 : 0);
	}

	public void updateCategory(long id, Faq.Category category) {
		this.jdbcTemplate.update(
				"UPDATE faq_categories SET name_ar = ?, name_en = ?, sort_order = ?, is_active = ? WHERE id = ?",
				category.nameAr(), category.nameEn(), category.sortOrder(), category.active() ? 1 : 0, id);
	}

	/**
	 * Deletes a category. Its items go with it: {@code fk_faq_items_category}
	 * is declared {@code ON DELETE CASCADE}, so the database removes them and
	 * an explicit delete here would be a second, redundant statement that
	 * could only drift from the constraint.
	 *
	 * <p>The count is taken first so the audit row can say how much went,
	 * which is the part an operator asks about afterwards -- the dashboard
	 * records nothing at all.
	 *
	 * @return how many items the cascade took
	 */
	public int deleteCategory(long id) {
		Integer items = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM faq_items WHERE faq_category_id = ?", Integer.class, id);
		this.jdbcTemplate.update("DELETE FROM faq_categories WHERE id = ?", id);
		return items == null ? 0 : items;
	}

	public void insertItem(Faq.Item item) {
		this.jdbcTemplate.update("""
				INSERT INTO faq_items
				  (faq_category_id, question_ar, question_en, answer_ar, answer_en,
				   app_platform, sort_order, is_active)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
				item.categoryId(), item.questionAr(), item.questionEn(), item.answerAr(),
				item.answerEn(), item.platform().stored(), item.sortOrder(), item.active() ? 1 : 0);
	}

	public void updateItem(long id, Faq.Item item) {
		this.jdbcTemplate.update("""
				UPDATE faq_items
				   SET faq_category_id = ?, question_ar = ?, question_en = ?, answer_ar = ?,
				       answer_en = ?, app_platform = ?, sort_order = ?, is_active = ?
				 WHERE id = ?""",
				item.categoryId(), item.questionAr(), item.questionEn(), item.answerAr(),
				item.answerEn(), item.platform().stored(), item.sortOrder(), item.active() ? 1 : 0, id);
	}

	public void deleteItem(long id) {
		this.jdbcTemplate.update("DELETE FROM faq_items WHERE id = ?", id);
	}

}
