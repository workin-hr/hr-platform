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
 * That a class a page renders is one a stylesheet that page loads defines
 * (<b>D-209</b>).
 *
 * <p>{@link AdminLayoutWiringTest} proves a page names <em>a</em> stylesheet
 * and that the file exists. Neither answers the question that actually
 * decides how a page looks: whether the sheets it names define the classes its
 * markup uses. Six pages were wrong about that and every one of them was
 * green -- {@code attendance} named one of the three
 * {@code pages/attendance/page.php:134} names, both detail pages named none
 * while legacy serves them under a {@code page.php} that names four and two,
 * {@code branches} reached for {@code account-card} from a page whose
 * stylesheet was never copied, five list pages wore {@code login-remember},
 * and {@code sessions} and {@code company-detail} were built out of class
 * names no stylesheet has ever defined. Unstyled markup renders; it does not
 * throw.
 *
 * <p>The shared set is read from {@code layout.jte} rather than listed here,
 * so adding one to the shell does not need editing this test. Classes are
 * attributed through {@code @template.admin.*} includes, because a partial's
 * markup renders inside its page and is styled by that page's sheets.
 *
 * <p>A {@code class} attribute holding an expression is skipped: its value is
 * not knowable here. That is a real hole and a deliberate one -- the
 * alternative is a test that guesses.
 */
class AdminPageStylesheetTest {

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	private static final Path ASSETS = Path.of("src/main/resources/static/admin/_assets");

	/**
	 * Classes no stylesheet defines <em>in legacy either</em>, checked one at a
	 * time against {@code hr-legacy/dashboard/**}{@code /*.css}. They are
	 * structural hooks the PHP carries for its own JavaScript and markup
	 * shape, and copying them faithfully includes copying the fact that
	 * nothing styles them. Removing one here would be a divergence, not a fix.
	 */
	private static final Set<String> UNSTYLED_IN_LEGACY_TOO = Set.of(
			"account-form", "org-form", "pager", "row-dialog__form",
			"checkbox-grid", "checkbox-item", "setting-templates-tab",
			"settings-form", "home-panel-icon--clock", "home-panel-icon--people");

	private static final Pattern CLASS_ATTRIBUTE = Pattern.compile("class=\"([^\"]*)\"");

	private static final Pattern SHEET_LINK = Pattern.compile("_assets/([a-z0-9-]+\\.css)");

	private static final Pattern PAGE_STYLE = Pattern.compile("\"([a-z0-9-]+\\.css)\"");

	private static final Pattern INCLUDE = Pattern.compile("@template\\.admin\\.([A-Za-z-]+)\\(");

	private static final Pattern CSS_CLASS = Pattern.compile("\\.(-?[_a-zA-Z][\\w-]*)");

	@Test
	void everyClassAPageRendersIsOneItsStylesheetsDefine() throws IOException {
		Map<String, String> templates = templates();
		Set<String> shared = sheetsLinkedBy(templates.get("layout"));
		assertThat(shared).as("the shell should link the shared set").isNotEmpty();

		List<String> unstyled = new ArrayList<>();
		for (Map.Entry<String, String> page : templates.entrySet()) {
			if (!page.getValue().contains("@template.admin.layout(")) {
				continue;   // a partial; its classes are checked through its pages
			}
			Set<String> sheets = new LinkedHashSet<>(shared);
			sheets.addAll(declaredStyles(page.getValue()));
			sheets.addAll(sheetsLinkedBy(page.getValue()));
			Set<String> defined = classesDefinedBy(sheets);

			for (String used : classesRenderedBy(page.getKey(), templates)) {
				if (!defined.contains(used) && !UNSTYLED_IN_LEGACY_TOO.contains(used)) {
					unstyled.add(page.getKey() + " renders ." + used
							+ ", defined by none of " + sheets);
				}
			}
		}
		assertThat(unstyled)
				.as("a class no loaded stylesheet defines renders as unstyled markup, "
						+ "silently -- name the sheet on the page, use the class legacy "
						+ "uses, or add it to UNSTYLED_IN_LEGACY_TOO once you have "
						+ "checked that legacy does not style it either")
				.isEmpty();
	}

	private Set<String> classesRenderedBy(String page, Map<String, String> templates) {
		Set<String> used = new TreeSet<>();
		Set<String> reached = new LinkedHashSet<>();
		collectIncludes(page, templates, reached);
		reached.add(page);
		for (String name : reached) {
			Matcher attribute = CLASS_ATTRIBUTE.matcher(templates.get(name));
			while (attribute.find()) {
				String value = attribute.group(1);
				if (value.contains("${") || value.contains("$unsafe")) {
					continue;
				}
				for (String token : value.trim().split("\\s+")) {
					if (token.matches("-?[_a-zA-Z][\\w-]*")) {
						used.add(token);
					}
				}
			}
		}
		return used;
	}

	private void collectIncludes(String name, Map<String, String> templates, Set<String> seen) {
		Matcher include = INCLUDE.matcher(templates.getOrDefault(name, ""));
		while (include.find()) {
			String child = include.group(1);
			if (!child.equals("layout") && templates.containsKey(child) && seen.add(child)) {
				collectIncludes(child, templates, seen);
			}
		}
	}

	private Set<String> classesDefinedBy(Set<String> sheets) throws IOException {
		Set<String> defined = new TreeSet<>();
		for (String sheet : sheets) {
			Path path = ASSETS.resolve(sheet);
			if (!Files.exists(path)) {
				continue;   // AdminLayoutWiringTest owns that failure
			}
			String css = Files.readString(path, StandardCharsets.UTF_8)
					.replaceAll("(?s)/\\*.*?\\*/", " ");
			Matcher name = CSS_CLASS.matcher(css);
			while (name.find()) {
				defined.add(name.group(1));
			}
		}
		return defined;
	}

	/**
	 * Every stylesheet literal in the template, which is the union across a
	 * conditional {@code pageStyles} rather than one branch of it --
	 * {@code settings.jte} names {@code app-content.css} only on one tab, and
	 * the partial that uses its classes only renders on that tab. Reading to
	 * the first {@code ')'} after {@code pageStyles =} lands inside
	 * {@code "app_content".equals(tab)} and finds nothing at all.
	 */
	private static Set<String> declaredStyles(String template) {
		Set<String> named = new LinkedHashSet<>();
		Matcher style = PAGE_STYLE.matcher(template);
		while (style.find()) {
			named.add(style.group(1));
		}
		return named;
	}

	private static Set<String> sheetsLinkedBy(String template) {
		Set<String> linked = new LinkedHashSet<>();
		Matcher link = SHEET_LINK.matcher(template);
		while (link.find()) {
			linked.add(link.group(1));
		}
		return linked;
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
}
