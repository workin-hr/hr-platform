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
 * That every page carries its shell and its stylesheets (<b>R-058</b>).
 *
 * <p>Both halves of that defect were invisible to the pages' own tests, and in
 * the same way: they assert on what a page says, and the content was never
 * what went missing. {@code currentAdminPhone} is declared on the templates
 * without a default, so a controller that did not set it left the layout
 * rendering the sessionless {@code auth-shell} branch silently -- fourteen
 * pages with no sidebar and no page title, all of them green. The stylesheets
 * were copied into {@code static/admin/_assets/} and linked by nothing.
 *
 * <p>The fix for the first half is that the value comes from
 * {@link AdminViewModelAdvice} now and no controller has to remember it. The
 * fix for the second is a per-template declaration, which a new page <em>can</em>
 * forget -- so this reads the templates rather than a list, and a page added
 * without one fails here.
 */
class AdminLayoutWiringTest {

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	private static final Path ASSETS = Path.of("src/main/resources/static/admin/_assets");

	private static final Pattern PAGE_STYLE = Pattern.compile("\"([a-z0-9-]+\\.css)\"");

	/**
	 * The pages that legitimately name no stylesheet.
	 *
	 * <p>{@code login}, {@code mfa},
	 * {@code enrol} and {@code enrol-confirm} render before there is a session
	 * and carry {@code login.css} through the shared set. {@code home},
	 * {@code sessions}, {@code company-confirm} and the two detail pages are
	 * the cases where legacy names none either -- checked against
	 * {@code $pageStyles} in the PHP, not assumed.
	 */
	private static final Set<String> NO_PAGE_STYLES = Set.of(
			"login", "mfa", "enrol", "enrol-confirm", "home", "sessions",
			"company-confirm", "company-detail", "employee-detail");

	@Test
	void everyPageTemplateNamesTheStylesheetsItsClassesNeed() throws IOException {
		List<String> missing = new ArrayList<>();
		for (Path template : pageTemplates()) {
			String name = fileName(template);
			String body = Files.readString(template, StandardCharsets.UTF_8);
			boolean declares = body.contains("pageStyles =");
			if (NO_PAGE_STYLES.contains(name)) {
				assertThat(declares)
						.as("%s is listed as needing none, so it should not declare any", name)
						.isFalse();
			}
			else if (!declares) {
				missing.add(name);
			}
		}
		assertThat(missing)
				.as("a page that names no stylesheet renders unstyled -- add it to the "
						+ "template, or to NO_PAGE_STYLES if legacy names none either")
				.isEmpty();
	}

	@Test
	void everyStylesheetNamedByATemplateExists() throws IOException {
		Set<String> named = new LinkedHashSet<>();
		for (Path template : pageTemplates()) {
			String body = Files.readString(template, StandardCharsets.UTF_8);
			int declaration = body.indexOf("pageStyles =");
			if (declaration < 0) {
				continue;
			}
			int end = body.indexOf(')', declaration);
			Matcher matcher = PAGE_STYLE.matcher(body.substring(declaration, end));
			while (matcher.find()) {
				named.add(matcher.group(1));
			}
		}
		assertThat(named).as("the sweep should have named several").hasSizeGreaterThan(4);
		for (String stylesheet : named) {
			assertThat(ASSETS.resolve(stylesheet))
					.as("%s is linked but was never copied -- the link would 404 in "
							+ "silence, which is the failure this catches", stylesheet)
					.exists();
		}
	}

	@Test
	void noControllerSetsTheAdminPhoneItselfAnyMore() throws IOException {
		// One authority. Fourteen controllers forgot this and six set it, which
		// is exactly the split a cross-cutting value gets when each page owns a
		// copy of it.
		List<String> offenders = new ArrayList<>();
		try (var paths = Files.list(Path.of("src/main/java/com/workin/backend/platformadmin/web"))) {
			for (Path source : paths.toList()) {
				if (!source.toString().endsWith("Controller.java")) {
					continue;
				}
				if (Files.readString(source, StandardCharsets.UTF_8).contains("\"currentAdminPhone\"")) {
					offenders.add(fileName(source));
				}
			}
		}
		assertThat(offenders)
				.as("AdminViewModelAdvice supplies it for the whole package")
				.isEmpty();
	}

	@Test
	void theAdviceSuppliesThePhoneAndTolerantlyOmitsItBeforeSignIn() {
		AdminViewModelAdvice advice = new AdminViewModelAdvice(null, null);
		assertThat(advice.currentAdminPhone(
				new PlatformAdminWebPrincipal(7L, "+201000000000", true)))
				.isEqualTo("+201000000000");
		// The login, MFA and enrolment pages have no principal, and the layout's
		// shell-less branch is right for them.
		assertThat(advice.currentAdminPhone(null)).isNull();
	}

	/**
	 * A page is a template that renders the shell. The rest -- the layout
	 * itself, the sidebar, and form fragments like {@code branch-form} that a
	 * page includes -- have no stylesheets of their own to name.
	 */
	private static List<Path> pageTemplates() throws IOException {
		try (var paths = Files.list(TEMPLATES)) {
			List<Path> pages = new ArrayList<>();
			for (Path path : paths.sorted().toList()) {
				if (path.toString().endsWith(".jte")
						&& Files.readString(path, StandardCharsets.UTF_8)
								.contains("@template.admin.layout(")) {
					pages.add(path);
				}
			}
			return pages;
		}
	}

	private static String fileName(Path path) {
		String name = path.getFileName().toString();
		return name.substring(0, name.lastIndexOf('.'));
	}

}
