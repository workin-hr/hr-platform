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
 * Every "are you sure?" in the admin dashboard goes through ui-confirm.js and the
 * layout's window, not the browser's {@code confirm()} (D-288).
 *
 * <p>The thirty inline {@code onsubmit="return confirm('...')"} handlers asked in the
 * browser's language rather than the page's, spliced a translation into a JavaScript
 * string inside an HTML attribute -- an apostrophe broke it -- and are what a strict
 * Content-Security-Policy refuses. A template that brings one back fails here.
 */
class AdminConfirmWindowTest {

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	private static final Path ASSETS = Path.of("src/main/resources/static/admin/_assets");

	/** confirm( as code: not inside a JTE comment, where the history is explained. */
	private static final Pattern CONFIRM_CALL = Pattern.compile("(?<![\\w.])confirm\\s*\\(");

	private static final Pattern COMMENT = Pattern.compile("<%--.*?--%>", Pattern.DOTALL);

	@Test
	void noTemplateAsksThroughTheBrowsersConfirm() throws IOException {
		List<String> offenders = new ArrayList<>();
		int templates = 0;
		try (Stream<Path> files = Files.walk(TEMPLATES)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".jte")).toList()) {
				templates++;
				String code = COMMENT.matcher(Files.readString(file, StandardCharsets.UTF_8)).replaceAll("");
				Matcher call = CONFIRM_CALL.matcher(code);
				while (call.find()) {
					offenders.add(file.getFileName() + " at offset " + call.start());
				}
			}
		}
		assertThat(templates).as("the templates were found; a moved directory would check nothing")
				.isGreaterThan(40);
		assertThat(offenders).as("use data-confirm on the form instead").isEmpty();
	}

	@Test
	void noDashboardScriptAsksThroughTheBrowsersConfirmEither() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.list(ASSETS)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".js")).toList()) {
				String code = Files.readString(file, StandardCharsets.UTF_8)
						.replaceAll("(?m)^\\s*//.*$", "");
				if (Pattern.compile("(?<![\\w.])(window\\.)?confirm\\s*\\(").matcher(code).find()) {
					offenders.add(file.getFileName().toString());
				}
			}
		}
		assertThat(offenders).isEmpty();
	}

	@Test
	void everyDataConfirmFormIsAFormAndSaysSomething() throws IOException {
		Pattern attribute = Pattern.compile("<(\\w+)\\b[^>]*\\bdata-confirm=\"([^\"]*)\"");
		int seen = 0;
		try (Stream<Path> files = Files.walk(TEMPLATES)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".jte")).toList()) {
				Matcher match = attribute.matcher(Files.readString(file, StandardCharsets.UTF_8));
				while (match.find()) {
					seen++;
					assertThat(match.group(1)).as("%s: ui-confirm.js listens for a form's submit", file.getFileName())
							.isEqualTo("form");
					assertThat(match.group(2)).as("%s: the window's message", file.getFileName()).isNotBlank();
				}
			}
		}
		assertThat(seen).as("the forms that ask first").isGreaterThanOrEqualTo(30);
	}

}
