package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * That an action a controller handles is one its page can actually reach
 * (<b>D-210</b>).
 *
 * <p>A dashboard page posts an {@code action} and the controller switches on
 * it. Nothing checked that the switch and the template agree, and they had
 * drifted in one direction on nine pages: the controller implements
 * {@code case "edit"}, fully guarded and audited, and the template renders no
 * way to trigger it. The row could be deleted but never corrected. It is
 * invisible from either side -- the controller's tests post the action
 * directly and pass, and the page renders without complaint.
 *
 * <p>The reverse direction is already covered: an action the template posts
 * and no controller handles falls to the {@code default} branch, which every
 * one of these controllers answers with "not found".
 *
 * <p>{@link #UNREACHABLE} is a list rather than a count, so that offering one
 * is a visible deletion from this file and so that an action cannot quietly
 * stop being reachable. Nine of the eleven are edits legacy offers, which is
 * why they read as a gap rather than a decision.
 */
class AdminActionReachabilityTest {

	private static final Path CONTROLLERS =
			Path.of("src/main/java/com/workin/backend/platformadmin/web");

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	/**
	 * Handled by a controller, offered by no template, as of D-210.
	 *
	 * <p>Every one is a capability legacy has and this surface does not,
	 * measured against {@code dashboard/pages/}: legacy's complaints page
	 * posts {@code set_status} twice, and its advances, assets, attendance,
	 * banners, faqs, penalties and settings pages each open an edit form this
	 * one never renders. The service work is done in all of them -- what is
	 * missing is the trigger and the form, which is why the fix for
	 * {@code phone_countries} was a template change and no service change at
	 * all.
	 */
	private static final Map<String, Set<String>> UNREACHABLE = Map.of(
			"advances", Set.of("edit_advance"),
			"assets", Set.of("edit_asset"),
			"attendance", Set.of("delete_range", "edit_attendance"),
			"banners", Set.of("edit"),
			"complaints", Set.of("set_status"),
			"faqs", Set.of("edit_category", "edit_item"),
			"penalties", Set.of("edit_penalty"),
			"settings", Set.of("edit_option"));

	private static final Pattern VIEW = Pattern.compile("String VIEW\\s*=\\s*\"admin/([a-z-]+)\"");

	private static final Pattern ACTION = Pattern.compile("case \"([a-z_]+)\"\\s*->");

	private static final Pattern INCLUDE = Pattern.compile("@template\\.admin\\.([A-Za-z-]+)\\(");

	@Test
	void everyActionAControllerHandlesIsOneItsPageCanTrigger() throws IOException {
		Map<String, String> templates = templates();
		List<String> unreachable = new ArrayList<>();
		int checked = 0;

		try (var sources = Files.list(CONTROLLERS)) {
			for (Path source : sources.sorted().toList()) {
				String java = Files.readString(source, StandardCharsets.UTF_8);
				Matcher view = VIEW.matcher(java);
				if (!view.find()) {
					continue;
				}
				String page = view.group(1);
				if (!templates.containsKey(page)) {
					continue;
				}
				String rendered = renderedBy(page, templates);
				Set<String> tolerated = UNREACHABLE.getOrDefault(page, Set.of());

				Matcher action = ACTION.matcher(java);
				while (action.find()) {
					checked++;
					String name = action.group(1);
					// The template names it as a literal somewhere: a hidden
					// input's value, a ternary choosing between two, or a
					// dialog's action parameter. All three are quoted.
					if (!rendered.contains("\"" + name + "\"") && !tolerated.contains(name)) {
						unreachable.add(page + " cannot trigger \"" + name + "\", which "
								+ source.getFileName() + " handles");
					}
				}
			}
		}

		assertThat(checked).as("the sweep should have found the switch actions").isGreaterThan(40);
		assertThat(unreachable)
				.as("a handled action no template offers is a capability that exists and "
						+ "cannot be used -- render the trigger, or add it to UNREACHABLE "
						+ "with the reason it is deliberate")
				.isEmpty();
	}

	/**
	 * An entry that has been offered since it was listed is a stale exemption,
	 * and a stale exemption is how the list stops meaning anything.
	 */
	@Test
	void nothingListedAsUnreachableIsActuallyReachable() throws IOException {
		Map<String, String> templates = templates();
		List<String> stale = new ArrayList<>();
		for (Map.Entry<String, Set<String>> entry : UNREACHABLE.entrySet()) {
			String rendered = renderedBy(entry.getKey(), templates);
			for (String action : entry.getValue()) {
				if (rendered.contains("\"" + action + "\"")) {
					stale.add(entry.getKey() + " -> " + action);
				}
			}
		}
		assertThat(stale)
				.as("these are offered now; delete them from UNREACHABLE")
				.isEmpty();
	}

	private static String renderedBy(String page, Map<String, String> templates) {
		Set<String> reached = new LinkedHashSet<>();
		collect(page, templates, reached);
		reached.add(page);
		StringBuilder all = new StringBuilder();
		for (String name : reached) {
			all.append(templates.getOrDefault(name, ""));
		}
		return all.toString();
	}

	private static void collect(String name, Map<String, String> templates, Set<String> seen) {
		Matcher include = INCLUDE.matcher(templates.getOrDefault(name, ""));
		while (include.find()) {
			String child = include.group(1);
			if (!child.equals("layout") && templates.containsKey(child) && seen.add(child)) {
				collect(child, templates, seen);
			}
		}
	}

	private static Map<String, String> templates() throws IOException {
		Map<String, String> byName = new LinkedHashMap<>();
		try (var paths = Files.list(TEMPLATES)) {
			for (Path path : paths.sorted().toList()) {
				String file = path.getFileName().toString();
				if (file.endsWith(".jte")) {
					byName.put(file.substring(0, file.length() - 4),
							Files.readString(path, StandardCharsets.UTF_8));
				}
			}
		}
		return byName;
	}

	static Set<String> allTolerated() {
		Set<String> all = new TreeSet<>();
		UNREACHABLE.forEach((page, actions) -> actions.forEach(a -> all.add(page + ":" + a)));
		return all;
	}
}
