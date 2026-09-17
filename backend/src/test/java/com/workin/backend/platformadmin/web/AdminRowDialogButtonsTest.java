package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
		String script = Files.readString(Path.of("src/main/resources/static/admin/_assets/row-dialog.js"), StandardCharsets.UTF_8);
		assertThat(footer)
				.as("the footer Cancel is a plain grey button carrying the hook row-dialog.js closes the window on")
				.contains("<button type=\"button\" class=\"btn btn-gray\" data-dialog-cancel>${cancelLabel}</button>");
		assertThat(script).contains("event.target.closest('[data-dialog-cancel]')");
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
	 * Legacy's own windows carry a Cancel only on the complaint reply ({@code complaints/page.php:240})
	 * and the company reject ({@code companies/page.php:288}), which company detail opens too.
	 */
	@Test
	void theWindowsLegacyGivesACancelPassOne() throws IOException {
		List<String> cancelling = new ArrayList<>();
		for (Path template : templates()) {
			Matcher call = CALL.matcher(Files.readString(template, StandardCharsets.UTF_8));
			while (call.find()) {
				Matcher id = Pattern.compile("id = \"([\\w-]+)\"").matcher(call.group(1));
				assertThat(id.find()).as("%s: a row window's id", template.getFileName()).isTrue();
				if (call.group(1).contains("cancelLabel = t.apply(\"cancel\")")) {
					cancelling.add(template.getFileName() + ":" + id.group(1));
				}
			}
		}
		assertThat(cancelling).containsExactly(
				"companies.jte:company-reject", "company-detail.jte:company-reject", "complaints.jte:complaint-reply");
	}

	private static final Pattern LABEL = Pattern.compile("<label\\b([^>]*)>(.*?)</label>", Pattern.DOTALL);

	/**
	 * Legacy writes one field to a {@code .form-row}: its label, then its control
	 * ({@code phone_countries/page.php:148-178}, {@code faqs/page.php:177-180}). Several labels
	 * wrapping their controls in one row render as narrow controls inline beside their text, spaced
	 * by nothing. A checkbox is the one control legacy's label wraps.
	 */
	@Test
	void eachFieldInARowWindowIsItsOwnFormRowWithALabelForItsControl() throws IOException {
		List<String> offenders = new ArrayList<>();
		int labels = 0;
		for (Path template : templates()) {
			Matcher call = CALL.matcher(Files.readString(template, StandardCharsets.UTF_8));
			while (call.find()) {
				String fields = JTE_COMMENT.matcher(call.group(2)).replaceAll("");
				String where = template.getFileName() + " " + call.group(1).replaceAll("\\s+", " ").trim();
				Deque<Integer> rows = new ArrayDeque<>();
				Map<Integer, Integer> labelsPerRow = new HashMap<>();
				Matcher tag = Pattern.compile("<div\\b[^>]*>|</div>|<label\\b").matcher(fields);
				while (tag.find()) {
					String token = tag.group();
					if (token.startsWith("<div")) {
						rows.push(token.matches("(?s).*class=\"([^\"]*\\s)?form-row(\\s[^\"]*)?\".*") ? tag.start() : -1);
					}
					else if (token.equals("</div>")) {
						rows.pop();
					}
					else {
						int row = rows.stream().filter(start -> start >= 0).findFirst().orElse(-1);
						labelsPerRow.merge(row, 1, Integer::sum);
					}
				}
				labelsPerRow.forEach((row, count) -> {
					if (count > 1) {
						offenders.add(where + ": " + count + " labels in one .form-row");
					}
				});
				Matcher label = LABEL.matcher(fields);
				while (label.find()) {
					labels++;
					String body = label.group(2);
					if (body.contains("type=\"checkbox\"")) {
						continue;
					}
					Matcher target = Pattern.compile("\\bfor=\"([\\w-]+)\"").matcher(label.group(1));
					if (!target.find()) {
						offenders.add(where + ": a label without for: " + body.trim());
					}
					else if (!fields.contains("id=\"" + target.group(1) + "\"")
							&& !fields.contains("inputId = \"" + target.group(1) + "\"")) {
						offenders.add(where + ": for=\"" + target.group(1) + "\" names no control in the window");
					}
					if (body.matches("(?s).*<(input|select|textarea)\\b.*")) {
						offenders.add(where + ": a label wrapping its control: " + body.trim());
					}
				}
			}
		}
		assertThat(labels).as("the sweep found the row windows' labels").isGreaterThanOrEqualTo(30);
		assertThat(offenders).isEmpty();
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
