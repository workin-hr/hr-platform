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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The two table conventions legacy applies through its own helpers, which the port had to repeat
 * in every template and did not (<b>#214 item 6</b>, D-255).
 *
 * <p>Both failures are invisible to a page's own test: the header still says "actions" and the
 * cell still shows its text, only wider or narrower than legacy's. So these read the templates.
 */
class AdminTableConventionsTest {

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	/**
	 * What each clipped column cuts to, from legacy's own calls: {@code requests/page.php:128-129}
	 * and {@code assets/page.php:165} take 60, {@code administrative_decisions/page.php:177} 60,
	 * {@code complaints/page.php:186} 50 and {@code :187} 40, {@code notifications/page.php:207} 50,
	 * {@code penalties/page.php:182} 40, and {@code advances/page.php:212-213} 40.
	 */
	private static final Map<String, Integer> CLIPPED = Map.ofEntries(
			Map.entry("administrative-decisions.jte:row.body()", 60),
			Map.entry("advances.jte:row.reason()", 40),
			Map.entry("advances.jte:row.rejectionReason()", 40),
			Map.entry("assets.jte:row.assetText()", 60),
			Map.entry("complaints.jte:row.message()", 50),
			Map.entry("complaints.jte:row.reply()", 40),
			Map.entry("notifications.jte:row.body()", 50),
			Map.entry("penalties.jte:row.reason()", 40),
			Map.entry("requests.jte:row.notes()", 60),
			Map.entry("requests.jte:row.reply()", 60));

	/** The one place the port clips with CSS instead: legacy's FAQ list has no clipped column. */
	private static final Set<String> CSS_CLIPPED = Set.of("faqs.jte");

	@Test
	void everyActionsHeaderComesFromTheSharedPartial() throws IOException {
		List<String> own = new ArrayList<>();
		for (Path template : templates()) {
			String name = template.getFileName().toString();
			if (name.equals("actionsHeader.jte")) {
				continue;
			}
			String body = Files.readString(template, StandardCharsets.UTF_8);
			Matcher headers = Pattern.compile("(?s)<th\\b[^>]*>.*?</th>").matcher(body);
			while (headers.find()) {
				if (headers.group().contains("t.apply(\"actions\")")) {
					own.add(name + ": " + headers.group().replaceAll("\\s+", " "));
				}
			}
		}
		assertThat(own)
				.as("legacy's tableStart() gives the actions column a title and a screen-reader-only "
						+ "label; a template writing its own header loses both")
				.isEmpty();
	}

	@Test
	void everyLongTextColumnCutsWhereLegacyCutsIt() throws IOException {
		// The argument ends in its own `()`, so the group must stop at the comma, not at a bracket.
		Pattern call = Pattern.compile("ListDisplay\\.notes\\(([^,]+),\\s*(\\d+)\\)");
		Map<String, Integer> found = new java.util.TreeMap<>();
		for (Path template : templates()) {
			String name = template.getFileName().toString();
			Matcher calls = call.matcher(Files.readString(template, StandardCharsets.UTF_8));
			while (calls.find()) {
				found.put(name + ":" + calls.group(1).trim(), Integer.valueOf(calls.group(2)));
			}
		}
		assertThat(found)
				.as("each column cuts to the length legacy's own call gives it, and no column "
						+ "clips that legacy leaves whole")
				.containsExactlyInAnyOrderEntriesOf(CLIPPED);
	}

	@Test
	void noColumnIsLeftClippingWithCssWhereLegacyCutsTheText() throws IOException {
		List<String> css = new ArrayList<>();
		for (Path template : templates()) {
			String name = template.getFileName().toString();
			if (CSS_CLIPPED.contains(name)) {
				continue;
			}
			if (Files.readString(template, StandardCharsets.UTF_8).contains("tbl-clip")) {
				css.add(name);
			}
		}
		assertThat(css)
				.as("CSS cuts at a width, legacy cuts at a character count; a column doing both "
						+ "disagrees with legacy about where the text ends")
				.isEmpty();
	}

	private static List<Path> templates() throws IOException {
		try (var paths = Files.list(TEMPLATES)) {
			return paths.filter(path -> path.toString().endsWith(".jte")).sorted().toList();
		}
	}

}
