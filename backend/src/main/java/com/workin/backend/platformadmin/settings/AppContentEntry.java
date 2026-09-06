package com.workin.backend.platformadmin.settings;

import java.time.LocalDateTime;

/**
 * One of the three fixed content documents ({@code app_content_load_map()}).
 *
 * <p>Always present: legacy seeds all three keys with empty values before
 * overlaying whatever rows exist, so a document that has never been saved
 * renders as blank rather than missing.
 */
public record AppContentEntry(
		String key,
		String labelAr,
		String labelEn,
		String icon,
		String valueAr,
		String valueEn,
		LocalDateTime updatedAt) {

	public boolean saved() {
		return this.updatedAt != null;
	}

}
