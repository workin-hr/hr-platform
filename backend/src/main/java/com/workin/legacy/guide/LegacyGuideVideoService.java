package com.workin.legacy.guide;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * {@code guide_videos_list_active()} -- turns rows into the shape the desktop
 * client reads, resolving each stored filename to a URL and a poster.
 *
 * <p>Two behaviours here are not incidental.
 *
 * <p><b>A row whose file is not on disk is dropped, not returned with a dead
 * URL.</b> That is what PHP does ({@code guide_videos_resolve_media()} returns
 * null and the loop {@code continue}s), and the client has no empty state for
 * a video that will not play.
 *
 * <p><b>The filename is validated, not trusted.</b> PHP takes
 * {@code basename()} of it and then requires
 * {@code ^[A-Za-z0-9._-]+\.(mp4|webm|mov|m4v)$}. The column is written by the
 * dashboard, so this is defence in depth rather than a boundary -- but it is
 * the only thing between a crafted row and a path leaving the media
 * directory, and dropping it while porting would be an invisible removal of a
 * control.
 */
@Service
public class LegacyGuideVideoService {

	private static final Pattern SAFE_VIDEO_NAME =
			Pattern.compile("^[A-Za-z0-9._-]+\\.(mp4|webm|mov|m4v)$", Pattern.CASE_INSENSITIVE);

	/** {@code faq_video_poster_filename()}: the first of these that exists beside the video. */
	private static final List<String> POSTER_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp");

	private static final String GUIDE_SUBDIR = "guide_videos";

	/** The legacy FAQ folder, checked second -- clips predating the split still live there. */
	private static final String FAQ_SUBDIR = "faqs";

	private final Path uploadPath;

	private final String uploadUrl;

	private final LegacyGuideVideoStore store;

	public LegacyGuideVideoService(
			LegacyGuideVideoStore store,
			@Value("${app.legacy-uploads.path:uploads}") String uploadPath,
			@Value("${app.legacy-uploads.url:/uploads/}") String uploadUrl) {
		this.store = store;
		this.uploadPath = Path.of(uploadPath);
		this.uploadUrl = uploadUrl.endsWith("/") ? uploadUrl : uploadUrl + "/";
	}

	/**
	 * @param english whether the caller's locale starts with {@code en}; the
	 *        {@code title} field is that language's, falling back to the other
	 *        when it is blank rather than returning an empty string
	 */
	public List<Map<String, Object>> list(boolean english) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (LegacyGuideVideo video : this.store.activeVideos()) {
			Media media = resolve(video.video());
			if (media == null) {
				continue;
			}
			String titleAr = video.titleAr() == null ? "" : video.titleAr();
			String titleEn = video.titleEn() == null ? "" : video.titleEn();

			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", video.id());
			row.put("title", english
					? (titleEn.isEmpty() ? titleAr : titleEn)
					: (titleAr.isEmpty() ? titleEn : titleAr));
			row.put("title_ar", titleAr);
			row.put("title_en", titleEn);
			row.put("video_url", media.url());
			row.put("thumbnail_url", media.thumbnailUrl());
			row.put("sort_order", video.sortOrder());
			out.add(row);
		}
		return out;
	}

	private record Media(String url, String thumbnailUrl) {
	}

	/** @return null when the name is unsafe or the file is in neither directory */
	private Media resolve(String storedName) {
		String filename = basename(storedName);
		if (filename.isEmpty() || !SAFE_VIDEO_NAME.matcher(filename).matches()) {
			return null;
		}
		for (String subdirectory : List.of(GUIDE_SUBDIR, FAQ_SUBDIR)) {
			Path file = this.uploadPath.resolve(subdirectory).resolve(filename);
			if (!Files.isRegularFile(file)) {
				continue;
			}
			String poster = posterFor(this.uploadPath.resolve(subdirectory), filename);
			return new Media(
					publicUrl(subdirectory, filename),
					poster == null ? null : publicUrl(subdirectory, poster));
		}
		return null;
	}

	/** {@code pathinfo($video, PATHINFO_FILENAME)} plus each candidate extension. */
	private static String posterFor(Path directory, String filename) {
		int dot = filename.lastIndexOf('.');
		String base = dot < 0 ? filename : filename.substring(0, dot);
		for (String extension : POSTER_EXTENSIONS) {
			String candidate = base + "." + extension;
			if (Files.isRegularFile(directory.resolve(candidate))) {
				return candidate;
			}
		}
		// PHP falls back to generating a poster with ffmpeg. Not reproduced:
		// spawning a transcoder from a request path is a different decision
		// with its own failure modes, and a null thumbnail is a shape the
		// client already handles for every video that has no poster yet.
		return null;
	}

	/** {@code rawurlencode($filename)} -- the segment, not the slashes. */
	private String publicUrl(String subdirectory, String filename) {
		return this.uploadUrl + subdirectory + "/" + rawUrlEncode(filename);
	}

	/**
	 * PHP's {@code rawurlencode}: percent-encoding with {@code %20} for space
	 * and {@code ~} left alone, where Java's URLEncoder emits {@code +} and
	 * escapes the tilde.
	 */
	private static String rawUrlEncode(String value) {
		try {
			return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
					.replace("+", "%20")
					.replace("%7E", "~");
		} catch (UnsupportedEncodingException ex) {
			throw new IllegalStateException("UTF-8 is always available", ex);
		}
	}

	/** {@code basename(str_replace('\\', '/', trim($name)))}. */
	private static String basename(String value) {
		if (value == null) {
			return "";
		}
		String normalised = value.trim().replace('\\', '/');
		int slash = normalised.lastIndexOf('/');
		return slash < 0 ? normalised : normalised.substring(slash + 1);
	}

	static boolean isEnglish(String locale) {
		return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("en");
	}

}
