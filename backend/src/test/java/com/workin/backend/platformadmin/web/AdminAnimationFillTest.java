package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * No admin animation holds its last frame (D-288).
 *
 * <p>{@code animation-fill-mode: both} (or {@code forwards}) keeps the final keyframe
 * applied after the animation ends. Every entrance animation here ends at a
 * {@code transform}, so the element kept a transform for as long as the page
 * lived -- and a transformed element is a stacking context and the containing
 * block of every {@code position: fixed} descendant. {@code .content} faded in
 * that way, so every dialog inside it was painted within {@code .content}; once
 * D-284 gave the topbar {@code z-index: 40}, the topbar sat above every open
 * dialog's backdrop and took its clicks. {@code backwards} applies the first
 * frame during the delay and nothing afterwards, which is identical on screen
 * because each last frame is the element's natural state.
 */
class AdminAnimationFillTest {

	private static final Path ASSETS = Path.of("src/main/resources/static/admin/_assets");

	private static final Pattern ANIMATION = Pattern.compile(
			"animation(?:-fill-mode)?\\s*:[^;{}]*\\b(both|forwards)\\b");

	@Test
	void noAnimationKeepsItsLastFrameAndWithItAStackingContext() throws IOException {
		List<String> offenders = new ArrayList<>();
		int sheets = 0;
		try (Stream<Path> files = Files.list(ASSETS)) {
			for (Path sheet : files.filter(path -> path.toString().endsWith(".css")).toList()) {
				sheets++;
				String css = Files.readString(sheet, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
				Matcher match = ANIMATION.matcher(css);
				while (match.find()) {
					offenders.add(sheet.getFileName() + ": " + match.group());
				}
			}
		}
		assertThat(sheets).as("the sheets were found").isGreaterThan(14);
		assertThat(offenders).as("use `backwards`: the last frame is the element's own state").isEmpty();
	}

}
