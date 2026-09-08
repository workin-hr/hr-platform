package com.workin.legacy.guide;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filesystem half of {@code guide_videos_list_active()}, which is where
 * its behaviour actually lives -- the query is four columns and an ORDER BY.
 *
 * <p>Driven against a real temporary directory rather than a mocked
 * filesystem: what is being reproduced is "does this file exist", and a mock
 * would assert only that the test knows which files it created.
 */
class LegacyGuideVideoEndToEndTest {

	@TempDir
	Path uploads;

	private LegacyGuideVideoService serviceFor(List<LegacyGuideVideo> rows) {
		// A DataSource that is never connected to: the store's constructor
		// builds a JdbcTemplate eagerly, and activeVideos() is overridden, so
		// nothing here reaches a database.
		LegacyGuideVideoStore store = new LegacyGuideVideoStore(new SimpleDriverDataSource()) {
			@Override
			public List<LegacyGuideVideo> activeVideos() {
				return rows;
			}
		};
		return new LegacyGuideVideoService(store, this.uploads.toString(), "/uploads/");
	}

	private void put(String subdirectory, String name) throws Exception {
		Path dir = this.uploads.resolve(subdirectory);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve(name), "x");
	}

	@Test
	void aVideoWhoseFileExistsIsListedWithItsUrl() throws Exception {
		put("guide_videos", "intro.mp4");

		List<Map<String, Object>> out = serviceFor(
				List.of(new LegacyGuideVideo(1, "مقدمة", "Intro", "intro.mp4", 0))).list(false);

		assertThat(out).hasSize(1);
		assertThat(out.get(0)).containsEntry("video_url", "/uploads/guide_videos/intro.mp4")
				.containsEntry("title", "مقدمة")
				.containsEntry("thumbnail_url", null);
	}

	/** PHP `continue`s past it; the client has no empty state for a video that will not play. */
	@Test
	void aRowWhoseFileIsMissingIsDroppedRatherThanReturnedWithADeadUrl() {
		assertThat(serviceFor(List.of(new LegacyGuideVideo(1, "أ", "A", "gone.mp4", 0))).list(false))
				.isEmpty();
	}

	@Test
	void theLegacyFaqFolderIsCheckedSecond() throws Exception {
		put("faqs", "old.mp4");

		List<Map<String, Object>> out = serviceFor(
				List.of(new LegacyGuideVideo(1, "أ", "A", "old.mp4", 0))).list(false);

		assertThat(out).hasSize(1);
		assertThat(out.get(0)).containsEntry("video_url", "/uploads/faqs/old.mp4");
	}

	@Test
	void aPosterBesideTheVideoBecomesTheThumbnail() throws Exception {
		put("guide_videos", "clip.mp4");
		put("guide_videos", "clip.png");

		assertThat(serviceFor(List.of(new LegacyGuideVideo(1, "أ", "A", "clip.mp4", 0))).list(false)
				.get(0)).containsEntry("thumbnail_url", "/uploads/guide_videos/clip.png");
	}

	/**
	 * The control the port would have silently dropped. The column is written
	 * by the dashboard, so this is defence in depth -- but it is the only
	 * thing standing between a crafted row and a path outside the media
	 * directory.
	 */
	@Test
	void aFilenameThatTriesToLeaveTheMediaDirectoryIsRefused() throws Exception {
		Files.writeString(this.uploads.resolve("secret.mp4"), "x");

		for (String hostile : List.of("../secret.mp4", "..\\secret.mp4",
				"/etc/passwd", "clip.mp4;rm -rf /", "clip.php", "clip.mp4.php")) {
			assertThat(serviceFor(List.of(new LegacyGuideVideo(1, "أ", "A", hostile, 0))).list(false))
					.as("must not resolve: %s", hostile)
					.isEmpty();
		}
	}

	@Test
	void aNonVideoExtensionIsRefusedEvenWhenThatFileExists() throws Exception {
		put("guide_videos", "clip.exe");

		assertThat(serviceFor(List.of(new LegacyGuideVideo(1, "أ", "A", "clip.exe", 0))).list(false))
				.isEmpty();
	}

	@Test
	void theTitleFallsBackToTheOtherLanguageWhenBlank() throws Exception {
		put("guide_videos", "a.mp4");
		put("guide_videos", "b.mp4");

		List<Map<String, Object>> english = serviceFor(List.of(
				new LegacyGuideVideo(1, "عربي", "", "a.mp4", 0),
				new LegacyGuideVideo(2, "", "English", "b.mp4", 1))).list(true);

		assertThat(english.get(0)).containsEntry("title", "عربي");
		assertThat(english.get(1)).containsEntry("title", "English");
	}

	/**
	 * A space is not in {@code [A-Za-z0-9._-]}, so PHP's own regex refuses it
	 * too and the file is never listed however it got onto disk.
	 *
	 * <p>Which makes {@code rawurlencode()} a no-op for everything that
	 * survives validation -- every allowed character is URL-safe. It is
	 * reproduced anyway, because the two are a pair: someone widening the
	 * charset later would otherwise be widening it into an unencoded URL.
	 */
	@Test
	void aFilenameWithASpaceIsRefusedByTheCharsetRule() throws Exception {
		put("guide_videos", "how to.mp4");

		assertThat(serviceFor(List.of(new LegacyGuideVideo(1, "أ", "A", "how to.mp4", 0))).list(false))
				.isEmpty();
	}

	@Test
	void localeDetectionMatchesPhpsStrStartsWithEn() {
		assertThat(LegacyGuideVideoService.isEnglish("en")).isTrue();
		assertThat(LegacyGuideVideoService.isEnglish("en-US")).isTrue();
		assertThat(LegacyGuideVideoService.isEnglish("EN")).isTrue();
		assertThat(LegacyGuideVideoService.isEnglish("ar")).isFalse();
		assertThat(LegacyGuideVideoService.isEnglish(null)).isFalse();
	}

}
