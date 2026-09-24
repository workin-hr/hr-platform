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
 * <p>It is seven rules, and each exists because the conversion actually tripped
 * over it -- the last three because a rendered page measured something a
 * reviewed palette had agreed with:
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
 * <li><b>Every text token is readable on every surface</b> -- 4.5:1, both
 * themes, all sixteen pairs. {@code --ui-text-faint} was 3.39:1 on
 * {@code --ui-surface-sunk} and carrying placeholders, and it had been through a
 * palette review.</li>
 * <li><b>The four text steps are four distinct greys.</b> Rule five's fix was to
 * darken the faint step until it cleared 4.5:1, which landed it 1.2 L* from the
 * muted step: readable, and the same grey twice. Readable is not sufficient.</li>
 * <li><b>Every gradient gradates.</b> Five places painted a flat colour as a
 * gradient from itself to itself, one of them behind two token names that
 * resolved to the same value.</li>
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

	/**
	 * Every token that carries text clears WCAG AA against every surface it can
	 * sit on.
	 *
	 * <p>A palette is reviewed by eye and drifts by eye. This one shipped
	 * {@code --ui-text-faint} at {@code #86827a}, which is <b>3.39:1</b> on
	 * {@code --ui-neutral-2} -- below the 4.5 a 12px label needs, and carrying real
	 * content: activity timestamps, list metadata, input placeholders. Nobody saw
	 * it; a rendered page measured it. This is that measurement, moved to where it
	 * cannot be skipped.
	 *
	 * <p>4.5:1 for every pair, not 3:1: these tokens are used at 12 and 13 pixels,
	 * which is never "large text" under the rule, and a token cannot know the size
	 * of the thing that will read it.
	 *
	 * <p>The dark block is checked the same way against its own surfaces, because a
	 * dark theme nobody has looked at is exactly where an unreadable pair survives.
	 */
	@Test
	void everyTextTokenIsReadableOnEverySurface() throws IOException {
		String sheet = Files.readString(ASSETS.resolve(TOKEN_SHEET), StandardCharsets.UTF_8);
		Matcher darkRule = Pattern.compile(":root\\[data-theme=\"dark\"\\]\\s*\\{").matcher(sheet);
		assertThat(darkRule.find()).as("the dark block exists").isTrue();

		List<String> tooClose = new ArrayList<>();
		int pairs = 0;
		for (String theme : List.of("light", "dark")) {
			Map<String, String> resolved = resolve(sheet, theme.equals("dark") ? darkRule.start() : -1);
			for (String text : TEXT_TOKENS) {
				for (String surface : SURFACE_TOKENS) {
					String fg = resolved.get(text);
					String bg = resolved.get(surface);
					if (fg == null || bg == null) {
						continue;
					}
					pairs++;
					double contrast = contrast(fg, bg);
					if (contrast < 4.5) {
						tooClose.add(String.format(
								"%s: %s (%s) on %s (%s) is %.2f:1, needs 4.5",
								theme, text, fg, surface, bg, contrast));
					}
				}
			}
		}
		assertThat(pairs)
				.as("both themes, every text token against every surface; a rename would "
						+ "otherwise make this pass by comparing nothing")
				.isEqualTo(TEXT_TOKENS.size() * SURFACE_TOKENS.size() * 2);
		assertThat(tooClose).isEmpty();
	}

	/**
	 * Four names for text have to be four visibly different greys.
	 *
	 * <p>This rule exists because the contrast rule above is not it, and I found
	 * that out the slow way. Pushing {@code --ui-text-faint} down until it cleared
	 * 4.5:1 landed it on {@code #6f6b62}, one step above {@code --ui-text-muted}'s
	 * {@code #6b6862} -- <b>1.2 L* apart, the same grey twice</b>. Every contrast
	 * assertion passed, because both were readable; what was gone was the reason
	 * to have two tokens. A vocabulary with two words for one value is the disease
	 * this class exists to cure, so readable is not sufficient.
	 *
	 * <p>The separation is measured in L*, not in hex distance: hex distance is
	 * not perceptual, and at the dark end of a ramp a large hex step is a small
	 * visible one. Six is below the ramp's own spacing (the four steps sit 8.7,
	 * 9.6 and 17.0 apart in light, 9.0, 9.7 and 15.6 in dark) and well above the
	 * collision it is here to catch.
	 */
	@Test
	void theFourTextStepsAreFourDistinctGreys() throws IOException {
		String sheet = Files.readString(ASSETS.resolve(TOKEN_SHEET), StandardCharsets.UTF_8);
		Matcher darkRule = Pattern.compile(":root\\[data-theme=\"dark\"\\]\\s*\\{").matcher(sheet);
		assertThat(darkRule.find()).as("the dark block exists").isTrue();

		List<String> collisions = new ArrayList<>();
		int comparisons = 0;
		for (String theme : List.of("light", "dark")) {
			Map<String, String> resolved = resolve(sheet, theme.equals("dark") ? darkRule.start() : -1);
			for (int step = 1; step < TEXT_TOKENS.size(); step++) {
				String lighter = TEXT_TOKENS.get(step);
				String darker = TEXT_TOKENS.get(step - 1);
				double gap = Math.abs(lightness(resolved.get(lighter)) - lightness(resolved.get(darker)));
				comparisons++;
				if (gap < 6.0) {
					collisions.add(String.format(
							"%s: %s (%s) and %s (%s) are %.1f L* apart, needs 6",
							theme, darker, resolved.get(darker), lighter, resolved.get(lighter), gap));
				}
			}
		}
		assertThat(comparisons)
				.as("every consecutive pair in both themes; a rename would otherwise "
						+ "make this pass by comparing nothing")
				.isEqualTo((TEXT_TOKENS.size() - 1) * 2);
		assertThat(collisions).isEmpty();
	}

	/**
	 * A gradient has to gradate.
	 *
	 * <p>Five places across three sheets painted a flat colour as {@code
	 * linear-gradient(135deg, X 0%, X 100%)}: two buttons and the current pager
	 * step in {@code app-ui.css}, the net-pay box in {@code salary-calculator.css},
	 * and the submit button in {@code login.css}. The last one names two different
	 * tokens -- {@code --login-blue} and {@code --login-blue-bright} -- which both
	 * resolved to {@code --ui-accent-500}, and that form is why this is a rule and
	 * not a grep: it reads as a gradient right up until you resolve it. The first
	 * draft of this rule could not see it either, because the resolver it borrowed
	 * followed only {@code --ui-} tokens and skipped any stop it could not reach.
	 *
	 * <p>It is not only cosmetic. A gradient background makes the element's
	 * computed {@code background-color} {@code transparent}, so a flat hover on the
	 * same element wins and the button switches from shaded to flat under the
	 * pointer, and any contrast tooling reads the transparency rather than the
	 * colour a reader sees.
	 *
	 * <p>Stops that are transparent or otherwise not a resolvable opaque colour are
	 * counted and named rather than judged -- a fade to {@code transparent} is a
	 * real gradient -- and the count is asserted so this cannot quietly become a
	 * rule about nothing.
	 */
	@Test
	void everyGradientActuallyGradates() throws IOException {
		Map<String, String> tokens = allTokenDefinitions();
		List<String> flat = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		int measured = 0;
		for (Path sheet : sheets()) {
			String css = Files.readString(sheet, StandardCharsets.UTF_8);
			for (String gradient : gradientsIn(css)) {
				List<Double> steps = new ArrayList<>();
				boolean resolvable = true;
				for (String stop : colourStops(gradient)) {
					double value = lightness(follow(stop, tokens, 0));
					if (value < 0) {
						resolvable = false;
						break;
					}
					steps.add(value);
				}
				if (!resolvable || steps.size() < 2) {
					skipped.add(sheet.getFileName() + ": " + gradient);
					continue;
				}
				measured++;
				double spread = steps.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
						- steps.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
				if (spread < 3.0) {
					flat.add(String.format("%s: %.1f L* of spread in %s",
							sheet.getFileName(), spread, gradient));
				}
			}
		}
		assertThat(flat).isEmpty();
		assertThat(measured)
				.as("gradients whose every stop resolves to an opaque colour -- pinned "
						+ "exactly, because a rule that silently measures nothing is the "
						+ "failure mode here, and because moving one out of reach of the "
						+ "resolver is how a flat gradient would come back")
				.isEqualTo(6);
		assertThat(skipped)
				.as("skipped, because a stop is transparent or the resolver cannot reach "
						+ "it at build time (a var() fallback, a runtime override)")
				.hasSize(10);
	}

	/** The tokens that paint text a reader has to read. */
	private static final List<String> TEXT_TOKENS =
			List.of("--ui-text", "--ui-text-soft", "--ui-text-muted", "--ui-text-faint");

	/** The surfaces that text sits on. */
	private static final List<String> SURFACE_TOKENS =
			List.of("--ui-surface", "--ui-bg", "--ui-surface-sunk", "--ui-surface-hover");

	/**
	 * Token to literal colour for one theme, following {@code var()} references.
	 *
	 * <p>The dark block redefines only what changes, so it is read as an overlay on
	 * the light one -- which is how the browser resolves it.
	 */
	private static Map<String, String> resolve(String sheet, int darkFrom) {
		Map<String, String> values = new java.util.LinkedHashMap<>();
		Matcher definition = Pattern.compile("(?m)^\\s*(--ui-[\\w-]+)\\s*:\\s*([^;]+);").matcher(sheet);
		while (definition.find()) {
			if (darkFrom >= 0 || definition.start() < indexOfDark(sheet)) {
				values.put(definition.group(1), definition.group(2).trim());
			}
		}
		Map<String, String> flat = new java.util.LinkedHashMap<>();
		values.forEach((token, value) -> flat.put(token, follow(value, values, 0)));
		return flat;
	}

	private static int indexOfDark(String sheet) {
		Matcher darkRule = Pattern.compile(":root\\[data-theme=\"dark\"\\]\\s*\\{").matcher(sheet);
		return darkRule.find() ? darkRule.start() : sheet.length();
	}

	private static String follow(String value, Map<String, String> values, int depth) {
		Matcher reference = Pattern.compile("var\\(\\s*(--[\\w-]+)\\s*\\)").matcher(value);
		if (depth > 6 || !reference.find()) {
			return value;
		}
		String target = values.get(reference.group(1));
		return target == null ? value : follow(target, values, depth + 1);
	}

	private static double contrast(String first, String second) {
		double a = luminance(first);
		double b = luminance(second);
		return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
	}

	private static double luminance(String colour) {
		Matcher hex = Pattern.compile("#([0-9a-fA-F]{6})").matcher(colour);
		if (!hex.find()) {
			return -1;
		}
		String value = hex.group(1);
		double[] channel = new double[3];
		for (int index = 0; index < 3; index++) {
			double raw = Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16) / 255.0;
			channel[index] = raw <= 0.03928 ? raw / 12.92 : Math.pow((raw + 0.055) / 1.055, 2.4);
		}
		return 0.2126 * channel[0] + 0.7152 * channel[1] + 0.0722 * channel[2];
	}

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

	/**
	 * CIE L* for a colour, or a negative number when it is not a resolvable opaque
	 * hex.
	 *
	 * <p>Perceptual lightness, not luminance: luminance at the dark end of a ramp
	 * compresses, so two steps that are plainly different to the eye differ by
	 * almost nothing in it, and two that look identical can differ by a lot.
	 */
	private static double lightness(String colour) {
		double relative = colour == null ? -1 : luminance(colour);
		if (relative < 0) {
			return -1;
		}
		return relative > 0.008856 ? 116 * Math.cbrt(relative) - 16 : 903.3 * relative;
	}

	/** Every custom property any sheet defines, light theme, for resolving a stop. */
	private static Map<String, String> allTokenDefinitions() throws IOException {
		Map<String, String> tokens = new java.util.LinkedHashMap<>();
		for (Path sheet : sheets()) {
			String css = Files.readString(sheet, StandardCharsets.UTF_8);
			String light = css.substring(0, indexOfDark(css));
			Matcher definition = Pattern.compile("(?m)^\\s*(--[\\w-]+)\\s*:\\s*([^;]+);").matcher(light);
			while (definition.find()) {
				tokens.putIfAbsent(definition.group(1), definition.group(2).trim());
			}
		}
		return tokens;
	}

	/** The text inside each {@code *-gradient(...)}, parenthesis-balanced. */
	private static List<String> gradientsIn(String css) {
		List<String> gradients = new ArrayList<>();
		Matcher opener = Pattern.compile("(linear|radial|conic)-gradient\\(").matcher(css);
		while (opener.find()) {
			int depth = 1;
			int at = opener.end();
			while (at < css.length() && depth > 0) {
				char character = css.charAt(at);
				depth += character == '(' ? 1 : character == ')' ? -1 : 0;
				at++;
			}
			gradients.add(css.substring(opener.start(), Math.min(at, css.length())));
		}
		return gradients;
	}

	/**
	 * The colour of each stop, in order.
	 *
	 * <p>Arguments are split on commas that are not inside parentheses -- a stop can
	 * be {@code rgba(0, 0, 0, .3)} or {@code var(--x, var(--y))}, both of which
	 * carry their own -- and an argument holding no colour at all is the direction
	 * or shape ({@code 135deg}, {@code to right},
	 * {@code ellipse 90% 70% at 85% 15%}), so it is dropped rather than parsed.
	 */
	private static List<String> colourStops(String gradient) {
		String inside = gradient.substring(gradient.indexOf('(') + 1,
				gradient.endsWith(")") ? gradient.length() - 1 : gradient.length());
		List<String> stops = new ArrayList<>();
		int depth = 0;
		int from = 0;
		for (int at = 0; at <= inside.length(); at++) {
			char character = at == inside.length() ? ',' : inside.charAt(at);
			depth += character == '(' ? 1 : character == ')' ? -1 : 0;
			if (character == ',' && depth == 0) {
				String argument = inside.substring(from, at).trim();
				if (Pattern.compile("var\\(|#[0-9a-fA-F]{3,8}|rgba?\\(|hsla?\\(|transparent|currentColor")
						.matcher(argument).find()) {
					stops.add(argument);
				}
				from = at + 1;
			}
		}
		return stops;
	}

}
