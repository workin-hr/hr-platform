package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * That the shell's responsive half is actually wired (<b>D-208</b>).
 *
 * <p>Every failure this covers is silent. A dropped {@code <script>} leaves a
 * hamburger that renders and does nothing. A renamed sidebar id leaves
 * {@code aria-controls} pointing at no element, which a sighted reviewer cannot
 * see at all. And the drawer's breakpoint is written twice -- once as a media
 * query in the stylesheet, once as a {@code matchMedia} query in the script --
 * so the two can drift into a window where the sidebar is a drawer that the
 * script still believes is a column.
 *
 * <p>The same shape as {@link AdminLayoutWiringTest}, and for the same reason:
 * the pages' own tests assert on what a page says, and none of this is
 * something a page says.
 */
class AdminResponsiveShellTest {

	private static final Path LAYOUT = Path.of("src/main/jte/admin/layout.jte");

	private static final Path SIDEBAR = Path.of("src/main/jte/admin/sidebar.jte");

	private static final Path ASSETS = Path.of("src/main/resources/static/admin/_assets");

	@Test
	void theShellLinksTheResponsiveStylesheetAndTheDrawerScript() throws IOException {
		String layout = read(LAYOUT);
		for (String asset : new String[] { "app-responsive.css", "nav-drawer.js" }) {
			assertThat(layout)
					.as("layout.jte should link %s -- without it the drawer is a "
							+ "button that does nothing on every page", asset)
					.contains("/admin/_assets/" + asset);
			assertThat(ASSETS.resolve(asset))
					.as("%s is linked but missing; the request would 404 in silence", asset)
					.exists();
		}
	}

	@Test
	void theToggleNamesTheElementItOpens() throws IOException {
		String layout = read(LAYOUT);
		Matcher controls = Pattern.compile("aria-controls=\"([a-z-]+)\"").matcher(layout);
		assertThat(controls.find()).as("the toggle should declare aria-controls").isTrue();

		assertThat(read(SIDEBAR))
				.as("aria-controls points at '%s', which no element carries -- the "
						+ "button still works, and assistive technology loses the "
						+ "relationship", controls.group(1))
				.contains("id=\"" + controls.group(1) + "\"");
	}

	@Test
	void theSkipLinkPointsAtTheMainRegion() throws IOException {
		String layout = read(LAYOUT);
		Matcher target = Pattern.compile("class=\"skip-link\" href=\"#([a-z-]+)\"").matcher(layout);
		assertThat(target.find()).as("the shell should carry a skip link").isTrue();
		assertThat(layout)
				.as("the skip link jumps to '#%s', which nothing declares -- it would "
						+ "move focus nowhere", target.group(1))
				.contains("id=\"" + target.group(1) + "\"");
	}

	@Test
	void theStylesheetAndTheScriptAgreeOnWhereTheDrawerBegins() throws IOException {
		String css = read(ASSETS.resolve("app-responsive.css"));
		String js = read(ASSETS.resolve("nav-drawer.js"));

		// The stylesheet's drawer block is the one that positions the sidebar
		// as fixed; other breakpoints in the file tune padding and columns.
		Matcher sheet = Pattern.compile(
				"@media \\(max-width: (\\d+)px\\) \\{[^@]*?\\.sidebar \\{[^}]*?position: fixed",
				Pattern.DOTALL).matcher(css);
		assertThat(sheet.find()).as("app-responsive.css should turn the sidebar into a "
				+ "drawer inside a max-width query").isTrue();

		Matcher script = Pattern.compile("matchMedia\\('\\(max-width: (\\d+)px\\)'\\)").matcher(js);
		assertThat(script.find()).as("nav-drawer.js should ask matchMedia for that width "
				+ "rather than measuring the window").isTrue();

		assertThat(script.group(1))
				.as("the script watches %spx and the stylesheet switches at %spx -- "
						+ "between them the sidebar is a drawer the script treats as a "
						+ "column, so following a link leaves it open over the page",
						script.group(1), sheet.group(1))
				.isEqualTo(sheet.group(1));
	}

	private static String read(Path path) throws IOException {
		return Files.readString(path, StandardCharsets.UTF_8);
	}
}
