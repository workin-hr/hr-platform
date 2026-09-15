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
	 * and carry {@code login.css} through the shared set. {@code sessions} has
	 * no legacy equivalent at all and uses only the shared vocabulary;
	 * {@code company-confirm} is the case where legacy names none either.
	 *
	 * <p>The two detail pages used to be here on the stated grounds that legacy
	 * named none for them, and that was wrong. Legacy serves both under a
	 * {@code page.php} that names four
	 * ({@code pages/employees/page.php:234}) and two
	 * ({@code pages/companies/page.php:139}) respectively, which is what a
	 * browser loads on legacy's own detail view. Both now name theirs, and the
	 * classes each was using -- {@code data-table-empty} and
	 * {@code toolbar-form} on one, {@code hr-page}-scoped rules on the other --
	 * resolve for the first time (D-209).
	 *
	 * <p>{@code home} used to be here and is not any more. It was exempt while
	 * it rendered a "signed in" panel and nothing else; legacy's
	 * {@code index.php} has always named {@code pages/home/assets/style.css},
	 * and now that the page is the overview legacy serves, so does this one
	 * (D-198).
	 */
	private static final Set<String> NO_PAGE_STYLES = Set.of(
			"login", "mfa", "enrol", "enrol-confirm", "sessions",
			"company-confirm");

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
			if (!body.contains("pageStyles =")) {
				continue;
			}
			// Every literal in the template, not the span up to the first ')':
			// a conditional pageStyles puts that ')' inside its condition
			// (settings.jte), and this sweep then checked nothing there.
			Matcher matcher = PAGE_STYLE.matcher(body);
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

	/**
	 * Legacy's dashboard renders in the stack its {@code style.css} names on {@code body},
	 * {@code 'Segoe UI', Tahoma, Arial}, and ships no web font. At the owner's choice the port
	 * does the same (D-251), and a template linking a font sheet, or a sheet declaring a face,
	 * would undo that without any page's own test noticing.
	 */
	@Test
	void theDashboardShipsNoWebFontAndKeepsLegacysSystemStack() throws IOException {
		List<String> faces = new ArrayList<>();
		List<String> fontFiles = new ArrayList<>();
		try (var files = Files.walk(ASSETS)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				String name = file.getFileName().toString();
				if (name.matches(".*\\.(woff2?|ttf|otf|eot)")) {
					fontFiles.add(ASSETS.relativize(file).toString());
				}
				if (name.endsWith(".css") && Files.readString(file, StandardCharsets.UTF_8).contains("@font-face")) {
					faces.add(ASSETS.relativize(file).toString());
				}
			}
		}
		assertThat(faces).as("stylesheets declaring a font face").isEmpty();
		assertThat(fontFiles).as("font files under _assets").isEmpty();
		try (var templates = Files.walk(TEMPLATES)) {
			for (Path template : templates.filter(file -> file.toString().endsWith(".jte")).toList()) {
				assertThat(Files.readString(template, StandardCharsets.UTF_8))
						.as("%s links a font stylesheet", fileName(template))
						.doesNotContain("fonts.css");
			}
		}
		assertThat(Files.readString(ASSETS.resolve("style.css"), StandardCharsets.UTF_8))
				.as("the copied style.css still names legacy's stack on body")
				.contains("font-family: 'Segoe UI', Tahoma, Arial, sans-serif;");
	}

	@Test
	void everyMessageKeyAControllerCanEmitResolvesInACatalogue() throws IOException {
		// A missing key does not fail: the translator answers with the key
		// itself, so the user is shown "actions_disabled" and everything stays
		// green. That is exactly how two controllers came to invent spellings
		// of admin_actions_disabled and mfa_required_for_actions that did not
		// exist, and it is why this reads the sources rather than trusting them.
		Set<String> known = new java.util.TreeSet<>();
		known.addAll(keysOf(Path.of("src/main/resources/i18n/admin-messages.properties")));
		known.addAll(keysOf(Path.of("src/main/resources/i18n/admin-own.properties")));
		assertThat(known).as("both catalogues should have loaded").hasSizeGreaterThan(50);

		Pattern emitted = Pattern.compile("->\\s*\"([a-z][a-z0-9_]{3,})\"");
		List<String> unresolved = new ArrayList<>();
		try (var paths = Files.list(
				Path.of("src/main/java/com/workin/backend/platformadmin/web"))) {
			for (Path source : paths.toList()) {
				if (!source.toString().endsWith("Controller.java")) {
					continue;
				}
				Matcher matcher = emitted.matcher(
						Files.readString(source, StandardCharsets.UTF_8));
				while (matcher.find()) {
					String key = matcher.group(1);
					// Only strings that look like message keys, not paths or
					// view names -- those never contain an underscore-free word
					// this pattern would catch alone.
					if (!known.contains(key) && key.contains("_")) {
						unresolved.add(fileName(source) + " -> " + key);
					}
				}
			}
		}
		assertThat(unresolved)
				.as("a key no catalogue defines reaches the user as its own name")
				.isEmpty();
	}

	private static Set<String> keysOf(Path properties) throws IOException {
		Set<String> keys = new java.util.TreeSet<>();
		for (String line : Files.readAllLines(properties, StandardCharsets.UTF_8)) {
			String trimmed = line.trim();
			int equals = trimmed.indexOf('=');
			if (!trimmed.isEmpty() && !trimmed.startsWith("#") && equals > 0) {
				keys.add(trimmed.substring(0, equals).trim());
			}
		}
		return keys;
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

	/**
	 * The PostgreSQL profile's case: no legacy clock in the context, and the
	 * advice falls back rather than failing to start.
	 */
	private static org.springframework.beans.factory.ObjectProvider<com.workin.legacy.LegacyClock>
			noClock() {
		return new org.springframework.beans.factory.ObjectProvider<>() {
			@Override
			public com.workin.legacy.LegacyClock getObject() {
				throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(
						com.workin.legacy.LegacyClock.class);
			}

			@Override
			public com.workin.legacy.LegacyClock getObject(Object... args) {
				return getObject();
			}

			@Override
			public com.workin.legacy.LegacyClock getIfAvailable() {
				return null;
			}

			@Override
			public com.workin.legacy.LegacyClock getIfUnique() {
				return null;
			}
		};
	}

	@Test
	void theAdviceSuppliesTheAdministratorsLabelAndTolerantlyOmitsItBeforeSignIn() {
		org.springframework.context.support.StaticMessageSource messages =
				new org.springframework.context.support.StaticMessageSource();
		messages.addMessage("admin", java.util.Locale.forLanguageTag("ar"), "أدمن");
		AdminViewModelAdvice advice = new AdminViewModelAdvice(messages, null, noClock(), companies(null));
		org.springframework.mock.web.MockHttpServletRequest request =
				new org.springframework.mock.web.MockHttpServletRequest();
		// One administrator (ADR-0018), shown by PHP's label for it, not by an id.
		assertThat(advice.currentAdminPhone(new PlatformAdminWebPrincipal(7L, "admin"), request))
				.isEqualTo("أدمن");
		// The login page has no principal, and the layout's shell-less branch
		// is right for it.
		assertThat(advice.currentAdminPhone(null, request)).isNull();
	}

	/**
	 * The toolbar's company filter is a select the advice fills (D-252). The list is read
	 * only when a rendered field asks for it, and once per request however often it asks.
	 */
	@Test
	void theCompanyFilterListIsReadOnlyWhenAFieldAsksAndOnlyOnce() {
		int[] reads = {0};
		com.workin.backend.platformadmin.org.ActiveCompanies store =
				new com.workin.backend.platformadmin.org.ActiveCompanies(null) {
					@Override
					public List<CompanyOption> all() {
						reads[0]++;
						return List.of(new CompanyOption(11, "Alpha Co"));
					}
				};
		AdminViewModelAdvice advice = new AdminViewModelAdvice(
				new org.springframework.context.support.StaticMessageSource(), null, noClock(), companies(store));

		java.util.function.Supplier<List<com.workin.backend.platformadmin.org.ActiveCompanies.CompanyOption>> options =
				advice.companyFilterOptions();
		assertThat(reads[0]).as("building the model reads nothing").isZero();
		assertThat(options.get()).extracting(com.workin.backend.platformadmin.org.ActiveCompanies.CompanyOption::name)
				.containsExactly("Alpha Co");
		options.get();
		assertThat(reads[0]).as("read once per request").isEqualTo(1);
	}

	@Test
	void noToolbarRendersTheCompanyFilterAsANumberBox() throws IOException {
		List<String> numberBoxes = new ArrayList<>();
		try (var templates = Files.list(TEMPLATES)) {
			for (Path template : templates.filter(file -> file.toString().endsWith(".jte")).sorted().toList()) {
				if (Pattern.compile("<input type=\"number\"[^>]*name=\"company_id\"")
						.matcher(Files.readString(template, StandardCharsets.UTF_8)).find()) {
					numberBoxes.add(fileName(template));
				}
			}
		}
		assertThat(numberBoxes)
				.as("legacy's toolbars name the company from a list; use companyFilterField.jte")
				.isEmpty();
	}

	private static org.springframework.beans.factory.ObjectProvider<com.workin.backend.platformadmin.org.ActiveCompanies>
			companies(com.workin.backend.platformadmin.org.ActiveCompanies store) {
		return new org.springframework.beans.factory.ObjectProvider<>() {
			@Override
			public com.workin.backend.platformadmin.org.ActiveCompanies getObject() {
				return store;
			}

			@Override
			public com.workin.backend.platformadmin.org.ActiveCompanies getObject(Object... args) {
				return store;
			}

			@Override
			public com.workin.backend.platformadmin.org.ActiveCompanies getIfAvailable() {
				return store;
			}

			@Override
			public com.workin.backend.platformadmin.org.ActiveCompanies getIfUnique() {
				return store;
			}
		};
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
