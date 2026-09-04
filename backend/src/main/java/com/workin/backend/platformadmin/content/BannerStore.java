package com.workin.backend.platformadmin.content;

import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code banners}.
 *
 * <p>Listed by {@code sort_order} then id, the order the clients render
 * them in, so the column is a visible contract rather than bookkeeping.
 */
@Repository
@Profile("phase1-mysql")
public class BannerStore {

	private final JdbcTemplate jdbcTemplate;

	public BannerStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private final RowMapper<Banner> mapper = (rs, rowNum) -> new Banner(
			rs.getLong("id"),
			rs.getString("image_url"),
			rs.getInt("is_active") == 1,
			rs.getInt("sort_order"),
			Faq.Platform.of(rs.getString("app_platform")),
			rs.getString("title_ar"),
			rs.getString("title_en"),
			rs.getString("description_ar"),
			rs.getString("description_en"),
			rs.getString("button_label_ar"),
			rs.getString("button_label_en"),
			Banner.Action.of(rs.getString("button_action_type")),
			rs.getString("button_action_value"));

	public List<Banner> list() {
		return this.jdbcTemplate.query(
				"SELECT * FROM banners ORDER BY sort_order ASC, id ASC", this.mapper);
	}

	public Optional<Banner> find(long id) {
		return this.jdbcTemplate.query("SELECT * FROM banners WHERE id = ?", this.mapper, id)
				.stream().findFirst();
	}

	public void insert(Banner banner) {
		this.jdbcTemplate.update("""
				INSERT INTO banners
				  (image_url, is_active, sort_order, app_platform, title_ar, title_en,
				   description_ar, description_en, button_label_ar, button_label_en,
				   button_action_type, button_action_value)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				banner.imageUrl(), banner.active() ? 1 : 0, banner.sortOrder(),
				banner.platform().stored(), banner.titleAr(), banner.titleEn(),
				banner.descriptionAr(), banner.descriptionEn(),
				banner.buttonLabelAr(), banner.buttonLabelEn(),
				banner.buttonActionType().stored(), banner.buttonActionValue());
	}

	public void update(long id, Banner banner) {
		this.jdbcTemplate.update("""
				UPDATE banners
				   SET image_url = ?, is_active = ?, sort_order = ?, app_platform = ?,
				       title_ar = ?, title_en = ?, description_ar = ?, description_en = ?,
				       button_label_ar = ?, button_label_en = ?,
				       button_action_type = ?, button_action_value = ?
				 WHERE id = ?""",
				banner.imageUrl(), banner.active() ? 1 : 0, banner.sortOrder(),
				banner.platform().stored(), banner.titleAr(), banner.titleEn(),
				banner.descriptionAr(), banner.descriptionEn(),
				banner.buttonLabelAr(), banner.buttonLabelEn(),
				banner.buttonActionType().stored(), banner.buttonActionValue(), id);
	}

	/**
	 * Removes the row. The uploaded image stays on disk, as it does in the
	 * dashboard -- and as {@code LegacyFileUploads} already documents for
	 * every other upload path: nothing in this system has ever deleted a
	 * stored file, and adding it here alone would make the behaviour
	 * inconsistent without making it complete.
	 */
	public void delete(long id) {
		this.jdbcTemplate.update("DELETE FROM banners WHERE id = ?", id);
	}

}
