package com.workin.legacy.guide;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code guide_videos_list_active()}'s query, and only its query -- the
 * filesystem half lives in {@link LegacyGuideVideoService}.
 *
 * <p>Ordered by {@code sort_order} then {@code id}, which the client renders
 * in the order received, so it is a visible contract.
 */
@Repository
public class LegacyGuideVideoStore {

	private final JdbcTemplate jdbcTemplate;

	public LegacyGuideVideoStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	public List<LegacyGuideVideo> activeVideos() {
		return this.jdbcTemplate.query("""
				SELECT id, title_ar, title_en, video, sort_order
				  FROM guide_videos
				 WHERE is_active = 1
				 ORDER BY sort_order ASC, id ASC""",
				(rs, rowNum) -> new LegacyGuideVideo(
						rs.getLong("id"),
						rs.getString("title_ar"),
						rs.getString("title_en"),
						rs.getString("video"),
						rs.getInt("sort_order")));
	}

}
