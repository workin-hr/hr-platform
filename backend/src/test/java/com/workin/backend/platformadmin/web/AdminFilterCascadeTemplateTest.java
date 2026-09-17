package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The filter cascades legacy attaches to its list toolbars (<b>#214 item 3</b>, D-260).
 *
 * <p>A toolbar whose selects do not narrow each other still filters, and a page's own test
 * cannot see a script that never initialised. So this reads the templates: a filter select
 * carries the marker its script looks for, and its form carries every attribute the script
 * reads. It fails closed -- the forms it finds are named, so a page that stops matching the
 * patterns fails rather than drops out.
 */
class AdminFilterCascadeTemplateTest {

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	private static final Pattern FORM = Pattern.compile("<form\\b([^>]*)>(.*?)</form>", Pattern.DOTALL);

	/** {@code org-filter-cascade.js}'s select markers, by the filter each select posts. */
	private static final Map<String, String> ORG_MARKERS = Map.of(
			"filter_branch", "data-filter-branch",
			"filter_department", "data-filter-department",
			"filter_job_title", "data-filter-job-title");

	/** Every attribute {@code org_filter_cascade_form_attrs()} writes ({@code org_helper.php:456-466}). */
	private static final List<String> ORG_FORM_ATTRIBUTES = List.of("data-org-filters",
			"data-branches-by-company", "data-departments-by-company", "data-departments-by-branch",
			"data-job-titles-by-dept", "data-filter-all", "data-select-company-msg", "data-pick-branch-first-msg",
			"data-pick-dept-first-msg", "data-selected-branch", "data-selected-department",
			"data-selected-job-title");

	/** Every attribute {@code hr_request_filter_form_attrs()} writes ({@code hr_list_helper.php:974-978}). */
	private static final List<String> REQUEST_FORM_ATTRIBUTES = List.of("data-request-filters",
			"data-request-types-by-company", "data-filter-all", "data-select-company-msg",
			"data-selected-request-type");

	@Test
	void everyOrgFilterSelectCarriesItsMarkerAndItsFormTheCascade() throws IOException {
		Map<String, List<String>> offenders = new TreeMap<>();
		List<String> cascading = new ArrayList<>();
		for (Path template : templates()) {
			String name = template.getFileName().toString();
			Matcher form = FORM.matcher(read(template));
			while (form.find()) {
				List<String> missing = new ArrayList<>();
				boolean filters = false;
				Matcher select = Pattern.compile("<select\\b[^>]*\\bname=\"(filter_branch|filter_department|filter_job_title)\"[^>]*>")
						.matcher(form.group(2));
				while (select.find()) {
					filters = true;
					if (!hasAttribute(select.group(), ORG_MARKERS.get(select.group(1)))) {
						missing.add(select.group(1) + " without " + ORG_MARKERS.get(select.group(1)));
					}
				}
				if (!filters) {
					continue;
				}
				cascading.add(name);
				for (String attribute : ORG_FORM_ATTRIBUTES) {
					if (!hasAttribute(form.group(1), attribute)) {
						missing.add("form without " + attribute);
					}
				}
				if (!missing.isEmpty()) {
					offenders.put(name, missing);
				}
			}
		}
		assertThat(cascading).as("the toolbars legacy draws with org_filter_cascade_form_attrs()")
				.containsExactly("attendance.jte", "departments.jte", "employees.jte", "job-titles.jte");
		assertThat(offenders).isEmpty();
		assertThat(read(TEMPLATES.resolve("layout.jte")))
				.as("legacy's layout.php:127 loads the script on every page")
				.contains("<script src=\"/admin/_assets/org-filter-cascade.js\"></script>");
	}

	@Test
	void theRequestTypeFilterCarriesItsMarkerAndItsFormTheCascade() throws IOException {
		List<String> cascading = new ArrayList<>();
		for (Path template : templates()) {
			String name = template.getFileName().toString();
			Matcher form = FORM.matcher(read(template));
			while (form.find()) {
				Matcher select = Pattern.compile("<select\\b[^>]*\\bname=\"type_id\"[^>]*>").matcher(form.group(2));
				if (!form.group(1).contains("method=\"GET\"") || !select.find()) {
					continue;
				}
				cascading.add(name);
				assertThat(hasAttribute(select.group(), "data-filter-request-type")).as(name).isTrue();
				for (String attribute : REQUEST_FORM_ATTRIBUTES) {
					assertThat(hasAttribute(form.group(1), attribute)).as("%s: form with %s", name, attribute).isTrue();
				}
			}
		}
		assertThat(cascading).containsExactly("requests.jte");
		assertThat(read(TEMPLATES.resolve("requests.jte")))
				.as("legacy's requests/page.php:61 loads the script")
				.contains("pageScripts = java.util.List.of(\"request-filter-cascade.js\")");
	}

	private static boolean hasAttribute(String tag, String attribute) {
		return Pattern.compile("\\s" + Pattern.quote(attribute) + "(?:=|\\s|>|$)").matcher(tag).find();
	}

	private static List<Path> templates() throws IOException {
		try (var files = Files.list(TEMPLATES)) {
			List<Path> found = files.filter(file -> file.toString().endsWith(".jte")).sorted().toList();
			assertThat(found).as("the admin templates").isNotEmpty();
			return found;
		}
	}

	private static String read(Path template) throws IOException {
		return Files.readString(template, StandardCharsets.UTF_8);
	}
}
