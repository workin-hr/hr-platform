package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The admin dashboard's colour vocabulary lives in one file, and these are the
 * properties that keep it there (D-284).
 *
 * <p>Before the design system there were <b>154 distinct colour literals across
 * 441 occurrences in 17 sheets</b>: three grey ramps running in parallel -- warm
 * ({@code #e8e6e0}), cool ({@code #e2e6ef}) and a Tailwind-ish one
 * ({@code #6b7280}) -- and five blues that all meant "the product's blue". None
 * of that was anybody's decision; it accumulated, because nothing said it could
 * not. That is what this class says.
 *
 * <p>It is four rules, and each exists because the conversion actually tripped
 * over it:
 *
 * <ol>
 * <li><b>No literal outside the token sheet.</b> The obvious one, and the only
 * one that would have been guessed.</li>
 * <li><b>No token used against its role</b> -- a text token as a background, or
 * a surface token as a colour. Mapping 154 literals by value silently turned the
 * home banner near-black, because {@code #1e293b} was a dark <em>surface</em> and
 * the map read it as text that happens to be dark. The sidebar went the same way.
 * A picture would not have found it; this rule did.</li>
 * <li><b>Every token used is defined.</b> A {@code var(--ui-typo)} resolves to
 * nothing and paints transparent or black, which in a dark-on-light dashboard
 * often looks like a deliberate choice rather than a mistake.</li>
 * <li><b>Every semantic token has a dark value.</b> D-284 says defining the dark
 * palette without shipping a toggle is what keeps it honest. That sentence is
 * only true if something checks it, or the first token added after this will be
 * the one that breaks the day a toggle lands.</li>
 * </ol>
 */
class AdminDesignTokensTest {

	private static final Path ASSETS = Path.of("src/main/resources/static/admin/_assets");

	private static final String TOKEN_SHEET = "app-tokens.css";

	/**
	 * The one colour literal that may live outside the token sheet.
	 *
	 * <p>{@code login.css}'s {@code mask-image: linear-gradient(180deg, #000 0%,
	 * transparent 85%)}. A mask reads only the alpha channel, so the {@code #000}
	 * is "opaque here", not a colour -- giving it a colour token would be a
	 * category error, and re-theming it would mean nothing.
	 */
	private static final Map<String, String> LITERALS_THAT_ARE_NOT_COLOURS = Map.of(
			"login.css#000", "a mask-image gradient stop: alpha, not colour");

	private static final Pattern COLOUR = Pattern.compile("#([0-9a-fA-F]{3,8})\\b");

	private static final Pattern DECLARATION =
			Pattern.compile("(?m)([a-z-]+)\\s*:\\s*([^;{}]+)");

	private static final Pattern TOKEN_USE = Pattern.compile("var\\(\\s*(--ui-[\\w-]+)");

	private static final Pattern TOKEN_DEFINITION = Pattern.compile("(?m)^\\s*(--ui-[\\w-]+)\\s*:");

	/** Tokens that name a text role; painting a surface with one is the mismatch. */
	private static final Set<String> TEXT_ROLES = Set.of(
			"--ui-text", "--ui-text-soft", "--ui-text-muted", "--ui-text-faint",
			"--ui-nav-text", "--ui-nav-text-muted", "--ui-nav-text-faint");

	/** Tokens that name a surface role; using one as a text colour is the mirror. */
	private static final Set<String> SURFACE_ROLES = Set.of(
			"--ui-bg", "--ui-surface-sunk", "--ui-surface-hover", "--ui-surface-raised",
			"--ui-nav-bg", "--ui-nav-bg-raised", "--ui-nav-bg-hover", "--ui-hero-bg");

	@Test
	void everyColourInTheAdminSheetsComesFromTheTokenSheet() throws IOException {
		List<String> literals = new ArrayList<>();
		Set<String> exemptionsUsed = new LinkedHashSet<>();
		int sheets = 0;
		for (Path sheet : sheets()) {
			String name = sheet.getFileName().toString();
			if (TOKEN_SHEET.equals(name)) {
				continue;
			}
			sheets++;
			Matcher colour = COLOUR.matcher(Files.readString(sheet, StandardCharsets.UTF_8));
			while (colour.find()) {
				String key = name + "#" + colour.group(1).toLowerCase(Locale.ROOT);
				if (LITERALS_THAT_ARE_NOT_COLOURS.containsKey(key)) {
					exemptionsUsed.add(key);
					continue;
				}
				literals.add(key + " -- give it a token in " + TOKEN_SHEET);
			}
		}
		assertThat(sheets)
				.as("the sheets checked; a glob that stopped matching would pass by checking nothing")
				.isGreaterThan(14);
		assertThat(literals).isEmpty();
		assertThat(exemptionsUsed)
				.as("an exemption whose literal is gone is a stale entry to delete, the same way "
						+ "every other exemption in this repository self-polices")
				.containsExactlyInAnyOrderElementsOf(LITERALS_THAT_ARE_NOT_COLOURS.keySet());
	}

	@Test
	void noTokenIsUsedAgainstTheRoleItsNameClaims() throws IOException {
		List<String> mismatches = new ArrayList<>();
		for (Path sheet : sheets()) {
			String name = sheet.getFileName().toString();
			String source = Files.readString(sheet, StandardCharsets.UTF_8);
			Matcher declaration = DECLARATION.matcher(source);
			while (declaration.find()) {
				String property = declaration.group(1);
				boolean paintsSurface = "background".equals(property)
						|| "background-color".equals(property);
				boolean paintsText = "color".equals(property);
				if (!paintsSurface && !paintsText) {
					continue;
				}
				Matcher used = TOKEN_USE.matcher(declaration.group(2));
				while (used.find()) {
					String token = used.group(1);
					if (paintsSurface && TEXT_ROLES.contains(token)) {
						mismatches.add(name + ": " + property + " painted with " + token
								+ ", which names a text role");
					}
					if (paintsText && SURFACE_ROLES.contains(token)) {
						mismatches.add(name + ": color set from " + token
								+ ", which names a surface role");
					}
				}
			}
		}
		assertThat(mismatches)
				.as("the neutral and accent ramps carry no role and may paint anything; these "
						+ "tokens do carry one, and using it the other way is how #1e293b -- a dark "
						+ "surface -- became the text colour and turned the home banner near-black")
				.isEmpty();
	}

	@Test
	void everyTokenUsedIsDefinedAndEveryTokenDefinedIsUsed() throws IOException {
		Set<String> defined = new TreeSet<>();
		Matcher definition = TOKEN_DEFINITION.matcher(
				Files.readString(ASSETS.resolve(TOKEN_SHEET), StandardCharsets.UTF_8));
		while (definition.find()) {
			defined.add(definition.group(1));
		}
		Set<String> used = new TreeSet<>();
		for (Path sheet : sheets()) {
			Matcher use = TOKEN_USE.matcher(Files.readString(sheet, StandardCharsets.UTF_8));
			while (use.find()) {
				used.add(use.group(1));
			}
		}
		assertThat(defined).as("the token sheet should define a vocabulary").hasSizeGreaterThan(60);
		Set<String> undefined = new TreeSet<>(used);
		undefined.removeAll(defined);
		assertThat(undefined)
				.as("var() on an undefined token paints transparent or black, which on a light "
						+ "dashboard reads as a choice rather than a typo")
				.isEmpty();
		Set<String> unused = new TreeSet<>(defined);
		unused.removeAll(used);
		assertThat(unused)
				.as("a token nothing reads is a decision nothing implements; delete it or use it")
				.isEmpty();
	}

	@Test
	void everySemanticTokenHasADarkValue() throws IOException {
		String tokens = Files.readString(ASSETS.resolve(TOKEN_SHEET), StandardCharsets.UTF_8);
		// The rule, not the sentence above it that names the selector: the file's
		// own header explains what the dark block is for, and matching that made
		// every token in the file look like a dark-only orphan.
		Matcher darkRule = Pattern.compile(":root\\[data-theme=\"dark\"\\]\\s*\\{").matcher(tokens);
		assertThat(darkRule.find()).as("the dark block exists").isTrue();
		int dark = darkRule.start();
		Map<String, String> light = valuesIn(tokens.substring(0, dark));
		Set<String> darkValues = declaredIn(tokens.substring(dark));

		// The ramps and the scales are theme-independent by construction: a step
		// of a neutral ramp is a value, and the semantic tokens above choose which
		// step each theme points at. Sizes, spacing and motion carry no colour.
		List<String> missing = new ArrayList<>();
		Set<String> exemptionsUsed = new LinkedHashSet<>();
		for (Map.Entry<String, String> token : light.entrySet()) {
			if (!needsADarkValue(token.getKey(), token.getValue())) {
				continue;
			}
			if (CORRECT_IN_BOTH_THEMES.containsKey(token.getKey())) {
				exemptionsUsed.add(token.getKey());
				continue;
			}
			if (!darkValues.contains(token.getKey())) {
				missing.add(token.getKey());
			}
		}
		assertThat(missing)
				.as("D-284 says defining the dark palette without shipping a toggle is what keeps "
						+ "it honest; a semantic token with no dark value renders as a light colour "
						+ "on a dark surface the day a toggle lands, which is the failure that "
						+ "sentence promises cannot happen")
				.isEmpty();

		assertThat(exemptionsUsed)
				.as("an exemption for a token that is no longer a colour, or that has since gained "
						+ "a dark value, is a stale entry to delete")
				.containsExactlyInAnyOrderElementsOf(CORRECT_IN_BOTH_THEMES.keySet());

		Set<String> orphans = new TreeSet<>(darkValues);
		orphans.removeAll(light.keySet());
		assertThat(orphans)
				.as("a dark value for a token the light theme does not define is a token nothing "
						+ "reads in either theme")
				.isEmpty();
	}

	/**
	 * Which tokens owe a dark value: the ones whose <em>value is a colour</em> and
	 * which are not a step of a raw ramp.
	 *
	 * <p>Decided from the value rather than from the name, because the names do not
	 * separate them: {@code --ui-text} is a colour and {@code --ui-text-sm} is a
	 * size, and a prefix rule demanded a dark value for the type scale.
	 *
	 * <p>A ramp step is excluded because a ramp <em>is</em> a set of values; the
	 * semantic tokens above choose which step each theme points at, and that choice
	 * is the thing that has to exist in both.
	 */
	private static boolean needsADarkValue(String token, String value) {
		if (token.matches("--ui-(neutral|accent)-\\d+")) {
			return false;
		}
		return COLOUR_VALUE.matcher(value).find();
	}

	/** A colour literal, an rgb()/rgba(), or a reference to a ramp step. */
	private static final Pattern COLOUR_VALUE = Pattern.compile(
			"#[0-9a-fA-F]{3,8}\\b|\\brgba?\\(|var\\(\\s*--ui-(?:neutral|accent)-\\d+");

	/**
	 * Colour tokens that are deliberately the same in both themes.
	 *
	 * <p>Self-policing like every other exemption here: one that stops being a
	 * colour, or gains a dark value, fails rather than sitting unread.
	 */
	private static final Map<String, String> CORRECT_IN_BOTH_THEMES = Map.ofEntries(
			Map.entry("--ui-text-on-accent",
					"white on a filled accent surface, which is filled in either theme"),
			// The navigation is the one dark surface in a light dashboard. In a
			// dark theme it is still that surface, so its ramp does not invert --
			// it is the only part of the page that already looked the way the rest
			// would.
			Map.entry("--ui-nav-bg", "the navigation is dark in either theme"),
			Map.entry("--ui-nav-bg-raised", "the navigation is dark in either theme"),
			Map.entry("--ui-nav-bg-hover", "the navigation is dark in either theme"),
			Map.entry("--ui-nav-border", "the navigation is dark in either theme"),
			Map.entry("--ui-nav-text", "the navigation is dark in either theme"),
			Map.entry("--ui-nav-text-muted", "the navigation is dark in either theme"),
			Map.entry("--ui-nav-text-faint", "the navigation is dark in either theme"),
			Map.entry("--ui-nav-accent", "the navigation is dark in either theme"),
			Map.entry("--ui-nav-accent-soft", "the navigation is dark in either theme"),
			Map.entry("--ui-nav-danger", "the navigation is dark in either theme"),
			Map.entry("--ui-hero-bg", "a dark brand surface, dark in either theme"));

	private static Map<String, String> valuesIn(String block) {
		Map<String, String> values = new java.util.LinkedHashMap<>();
		Matcher definition = Pattern.compile("(?m)^\\s*(--ui-[\\w-]+)\\s*:\\s*([^;]+);").matcher(block);
		while (definition.find()) {
			values.put(definition.group(1), definition.group(2).trim());
		}
		return values;
	}

	private static Set<String> declaredIn(String block) {
		Set<String> declared = new TreeSet<>();
		Matcher definition = TOKEN_DEFINITION.matcher(block);
		while (definition.find()) {
			declared.add(definition.group(1));
		}
		return declared;
	}

	private static List<Path> sheets() throws IOException {
		try (var files = Files.list(ASSETS)) {
			return files.filter(path -> path.toString().endsWith(".css")).sorted().toList();
		}
	}

}
