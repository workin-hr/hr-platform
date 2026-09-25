package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * {@code pager.jte} skips a carried parameter that names its own page key, and
 * that is a no-op for every page but one.
 *
 * <p>The skip exists because the attendance page renders <em>two</em> pagers
 * over one filter map: each carries the other's current page and must not also
 * write its own, which the pager appends itself (legacy's {@code pagerHtml()}
 * unsets only its own key for the same reason). It was written for that one
 * page, and every other page rendering this template had to be unaffected --
 * true only because no other page put a page key in its carry map, which
 * nothing asserted.
 *
 * <p>The devices page has since made it the rule rather than the exception:
 * <b>five paginated lists behind one filter map</b>, one page key each, every
 * pager carrying the other four. They never all render at once -- the page shows
 * either the overview's four lists or a selected terminal's two -- but the map is
 * built once and holds all five keys, so each of the six pager calls is handed
 * its own key and must not also write it. So this is no longer "the one page that
 * does it" but the list of pages that do, which is what the allow-list below is
 * -- and the reason it is a list of file names rather than a count is that a page
 * joining them should be a visible edit here.
 *
 * <p>This is that assertion, and it is about the templates rather than about
 * one rendered page: a future filter literally named {@code page} would be
 * dropped from that page's own pager links silently, and the page's own tests
 * would go on passing unless they happened to assert a whole link.
 */
class AdminPagerCarryTest {

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	/**
	 * The pages whose carry names their pagers' own keys, on purpose, once per
	 * pager call: a list rather than a count, so a page joining them is a visible
	 * edit here and not a number going up.
	 *
	 * <p>{@code attendance.jte} twice, for its two tables.
	 * {@code devices.jte} six times: the four lists of the overview (devices,
	 * unclaimed serials, agents, punches) and the two of a selected terminal (its
	 * punches and its unreadable lines), which are six <em>calls</em> rendering at
	 * most four at once, because the page shows one half or the other.
	 */
	private static final List<String> CARRIES_ITS_OWN_PAGE_KEY = List.of(
			"attendance.jte", "attendance.jte",
			"devices.jte", "devices.jte", "devices.jte", "devices.jte", "devices.jte", "devices.jte");

	private static final Pattern PAGER_CALL = Pattern.compile("@template\\.admin\\.pager\\s*\\(");

	private static final Pattern PAGE_PARAM = Pattern.compile("pageParam\\s*=\\s*\"([^\"]+)\"");

	private static final Pattern CARRY_ARGUMENT = Pattern.compile("carry\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)");

	/** The whole carry argument, so a bare name can be told from a call on one. */
	private static final Pattern CARRY_EXPRESSION =
			Pattern.compile("carry\\s*=\\s*([A-Za-z_][A-Za-z0-9_.()]*)");

	@Test
	void onlyTheTwoPagerPageCarriesItsOwnPageKey() throws IOException {
		List<String> selfCarrying = new ArrayList<>();
		try (var paths = Files.list(TEMPLATES)) {
			for (Path path : paths.sorted().toList()) {
				if (!path.toString().endsWith(".jte")) {
					continue;
				}
				String source = TemplateText.withoutComments(
						Files.readString(path, StandardCharsets.UTF_8));
				for (String call : pagerCalls(source)) {
					Matcher pageParam = PAGE_PARAM.matcher(call);
					String key = pageParam.find() ? pageParam.group(1) : "page";
					if (carriedKeys(source, call).contains(key)) {
						selfCarrying.add(path.getFileName().toString());
					}
				}
			}
		}
		assertThat(selfCarrying).as("pages whose carry names their pager's own page key")
				.containsExactlyElementsOf(CARRIES_ITS_OWN_PAGE_KEY);
	}

	/**
	 * A pager's carry map is never a bare parameter of the template that renders
	 * the pager.
	 *
	 * <p>This rule reads one file at a time and finds a map's keys by its
	 * {@code put} calls. So a pager whose {@code carry} came in as a
	 * {@code @param} from a calling template has no visible keys here and is
	 * silently treated as carrying none -- it could self-carry and
	 * {@link #onlyTheTwoPagerPageCarriesItsOwnPageKey} would say nothing. The
	 * devices page's punch table was exactly that shape: the pager sat in
	 * {@code devicePunches.jte} while the map was built in {@code devices.jte}.
	 * The call moved to where the map is, and this keeps it there.
	 *
	 * <p>{@code carry = filters.asQueryParameters()} is not that shape and is not
	 * flagged: the map is built here, from a typed object whose contents are that
	 * record's business, and {@code asQueryParameters()} deliberately holds no
	 * page key at all.
	 */
	@Test
	void noPagerTakesItsCarryMapAsABareTemplateParameter() throws IOException {
		List<String> passedIn = new ArrayList<>();
		try (var paths = Files.list(TEMPLATES)) {
			for (Path path : paths.sorted().toList()) {
				if (!path.toString().endsWith(".jte")) {
					continue;
				}
				String source = TemplateText.withoutComments(
						Files.readString(path, StandardCharsets.UTF_8));
				for (String call : pagerCalls(source)) {
					Matcher argument = CARRY_EXPRESSION.matcher(call);
					if (!argument.find()) {
						continue;
					}
					String expression = argument.group(1);
					if (expression.contains(".")) {
						// Built here from a typed object, not handed in.
						continue;
					}
					boolean isParameter = Pattern.compile(
							"(?m)^@param\\b.*\\b" + Pattern.quote(expression) + "\\s*(?:=|$)")
							.matcher(source).find();
					if (isParameter) {
						passedIn.add(path.getFileName().toString() + " -> " + expression);
					}
				}
			}
		}
		assertThat(passedIn)
				.as("a carry map handed in by a caller has no visible keys, so the rule above "
						+ "cannot see whether that pager self-carries")
				.isEmpty();
	}

	/**
	 * Both of the attendance page's pagers carry the other's page, so the skip
	 * stays exercised: a change that stopped one of them carrying would make
	 * the rule above pass by doing less rather than by being satisfied.
	 */
	@Test
	void theAttendancePageCarriesBothOfItsPageKeys() throws IOException {
		String source = TemplateText.withoutComments(
				Files.readString(TEMPLATES.resolve("attendance.jte"), StandardCharsets.UTF_8));
		List<String> calls = pagerCalls(source);
		assertThat(calls).as("attendance renders two pagers").hasSize(2);

		List<String> keys = new ArrayList<>();
		for (String call : calls) {
			Matcher pageParam = PAGE_PARAM.matcher(call);
			keys.add(pageParam.find() ? pageParam.group(1) : "page");
		}
		assertThat(keys).as("one of each").containsExactlyInAnyOrder("page", "agg_page");
		assertThat(carriedKeys(source, calls.get(0)))
				.as("the filter map both pagers share holds both page keys")
				.contains("page", "agg_page");
	}

	/** Every {@code @template.admin.pager(...)} call in one template, brackets balanced. */
	private static List<String> pagerCalls(String source) {
		List<String> calls = new ArrayList<>();
		Matcher call = PAGER_CALL.matcher(source);
		while (call.find()) {
			int depth = 1;
			int at = call.end();
			while (at < source.length() && depth > 0) {
				char character = source.charAt(at);
				if (character == '(') {
					depth++;
				}
				else if (character == ')') {
					depth--;
				}
				at++;
			}
			calls.add(source.substring(call.end(), at));
		}
		return calls;
	}

	/** The keys put into the map this call passes as its {@code carry}. */
	private static Set<String> carriedKeys(String source, String call) {
		Matcher argument = CARRY_ARGUMENT.matcher(call);
		if (!argument.find()) {
			return Set.of();
		}
		Set<String> keys = new LinkedHashSet<>();
		Matcher put = Pattern.compile(Pattern.quote(argument.group(1)) + "\\.put\\(\"([^\"]+)\"")
				.matcher(source);
		while (put.find()) {
			keys.add(put.group(1));
		}
		return keys;
	}

}
