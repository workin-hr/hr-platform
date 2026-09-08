package com.workin.backend.platformadmin.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the guide-video validation to {@code guide_videos_validate_post()} and,
 * through it, to {@code faq_parse_video_filenames()}.
 *
 * <p>The filename cases are the reason this file exists. The value is typed in
 * by hand on an administrator's form and is later joined onto a media
 * directory to serve the clip, so the allow-list is the control standing
 * between a crafted entry and a path outside that directory. It is asserted
 * here as behaviour rather than trusted because it is shared with the read
 * path: if either side ever stops calling it, one of these fails.
 */
class GuideVideoFormTest {

	private static GuideVideoForm.Result submit(String video) {
		return GuideVideoForm.validate("دليل", "Guide", video, "0", true);
	}

	@Test
	void bothTitlesAreRequired() {
		assertThat(GuideVideoForm.validate("دليل", "Guide", "1.mp4", "0", true).ok()).isTrue();
		assertThat(GuideVideoForm.validate("", "Guide", "1.mp4", "0", true).errorKey())
				.isEqualTo("error_required");
		assertThat(GuideVideoForm.validate("دليل", "   ", "1.mp4", "0", true).errorKey())
				.isEqualTo("error_required");
	}

	@Test
	void titlesAreTrimmedBeforeStorage() {
		GuideVideoForm.Result result =
				GuideVideoForm.validate("  دليل  ", "  Guide  ", "1.mp4", "0", true);
		assertThat(result.video().titleAr()).isEqualTo("دليل");
		assertThat(result.video().titleEn()).isEqualTo("Guide");
	}

	@Test
	void aMissingFilenameIsRefusedLikeAMissingTitle() {
		assertThat(submit("").errorKey()).isEqualTo("error_required");
		assertThat(submit(null).errorKey()).isEqualTo("error_required");
		assertThat(submit("   ").errorKey()).isEqualTo("error_required");
	}

	@Test
	void onlyTheFourVideoExtensionsAreAccepted() {
		for (String name : new String[] { "clip.mp4", "clip.webm", "clip.mov", "clip.m4v" }) {
			assertThat(submit(name).ok()).as(name).isTrue();
		}
		for (String name : new String[] { "clip.exe", "clip.php", "clip.mp4.php", "clip", "clip.mp3" }) {
			assertThat(submit(name).errorKey()).as(name).isEqualTo("error_required");
		}
	}

	@Test
	void theExtensionCheckIsCaseInsensitiveAsPhpsIs() {
		assertThat(submit("Clip.MP4").video().video()).isEqualTo("Clip.MP4");
	}

	@Test
	void aPathIsReducedToItsLastSegmentAndThenChecked() {
		// basename() runs first, so a directory prefix does not by itself
		// disqualify the name -- it is stripped, and what is left must still
		// match the allow-list.
		assertThat(submit("videos/clip.mp4").video().video()).isEqualTo("clip.mp4");
		assertThat(submit("videos\\clip.mp4").video().video())
				.as("backslashes are normalised to slashes before basename")
				.isEqualTo("clip.mp4");
	}

	@Test
	void traversalAttemptsDoNotSurvive() {
		// Whatever is left after basename() must match ^[A-Za-z0-9._-]+\.(ext)$,
		// which admits no separator at all -- so nothing that could climb out of
		// the media directory can reach the column.
		for (String attempt : new String[] {
			"../../etc/passwd", "../../../secret.mp4", "..", ".", "/", "\\",
			"..%2f..%2fclip.mp4", "clip.mp4/../../x", "" }) {
			GuideVideoForm.Result result = submit(attempt);
			if (result.ok()) {
				assertThat(result.video().video())
						.as("%s must not keep a separator", attempt)
						.doesNotContain("/").doesNotContain("\\").doesNotContain("..");
			}
		}
		assertThat(submit("../../etc/passwd").errorKey()).isEqualTo("error_required");
		assertThat(submit("..").errorKey()).isEqualTo("error_required");
	}

	@Test
	void theFirstOfSeveralNamesWins() {
		// faq_parse_video_filenames() splits on whitespace, commas and
		// semicolons; guide_videos_sanitize_filename() keeps names[0].
		assertThat(submit("a.mp4 b.mp4").video().video()).isEqualTo("a.mp4");
		assertThat(submit("a.mp4,b.mp4").video().video()).isEqualTo("a.mp4");
		assertThat(submit("a.mp4;b.mp4").video().video()).isEqualTo("a.mp4");
		assertThat(submit("bad.exe good.mp4").video().video())
				.as("an unusable first entry is skipped, not fatal").isEqualTo("good.mp4");
	}

	@Test
	void sortOrderDefaultsToZeroWhenUnparsable() {
		assertThat(submit("1.mp4").video().sortOrder()).isZero();
		assertThat(GuideVideoForm.validate("د", "G", "1.mp4", "abc", true).video().sortOrder()).isZero();
		assertThat(GuideVideoForm.validate("د", "G", "1.mp4", "", true).video().sortOrder()).isZero();
		assertThat(GuideVideoForm.validate("د", "G", "1.mp4", "7", true).video().sortOrder()).isEqualTo(7);
	}

	@Test
	void theActiveFlagIsCarriedThrough() {
		assertThat(GuideVideoForm.validate("د", "G", "1.mp4", "0", true).video().active()).isTrue();
		assertThat(GuideVideoForm.validate("د", "G", "1.mp4", "0", false).video().active()).isFalse();
	}

}
