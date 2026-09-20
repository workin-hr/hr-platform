package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The colour of a row menu's actions, read from the templates themselves.
 *
 * <p>Legacy gives each row action a variant in {@code hr_list_helper.php}:
 * approve and the "done" actions are {@code success}, reject and delete are
 * {@code danger}. {@code hr_row_actions_menu} renders it as
 * {@code row-actions__item--<variant>}. An item written without the class
 * looks like a neutral action next to a red delete.
 */
class AdminRowActionVariantsTest {

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	/** The port's label key, and the variant legacy gives that action. */
	private static final Map<String, String> VARIANTS = Map.of(
			"approve", "success",
			"reject", "danger",
			"delete", "danger",
			"mark_paid", "success",
			// Legacy's menus label these two mark_applied and mark_returned.
			"applied_payroll", "success",
			"is_returned", "success");

	private static final Pattern LABEL = Pattern.compile("\\$\\{t\\.apply\\(\"([a-z_]+)\"\\)}</button>");

	private static final Pattern CLASS = Pattern.compile("class=\"([^\"]*)\"");

	@Test
	void everyRowMenuActionCarriesLegacysVariant() throws IOException {
		List<String> missing = new ArrayList<>();
		Set<String> found = new TreeSet<>();
		try (Stream<Path> files = Files.list(TEMPLATES)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".jte")).sorted().toList()) {
				String template = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
				Matcher label = LABEL.matcher(template);
				while (label.find()) {
					String variant = VARIANTS.get(label.group(1));
					// Buttons do not nest, so the nearest opening tag is this label's.
					int open = template.lastIndexOf("<button", label.start());
					if (variant == null || open < 0) {
						continue;
					}
					Matcher classAttribute = CLASS.matcher(template.substring(open, label.start()));
					List<String> classes = classAttribute.find()
							? List.of(classAttribute.group(1).trim().split("\\s+"))
							: List.of();
					if (!classes.contains("row-actions__item")) {
						continue;
					}
					found.add(label.group(1));
					if (!classes.contains("row-actions__item--" + variant)) {
						missing.add(file.getFileName() + ":" + lineOf(template, label.start())
								+ " " + label.group(1) + " needs row-actions__item--" + variant);
					}
				}
			}
		}

		assertThat(found).as("each mapped action appears in some row menu").containsAll(VARIANTS.keySet());
		assertThat(missing).as("row menu actions without legacy's variant").isEmpty();
	}

	/** The template with its comments blanked, keeping line numbers. */
	private static String withoutComments(String template) {
		return TemplateText.blankComments(template);
	}

	private static long lineOf(String text, int index) {
		return text.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
	}
}
