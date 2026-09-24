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
		// Across lines, and counted: a cell wrapped so the pattern cannot read it fails rather than
		// drops out of the sweep.
		Pattern cell = Pattern.compile("(?s)<td\\b([^>]*)>\\s*\\$\\{ListDisplay\\.notes\\(([^,]+),");
		int calls = 0;
		int read = 0;
		for (Path template : templates()) {
			String name = template.getFileName().toString();
			String source = Files.readString(template, StandardCharsets.UTF_8);
			calls += (int) Pattern.compile("ListDisplay\\.notes\\(").matcher(source).results().count();
			Matcher cells = cell.matcher(source);
			while (cells.find()) {
				read++;
				String accessor = cells.group(2).trim();
				if (!cells.group(1).contains("title=") || !cells.group(1).contains(accessor)) {
					offenders.add(name + ": " + cells.group().replaceAll("\\s+", " "));
				}
			}
		}
		assertThat(read).as("every ListDisplay.notes() call is a cell this sweep read").isEqualTo(calls);
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
	private static final Pattern BADGE = Pattern.compile("(?<![\\w-])badge(?![\\w-])");

	private static final Pattern BADGE_COLOUR = Pattern.compile("(?<![\\w-])badge-[\\w-]+");

	/** The badge class in any spelling an expression can hold: {@code "badge "}, {@code "badge-" + colour}. */
	private static final Pattern ANY_BADGE = Pattern.compile("(?<![\\w-])badge(?!\\w)");

	/** Where a class stops being text: an expression, or a JTE directive that picks between texts. */
	private static final Pattern COMPUTED = Pattern.compile("\\$(?:unsafe)?\\{|@(?:if|elseif|else|for)\\b");

	private static final Pattern DIRECTIVE = Pattern.compile("@(?:if|elseif|for)\\s*\\(");

	private static final Pattern SPAN = Pattern.compile("<span\\b");

	private static final Pattern CLASS_ATTRIBUTE = Pattern.compile("(?<![\\w-])class=\"");

	/**
	 * The label keys statusBadge.jte gives a status; a page that prints one in a badge is drawing a status.
	 * A bare prefix counts: {@code "status_" + row.status()} is how statusBadge.jte itself builds the key.
	 */
	private static final Pattern STATUS_LABEL = Pattern.compile("\"((status|gender|method|role)_[^\"]*|yes|no)\"");

	/**
	 * Each span's tag and class are read to the {@code >} and {@code "} that close them outside any
	 * expression, because an expression may hold both: {@code class="${ok ? "badge badge-green" : …}"}.
	 * A class held in a variable and printed as {@code class="${cls}"} names no badge here and is not
	 * caught.
	 */
	@Test
	void everyStatusBadgeComesFromTheSharedPartial() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path template : templates()) {
			String name = template.getFileName().toString();
			if (name.equals("statusBadge.jte")) {
				continue;
			}
			String source = Files.readString(template, StandardCharsets.UTF_8);
			Matcher span = SPAN.matcher(source);
			while (span.find()) {
				int tagEnd = outsideExpressions(source, span.start(), '>');
				String tag = source.substring(span.start(), tagEnd + 1);
				Matcher attribute = CLASS_ATTRIBUTE.matcher(tag);
				if (!attribute.find()) {
					continue;
				}
				String value = tag.substring(attribute.end(), outsideExpressions(tag, attribute.end(), '"'));
				if (COMPUTED.matcher(value).find()) {
					if (ANY_BADGE.matcher(value).find()) {
						offenders.add(name + ": " + tag.replaceAll("\\s+", " "));
					}
					continue;
				}
				// A badge with a fixed colour is a count, a time or a value; one labelled with a status is
				// a status badge that chose its own colour.
				if (BADGE.matcher(value).find() && BADGE_COLOUR.matcher(value).find()) {
					int close = source.indexOf("</span>", tagEnd);
					String label = source.substring(tagEnd + 1, close < 0 ? source.length() : close);
					if (STATUS_LABEL.matcher(label).find()) {
						offenders.add(name + ": " + (tag + label).replaceAll("\\s+", " "));
					}
				}
			}
		}
		assertThat(offenders)
				.as("a status badge picks its colour in statusBadge.jte, not in the page")
				.isEmpty();
	}

	/**
	 * The first {@code stop} at or after {@code from} outside any expression or directive condition,
	 * and, when looking for a tag's {@code >}, outside its quoted attribute values.
	 */
	static int outsideExpressions(String text, int from, char stop) {
		for (int i = from; i < text.length(); i++) {
			if (text.startsWith("${", i) || text.startsWith("$unsafe{", i)) {
				i = closing(text, text.indexOf('{', i), '{', '}');
			} else if (DIRECTIVE.matcher(text).region(i, text.length()).lookingAt()) {
				i = closing(text, text.indexOf('(', i), '(', ')');
			} else if (stop == '>' && text.charAt(i) == '"') {
				i = outsideExpressions(text, i + 1, '"');
			} else if (text.charAt(i) == stop) {
				return i;
			}
		}
		return text.length() - 1;
	}

	/** Where the bracket at {@code at} closes, past any string or character literal inside it. */
	private static int closing(String text, int at, char open, char close) {
		int depth = 0;
		for (int i = at; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"' || c == '\'') {
				for (i++; i < text.length() && text.charAt(i) != c; i++) {
					if (text.charAt(i) == '\\') {
						i++;
					}
				}
			} else if (c == open) {
				depth++;
			} else if (c == close && --depth == 0) {
				return i;
			}
		}
		return text.length() - 1;
	}

	/** Legacy closes the table card and only then draws the pager ({@code requests/page.php:137-140}). */
	/**
	 * The accessors whose value is one indivisible token.
	 *
	 * <p>A closed, declared set rather than a pattern over the rendered text: what this rule is
	 * about is the shape of the value a cell holds, and the only place that is knowable from the
	 * template is the call that produces it. Matching a date-looking string instead would read
	 * {@code "2026-09-04"} out of a notes column and miss a date a formatter spells differently.
	 */
	private static final List<String> ATOMIC = List.of(
			"createdDate()", "penaltyDate()", "requestDate()", "assetDate()", "assetEndDate()",
			"createdAtDisplay()", "employeeCode()", "phoneLabel()", "hireDateLabel()",
			"periodFrom()", "periodTo()", "workTenure(", "EmployeeDisplay.date(",
			"AttendanceDisplay.date(", "HomeDisplay.dateTime(", "createdAt().toString()",
			"lastAccessedAt().toString()");

	/**
	 * The cells that hold an atomic value and still wrap, with the reason.
	 *
	 * <p>Keyed by template and accessor rather than by line, because a line number is not a fact
	 * about the cell and goes stale on the next edit above it.
	 */
	private static final Map<String, String> WRAPS_ON_PURPOSE = Map.of(
			"employees.jte:employeeCode()",
			"the second line of the name cell, whose first line is a name that must be free to wrap");

	/**
	 * A cell holding one indivisible value says so.
	 *
	 * <p>A date breaks at its hyphen and a phone number at its space, so a column squeezed by the
	 * fourteen the employees list carries renders {@code 2026-09-} above {@code 04}. Legacy's
	 * {@code .tbl th} has carried {@code nowrap} since the copy and {@code .tbl td} never has, so
	 * every one of these wrapped, on every list, for as long as the port has existed -- and a
	 * page's own test cannot see it, because the text is all there.
	 *
	 * <p>Not every cell: an address and a branch's meta line are meant to wrap, and forcing the
	 * whole table would trade one defect for a wider one. The count is pinned so a template that
	 * stops calling an accessor cannot quietly empty the rule.
	 */
	@Test
	void everyCellHoldingOneIndivisibleValueSaysSo() throws IOException {
		Pattern cell = Pattern.compile("<td\\b([^>]*)>(.*?)</td>", Pattern.DOTALL);
		List<String> wrapping = new ArrayList<>();
		int examined = 0;
		try (var templates = Files.list(TEMPLATES)) {
			for (Path template : templates.filter(path -> path.toString().endsWith(".jte")).sorted().toList()) {
				String source = Files.readString(template, StandardCharsets.UTF_8);
				Matcher match = cell.matcher(source);
				while (match.find()) {
					String attributes = match.group(1);
					String body = match.group(2);
					if (attributes.contains("col-actions")) {
						continue;
					}
					String accessor = ATOMIC.stream().filter(body::contains).findFirst().orElse(null);
					if (accessor == null) {
						continue;
					}
					String key = template.getFileName() + ":" + accessor;
					if (WRAPS_ON_PURPOSE.containsKey(key)) {
						continue;
					}
					examined++;
					if (!attributes.contains("nowrap")) {
						wrapping.add(key + " -- " + body.replaceAll("\\s+", " ").trim());
					}
				}
			}
		}
		assertThat(wrapping)
				.as("a cell whose value cannot be broken in half must carry .nowrap")
				.isEmpty();
		assertThat(examined)
				.as("cells holding an atomic value; pinned so the rule cannot measure nothing, "
						+ "and so adding a list page is a decision about its columns")
				.isEqualTo(32);
	}

	/**
	 * Every icon a card asks for is one the registry draws.
	 *
	 * <p>{@code AdminIcons.of} answers an empty string for a name it does not know, which is the
	 * right call for a sidebar that must not go down over a glyph -- and it means a typo in a
	 * template shows a coloured tile with nothing in it, on the first page an administrator sees,
	 * with nothing in a log. The home grid's nineteen cards name their glyph now rather than
	 * carrying an emoji, so the silent fallback became reachable the moment they did.
	 */
	@Test
	void everyStatCardNamesAnIconTheRegistryDefines() throws IOException {
		Pattern named = Pattern.compile("icon\\s*=\\s*\"([^\"]*)\"");
		List<String> missing = new ArrayList<>();
		int asked = 0;
		try (var templates = Files.list(TEMPLATES)) {
			for (Path template : templates.filter(path -> path.toString().endsWith(".jte")).sorted().toList()) {
				Matcher match = named.matcher(Files.readString(template, StandardCharsets.UTF_8));
				while (match.find()) {
					asked++;
					if (AdminIcons.of(match.group(1)).isEmpty()) {
						missing.add(template.getFileName() + ": " + match.group(1));
					}
				}
			}
		}
		assertThat(missing).as("an icon name the registry cannot draw renders an empty tile").isEmpty();
		assertThat(Files.readString(TEMPLATES.resolve("statCard.jte"), StandardCharsets.UTF_8))
				.as("the card's glyph is not the sidebar's: `nav-icon` carries the copied sidebar's "
						+ "sizing and leaves the tile's own rule matching nothing, so the SVG falls "
						+ "back to its 18px attributes and to inline alignment inside a flex tile")
				.contains("AdminIcons.of(icon, \"stat-icon\")");
		assertThat(asked)
				.as("icon arguments across the admin templates; pinned so the rule cannot pass "
						+ "by finding none")
				.isEqualTo(19);
	}

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

	/**
	 * The status each page hands the partial, from legacy's own call at the same place. Five org
	 * pages say {@code 'suspended'} where the rest say {@code 'inactive'}, and the employee detail
	 * page says {@code '1'/'0'} -- different words, same grey -- so a partial that maps correctly
	 * can still render the wrong label if a page passes the wrong status.
	 *
	 * <p>Empty means the page passes the row's own status, as legacy's {@code badge($row['status'])}
	 * does -- except complaints, where legacy draws no badge at all: it always renders an inline
	 * status select ({@code complaints/page.php:195-205}), and the port's badge is its read-only
	 * stand-in for a viewer who cannot write.
	 *
	 * <p>This checks the badges the port draws. It cannot see one legacy draws and the port does not;
	 * that is each page's own end-to-end test.
	 */
	private static final Map<String, List<String>> LEGACY_STATUS = Map.ofEntries(
			Map.entry("administrative-decisions.jte", List.of("active", "inactive")),
			Map.entry("advances.jte", List.of()),
			Map.entry("assets.jte", List.of("1", "0")),
			Map.entry("banners.jte", List.of("active", "inactive")),
			Map.entry("branches.jte", List.of("active", "suspended")),
			Map.entry("companies.jte", List.of()),
			// The OTP flag and each employee's active flag (companies/detail.php:47, :80); the company's
			// status and each HR user's role pass the row's own (:43, :73).
			Map.entry("company-detail.jte", List.of("1", "0", "1", "0")),
			Map.entry("complaints.jte", List.of()),
			Map.entry("departments.jte", List.of("active", "suspended")),
			// No legacy page: terminals and agents are on or off, and "inactive" says so.
			Map.entry("devices.jte", List.of("active", "inactive", "active", "inactive", "active", "inactive")),
			// The header's active flag and each penalty's applied-to-payroll flag (detail.php:61, :102); the
			// request and advance tables pass the row's status (:95, :109).
			Map.entry("employee-detail.jte", List.of("1", "0", "1", "0")),
			Map.entry("employees.jte", List.of("active", "suspended")),
			Map.entry("faqs.jte", List.of("active", "inactive", "active", "inactive")),
			Map.entry("guide-videos.jte", List.of("active", "inactive")),
			Map.entry("job-titles.jte", List.of("active", "suspended")),
			Map.entry("join-requests.jte", List.of("approved", "approved", "rejected", "rejected", "pending")),
			Map.entry("notifications.jte", List.of("1", "0")),
			Map.entry("payroll.jte", List.of()),
			Map.entry("penalties.jte", List.of("1", "0")),
			Map.entry("phone-countries.jte", List.of("active", "inactive")),
			Map.entry("requests.jte", List.of()),
			Map.entry("shifts.jte", List.of("active", "suspended")));

	@Test
	void everyBadgePassesTheStatusLegacyPassesAtThatPlace() throws IOException {
		Pattern call = Pattern.compile("(?s)@template\\.admin\\.statusBadge\\(status = (.+?), t = t\\)");
		Pattern literal = Pattern.compile("\"([^\"]*)\"");
		Map<String, List<String>> passed = new java.util.TreeMap<>();
		for (Path template : templates()) {
			String name = template.getFileName().toString();
			Matcher calls = call.matcher(Files.readString(template, StandardCharsets.UTF_8));
			while (calls.find()) {
				Matcher literals = literal.matcher(calls.group(1));
				List<String> statuses = new ArrayList<>(passed.getOrDefault(name, List.of()));
				while (literals.find()) {
					statuses.add(literals.group(1));
				}
				passed.put(name, statuses);
			}
		}
		assertThat(passed)
				.as("the partial maps a status to a colour and a label; passing the wrong status "
						+ "renders the wrong word in the right colour, which no other check sees")
				.containsExactlyInAnyOrderEntriesOf(LEGACY_STATUS);
	}

	/**
	 * The statuses above are read in source order, which cannot tell {@code row.active() ? "active"
	 * : "suspended"} from its negation. Every two-way badge here asks whether the row is on, so its
	 * condition is not negated and the "on" status comes first.
	 */
	@Test
	void everyTwoWayBadgeShowsItsOnStatusWhenItsConditionHolds() throws IOException {
		Pattern call = Pattern.compile("(?s)@template\\.admin\\.statusBadge\\(status = (.+?), t = t\\)");
		// One condition and two literals; join-requests' three-way chain is not one.
		Pattern twoWay = Pattern.compile("(?s)^([^?]+?)\\s*\\?\\s*\"([^\"]*)\"\\s*:\\s*\"([^\"]*)\"$");
		List<String> inverted = new ArrayList<>();
		int twoWays = 0;
		for (Path template : templates()) {
			Matcher calls = call.matcher(Files.readString(template, StandardCharsets.UTF_8));
			while (calls.find()) {
				Matcher ternary = twoWay.matcher(calls.group(1).trim());
				if (!ternary.matches()) {
					continue;
				}
				twoWays++;
				String condition = ternary.group(1).trim();
				if (condition.startsWith("!") || !List.of("active", "1").contains(ternary.group(2))
						|| List.of("active", "1").contains(ternary.group(3))) {
					inverted.add(template.getFileName() + ": " + calls.group(1).replaceAll("\\s+", " "));
				}
			}
		}
		assertThat(twoWays).as("the sweep found the two-way badges").isGreaterThanOrEqualTo(19);
		assertThat(inverted).isEmpty();
	}

	/**
	 * The partial builds its keys ({@code "status_" + status} and the rest), so the template
	 * message-key gate, which reads literal {@code t.apply("...")} calls, no longer covers any badge
	 * label. A missing key renders as its own name, in production, with every test green.
	 */
	@Test
	void everyLabelTheStatusBadgeCanAskForResolves() throws IOException {
		java.util.Set<String> known = new java.util.TreeSet<>();
		for (String bundle : List.of("admin-messages.properties", "admin-own.properties")) {
			Path path = Path.of("src/main/resources/i18n", bundle);
			for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
				String trimmed = line.trim();
				int equals = trimmed.indexOf('=');
				if (!trimmed.isEmpty() && !trimmed.startsWith("#") && equals > 0) {
					known.add(trimmed.substring(0, equals).trim());
				}
			}
		}
		List<String> statuses = List.of("active", "pending", "rejected", "suspended", "approved", "draft",
				"finalized", "open", "in_review", "closed", "done", "paid", "inactive");
		List<String> missing = new ArrayList<>();
		for (String status : statuses) {
			if (!known.contains("status_" + status)) {
				missing.add("status_" + status);
			}
		}
		for (String key : List.of("yes", "no", "gender_male", "gender_female", "method_app", "method_excel",
				"method_qr", "role_admin", "role_hr", "role_manager", "role_employee")) {
			if (!known.contains(key)) {
				missing.add(key);
			}
		}
		assertThat(missing).as("a label the badge can ask for but no catalogue defines").isEmpty();
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
