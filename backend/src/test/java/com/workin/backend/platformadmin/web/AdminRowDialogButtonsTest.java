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
		int[] labels = {0};
		for (Path template : templates()) {
			Matcher call = CALL.matcher(Files.readString(template, StandardCharsets.UTF_8));
			while (call.find()) {
				String fields = JTE_COMMENT.matcher(call.group(2)).replaceAll("");
				String where = template.getFileName() + " " + call.group(1).replaceAll("\\s+", " ").trim();
				checkFormRows(where, fields, offenders, labels);
			}
		}
		assertThat(labels[0]).as("the sweep found the row windows' labels").isGreaterThanOrEqualTo(30);
		assertThat(offenders).isEmpty();
	}

	/**
	 * Each control is its own labelled cell. A label belongs to the innermost element that is a
	 * cell around it, and two labels in one of those is the offence: two directly in a
	 * {@code .form-row}, two in one classed element inside a {@code .form-row}, or two loose in a
	 * window with no {@code .form-row} around them at all. Legacy does lay several controls across
	 * one row -- the employees window's country, phone and password sit in one {@code .form-row} as
	 * a nested grid ({@code _employee_form.php:90-114}) -- and each of those still has its own
	 * labelled cell, which is what this reads. A classed element counts as a cell only inside a
	 * {@code .form-row}: #297's review found the first form of this rule made every classed element
	 * a cell anywhere in a window, which let two loose labels through.
	 *
	 * <p>The same rule for every window a page writes itself, its add modals included: legacy's add
	 * windows write one field to a row too ({@code faqs/page.php:153-217},
	 * {@code phone_countries/page.php:95-138}, {@code guide_videos/page.php:91-123},
	 * {@code banners/page.php:111-232}, {@code notifications/page.php:233-296}), and a page's add
	 * and edit windows should not look different. It reads every {@code .modal-bg} window, with an id
	 * or without, to its closing tag, whether its form sits inside it or around it, and counts every
	 * {@code modal-bg} the templates write, so a window whose tag it cannot read fails rather than
	 * drops out.
	 */
	@Test
	void eachFieldInAPagesOwnWindowIsItsOwnFormRowWithALabelForItsControl() throws IOException {
		List<String> offenders = new ArrayList<>();
		List<String> windows = new ArrayList<>();
		int[] labels = {0};
		int written = 0;
		for (Path template : templates()) {
			String source = JTE_COMMENT.matcher(Files.readString(template, StandardCharsets.UTF_8)).replaceAll("");
			written += (int) Pattern.compile("\\bmodal-bg\\b").matcher(source).results().count();
			for (int at = source.indexOf(WINDOW); at >= 0; at = source.indexOf(WINDOW, at + 1)) {
				// A window's opening tag holds JTE expressions but no markup, so it ends before the next '<'.
				String tag = source.substring(at, source.indexOf('<', at + 1));
				Matcher id = WINDOW_ID.matcher(tag);
				String where = template.getFileName() + " "
						+ (id.find() ? "#" + id.group(1) : "line " + source.substring(0, at).lines().count());
				windows.add(where);
				// The window's own markup, to its closing tag: a form wrapped around the window, or none
				// at all, still has its fields read.
				checkFormRows(where, source.substring(at, windowEnd(source, at, where)), offenders, labels);
			}
		}
		assertThat(windows).as("every window the templates write, each read by the sweep").hasSize(written);
		assertThat(windows).as("the pages' own windows").contains(
				"banners.jte #bannerModal", "faqs.jte #faqCatModal", "faqs.jte #faqItemModal",
				"guide-videos.jte #gvModal", "notifications.jte #notifModal", "phone-countries.jte #pcModal",
				"companies.jte #companyModal", "settings-templates.jte #settingDefinitionModal",
				"settings-templates.jte #settingOptionModal");
		assertThat(labels[0]).as("the sweep found their labels").isGreaterThanOrEqualTo(90);
		assertThat(offenders).isEmpty();
	}

	/** Where a window's opening tag starts its class; a tag written any other way fails the count above. */
	private static final String WINDOW = "<div class=\"modal-bg";

	private static final Pattern WINDOW_ID = Pattern.compile("\\bid=\"([\\w-]+)\"");

	private static final Pattern DIV = Pattern.compile("<div\\b|</div>");

	private static int windowEnd(String source, int start, String where) {
		Matcher div = DIV.matcher(source).region(start, source.length());
		int depth = 0;
		while (div.find()) {
			depth += div.group().equals("</div>") ? -1 : 1;
			if (depth == 0) {
				return div.end();
			}
		}
		throw new AssertionError(where + " never closes");
	}

	/**
	 * Fields legacy writes as a {@code <textarea>} ({@code faqs/page.php:200-203} and {@code :233-236},
	 * {@code assets/page.php:207}, {@code advances/page.php:261}, {@code penalties/page.php:242}), so
	 * both the port's add window and its row window write them as one. As single-line inputs they cut
	 * a long question or reason to one scrolling line.
	 */
	@Test
	void theFieldsLegacyWritesAsTextareasAreTextareasInEveryWindow() throws IOException {
		Map<String, List<String>> fields = Map.of(
				"faqs.jte", List.of("questionAr", "questionEn", "answerAr", "answerEn"),
				"assets.jte", List.of("asset_text"),
				"advances.jte", List.of("reason"),
				"penalties.jte", List.of("reason"));
		List<String> found = new ArrayList<>();
		List<String> inputs = new ArrayList<>();
		for (Map.Entry<String, List<String>> page : fields.entrySet()) {
			String source = Files.readString(TEMPLATES.resolve(page.getKey()), StandardCharsets.UTF_8);
			for (String name : page.getValue()) {
				Matcher control = Pattern.compile("<(input|select|textarea)\\b[^>]*\\bname=\"" + name + "\"").matcher(source);
				while (control.find()) {
					found.add(page.getKey() + " " + name);
					if (!control.group(1).equals("textarea")) {
						inputs.add(page.getKey() + ": " + control.group());
					}
				}
			}
		}
		assertThat(found).as("each field in its add window and its row window").hasSize(14);
		assertThat(inputs).isEmpty();
	}

	/**
	 * A tag's class attribute as the browser will see it -- its literal text, with every JTE
	 * expression taken out -- or empty when it has none.
	 *
	 * <p>Three shapes were being read as a class that is not one. {@code data-dialog-class="x"} ends
	 * in {@code class="x"} on a word boundary, so a tag carrying only that attribute counted as a
	 * cell and released the labels inside it; the attribute has to start where an attribute starts.
	 * {@code class="${row.cssClass()}"} is text in the template and can render to nothing, so an
	 * element that is a cell here and no element at all in the browser would do the same. And an
	 * expression carries its own quotes ({@code class="${t.apply("x")}"}), which a
	 * {@code class="([^"]*)"} pattern cuts in the middle, leaving half an expression that reads as a
	 * class. So the value is scanned rather than matched: quotes and braces inside {@code ${...}}
	 * belong to the expression, and the attribute ends at the first quote outside one.
	 *
	 * <p>A tag whose class attribute never closes -- which is what a {@code >} inside an expression
	 * looks like, because the caller's tag pattern stops there -- fails rather than passing as
	 * unclassed.
	 */
	private static String classesOf(String tag) {
		Matcher attribute = CLASS_ATTRIBUTE.matcher(tag);
		if (!attribute.find()) {
			return "";
		}
		StringBuilder literal = new StringBuilder();
		int depth = 0;
		for (int at = attribute.end(); at < tag.length(); at++) {
			char character = tag.charAt(at);
			if (character == '$' && at + 1 < tag.length() && tag.charAt(at + 1) == '{') {
				depth++;
				at++;
			}
			else if (depth > 0) {
				depth += character == '{' ? 1 : character == '}' ? -1 : 0;
			}
			else if (character == '"') {
				return literal.toString().replaceAll("\\s+", " ").trim();
			}
			else {
				literal.append(character);
			}
		}
		throw new AssertionError("a class attribute that never closes: " + tag);
	}

	private static final Pattern CLASS_ATTRIBUTE = Pattern.compile("(?s)\\sclass=\"");

	private static void checkFormRows(String where, String fields, List<String> offenders, int[] labels) {
		Deque<Integer> rows = new ArrayDeque<>();
		Deque<Boolean> formRows = new ArrayDeque<>();
		Map<Integer, Integer> labelsPerRow = new HashMap<>();
		Matcher tag = Pattern.compile("<div\\b[^>]*>|</div>|<label\\b").matcher(fields);
		while (tag.find()) {
			String token = tag.group();
			if (token.startsWith("<div")) {
				// A label belongs to the innermost classed element around it -- its cell -- and to the
				// row only when it has no cell of its own. Legacy lays three controls across one row
				// in `_employee_form.php:90-114`, each in its own cell of a nested grid, so counting
				// per row alone would read that as three fields sharing a row.
				//
				// A classed div is a cell only INSIDE a `.form-row`. Outside one, labels still fall
				// together into the "no row" bucket and two of them are still an offence: #297's
				// review round 2 found the first form of this rule made every classed div a cell
				// anywhere in a window, which let that bucket through. Round 3 found the reading of
				// the attribute itself too loose, in both directions: see `classesOf`.
				String classes = classesOf(token);
				boolean row = (" " + classes + " ").contains(" form-row ");
				boolean cell = !row && formRows.contains(Boolean.TRUE) && !classes.isEmpty();
				rows.push(row || cell ? tag.start() : -1);
				formRows.push(row);
			}
			else if (token.equals("</div>")) {
				rows.pop();
				formRows.pop();
			}
			else {
				int row = rows.stream().filter(start -> start >= 0).findFirst().orElse(-1);
				labelsPerRow.merge(row, 1, Integer::sum);
			}
		}
		labelsPerRow.forEach((row, count) -> {
			if (count > 1) {
				offenders.add(where + ": " + count + " labels in one row or cell");
			}
		});
		Matcher label = LABEL.matcher(fields);
		while (label.find()) {
			labels[0]++;
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
