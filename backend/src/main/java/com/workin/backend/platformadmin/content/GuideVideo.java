package com.workin.backend.platformadmin.content;

/**
 * One row of {@code guide_videos} as
 * {@code dashboard/pages/guide_videos/page.php} lists it.
 *
 * <p>{@link com.workin.legacy.guide.LegacyGuideVideo} is the same table read
 * for the client: active rows only, and without {@code is_active} because the
 * client never sees an inactive one. This record is the dashboard's view —
 * every row, active or not, because turning a clip off is what the page is
 * for.
 *
 * <p>The table has no {@code company_id}. Guide videos are platform-wide
 * content, which is why this page is administrator-only in
 * {@code DashboardAccess} and carries none of the tenant guards the HR pages
 * do: there is no tenant to guard.
 *
 * @param video the stored filename — not a path and not a URL. It reaches a
 *     filesystem path when the client resolves it, so what may be written here
 *     is constrained by {@link GuideVideoForm}.
 */
public record GuideVideo(
		long id,
		String titleAr,
		String titleEn,
		String video,
		int sortOrder,
		boolean active) {
}
