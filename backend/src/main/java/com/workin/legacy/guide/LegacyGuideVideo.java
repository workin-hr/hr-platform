package com.workin.legacy.guide;

/**
 * One row of {@code guide_videos} -- a how-to clip the desktop client lists.
 *
 * @param video the stored filename, not a path and not a URL; resolved to
 *        both by {@link LegacyGuideVideoService}
 */
public record LegacyGuideVideo(
		long id, String titleAr, String titleEn, String video, int sortOrder) {
}
