package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
	 * does the same (D-251). A face, a font file, an imported or linked font sheet, or a later
	 * {@code font-family} would each undo that without any page's own test noticing, in any
	 * letter case CSS accepts.
	 */
	@Test
	void theDashboardShipsNoWebFontAndKeepsLegacysSystemStack() throws IOException {
		String stack = "'Segoe UI', Tahoma, Arial, sans-serif";
		// JTE's own `@import java...` directives are not CSS imports, so an import must name a URL or a
		// string. CSS needs no space before either: `@import"x.css"` is an import.
		Pattern webFont = Pattern.compile("(?i)@font-face|@import\\s*(url\\(|[\"'])|fonts\\.(googleapis|gstatic)\\.com");
		Pattern family = Pattern.compile("(?i)font-family\\s*:\\s*([^;}]+)");
		// The shorthand carries the family too: `font: 14px "Cairo", sans-serif`.
		Pattern shorthand = Pattern.compile("(?i)(?:^|[;{\\s\"'])font\\s*:\\s*([^;}]+)");
		Set<String> stackSheets = Set.of("style.css", "login.css");
		// `inherit`, and the emoji stack on the one icon rule in app-content.css.
		Set<String> otherFamilies = Set.of(
				"inherit", "\"Apple Color Emoji\", \"Segoe UI Emoji\", \"Noto Color Emoji\", sans-serif");
		Set<String> offenders = new LinkedHashSet<>();
		try (var files = Files.walk(ASSETS)) {
			for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
				String name = ASSETS.relativize(file).toString();
				String lower = name.toLowerCase(java.util.Locale.ROOT);
				if (lower.matches(".*\\.(woff2?|ttf|otf|eot)")) {
					offenders.add(name + " is a font file");
				}
				if (!lower.endsWith(".css")) {
					continue;
				}
				String raw = Files.readString(file, StandardCharsets.UTF_8);
				for (String css : List.of(raw, cssTokens(raw))) {
					if (webFont.matcher(css).find()) {
						offenders.add(name + " declares or imports a font");
					}
					Matcher families = family.matcher(css);
					while (families.find()) {
						String value = families.group(1).trim();
						if (!otherFamilies.contains(value) && !(stackSheets.contains(name) && value.equals(stack))) {
							offenders.add(name + " sets font-family " + value);
						}
					}
					Matcher shorthands = shorthand.matcher(css);
					while (shorthands.find()) {
						String value = shorthands.group(1).trim();
						if (!"inherit".equals(value)) {
							offenders.add(name + " sets font " + value);
						}
					}
				}
			}
		}
		// Any quoting HTML accepts, because a `rel=stylesheet` without quotes loads just as well.
		Pattern link = Pattern.compile("(?i)<link\\b[^>]*>");
		Pattern styles = Pattern.compile("(?i)rel\\s*=\\s*[\"']?[^\"'>]*\\bstylesheet\\b");
		Pattern href = Pattern.compile("(?i)href\\s*=\\s*([\"']?)([^\"'>\\s]+)\\1");
		try (var templates = Files.walk(TEMPLATES)) {
			for (Path template : templates.filter(file -> file.toString().endsWith(".jte")).sorted().toList()) {
				String body = Files.readString(template, StandardCharsets.UTF_8);
				for (String inline : List.of(body, cssTokens(body))) {
					if (webFont.matcher(inline).find() || family.matcher(inline).find()
							|| shorthand.matcher(inline).find()) {
						offenders.add(fileName(template) + " carries a font of its own");
					}
				}
				Matcher links = link.matcher(body);
				while (links.find()) {
					String tag = links.group();
					if (!styles.matcher(tag).find()) {
						continue;
					}
					Matcher target = href.matcher(tag);
					if (!target.find() || !target.group(2).startsWith("/admin/_assets/")) {
						offenders.add(fileName(template) + " links a stylesheet from outside /admin/_assets/");
					}
				}
			}
		}
		assertThat(offenders).as("ways a web font or another body font would come back").isEmpty();
		assertThat(Files.readString(ASSETS.resolve("style.css"), StandardCharsets.UTF_8))
				.as("the copied style.css still names legacy's stack on body")
				.contains("font-family: " + stack + ";");
	}

	private static final Pattern CSS_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

	private static final Pattern CSS_ESCAPE = Pattern.compile("\\\\(?:([0-9a-fA-F]{1,6})[ \\t\\r\\n\\f]?|([^\\r\\n\\f0-9a-fA-F]))");

	/**
	 * CSS as a parser reads it: comments removed and escapes decoded. {@code @import} followed by a
	 * comment and then a string is an import, and so is {@code @\69mport}; Chromium loads both.
	 * A {@code /*} that opens no comment -- in a string, a URL, or a JTE comment's
	 * {@code /admin/_assets/**} -- makes this remove real text up to the next {@code *}{@code /}, so
	 * the font test matches each text as written too, and either one flags it.
	 */
	static String cssTokens(String css) {
		String uncommented = CSS_COMMENT.matcher(css).replaceAll("");
		return CSS_ESCAPE.matcher(uncommented).replaceAll(escape -> {
			if (escape.group(2) != null) {
				return Matcher.quoteReplacement(escape.group(2));
			}
			int codePoint = Integer.parseInt(escape.group(1), 16);
			boolean valid = codePoint > 0 && codePoint <= Character.MAX_CODE_POINT
					&& !(codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE);
			return Matcher.quoteReplacement(Character.toString(valid ? codePoint : 0xFFFD));
		});
	}

	/**
	 * Legacy's layout and sign-in page name its logo as their favicon and touch icon, and the
	 * sign-in page shows it twice (D-254). The file is legacy's own {@code logo.png}. The icons are
	 * 32 and 180 pixel copies of it, because the 1024 pixel file is 1.3 MB on every page (D-266).
	 */
	@Test
	void theLayoutAndTheSignInPageCarryLegacysLogo() throws Exception {
		for (String page : List.of("layout", "login")) {
			assertThat(Files.readString(TEMPLATES.resolve(page + ".jte"), StandardCharsets.UTF_8))
					.as("%s names the logo's small copies as its icons", page)
					.contains("<link rel=\"icon\" type=\"image/png\" sizes=\"32x32\" href=\"/admin/_assets/favicon-32.png\">")
					.contains("<link rel=\"apple-touch-icon\" sizes=\"180x180\" href=\"/admin/_assets/apple-touch-icon.png\">")
					.doesNotContain("rel=\"icon\" type=\"image/png\" href=\"/admin/_assets/logo.png\"");
		}
		assertThat(Files.readString(TEMPLATES.resolve("login.jte"), StandardCharsets.UTF_8))
				.contains("<img src=\"/admin/_assets/logo.png\" alt=\"\" class=\"login-hero-logo\" width=\"40\" height=\"40\">")
				.contains("<img src=\"/admin/_assets/logo.png\" alt=\"${t.apply(\"app_name\")}\" "
						+ "class=\"login-card-logo\" width=\"48\" height=\"48\">");
		assertThat(Files.readString(TEMPLATES.resolve("sidebar.jte"), StandardCharsets.UTF_8))
				.as("legacy's sidebar shows it beside the name (sidebar/view.php:15-18)")
				.contains("<img src=\"/admin/_assets/logo.png\" alt=\"${t.apply(\"app_name\")}\" "
						+ "class=\"logo-icon\" width=\"28\" height=\"28\">");
		byte[] logo = Files.readAllBytes(ASSETS.resolve("logo.png"));
		assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(logo)))
				.as("legacy's logo.png, byte for byte")
				.isEqualTo("7bc29d3139d0dd87675ce9852155d9c467379b4e879894c6c2a009c9a0895bd9");
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

	/**
	 * Legacy's layout shows the message a write flashed (D-253). This layout takes it as
	 * {@code flash} and {@code flashType}, so a page that does not hand them on silently drops
	 * every success message its controller sets.
	 */
	@Test
	void everyPageHandsTheLayoutTheFlashAWriteLeft() throws IOException {
		List<String> dropping = new ArrayList<>();
		for (Path template : pageTemplates()) {
			String body = Files.readString(template, StandardCharsets.UTF_8);
			if (!body.contains("@param String flash = null") || !body.contains("flash = flash,")
					|| !body.contains("flashType = flashType,")) {
				dropping.add(fileName(template));
			}
		}
		assertThat(dropping).as("pages that drop the flash a write left for them").isEmpty();
	}

	/**
	 * The controllers whose posts legacy answers with no flash: signing in and out, this
	 * surface's own session revocation, and the salary calculator, which calculates and
	 * writes nothing.
	 */
	private static final Set<String> NO_FLASH = Set.of(
			"PlatformAdminWebController", "PlatformAdminSessionsController",
			"AdminSalaryCalculatorController");

	@Test
	void everyControllerThatWritesFlashesLegacysMessage() throws IOException {
		List<String> silent = new ArrayList<>();
		try (var paths = Files.list(Path.of("src/main/java/com/workin/backend/platformadmin/web"))) {
			for (Path source : paths.sorted().toList()) {
				String name = fileName(source);
				String code = Files.readString(source, StandardCharsets.UTF_8);
				if (name.endsWith("Controller") && code.contains("@PostMapping")
						&& !NO_FLASH.contains(name) && !code.contains("AdminFlash.")) {
					silent.add(name);
				}
			}
		}
		assertThat(silent).as("a write legacy confirms with a flash, confirmed with nothing").isEmpty();
	}

	/**
	 * The join requests page hides both of its decisions behind the actions
	 * switch (D-161) and said nothing when it was off, so the row menu was
	 * simply empty. It now carries the employees page's own banner, word for
	 * word: {@code canManage && !actionsEnabled}, the condition the sixteen
	 * pages that also gate on a section permission use. The seven pages an
	 * administrator alone reaches test {@code !actionsEnabled} on its own, so
	 * this compares the two pages the change is about rather than all of them.
	 *
	 * <p>Two pages still take the switch, gate a control on it and show no
	 * banner: {@code company-detail.jte} and {@code settings.jte}. They are
	 * named here so the omission is recorded rather than assumed, and are not
	 * this change's pages.
	 */
	@Test
	void theJoinRequestsPageSaysWhyItsDecisionsAreMissing() throws IOException {
		String banner = collapse("@if(canManage && !actionsEnabled)"
				+ "<div class=\"flash flash-warning\">${t.apply(\"admin_actions_disabled\")}</div>"
				+ "@endif");
		assertThat(collapsed(TEMPLATES.resolve("join-requests.jte")))
				.as("the banner the employees page shows, word for word")
				.contains(banner);
		assertThat(collapsed(TEMPLATES.resolve("employees.jte"))).contains(banner);

		for (String page : List.of("company-detail.jte", "settings.jte")) {
			assertThat(collapsed(TEMPLATES.resolve(page)))
					.as("%s still has no banner; when it gains one, take it off this list", page)
					.doesNotContain("admin_actions_disabled");
		}
	}

	/** A template with every run of whitespace removed, so indentation is not the assertion. */
	private static String collapsed(Path template) throws IOException {
		return collapse(Files.readString(template, StandardCharsets.UTF_8));
	}

	private static String collapse(String markup) {
		return markup.replaceAll("\\s+", "");
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
		AdminViewModelAdvice advice = new AdminViewModelAdvice(messages, null, noClock(), companies(null), cascades(null), phoneCountries(null));
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
				new org.springframework.context.support.StaticMessageSource(), null, noClock(), companies(store),
				cascades(null), phoneCountries(null));

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
				// Each input tag as a whole, so the attributes may come in any order.
				Matcher inputs = Pattern.compile("<input\\b[^>]*>")
						.matcher(Files.readString(template, StandardCharsets.UTF_8));
				while (inputs.find()) {
					String tag = inputs.group();
					if (tag.contains("type=\"number\"") && tag.contains("name=\"company_id\"")) {
						numberBoxes.add(fileName(template));
						break;
					}
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
	 * A toolbar's org filter cascade carries every company's rows for an administrator, whose
	 * company select changes them without a request, and nothing for a session bound to one
	 * company, which has no company select for legacy's script to read. Read only when a
	 * toolbar asks, and once per request.
	 */
	@Test
	void theOrgFilterCascadeIsEveryCompanysForAnAdministratorReadOnceAndOnlyWhenAsked() {
		int[] reads = {0};
		long[] companyAsked = {-1};
		com.workin.backend.platformadmin.org.OrgCascadeStore store =
				new com.workin.backend.platformadmin.org.OrgCascadeStore(null) {
					@Override
					public com.workin.backend.platformadmin.org.OrgCascade cascade(long companyId) {
						reads[0]++;
						companyAsked[0] = companyId;
						var branch = new com.workin.backend.platformadmin.org.OrgCascade.Option(3, "Main \"HQ\"");
						return new com.workin.backend.platformadmin.org.OrgCascade(java.util.Map.of(11L, List.of(branch)),
								java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
					}
				};
		org.springframework.mock.web.MockHttpServletRequest request =
				new org.springframework.mock.web.MockHttpServletRequest();
		AdminViewModelAdvice advice = new AdminViewModelAdvice(
				new org.springframework.context.support.StaticMessageSource(), null, noClock(), companies(null),
				cascades(store), phoneCountries(null));

		java.util.function.Supplier<OrgFilterCascade> cascade = advice.orgFilterCascade(request);
		assertThat(reads[0]).as("building the model reads nothing").isZero();
		assertThat(cascade.get().attached()).isTrue();
		assertThat(companyAsked[0]).as("every company's rows, the administrator's reach").isZero();
		assertThat(cascade.get().branchesByCompany()).isEqualTo("{\"11\":[{\"id\":3,\"name\":\"Main \\\"HQ\\\"\"}]}");
		assertThat(reads[0]).as("read once per request").isEqualTo(1);

		AdminViewModelAdvice ownerView = new AdminViewModelAdvice(
				new org.springframework.context.support.StaticMessageSource(), null, noClock(), companies(null),
				cascades(store), phoneCountries(null)) {
			@Override
			public DashboardSession session(jakarta.servlet.http.HttpServletRequest ignored) {
				return DashboardSession.company(7L);
			}
		};
		assertThat(ownerView.orgFilterCascade(request).get())
				.as("a session bound to one company attaches no cascade, and reads nothing")
				.isEqualTo(OrgFilterCascade.NONE);
		assertThat(reads[0]).isEqualTo(1);
	}

	private static org.springframework.beans.factory.ObjectProvider<com.workin.backend.platformadmin.org.OrgCascadeStore>
			cascades(com.workin.backend.platformadmin.org.OrgCascadeStore store) {
		return new org.springframework.beans.factory.ObjectProvider<>() {
			@Override
			public com.workin.backend.platformadmin.org.OrgCascadeStore getObject() {
				return store;
			}

			@Override
			public com.workin.backend.platformadmin.org.OrgCascadeStore getObject(Object... args) {
				return store;
			}

			@Override
			public com.workin.backend.platformadmin.org.OrgCascadeStore getIfAvailable() {
				return store;
			}

			@Override
			public com.workin.backend.platformadmin.org.OrgCascadeStore getIfUnique() {
				return store;
			}
		};
	}

	/**
	 * A company or employee form's country select and phone rules (D-261): the active
	 * countries, labelled in the page's language, read only when a form asks and once per
	 * request. Without the legacy database there are no rules, so the layout loads no phone
	 * script to refuse every number.
	 */
	@Test
	void thePhoneCountriesAreReadOnceAndOnlyWhenAFormAsks() {
		int[] reads = {0};
		com.workin.legacy.phone.LegacyPhoneCountries store = new com.workin.legacy.phone.LegacyPhoneCountries(
				new org.springframework.jdbc.datasource.DriverManagerDataSource()) {
			@Override
			public List<com.workin.legacy.phone.LegacyPhoneCountry> allActive() {
				reads[0]++;
				return List.of(new com.workin.legacy.phone.LegacyPhoneCountry(1, "+20", "مصر", "Egypt", "🇪🇬",
						11, "[\"010\"]", 1, 1));
			}
		};
		org.springframework.mock.web.MockHttpServletRequest request =
				new org.springframework.mock.web.MockHttpServletRequest();
		request.setParameter("lang", "en");
		AdminViewModelAdvice advice = new AdminViewModelAdvice(
				new org.springframework.context.support.StaticMessageSource(), null, noClock(), companies(null),
				cascades(null), phoneCountries(store));

		java.util.function.Supplier<PhoneCountryChoices> choices = advice.phoneCountries(request);
		assertThat(reads[0]).as("building the model reads nothing").isZero();
		assertThat(choices.get().options()).as("labelled in the page's language")
				.containsExactly(new PhoneCountryChoices.Option("+20", "🇪🇬 Egypt (+20)"));
		assertThat(choices.get().rules()).isEqualTo("{\"+20\":{\"phone_length\":11,\"phone_prefixes\":[\"010\"]}}");
		assertThat(reads[0]).as("read once per request").isEqualTo(1);

		AdminViewModelAdvice withoutLegacy = new AdminViewModelAdvice(
				new org.springframework.context.support.StaticMessageSource(), null, noClock(), companies(null),
				cascades(null), phoneCountries(null));
		assertThat(withoutLegacy.phoneCountries(request).get()).isEqualTo(PhoneCountryChoices.NONE);
	}

	private static org.springframework.beans.factory.ObjectProvider<com.workin.legacy.phone.LegacyPhoneCountries>
			phoneCountries(com.workin.legacy.phone.LegacyPhoneCountries store) {
		return new org.springframework.beans.factory.ObjectProvider<>() {
			@Override
			public com.workin.legacy.phone.LegacyPhoneCountries getObject() {
				return store;
			}

			@Override
			public com.workin.legacy.phone.LegacyPhoneCountries getObject(Object... args) {
				return store;
			}

			@Override
			public com.workin.legacy.phone.LegacyPhoneCountries getIfAvailable() {
				return store;
			}

			@Override
			public com.workin.legacy.phone.LegacyPhoneCountries getIfUnique() {
				return store;
			}
		};
	}

	/**
	 * The classes beside {@code .content} on legacy's page, from each page's own
	 * {@code <div class="content ...">}. A stylesheet scopes rules to them --
	 * {@code payroll-pages.css} writes {@code .payroll-page .page-toolbar},
	 * {@code .payroll-page .data-table-card} and {@code .payroll-page .att-overtime-badge} --
	 * so a page that drops one renders without those rules, with nothing on the page
	 * to say so.
	 */
	private static final Map<String, String> LEGACY_CONTENT_CLASS = Map.ofEntries(
			Map.entry("activities", "hr-page activities-page"),
			Map.entry("administrative-decisions", "hr-page"),
			Map.entry("advances", "hr-page"),
			Map.entry("assets", "hr-page"),
			Map.entry("attendance", "payroll-page hr-page"),
			Map.entry("banners", "hr-page"),
			Map.entry("branches", "hr-page"),
			Map.entry("companies", "hr-page"),
			Map.entry("company-detail", ""),
			Map.entry("complaints", "hr-page"),
			Map.entry("departments", "hr-page"),
			Map.entry("employee-detail", ""),
			Map.entry("employees", "hr-page"),
			Map.entry("faqs", "hr-page"),
			Map.entry("guide-videos", "hr-page"),
			Map.entry("home", "home-page"),
			Map.entry("job-titles", "hr-page"),
			Map.entry("join-requests", "hr-page"),
			Map.entry("leave-balances", "hr-page"),
			Map.entry("notifications", "hr-page"),
			Map.entry("payroll", "payroll-page hr-page"),
			Map.entry("penalties", "hr-page"),
			Map.entry("phone-countries", "hr-page"),
			Map.entry("requests", "hr-page"),
			Map.entry("salary-calculator", "payroll-page org-page-salary-calculator"),
			Map.entry("settings", "settings-hub-page hr-page"),
			Map.entry("shifts", "hr-page"),
			Map.entry("workforce-planning", "hr-page"));

	/** Pages with no legacy counterpart, so no legacy wrapper to match. */
	private static final Set<String> NO_LEGACY_PAGE = Set.of("company-delete", "sessions", "devices");

	@Test
	void everyPageWrapsItsContentInLegacysClasses() throws IOException {
		Matcher fallback = Pattern.compile("@param String contentClass = \"([^\"]*)\"")
				.matcher(Files.readString(TEMPLATES.resolve("layout.jte"), StandardCharsets.UTF_8));
		assertThat(fallback.find()).as("layout.jte declares contentClass with a default").isTrue();
		Pattern passed = Pattern.compile("contentClass = \"([^\"]*)\"");
		List<String> wrong = new ArrayList<>();
		List<String> unmapped = new ArrayList<>();
		for (Path template : pageTemplates()) {
			String name = fileName(template);
			if (NO_LEGACY_PAGE.contains(name)) {
				continue;
			}
			if (!LEGACY_CONTENT_CLASS.containsKey(name)) {
				unmapped.add(name);
				continue;
			}
			Matcher own = passed.matcher(Files.readString(template, StandardCharsets.UTF_8));
			String actual = own.find() ? own.group(1) : fallback.group(1);
			if (!actual.equals(LEGACY_CONTENT_CLASS.get(name))) {
				wrong.add(name + ": \"" + actual + "\", legacy \"" + LEGACY_CONTENT_CLASS.get(name) + "\"");
			}
		}
		assertThat(unmapped)
				.as("a new page needs its legacy wrapper classes here, or a place in NO_LEGACY_PAGE")
				.isEmpty();
		assertThat(wrong).as("pages whose .content classes differ from legacy's").isEmpty();
	}

	/**
	 * A window the server renders already open never hides its Cancel behind
	 * the actions switch.
	 *
	 * <p>{@code app.platform-admin.actions.enabled} ships <b>false</b>, so the
	 * gated path is the normal one, not an edge case. It matters only for the
	 * windows written {@code modal-bg open}: those are on screen because the
	 * URL put them there ({@code ?action=edit}, {@code ?action=qr}), and the
	 * link that puts them there is not itself gated, so a reader reaches them
	 * with the switch off. They are full-viewport overlays that legacy gives no
	 * close button, which leaves Cancel as the only way out that announces
	 * itself -- {@code crud.js} closes on the backdrop and {@code modal-a11y.js}
	 * on Escape, but nothing on screen says so.
	 *
	 * <p>The row dialogs and add modals are deliberately not covered: they are
	 * written {@code modal-bg} with no {@code open}, and the control that opens
	 * them is itself behind the same gate, so with the switch off they never
	 * appear at all and a gated Cancel inside them cannot strand anyone.
	 *
	 * <p>#305's review round 1 found the branch QR window gating its footer
	 * where {@code branch-form.jte} beside it had always kept Cancel outside
	 * the gate. The rule is written here rather than on that one window so the
	 * next one cannot repeat it.
	 *
	 * <p>It asserts two things, because the second without the first is
	 * vacuous: every one of these windows <b>has</b> a Cancel, and none of them
	 * puts it behind the gate. Checking only the second would let the control
	 * be deleted outright, which is the same defect by a shorter route.
	 */
	@Test
	void aServerOpenedWindowsCancelIsNeverGatedByTheActionsSwitch() throws IOException {
		List<String> gated = new ArrayList<>();
		List<String> without = new ArrayList<>();
		int windows = 0;
		try (var paths = Files.list(TEMPLATES)) {
			for (Path path : paths.sorted().toList()) {
				if (!path.toString().endsWith(".jte")) {
					continue;
				}
				String source = TemplateText.withoutComments(Files.readString(path, StandardCharsets.UTF_8));
				for (int at : serverOpenedWindows(source, path)) {
					if (insideAGate(source, at, path)) {
						continue;
					}
					windows++;
					String window = source.substring(at, endOfWindow(source, at, path));
					// A window with no Cancel at all would satisfy the loop below vacuously,
					// so deleting one would pass the gate that exists to keep it (Codex, on
					// this pull request). Both halves are asserted.
					if (!window.contains(CANCEL)) {
						without.add(fileName(path));
					}
					if (cancelIsBehindTheSwitch(window, path)) {
						gated.add(fileName(path));
					}
				}
			}
		}
		assertThat(windows).as("the windows the server renders already open").isGreaterThanOrEqualTo(9);
		assertThat(without).as("windows on screen with no Cancel at all").isEmpty();
		assertThat(gated).as("windows on screen with the switch off whose Cancel is not")
				.isEmpty();
	}

	/**
	 * A window the server renders already open says it is a dialog, or is named
	 * here as one that does not yet.
	 *
	 * <p>{@code rowDialog.jte:40} is the port's shape -- {@code role="dialog"},
	 * {@code aria-modal="true"} and an {@code aria-labelledby} pointing at the
	 * window's own heading. Legacy carries none of it, so this is the port's
	 * addition rather than a parity item, and without it a screen reader is
	 * handed ordinary page content underneath an overlay it cannot see.
	 *
	 * <p>{@link #NO_DIALOG_SEMANTICS} is a list rather than a count, so that
	 * fixing one is a visible deletion from this file and a new window cannot
	 * quietly join them. Every entry predates #305, which added the semantics to
	 * the window it converted and left the rest to their own pages' changes
	 * (#306).
	 */
	@Test
	void aServerOpenedWindowSaysItIsADialog() throws IOException {
		List<String> missing = new ArrayList<>();
		try (var paths = Files.list(TEMPLATES)) {
			for (Path path : paths.sorted().toList()) {
				if (!path.toString().endsWith(".jte")) {
					continue;
				}
				String source = TemplateText.withoutComments(Files.readString(path, StandardCharsets.UTF_8));
				for (int at : serverOpenedWindows(source, path)) {
					String window = source.substring(at, endOfWindow(source, at, path));
					if (!window.contains("role=\"dialog\"") || !window.contains("aria-modal=\"true\"")
							|| !window.contains("aria-labelledby=")) {
						missing.add(fileName(path));
					}
				}
			}
		}
		assertThat(missing).as("windows the server opens with no dialog semantics")
				.containsExactlyInAnyOrderElementsOf(NO_DIALOG_SEMANTICS);
	}

	/** Server-opened windows that predate the rule; shrink it, never grow it (#306). */
	private static final List<String> NO_DIALOG_SEMANTICS = List.of(
			"administrative-decisions", "branch-form", "departments", "employees",
			"job-titles", "payroll", "shifts", "workforce-planning");

	/**
	 * The shapes the scan must and must not enrol, which the templates cannot ask
	 * it: every window written today is a plain {@code modal-bg open} or the built
	 * form, so a scan that went back to matching substrings would stay green while
	 * enrolling a dropdown that happens to be open (#305's review round 4).
	 */
	@Test
	void theWindowScanReadsWholeClassTokens() {
		Path synthetic = Path.of("synthetic.jte");
		assertThat(serverOpenedWindows("<div class=\"modal-bg open\">", synthetic))
				.as("written open").hasSize(1);
		assertThat(serverOpenedWindows("<div class=\"modal-bg${formOpen ? \" open\" : \"\"}\">", synthetic))
				.as("built open, which companies.jte and banners.jte write").hasSize(1);
		assertThat(serverOpenedWindows("<div class=\"modal-bg-open\">", synthetic))
				.as("one hyphenated class, not two").isEmpty();
		assertThat(serverOpenedWindows("<div class=\"modal-bg dropdown-open\">", synthetic))
				.as("a dropdown that is open, inside a window that is not").isEmpty();
		assertThat(serverOpenedWindows("<div data-open-label=\"x\" class=\"modal-bg\">", synthetic))
				.as("open in another attribute entirely").isEmpty();
		assertThat(serverOpenedWindows("<div class=\"modal-bg${row.open() ? 'a' : 'b'}\">", synthetic))
				.as("`open` as a method name in the expression, which renders neither a nor b as it")
				.isEmpty();
		assertThat(serverOpenedWindows("<div class=\"modal-bg${row.wide() ? \" open\" : \"\"}\">", synthetic))
				.as("the same shape where the expression's own string does render it").hasSize(1);
		// The silent direction, kept deliberately and stated so it is not mistaken for an
		// oversight: what `${state}` renders is unknown, so a window built that way is not
		// enrolled. No template writes one; the alternative is enrolling every dynamic class.
		assertThat(serverOpenedWindows("<div class=\"modal-bg ${state}\">", synthetic))
				.as("a class whose second token is wholly unknown").isEmpty();
	}

	/**
	 * Where each window that can be on screen when the page loads begins.
	 *
	 * <p>Its class holds {@code modal-bg} and can render {@code open}: written
	 * literally, or built ({@code class="modal-bg${formOpen ? " open" : ""}"},
	 * which {@code companies.jte} and {@code banners.jte} do). The class is read
	 * by {@link TemplateText#rawClassOf(String, Object)}, so an expression's own quotes do
	 * not cut the value in half and {@code open} in some other attribute does not
	 * enrol a window the server never opens -- both of which a fixed prefix and a
	 * naive attribute pattern got wrong in turn (#305's rounds 2 and 3).
	 */
	private static List<Integer> serverOpenedWindows(String source, Path path) {
		List<Integer> starts = new ArrayList<>();
		Matcher tag = DIV.matcher(source);
		while (tag.find()) {
			// The raw class, expressions included: this asks what the class *could* be,
			// not what it certainly is, because a window built as
			// `class="modal-bg${formOpen ? " open" : ""}"` is one of these.
			// The class as text that can reach the browser: an expression's string literals,
			// not its code, so a method named `open()` in it does not enrol a window (round 5).
			String classes = TemplateText.possibleClassText(TemplateText.rawClassOf(tag.group(), path));
			// Whole class tokens: `modal-bg-open` and `dropdown-open` are not this window.
			if (CLASS_MODAL_BG.matcher(classes).find() && CLASS_OPEN.matcher(classes).find()) {
				starts.add(tag.start());
			}
		}
		return starts;
	}

	private static final Pattern DIV = Pattern.compile("(?s)<div\\b[^>]*>");

	private static final Pattern CLASS_MODAL_BG = Pattern.compile("(?<![\\w-])modal-bg(?![\\w-])");

	private static final Pattern CLASS_OPEN = Pattern.compile("(?<![\\w-])open(?![\\w-])");

	/**
	 * Whether the whole window sits inside a gate that removes it, rather than
	 * rendering with the switch off. {@code banners.jte} writes its window
	 * inside {@code @if(canWrite)}: with the switch off there is no window at
	 * all, so it cannot strand anyone and neither rule applies to it.
	 *
	 * <p>Narrow is the safe direction here, which is why this reads only a
	 * condition that <b>is</b> the switch, where
	 * {@link #cancelIsBehindTheSwitch} reads any condition that names it.
	 * Skipping is the permissive direction -- a window skipped is a window no
	 * rule looks at -- so a condition like {@code @if(canWrite && somethingElse)}
	 * does not skip it: with the switch off that window may still render.
	 */
	private static boolean insideAGate(String source, int at, Path path) {
		for (String gate : List.of("@if(canWrite)", "@if(actionsEnabled)")) {
			for (int start = source.indexOf(gate); start >= 0; start = source.indexOf(gate, start + 1)) {
				int end = endOfBlock(source, start, path);
				// Only the gate's own arm. A window in the `@else` renders exactly when the
				// switch is OFF, which is the case these rules exist for, so skipping it
				// would be the one direction that hides a defect (#305's review round 3).
				if (start < at && armEnd(source, start, end) > at) {
					return true;
				}
			}
		}
		return false;
	}

	/** The {@code @if} nesting depth at {@code upTo}, counting from {@code start}. */
	private static int depthAt(String source, int start, int upTo) {
		int depth = 0;
		Matcher tag = Pattern.compile("@if\\(|@endif\\b").matcher(source).region(start, upTo);
		while (tag.find()) {
			depth += tag.group().equals("@endif") ? -1 : 1;
		}
		return depth;
	}

	/**
	 * The conditions this rule must read as a gate and the ones it must not,
	 * which the templates cannot ask it: every window written today gates with a
	 * bare {@code @if(canWrite)} or nothing at all, so each of the four earlier
	 * forms of this rule was green against the whole tree while blind to a
	 * spelling a reviewer then wrote by hand (#305's rounds 1 to 5).
	 *
	 * <p>The two false directions are both here: a Cancel written in the gate's
	 * {@code @else} arm renders exactly when the switch is off and must not be
	 * reported, and a Cancel in the arm of a negated condition renders only when
	 * it is on and must be.
	 */
	@Test
	void aCancelBehindTheSwitchIsFoundHoweverTheConditionIsWritten() {
		Path synthetic = Path.of("synthetic.jte");
		String cancel = "<a class=\"btn\">${" + CANCEL + "}</a>";
		assertThat(cancelIsBehindTheSwitch("@if(canWrite)" + cancel + "@endif", synthetic))
				.as("the plain write gate").isTrue();
		assertThat(cancelIsBehindTheSwitch("@if(actionsEnabled)" + cancel + "@endif", synthetic))
				.as("the switch by its other name").isTrue();
		assertThat(cancelIsBehindTheSwitch("@if(canWrite && qrRow != null)" + cancel + "@endif", synthetic))
				.as("the switch and something else, which four templates write").isTrue();
		assertThat(cancelIsBehindTheSwitch("@if(row.ready() && actionsEnabled)" + cancel + "@endif", synthetic))
				.as("the switch written second").isTrue();
		assertThat(cancelIsBehindTheSwitch("@if(!canWrite)<span>x</span>@else" + cancel + "@endif", synthetic))
				.as("negated, so the else arm is the one that needs the switch on").isTrue();
		assertThat(cancelIsBehindTheSwitch("@if(canWrite)<span>x</span>@else" + cancel + "@endif", synthetic))
				.as("the correct shape: Cancel exactly when the switch is off").isFalse();
		assertThat(cancelIsBehindTheSwitch("@if(!canWrite)" + cancel + "@endif", synthetic))
				.as("negated, Cancel in the arm that renders with the switch off").isFalse();
		assertThat(cancelIsBehindTheSwitch("@if(row.editable())" + cancel + "@endif", synthetic))
				.as("a condition that is not about the switch at all").isFalse();
		assertThat(cancelIsBehindTheSwitch("@if(canWrite)<span>x</span>@endif" + cancel, synthetic))
				.as("outside the gate, where every window keeps it").isFalse();
		assertThat(cancelIsBehindTheSwitch(
				"@if(canWrite)@if(row.wide())<span>a</span>@else<span>b</span>@endif@endif" + cancel, synthetic))
				.as("a nested @else inside the gate does not end its arm early").isFalse();
		assertThatThrownBy(() -> cancelIsBehindTheSwitch(
				"@if(canWrite && !actionsEnabled)" + cancel + "@endif", synthetic))
				.as("a condition naming the switch both ways is not guessed at")
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("synthetic.jte")
				.hasMessageContaining("both ways");
	}

	/**
	 * Whether this window's Cancel renders only when the actions switch is on.
	 *
	 * <p>Every {@code @if} in the window is read by its <b>condition</b>, not by
	 * the text it is written with. Rounds 1 to 5 each found this rule green for a
	 * Cancel that was gated: the control absent entirely, the window invisible,
	 * a comment read as markup, {@code @if(actionsEnabled)} unknown to the scan,
	 * and then {@code @if(canWrite && ...)} -- four spellings of one idea, each
	 * added to a literal list after a reviewer found it. A condition that names
	 * the switch is a gate whatever else it says, so a fifth spelling is covered
	 * without being enumerated.
	 *
	 * <p>Polarity decides <em>which arm</em> is the gated one, and both directions
	 * are real: under {@code @if(canWrite)} the then-arm needs the switch on, and
	 * under {@code @if(!canWrite)} it is the {@code @else} arm that does. A
	 * condition naming the switch both ways cannot be read here and fails loudly
	 * rather than being guessed at.
	 *
	 * <p>Broad is the safe direction here, and it is the opposite of
	 * {@link #insideAGate}'s, which skips a window entirely and so reads only a
	 * condition that <em>is</em> the switch. They no longer share a list.
	 */
	private static boolean cancelIsBehindTheSwitch(String window, Path path) {
		for (Matcher opening = IF.matcher(window); opening.find(); ) {
			String condition = conditionAt(window, opening.end() - 1, path);
			if (!SWITCH.matcher(condition).find()) {
				continue;
			}
			int block = opening.start();
			int end = endOfBlock(window, block, path);
			int otherwise = armEnd(window, block, end);
			// The arm that renders only with the switch on: the `@if` arm for a condition
			// that names the switch plainly, the `@else` arm for one that negates it.
			String gatedArm = negatesTheSwitch(condition, path)
					? window.substring(Math.min(otherwise, end), end)
					: window.substring(block, otherwise);
			if (gatedArm.contains(CANCEL)) {
				return true;
			}
		}
		return false;
	}

	/** Where {@code @if}'s own arm ends: its {@code @else} if it has one, else the whole block. */
	private static int armEnd(String source, int block, int end) {
		for (int otherwise = source.indexOf("@else", block); otherwise > 0 && otherwise < end;
				otherwise = source.indexOf("@else", otherwise + 1)) {
			// The block's OWN else, which is the first at depth 1: an `@else` belonging to an
			// `@if` nested inside it sits deeper, and taking that one ends the arm early.
			if (depthAt(source, block, otherwise) == 1) {
				return otherwise;
			}
		}
		return end;
	}

	/**
	 * The condition of the {@code @if} whose opening parenthesis is at {@code open},
	 * with its own parentheses balanced.
	 */
	private static String conditionAt(String source, int open, Path path) {
		int depth = 0;
		for (int at = open; at < source.length(); at++) {
			char character = source.charAt(at);
			depth += character == '(' ? 1 : character == ')' ? -1 : 0;
			if (depth == 0) {
				return source.substring(open + 1, at);
			}
		}
		throw new AssertionError(path.getFileName() + ": an @if condition that never closes at " + open);
	}

	/** Whether a condition names the switch only negated, so its {@code @else} is the gated arm. */
	private static boolean negatesTheSwitch(String condition, Path path) {
		boolean negated = false;
		boolean plain = false;
		for (Matcher name = SWITCH.matcher(condition); name.find(); ) {
			String before = condition.substring(0, name.start()).stripTrailing();
			boolean thisOne = before.endsWith("!");
			negated |= thisOne;
			plain |= !thisOne;
		}
		if (negated && plain) {
			// Which arm needs the switch on is then a question about the whole expression,
			// which this does not evaluate. No template writes one; if one does, it says so.
			throw new AssertionError(path.getFileName()
					+ ": a condition that names the actions switch both ways: " + condition);
		}
		return negated;
	}

	/**
	 * An {@code @if}'s opening, written exactly as {@link #endOfBlock} and
	 * {@link #depthAt} count it. No template writes {@code @if (} with a space, and a
	 * pattern here that accepted one would find a block those two would not count.
	 */
	private static final Pattern IF = Pattern.compile("@if\\(");

	/** The switch, by either name a template gives it. `canWrite` is `canManage && actionsEnabled`. */
	private static final Pattern SWITCH = Pattern.compile("\\b(canWrite|actionsEnabled)\\b");

	private static final String CANCEL = "t.apply(\"cancel\")";

	/** Where the window opening at {@code start} closes, by {@code <div>} depth. */
	private static int endOfWindow(String source, int start, Path path) {
		Matcher tag = Pattern.compile("<div\\b|</div>").matcher(source).region(start, source.length());
		int depth = 0;
		while (tag.find()) {
			depth += tag.group().equals("</div>") ? -1 : 1;
			if (depth == 0) {
				return tag.end();
			}
		}
		throw new AssertionError(path.getFileName() + ": a modal-bg that never closes at " + start);
	}

	/**
	 * Where the {@code @if} opening at {@code start} closes, counting the
	 * {@code @if}s nested inside it. A block that never closes fails here
	 * rather than reading as an empty one.
	 */
	private static int endOfBlock(String source, int start, Path path) {
		Matcher tag = Pattern.compile("@if\\(|@endif\\b").matcher(source).region(start, source.length());
		int depth = 0;
		while (tag.find()) {
			depth += tag.group().equals("@endif") ? -1 : 1;
			if (depth == 0) {
				return tag.end();
			}
		}
		throw new AssertionError(path.getFileName() + ": an @if that never closes at " + start);
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
