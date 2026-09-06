package com.workin.backend.platformadmin.content;

import java.util.List;

import com.workin.legacy.guide.LegacyGuideVideoService;

/**
 * Validates a submitted guide video, reproducing
 * {@code guide_videos_validate_post()}.
 *
 * <p>Both titles are required in both languages, as everywhere else in this
 * catalogue: the clients read whichever column matches their locale and do not
 * fall back, so a half-translated row renders blank rather than hidden.
 *
 * <p><b>The filename is the part that matters.</b> It is typed in by hand and
 * ends up in a filesystem path when a client resolves the clip, so
 * {@code guide_videos_sanitize_filename()} passes it through
 * {@code faq_parse_video_filenames()} and keeps the first survivor —
 * {@code basename()}, then an allow-list of
 * {@code ^[A-Za-z0-9._-]+\.(mp4|webm|mov|m4v)$}, which admits no separator of
 * any kind. This calls the same method the read path uses rather than
 * restating the rule, so the two cannot drift apart.
 */
public final class GuideVideoForm {

	/** @param errorKey a message key, or null when the submission is usable */
	public record Result(GuideVideo video, String errorKey) {

		public boolean ok() {
			return this.video != null;
		}
	}

	private GuideVideoForm() {
	}

	public static Result validate(
			String titleAr, String titleEn, String video, String sortOrder, boolean active) {

		String ar = trim(titleAr);
		String en = trim(titleEn);
		String filename = firstSafeFilename(video);

		// `if ($titleAr === '' || $titleEn === '' || $video === null)` -- one
		// message for all three, which is what the page flashes.
		if (ar.isEmpty() || en.isEmpty() || filename == null) {
			return new Result(null, "error_required");
		}
		return new Result(
				new GuideVideo(0L, ar, en, filename, parseInt(sortOrder), active), null);
	}

	/** {@code guide_videos_sanitize_filename()}: the first name that survives, or null. */
	private static String firstSafeFilename(String raw) {
		List<String> names = LegacyGuideVideoService.parseVideoFilenames(raw);
		return names.isEmpty() ? null : names.get(0);
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}

	/** {@code (int) ($post['sort_order'] ?? 0)}: anything unparseable is zero. */
	private static int parseInt(String value) {
		try {
			return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim());
		}
		catch (NumberFormatException notANumber) {
			return 0;
		}
	}

}
