package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The row window's shape and buttons, read from {@code rowDialog.jte} itself.
 *
 * <p>The window is legacy's {@code .modal-bg > .modal}: the markup crud.js closes on its close
 * button and backdrop, and modal-a11y.js gives Escape, the Tab trap and focus. Enter in a field
 * submits a form through its first submit button, so the close button must not be one: while a
 * Cancel was a {@code formmethod="dialog"} submit ahead of Save, Enter in any row window closed it
 * and lost what had been typed. The browser spec checks the behaviour on a page shaped like this
 * template; this checks the template still has that shape, on every page that includes it.
 */
class AdminRowDialogButtonsTest {

	private static final Path ROW_DIALOG = Path.of("src/main/jte/admin/rowDialog.jte");

	private static final Pattern SUBMIT = Pattern.compile("type=\"submit\"");

	private static final Pattern JTE_COMMENT = Pattern.compile("<%--.*?--%>", Pattern.DOTALL);

	@Test
	void theWindowIsLegacysModalAndSaveIsItsOnlySubmitButton() throws IOException {
		// The markup only: the template's comments explain the old dialog, by name.
		String template = JTE_COMMENT.matcher(Files.readString(ROW_DIALOG, StandardCharsets.UTF_8)).replaceAll("");

		assertThat(template)
				.as("legacy's window, which crud.js and modal-a11y.js open, close and make keyboard-usable")
				.contains("<div class=\"modal-bg\" id=\"${id}\"")
				.contains("<div class=\"modal\" role=\"dialog\" aria-modal=\"true\"")
				.contains("<div class=\"form-footer\">")
				.doesNotContain("<dialog");
		assertThat(SUBMIT.matcher(template).results().count())
				.as("Save is the only submit button, so Enter in a field submits through it")
				.isEqualTo(1);
		assertThat(template)
				.as("the close button closes the window without submitting the form")
				.contains("<button type=\"button\" class=\"modal-close\"")
				.doesNotContain("formmethod=\"dialog\"");
		String footer = template.substring(template.indexOf("<div class=\"form-footer\">"));
		assertThat(footer)
				.as("no footer button takes the ×'s .modal-close, whose position would lay it over Save")
				.doesNotContain("modal-close");
	}

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	private static final Pattern CALL = Pattern.compile(
			"(?s)@template\\.admin\\.rowDialog\\((.*?)fields = @`(.*?)`\\)");

	private static final Pattern TAG = Pattern.compile("<div\\b[^>]*>|</div>|<label\\b");

	/**
	 * Legacy's windows write each field as {@code <div class="form-row"><label>…}, and
	 * {@code .form-row label} is the only rule that styles a label. A label outside a form-row
	 * renders inline, at the body's size, butting against the field below it.
	 */
	@Test
	void everyLabelInARowWindowSitsInAFormRow() throws IOException {
		List<String> outside = new ArrayList<>();
		int windows = 0;
		for (Path template : templates()) {
			Matcher call = CALL.matcher(Files.readString(template, StandardCharsets.UTF_8));
			while (call.find()) {
				windows++;
				Deque<Boolean> open = new ArrayDeque<>();
				Matcher tag = TAG.matcher(call.group(2));
				while (tag.find()) {
					String token = tag.group();
					if (token.startsWith("<div")) {
						open.push(token.matches("(?s).*class=\"([^\"]*\\s)?form-row(\\s[^\"]*)?\".*"));
					}
					else if (token.equals("</div>")) {
						assertThat(open).as("%s closes a div it did not open", template.getFileName()).isNotEmpty();
						open.pop();
					}
					else if (!open.contains(true)) {
						outside.add(template.getFileName() + ": " + call.group(1).replaceAll("\\s+", " ").trim());
					}
				}
			}
		}
		assertThat(windows).as("the sweep found the row windows").isGreaterThanOrEqualTo(14);
		assertThat(outside).as("row windows with a label outside a .form-row").isEmpty();
	}

	/**
	 * {@code btn-danger} rendered three reject buttons as plain grey text: no stylesheet defines it.
	 * The variant is built at runtime, so the page stylesheet gate cannot see it.
	 */
	@Test
	void everyRowWindowsSubmitButtonIsAButtonTheStylesheetsDefine() throws IOException {
		String sheets = Files.readString(Path.of("src/main/resources/static/admin/_assets/style.css"), StandardCharsets.UTF_8)
				+ Files.readString(Path.of("src/main/resources/static/admin/_assets/app-ui.css"), StandardCharsets.UTF_8);
		Matcher fallback = Pattern.compile("@param String submitVariant = \"(\\w+)\"")
				.matcher(Files.readString(ROW_DIALOG, StandardCharsets.UTF_8));
		assertThat(fallback.find()).as("rowDialog.jte declares a default variant").isTrue();
		List<String> undefined = new ArrayList<>();
		for (Path template : templates()) {
			Matcher call = CALL.matcher(Files.readString(template, StandardCharsets.UTF_8));
			while (call.find()) {
				Matcher variant = Pattern.compile("submitVariant = \"(\\w+)\"").matcher(call.group(1));
				String name = variant.find() ? variant.group(1) : fallback.group(1);
				if (!Pattern.compile("\\.btn-" + name + "\\b").matcher(sheets).find()) {
					undefined.add(template.getFileName() + ": btn-" + name);
				}
			}
		}
		assertThat(undefined).as("submit buttons whose class no stylesheet defines").isEmpty();
	}

	private static List<Path> templates() throws IOException {
		try (Stream<Path> paths = Files.list(TEMPLATES)) {
			return paths.filter(path -> path.toString().endsWith(".jte")).sorted().toList();
		}
	}
}
