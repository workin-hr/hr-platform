package com.workin.backend.platformadmin.content;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code guide_videos} for the dashboard.
 *
 * <p>{@code guide_videos_admin_list()} orders by {@code sort_order} then id
 * and filters nothing — the page has to show inactive rows, because switching
 * one off is one of the three things it can do.
 * {@link com.workin.legacy.guide.LegacyGuideVideoStore} is the client's read
 * of the same table and keeps its own {@code is_active = 1}.
 *
 * <p>PHP calls {@code guide_videos_ensure_table()} on every request, which
 * creates the table and migrates it from {@code faq_items} if it is missing.
 * That is not reproduced: the table is in the vendored schema, the Java
 * deployment has no PHP to run the migration a second time, and issuing DDL
 * from a page render is not a behaviour worth carrying onto a surface that has
 * a schema contract. If the table were ever absent the page would fail loudly
 * here rather than silently rebuilding it.
 */
@Repository
@Profile("phase1-mysql")
public class GuideVideoStore {

	private final JdbcTemplate jdbcTemplate;

	public GuideVideoStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private final RowMapper<GuideVideo> mapper = (rs, rowNum) -> new GuideVideo(
			rs.getLong("id"),
			rs.getString("title_ar"),
			rs.getString("title_en"),
			rs.getString("video"),
			rs.getInt("sort_order"),
			rs.getInt("is_active") == 1);

	/** {@code guide_videos_admin_list()}. */
	public List<GuideVideo> all() {
		return this.jdbcTemplate.query(
				"SELECT id, title_ar, title_en, video, sort_order, is_active"
						+ " FROM guide_videos ORDER BY sort_order ASC, id ASC",
				this.mapper);
	}

	public void insert(GuideVideo video) {
		this.jdbcTemplate.update(
				"INSERT INTO guide_videos (title_ar, title_en, video, sort_order, is_active)"
						+ " VALUES (?, ?, ?, ?, ?)",
				video.titleAr(), video.titleEn(), video.video(), video.sortOrder(),
				video.active() ? 1 : 0);
	}

	/**
	 * {@code dbUpdate('guide_videos', $validated['fields'], $id)}: the same five
	 * columns the insert writes. {@code id} and {@code created_at} are not among
	 * them, so nothing about the row's identity is reachable from the form.
	 */
	public void update(long id, GuideVideo video) {
		this.jdbcTemplate.update(
				"UPDATE guide_videos SET title_ar = ?, title_en = ?, video = ?,"
						+ " sort_order = ?, is_active = ? WHERE id = ?",
				video.titleAr(), video.titleEn(), video.video(), video.sortOrder(),
				video.active() ? 1 : 0, id);
	}

	public void delete(long id) {
		this.jdbcTemplate.update("DELETE FROM guide_videos WHERE id = ?", id);
	}

	/** The title an audit entry names, read before the row is changed or removed. */
	public String titleOf(long id) {
		List<String> found = this.jdbcTemplate.queryForList(
				"SELECT title_en FROM guide_videos WHERE id = ?", String.class, id);
		return found.isEmpty() ? null : found.get(0);
	}

}
