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
 * unsets only its own key for the same reason). Eighteen pages render this
 * template, so the change had to be a no-op for the other seventeen -- true
 * only because no other page puts a page key in its carry map, which nothing
 * asserted.
 *
 * <p>This is that assertion, and it is about the templates rather than about
 * one rendered page: a future filter literally named {@code page} would be
 * dropped from that page's own pager links silently, and the page's own tests
 * would go on passing unless they happened to assert a whole link.
 */
class AdminPagerCarryTest {

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	/**
	 * The one page whose carry names its pagers' own keys, on purpose, once per
	 * pager: a list rather than a count, so a page joining them is a visible
	 * edit here and not a number going up.
	 */
	private static final List<String> CARRIES_ITS_OWN_PAGE_KEY =
			List.of("attendance.jte", "attendance.jte");

	private static final Pattern PAGER_CALL = Pattern.compile("@template\\.admin\\.pager\\s*\\(");

	private static final Pattern PAGE_PARAM = Pattern.compile("pageParam\\s*=\\s*\"([^\"]+)\"");

	private static final Pattern CARRY_ARGUMENT = Pattern.compile("carry\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)");

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
