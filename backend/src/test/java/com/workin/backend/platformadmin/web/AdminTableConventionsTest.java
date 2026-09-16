package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The table conventions legacy applies through its own helpers, which the port had to repeat in
 * every template and did not (<b>#214 item 6</b>, D-255).
 *
 * <p>Every failure here is invisible to a page's own test: the header still says "actions", the
 * cell still shows its text, the badge still renders. Only the shape is wrong. So these read the
 * templates, and they fail closed -- a call this cannot parse is a failure, not a skip.
 */
class AdminTableConventionsTest {

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	/**
	 * What each column cut with {@code hr_request_notes_display()} cuts to, from legacy's own
	 * calls: {@code requests/page.php:128-129} and {@code assets/page.php:165} take 60,
	 * {@code administrative_decisions/page.php:177} 60, {@code complaints/page.php:186} 50 and
	 * {@code :187} 40, {@code notifications/page.php:207} 50, {@code penalties/page.php:182} 40,
	 * and {@code advances/page.php:212-213} 40.
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

	/**
	 * The FAQ list is legacy's one {@code mb_strimwidth()} table ({@code faqs/page.php:131-132}):
	 * a different function, with the ellipsis counted inside the width and no {@code title}.
	 */
	private static final Map<String, Integer> STRIMMED = Map.ofEntries(
			Map.entry("faqs.jte:item.questionAr()", 60),
			Map.entry("faqs.jte:item.questionEn()", 60));

	@Test
	void everyActionsHeaderComesFromTheSharedPartial() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path template : templates()) {
			String name = template.getFileName().toString();
			String body = Files.readString(template, StandardCharsets.UTF_8);
			if (name.equals("actionsHeader.jte")) {
				continue;
			}
			Matcher headers = Pattern.compile("(?s)<th\\b[^>]*>.*?</th>").matcher(body);
			while (headers.find()) {
				String header = headers.group();
				if (header.contains("t.apply(\"actions\")") || header.contains("col-actions")) {
					offenders.add(name + " writes its own actions header: " + header.replaceAll("\\s+", " "));
				}
			}
			if (body.contains("<td class=\"col-actions\"") && !body.contains("@template.admin.actionsHeader(")) {
				offenders.add(name + " has an actions column with no shared header");
			}
		}
		assertThat(offenders)
				.as("legacy's tableStart() gives the actions column its width, its title and a "
						+ "screen-reader-only label; a template writing its own header loses them")
				.isEmpty();
	}

	@Test
	void everyLongTextColumnCutsWhereLegacyCutsIt() throws IOException {
		Map<String, Integer> notes = new java.util.TreeMap<>();
		Map<String, Integer> strimmed = new java.util.TreeMap<>();
		List<String> unparsed = new ArrayList<>();
		// Any arity, so a one-argument call is a failure rather than something the sweep skips.
		Pattern call = Pattern.compile("ListDisplay\\.(notes|strimwidth)\\(([^;]*?)\\)\\}");
		Pattern twoArguments = Pattern.compile("^(.*),\\s*(\\d+)$");
		for (Path template : templates()) {
			String name = template.getFileName().toString();
			Matcher calls = call.matcher(Files.readString(template, StandardCharsets.UTF_8));
			while (calls.find()) {
				Matcher arguments = twoArguments.matcher(calls.group(2).trim());
				if (!arguments.matches()) {
					unparsed.add(name + ": " + calls.group());
					continue;
				}
				String key = name + ":" + arguments.group(1).trim();
				Integer length = Integer.valueOf(arguments.group(2));
				if (calls.group(1).equals("notes")) {
					notes.put(key, length);
				}
				else {
					strimmed.put(key, length);
				}
			}
		}
		assertThat(unparsed)
				.as("a clip this sweep cannot read is a clip it cannot check; write the length out")
				.isEmpty();
		assertThat(notes)
				.as("each column cuts to the length legacy's own call gives it, and no column "
						+ "clips that legacy leaves whole")
				.containsExactlyInAnyOrderEntriesOf(CLIPPED);
		assertThat(strimmed)
				.as("the FAQ list keeps legacy's mb_strimwidth cut")
				.containsExactlyInAnyOrderEntriesOf(STRIMMED);
	}

	/**
	 * Legacy's clipped cells carry the whole text in {@code title}, so nothing is lost. Cutting
	 * without it would truncate a note permanently, and every test here would stay green.
	 */
	@Test
	void everyClippedCellKeepsTheWholeTextInItsTitle() throws IOException {
		List<String> offenders = new ArrayList<>();
		Pattern cell = Pattern.compile("<td\\b[^>]*>\\$\\{ListDisplay\\.notes\\(([^,]+),");
		for (Path template : templates()) {
			String name = template.getFileName().toString();
			for (String line : Files.readString(template, StandardCharsets.UTF_8).split("\n")) {
				Matcher cells = cell.matcher(line);
				while (cells.find()) {
					String accessor = cells.group(1).trim();
					String openingTag = line.substring(cells.start(), line.indexOf('>', cells.start()));
					if (!openingTag.contains("title=") || !openingTag.contains(accessor)) {
						offenders.add(name + ": " + line.trim());
					}
				}
			}
		}
		assertThat(offenders)
				.as("a clipped cell without its own value in title() loses the rest of the text "
						+ "with no way to read it")
				.isEmpty();
	}

	@Test
	void noColumnClipsByWidthWhereLegacyCutsTheText() throws IOException {
		List<String> css = new ArrayList<>();
		for (Path template : templates()) {
			if (Files.readString(template, StandardCharsets.UTF_8).contains("tbl-clip")) {
				css.add(template.getFileName().toString());
			}
		}
		assertThat(css)
				.as("CSS cuts at a width, legacy cuts at a character count; a column doing both "
						+ "disagrees with legacy about where the text ends")
				.isEmpty();
	}

	/**
	 * legacy's {@code badge()} is one map of status to colour and label ({@code layout.php:77-107}).
	 * A page choosing its own colours is how pending came to be grey here and yellow there.
	 */
	@Test
	void everyStatusBadgeComesFromTheSharedPartial() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path template : templates()) {
			String name = template.getFileName().toString();
			if (name.equals("statusBadge.jte")) {
				continue;
			}
			for (String line : Files.readString(template, StandardCharsets.UTF_8).split("\n")) {
				if (line.contains("class=\"badge ${")) {
					offenders.add(name + ": " + line.trim());
				}
			}
		}
		assertThat(offenders)
				.as("a status badge picks its colour in statusBadge.jte, not in the page")
				.isEmpty();
	}

	/** Legacy closes the table card and only then draws the pager ({@code requests/page.php:137-140}). */
	@Test
	void noPagerSitsInsideItsTableCard() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path template : templates()) {
			String[] lines = Files.readString(template, StandardCharsets.UTF_8).split("\n");
			for (int pager = 0; pager < lines.length; pager++) {
				if (!lines[pager].contains("@template.admin.pager(")) {
					continue;
				}
				for (int open = 0; open < pager; open++) {
					if (!lines[open].contains("class=\"data-table-card\"")) {
						continue;
					}
					int depth = 0;
					for (int i = open; i < lines.length; i++) {
						depth += count(lines[i], "<div") - count(lines[i], "</div>");
						if (depth == 0 && i > open) {
							if (open < pager && pager < i) {
								offenders.add(template.getFileName() + ":" + (pager + 1));
							}
							break;
						}
					}
				}
			}
		}
		assertThat(offenders).as("the pager belongs after the card, as legacy renders it").isEmpty();
	}

	/**
	 * legacy's map, status by status ({@code includes/layout.php:79-107}). Read from the partial
	 * rather than rendered, so a changed arm fails here even if no page happens to show that status.
	 */
	@Test
	void theStatusBadgeCarriesLegacysColourAndLabelForEveryStatus() throws IOException {
		Map<String, String> colours = Map.ofEntries(
				Map.entry("active", "badge-green"), Map.entry("approved", "badge-green"),
				Map.entry("finalized", "badge-green"), Map.entry("done", "badge-green"),
				Map.entry("paid", "badge-green"), Map.entry("qr", "badge-green"),
				Map.entry("hr", "badge-green"), Map.entry("1", "badge-green"),
				Map.entry("pending", "badge-yellow"), Map.entry("draft", "badge-yellow"),
				Map.entry("open", "badge-yellow"), Map.entry("excel", "badge-yellow"),
				Map.entry("manager", "badge-yellow"), Map.entry("rejected", "badge-red"),
				Map.entry("in_review", "badge-blue"), Map.entry("male", "badge-blue"),
				Map.entry("app", "badge-blue"), Map.entry("company_admin", "badge-blue"),
				Map.entry("female", "badge-pink"));
		String partial = Files.readString(TEMPLATES.resolve("statusBadge.jte"), StandardCharsets.UTF_8);
		String colourBlock = partial.substring(partial.indexOf("String colour"), partial.indexOf("String key"));

		List<String> wrong = new ArrayList<>();
		for (Map.Entry<String, String> entry : colours.entrySet()) {
			Matcher arm = Pattern.compile("case ([^>]*)-> \"(badge-[a-z]+)\";").matcher(colourBlock);
			String found = null;
			while (arm.find()) {
				if (arm.group(1).contains("\"" + entry.getKey() + "\"")) {
					found = arm.group(2);
				}
			}
			if (!entry.getValue().equals(found)) {
				wrong.add(entry.getKey() + " is " + found + ", legacy has " + entry.getValue());
			}
		}
		assertThat(wrong).as("pending is yellow and inactive is grey, as legacy paints them").isEmpty();
		assertThat(colourBlock).as("anything legacy does not list falls back to grey").contains("default -> \"badge-gray\"");
		assertThat(partial).as("the labels legacy gives the pairs it renames")
				.contains("case \"1\" -> \"yes\"").contains("case \"0\" -> \"no\"")
				.contains("\"status_\" + status");
	}

	private static int count(String line, String token) {
		int total = 0;
		for (int at = line.indexOf(token); at >= 0; at = line.indexOf(token, at + 1)) {
			total++;
		}
		return total;
	}

	private static List<Path> templates() throws IOException {
		try (var paths = Files.list(TEMPLATES)) {
			return paths.filter(path -> path.toString().endsWith(".jte")).sorted().toList();
		}
	}

}
