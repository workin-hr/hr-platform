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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The admin dashboard's colour vocabulary lives in one file, and these are the
 * properties that keep it there (D-284).
 *
 * <p>Before the design system there were <b>217 distinct colour literals across
 * 576 occurrences in 18 sheets</b> -- 507 written as hex and 69 in
 * {@code rgba()}/{@code hsl()} form, which is the count this javadoc first gave as
 * "154 across 441 in 17 sheets" from a hex-only sweep of a stale file list:
 * three grey ramps running in parallel -- warm
 * ({@code #e8e6e0}), cool ({@code #e2e6ef}) and a Tailwind-ish one
 * ({@code #6b7280}) -- and five blues that all meant "the product's blue". None
 * of that was anybody's decision; it accumulated, because nothing said it could
 * not. That is what this class says.
 *
 * <p>It is thirteen rules, and each exists because the conversion or a review of
 * it actually tripped over it. The first seven came with the conversion -- the
 * fifth to seventh because a rendered page measured something a reviewed palette
 * had agreed with -- and the other six with its first review round:
 *
 * <ol>
 * <li><b>No literal outside the token sheet.</b> The obvious one, and the only
 * one that would have been guessed.</li>
 * <li><b>No token used against its role</b> -- a text token as a background, or
 * a surface token as a colour. Mapping 217 literals by value silently turned the
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
 * <li><b>Every filled label is readable on its fill</b>, in every state and in
 * whichever sheet wins the cascade.</li>
 * <li><b>A tint is its token at an opacity</b>, so a wash and its glyph cannot
 * drift into two blues.</li>
 * <li><b>The shell scrolls its pane and not the document</b>, which is what every
 * sticky rule depends on.</li>
 * <li><b>Every sticky rule has a scrollport that can scroll</b>, or is listed as
 * inert with the reason.</li>
 * <li><b>No alias is declared without a referrer</b>, in any sheet's
 * {@code :root}.</li>
 * <li><b>No token is declared twice in one block</b>, since CSS ships the last and
 * the reader believes the first.</li>
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

	/**
	 * A colour written out rather than taken from a token.
	 *
	 * <p>Hex <b>and</b> the functional notations, because reading hex alone made this
	 * rule a rule about a spelling. Sixty-six {@code rgba()} literals sat outside the
	 * token sheet while it was green -- forty-two of them chromatic, on five base
	 * colours, of which only three were a token's value. One was Tailwind's
	 * {@code #0f172a} as {@code rgba(15, 23, 42, ...)}: the palette this design system
	 * replaced, still shipping, in a notation the gate could not read. A grep for the
	 * hex found nothing, which is how it survived a conversion that was otherwise
	 * complete.
	 *
	 * <p>{@code rgb(var(--ui-...-rgb) / .12)} is not a literal and must not match: it
	 * is a token carrying an alpha, which is the reason the triples exist. The
	 * lookahead sits before the whitespace: after it, {@code \\s*} backtracked to
	 * zero width so {@code rgb( var(...) / .12)} read as a literal and the message
	 * sent its author to make a token for a token.
	 */
	private static final Pattern COLOUR = Pattern.compile(
			"#([0-9a-fA-F]{3,8})\\b|(?:rgba?|hsla?)\\((?!\\s*var\\()\\s*([^)]*)\\)");

	/**
	 * A colour written as its CSS name.
	 *
	 * <p>The third spelling of a literal, and the one {@link #COLOUR} could not
	 * read: {@code background: white} passed every rule here. Matched in declaration
	 * values only, and as a whole word, so {@code white-space}, {@code --ui-white-rgb}
	 * and {@code .btn-red} are names rather than colours. {@code transparent} and
	 * {@code currentColor} are not on the list: neither is a colour anybody chose.
	 */
	private static final Pattern NAMED_COLOUR = Pattern.compile("(?i)(?<![\\w.#-])(?:"
			+ "aliceblue|antiquewhite|aqua|aquamarine|azure|beige|bisque|black|blanchedalmond|"
			+ "blue|blueviolet|brown|burlywood|cadetblue|chartreuse|chocolate|coral|"
			+ "cornflowerblue|cornsilk|crimson|cyan|darkblue|darkcyan|darkgoldenrod|darkgray|"
			+ "darkgreen|darkgrey|darkkhaki|darkmagenta|darkolivegreen|darkorange|darkorchid|"
			+ "darkred|darksalmon|darkseagreen|darkslateblue|darkslategray|darkslategrey|"
			+ "darkturquoise|darkviolet|deeppink|deepskyblue|dimgray|dimgrey|dodgerblue|"
			+ "firebrick|floralwhite|forestgreen|fuchsia|gainsboro|ghostwhite|gold|goldenrod|"
			+ "gray|green|greenyellow|grey|honeydew|hotpink|indianred|indigo|ivory|khaki|"
			+ "lavender|lavenderblush|lawngreen|lemonchiffon|lightblue|lightcoral|lightcyan|"
			+ "lightgoldenrodyellow|lightgray|lightgreen|lightgrey|lightpink|lightsalmon|"
			+ "lightseagreen|lightskyblue|lightslategray|lightslategrey|lightsteelblue|"
			+ "lightyellow|lime|limegreen|linen|magenta|maroon|mediumaquamarine|mediumblue|"
			+ "mediumorchid|mediumpurple|mediumseagreen|mediumslateblue|mediumspringgreen|"
			+ "mediumturquoise|mediumvioletred|midnightblue|mintcream|mistyrose|moccasin|"
			+ "navajowhite|navy|oldlace|olive|olivedrab|orange|orangered|orchid|palegoldenrod|"
			+ "palegreen|paleturquoise|palevioletred|papayawhip|peachpuff|peru|pink|plum|"
			+ "powderblue|purple|rebeccapurple|red|rosybrown|royalblue|saddlebrown|salmon|"
			+ "sandybrown|seagreen|seashell|sienna|silver|skyblue|slateblue|slategray|"
			+ "slategrey|snow|springgreen|steelblue|tan|teal|thistle|tomato|turquoise|violet|"
			+ "wheat|white|whitesmoke|yellow|yellowgreen)(?![\\w-])");

	/**
	 * A token named for the job of carrying a label.
	 *
	 * <p>One constant, read by the sweep and by the pin that keeps the sweep from
	 * iterating nothing. Two copies of it meant a mutant could empty the sweep while
	 * the pin still counted eight.
	 */
	private static final Pattern FILL_TOKEN = Pattern.compile("--ui-[\\w-]*-fill(-hover)?");

	private static final Pattern DECLARATION =
			Pattern.compile("(?m)([a-z-]+)\\s*:\\s*([^;{}]+)");

	private static final Pattern TOKEN_USE = Pattern.compile("var\\(\\s*(--ui-[\\w-]+)");

	/**
	 * A custom property declared, wherever on its line it starts.
	 *
	 * <p>On a declaration boundary, not on a line start. Both rules that read this
	 * were written {@code (?m)^\\s*--x:}, and the sheets put several declarations on
	 * one line -- {@code --topbar-h: a; --topbar-h: b;} was a duplicate nobody saw.
	 */
	private static final Pattern DECLARED_PROPERTY =
			Pattern.compile("(?m)(?:^|[;{])\\s*(--[\\w-]+)\\s*:\\s*([^;}]*)");

	/** {@code position: sticky}, on a declaration boundary for the same reason. */
	private static final Pattern STICKY =
			Pattern.compile("(?m)(?:^|[;{])\\s*position\\s*:\\s*sticky");

	private static final Pattern TOKEN_DEFINITION = Pattern.compile("(?m)^\\s*(--ui-[\\w-]+)\\s*:");

	/** Tokens that name a text role; painting a surface with one is the mismatch. */
	private static final Set<String> TEXT_ROLES = Set.of(
			"--ui-text", "--ui-text-soft", "--ui-text-muted", "--ui-text-faint",
			"--ui-nav-text", "--ui-nav-text-muted", "--ui-nav-text-faint");

	/**
	 * Tokens that name a surface role; using one as a text colour is the mirror.
	 *
	 * <p>{@code --ui-surface-raised} was in this list and is defined nowhere. A name
	 * that does not exist cannot be misused, so the entry was a rule about nothing --
	 * written from what the palette sounded like rather than from the sheet. The
	 * assertion in the rule below now reads both lists against the token sheet, so the
	 * next invented name fails instead of reassuring.
	 */
	private static final Set<String> SURFACE_ROLES = Set.of(
			"--ui-bg", "--ui-surface-sunk", "--ui-surface-hover",
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
			// Declarations, not prose. A comment cannot paint anything, and an issue
			// reference is spelled exactly like a three-digit hex colour: `#338` in a
			// sentence explaining why a rule was removed was reported as a shipped
			// colour by the first version of this sweep, in three sheets at once.
			Matcher colour = COLOUR.matcher(
					withoutComments(Files.readString(sheet, StandardCharsets.UTF_8)));
			while (colour.find()) {
				String written = colour.group(1) != null
						? "#" + colour.group(1).toLowerCase(Locale.ROOT)
						: colour.group().replaceAll("\\s+", "");
				String key = name + written;
				if (LITERALS_THAT_ARE_NOT_COLOURS.containsKey(key)) {
					exemptionsUsed.add(key);
					continue;
				}
				literals.add(key + " -- give it a token in " + TOKEN_SHEET);
			}
			Matcher declaration = DECLARATION.matcher(
					withoutComments(Files.readString(sheet, StandardCharsets.UTF_8)));
			while (declaration.find()) {
				Matcher named = NAMED_COLOUR.matcher(declaration.group(2));
				while (named.find()) {
					literals.add(name + " " + declaration.group(1) + ": " + named.group()
							+ " -- a colour by name is a literal too; give it a token in "
							+ TOKEN_SHEET);
				}
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

		// The tree is clean, so narrowing COLOUR back to hex-only kills nothing here:
		// a mutant reverting the pattern survived this rule and every other. The
		// pattern is the fix, so the pattern is what needs a subject.
		assertThat(COLOUR.matcher("box-shadow: 0 1px 2px rgba(15, 23, 42, .45);").find())
				.as("the notation sixty-six literals used, and the one a hex-only pattern cannot "
						+ "see. Tailwind's slate-900 shipped in exactly this form")
				.isTrue();
		assertThat(COLOUR.matcher("color: hsl(210 90% 40%);").find())
				.as("and the other functional notation, before somebody reaches for it")
				.isTrue();
		assertThat(COLOUR.matcher("background: rgb(var(--ui-accent-rgb) / .12);").find())
				.as("but a token carrying an alpha is not a literal -- it is the whole reason the "
						+ "triples exist, and matching it would make the fix unusable")
				.isFalse();
		assertThat(COLOUR.matcher("border-color: #185fa5;").find())
				.as("and the hex arm still matches, so widening took nothing away")
				.isTrue();

		assertThat(COLOUR.matcher("background: rgb( var(--ui-accent-rgb) / .12);").find())
				.as("nor is the same token with a space inside the parenthesis")
				.isFalse();
		assertThat(NAMED_COLOUR.matcher("white").find())
				.as("a colour by its name is the spelling the two arms above cannot read")
				.isTrue();
		assertThat(NAMED_COLOUR.matcher("0 0 0 1px Red inset").find())
				.as("in any case, inside a shorthand")
				.isTrue();
		assertThat(NAMED_COLOUR.matcher("nowrap var(--ui-white-rgb) .btn-red transparent "
						+ "currentColor white-space #red").find())
				.as("and not inside a token, a class, a property or a keyword that is no colour")
				.isFalse();

		assertThat(COLOUR.matcher(withoutComments("/* removed as inert; see #338 */")).find())
				.as("an issue number in a comment is not a colour, and three sheets cite one")
				.isFalse();
		assertThat(COLOUR.matcher(withoutComments("a { color: #338; } /* see #338 */")).find())
				.as("but stripping comments must not strip declarations: the same six characters "
						+ "in a value are exactly what this rule is for")
				.isTrue();
	}

	/**
	 * A role token is used for its role, whatever name it is reached under.
	 *
	 * <p><b>Through the alias layer.</b> This read {@code var(--ui-...)} only, and
	 * {@code app-ui.css} declares sixteen {@code --app-*} aliases pointing straight at
	 * {@code --ui-*} tokens -- {@code --app-text}, {@code --app-surface} -- which nine
	 * declarations across two sheets then use for {@code color} and {@code background}.
	 * Repointing {@code --app-surface} at {@code --ui-text} was invisible to this rule
	 * and to every other, so the one indirection the design system deliberately keeps
	 * was the one place the role check did not reach. Every custom property is resolved
	 * to the {@code --ui-} token it ends at before the role is judged, and a
	 * {@code var(x, var(y))} fallback is judged on both arms, since either may be what
	 * paints.
	 *
	 * <p>And the counts are pinned. This rule had no coverage assertion at all: a
	 * {@code DECLARATION} pattern that stopped matching would have made it pass on
	 * nothing, which is the vacuity every other rule in this file guards against.
	 */
	@Test
	void noTokenIsUsedAgainstTheRoleItsNameClaims() throws IOException {
		Map<String, String> definitions = allTokenDefinitions();
		assertThat(definitions.keySet())
				.as("a role list may name only tokens that exist; an invented name is a rule "
						+ "about nothing, which is what --ui-surface-raised was")
				.containsAll(TEXT_ROLES)
				.containsAll(SURFACE_ROLES);

		List<String> mismatches = new ArrayList<>();
		int painted = 0;
		int reachedThroughAnAlias = 0;
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
				painted++;
				Matcher used = Pattern.compile("var\\(\\s*(--[\\w-]+)")
						.matcher(declaration.group(2));
				while (used.find()) {
					String written = used.group(1);
					String token = roleTokenFor(written, definitions);
					if (!written.equals(token)) {
						reachedThroughAnAlias++;
					}
					if (paintsSurface && TEXT_ROLES.contains(token)) {
						mismatches.add(name + ": " + property + " painted with " + written
								+ (written.equals(token) ? "" : " -> " + token)
								+ ", which names a text role");
					}
					if (paintsText && SURFACE_ROLES.contains(token)) {
						mismatches.add(name + ": color set from " + written
								+ (written.equals(token) ? "" : " -> " + token)
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
		assertThat(painted)
				.as("the colour and background declarations examined; this rule shipped with no "
						+ "count at all, so a pattern that stopped matching would have passed it "
						+ "on nothing")
				.isGreaterThan(200);
		assertThat(reachedThroughAnAlias)
				.as("and the ones reached through an alias, which is the half this rule could not "
						+ "see; pinned above zero so removing the resolution fails here rather "
						+ "than silently narrowing the rule back")
				.isGreaterThan(5);
	}

	/**
	 * The {@code --ui-} token a custom property ends at, or the property itself.
	 *
	 * <p>Bounded, because a cycle between two aliases would otherwise not return. Six
	 * hops is more than the one the alias layer actually uses.
	 */
	private static String roleTokenFor(String property, Map<String, String> definitions) {
		String current = property;
		for (int hop = 0; hop < 6; hop++) {
			if (current.startsWith("--ui-")) {
				return current;
			}
			String value = definitions.get(current);
			if (value == null) {
				return current;
			}
			Matcher reference = Pattern.compile("var\\(\\s*(--[\\w-]+)").matcher(value);
			if (!reference.find()) {
				return current;
			}
			current = reference.group(1);
		}
		return current;
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
			// True now, and it was not. This said "white on a filled accent surface,
			// which is filled in either theme", describing the intent; the cascade
			// had the buttons painted from --ui-success and friends, which LIFT in
			// dark so they can be read as text, so the dark theme's filled buttons
			// were white on #8fc95a at 1.97:1 and five more like it. A fill is a
			// separate job from a foreground and now has its own tokens, which are
			// theme-invariant because a filled chip is its own dark surface either
			// way. everyFilledLabelIsReadableOnItsFill is what makes this true.
			Map.entry("--ui-text-on-accent",
					"white on a filled surface, and every fill it is paired with is one of the "
							+ "theme-invariant --ui-*-fill tokens"),
			Map.entry("--ui-accent-fill", "a filled chip is its own dark surface in either theme"),
			Map.entry("--ui-accent-fill-hover", "a filled chip is its own dark surface in either theme"),
			Map.entry("--ui-success-fill", "a filled chip is its own dark surface in either theme"),
			Map.entry("--ui-success-fill-hover", "a filled chip is its own dark surface in either theme"),
			Map.entry("--ui-danger-fill", "a filled chip is its own dark surface in either theme"),
			Map.entry("--ui-danger-fill-hover", "a filled chip is its own dark surface in either theme"),
			Map.entry("--ui-warning-fill", "a filled chip is its own dark surface in either theme"),
			Map.entry("--ui-warning-fill-hover", "a filled chip is its own dark surface in either theme"),
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

	/** Each RGB triple and the hex token whose colour it must be. */
	private static final Map<String, String> TRIPLE_OF = Map.of(
			"--ui-accent-rgb", "--ui-accent-500",
			"--ui-danger-rgb", "--ui-danger",
			"--ui-success-rgb", "--ui-success",
			"--ui-nav-accent-rgb", "--ui-nav-accent",
			"--ui-nav-accent-soft-rgb", "--ui-nav-accent-soft",
			"--ui-ink-rgb", "--ui-neutral-12",
			"--ui-white-rgb", "#ffffff");

	/**
	 * A triple that is deliberately not its token's colour, with the reason.
	 *
	 * <p>Keyed {@code <token>@dark}, because a triple may follow its token in the light
	 * theme and something else in the dark one.
	 */
	private static final Map<String, String> TRIPLE_DIFFERS = Map.of(
			"--ui-ink-rgb@dark",
			"The dark shadows are pure black rather than the warm neutral -- "
					+ "--ui-shadow-sm is `rgba(0, 0, 0, .45)` there -- and the ink follows the "
					+ "shadows it is the base of, not the ramp step it matches in light.");

	/**
	 * A label on a filled surface is readable on it, in both themes.
	 *
	 * <p>The rule {@code --ui-text-on-accent}'s exemption needed. That entry said the
	 * token is "correct in both themes" because the surface under it is filled either
	 * way -- which described the intent. The cascade painted those surfaces from
	 * {@code --ui-success}, {@code --ui-danger} and {@code --ui-warning}, and those
	 * <em>lift</em> in the dark theme so they can be read as text on a dark page. So
	 * the dark theme's filled buttons were white on {@code #8fc95a} at <b>1.97:1</b>,
	 * white on {@code #f08a86} at 2.42, white on {@code #e0a951} at 2.11, and their
	 * three hover states at 1.62, 1.95 and 1.74. Six failures, all below AA, none of
	 * them visible to the readability rule, which pairs text tokens against
	 * <em>surface</em> tokens and never against a status hue.
	 *
	 * <p>One token was doing two jobs. A status as a foreground must lift; a status as
	 * the surface under a white label must not. The fills are their own tokens now, and
	 * they carry no dark override because a filled chip is its own dark surface in
	 * either theme -- which is why white on them is 6.2 to 8.7 to one throughout.
	 *
	 * <p>Read from the sheet, not from a list: every rule that sets a background and a
	 * colour in the same declaration block is measured, so a new filled variant is
	 * checked without anyone remembering to add it.
	 */
	@Test
	void everyFilledLabelIsReadableOnItsFill() throws IOException {
		String tokens = Files.readString(ASSETS.resolve(TOKEN_SHEET), StandardCharsets.UTF_8);
		int dark = indexOfDark(tokens);
		Map<String, String> light = resolve(tokens.substring(0, dark), -1);
		Map<String, String> overlay = new java.util.LinkedHashMap<>(light);
		overlay.putAll(valuesIn(tokens.substring(dark)));
		Map<String, String> darkTheme = new java.util.LinkedHashMap<>();
		overlay.forEach((token, value) -> darkTheme.put(token, follow(value, overlay, 0)));

		List<String> unreadable = new ArrayList<>();
		int measured = 0;
		for (Path sheet : sheets()) {
			String css = Files.readString(sheet, StandardCharsets.UTF_8);
			for (String block : ruleBlocks(css)) {
				String background = valueOf(block, "background");
				String colour = valueOf(block, "color");
				// Only a label that DECLARES itself as sitting on a fill. Measuring
				// every background/colour pair instead reports something real and much
				// larger: the sheets paint backgrounds from raw ramp steps, which are
				// theme-invariant, so a dark theme would put light grey text on
				// `--ui-neutral-2`. That is the dark theme not being implemented -- it
				// is defined and not shipped, and nothing sets `data-theme` -- and it
				// is a project rather than a rule. This rule is about the one pairing
				// that claims to be correct in both themes, which is the claim that
				// was false.
				if (background == null || colour == null
						|| !colour.contains("--ui-text-on-accent")) {
					continue;
				}
				for (Map.Entry<String, Map<String, String>> theme : Map.of(
						"light", light, "dark", darkTheme).entrySet()) {
					String fill = tokenValue(background, theme.getValue());
					String label = tokenValue(colour, theme.getValue());
					if (fill == null || label == null) {
						continue;
					}
					measured++;
					double ratio = contrast(label, fill);
					if (ratio < 4.5) {
						unreadable.add(String.format("%s@%s: %s on %s is %.2f:1 (%s on %s)",
								sheet.getFileName(), theme.getKey(), colour.trim(),
								background.trim(), ratio, label, fill));
					}
				}
			}
		}
		assertThat(unreadable)
				.as("a label on a fill it cannot be read on is unreadable in exactly the place a "
						+ "reader is most certain of what it says -- a button")
				.isEmpty();
		assertThat(measured)
				.as("the label-on-fill pairs measured, across both themes -- the four filled "
						+ "buttons twice over; pinned so a parser that stopped resolving cannot "
						+ "pass this on nothing")
				.isEqualTo(8);

		// And every fill token on its own, which is what covers a hover. A `:hover`
		// rule sets a background and no colour -- it inherits the label from the base
		// selector -- so the declaration pass above skips it, and a mutant repointing
		// a hover fill at `--ui-danger-strong` (`#f5a5a1` in dark, 1.95:1 under white)
		// was caught only by the unrelated used-and-defined rule. A `-fill` token is
		// by its name a surface a label sits on, so it is checked as one whether or
		// not any single block pairs the two.
		List<String> fills = new ArrayList<>();
		for (Map.Entry<String, Map<String, String>> theme : Map.of(
				"light", light, "dark", darkTheme).entrySet()) {
			String label = theme.getValue().get("--ui-text-on-accent");
			for (Map.Entry<String, String> token : new TreeMap<>(theme.getValue()).entrySet()) {
				if (!FILL_TOKEN.matcher(token.getKey()).matches()) {
					continue;
				}
				double ratio = contrast(label, token.getValue());
				if (ratio < 4.5) {
					fills.add(String.format("%s@%s: %s under %s is %.2f:1",
							token.getKey(), theme.getKey(), token.getValue(), label, ratio));
				}
			}
		}
		assertThat(fills)
				.as("a fill is named for the job of carrying a label, so it carries one at 4.5:1 "
						+ "in both themes -- including the hover states, which set no colour of "
						+ "their own and are therefore invisible to the pass above")
				.isEmpty();
		assertThat(light.keySet().stream().filter(t -> FILL_TOKEN.matcher(t).matches()).count())
				.as("the fill tokens found; pinned so renaming the convention cannot make the "
						+ "check above iterate nothing")
				.isEqualTo(8);

		// Every state of a filled button, which is the property the two passes above
		// approximate from either side. A `:hover` rule sets a background and inherits
		// the label from its base selector, so the label the reader sees on hover is
		// the base's -- and repointing that hover at `--ui-danger-strong` (`#f5a5a1`
		// in dark, 1.95:1 under white) was caught by neither pass. The base rule names
		// the label; every rule whose selector starts with the same class must paint a
		// background that carries it.
		//
		// In every sheet, not in style.css. app-ui.css loads after it and declared
		// `.btn-green { background: var(--ui-success) }` at the same specificity, so
		// its background was the one that painted -- white on #8fc95a at 1.97:1 in
		// dark, the exact figure the `-fill` split was written to remove -- while this
		// pass measured the losing declaration and passed. The sheet that wins has to
		// be a sheet that is read.
		StringBuilder allSheets = new StringBuilder();
		for (Path sheet : sheets()) {
			allSheets.append(Files.readString(sheet, StandardCharsets.UTF_8)).append('\n');
		}
		String style = allSheets.toString();
		Set<String> filled = new TreeSet<>();
		Matcher base = Pattern.compile(
				"(?m)^(\\.[\\w-]+)\\s*\\{[^}]*color:\\s*var\\(--ui-text-on-accent\\)").matcher(style);
		while (base.find()) {
			filled.add(base.group(1));
		}
		assertThat(filled)
				.as("the classes that declare a label on a fill; this is what the states below "
						+ "are found from, so an empty set would check nothing")
				.hasSize(4);

		List<String> states = new ArrayList<>();
		for (String selector : filled) {
			Matcher rule = Pattern.compile("(?m)^" + Pattern.quote(selector)
					+ "[\\w\\s:().,\\[\\]=\"-]*\\{([^}]*)\\}").matcher(style);
			while (rule.find()) {
				Matcher background = Pattern.compile(
						"background:\\s*var\\(\\s*(--[\\w-]+)\\s*\\)").matcher(rule.group(1));
				while (background.find()) {
					String token = background.group(1);
					for (Map.Entry<String, Map<String, String>> theme : Map.of(
							"light", light, "dark", darkTheme).entrySet()) {
						String fill = theme.getValue().get(token);
						String label = theme.getValue().get("--ui-text-on-accent");
						if (fill == null || !fill.startsWith("#")) {
							continue;
						}
						double ratio = contrast(label, fill);
						if (ratio < 4.5) {
							states.add(String.format("%s@%s: a state of %s paints %s (%s), %.2f:1 "
									+ "under the label %s inherits", selector, theme.getKey(),
									selector, token, fill, ratio, selector));
						}
					}
				}
			}
		}
		assertThat(states)
				.as("a filled button's hover and focus states inherit its label, so each of their "
						+ "fills carries that label too -- the state that fails is the one the "
						+ "reader is pointing at")
				.isEmpty();
	}

	/** One declaration's value inside a rule block, or null when it sets none. */
	private static @org.jspecify.annotations.Nullable String valueOf(String block, String property) {
		Matcher declaration = Pattern.compile(
				"(?:^|;)\\s*" + Pattern.quote(property) + "\\s*:\\s*([^;}]+)").matcher(block);
		return declaration.find() ? declaration.group(1) : null;
	}

	/** An opaque hex for a single {@code var(--x)} value, or null for anything else. */
	private static @org.jspecify.annotations.Nullable String tokenValue(
			String value, Map<String, String> theme) {
		Matcher single = Pattern.compile("^\\s*var\\(\\s*(--[\\w-]+)\\s*\\)\\s*$").matcher(value);
		if (!single.find()) {
			return null;
		}
		String resolved = follow("var(" + single.group(1) + ")", theme, 0);
		Matcher hex = Pattern.compile("^#[0-9a-fA-F]{6}$").matcher(resolved.trim());
		return hex.find() ? resolved.trim().toLowerCase(Locale.ROOT) : null;
	}

	/**
	 * A tint is its token at an opacity, not a fourth colour.
	 *
	 * <p>The rule the sidebar needed. {@code .nav-chevron} painted its glyph
	 * {@code var(--ui-nav-accent-soft)} -- {@code #9ec8f5} -- and the pill directly
	 * behind it {@code rgba(133, 183, 235, .14)}, which is {@code #85b7eb}: a glyph and
	 * its own wash in two different blues, shipped, and invisible to a rule that read
	 * hex only. {@code login.css} carried the same orphan.
	 *
	 * <p>So the triples are not free-standing values. Each one names a token and must
	 * be that token's colour, which is what makes
	 * {@code rgb(var(--ui-accent-rgb) / .12)} a twelve-percent accent rather than a
	 * twelve-percent something-near-the-accent. Checked in both themes, because a
	 * token that lifts in dark and a triple that does not would put the pair back out
	 * of step exactly where nobody looks.
	 */
	@Test
	void aTintIsItsTokenAtAnOpacity() throws IOException {
		String sheet = Files.readString(ASSETS.resolve(TOKEN_SHEET), StandardCharsets.UTF_8);
		int dark = indexOfDark(sheet);
		Map<String, String> light = valuesIn(sheet.substring(0, dark));
		Map<String, String> overlay = new java.util.LinkedHashMap<>(light);
		overlay.putAll(valuesIn(sheet.substring(dark)));

		Set<String> triples = new TreeSet<>();
		Matcher declared = Pattern.compile("(--ui-[\\w-]*-rgb)\\s*:").matcher(sheet);
		while (declared.find()) {
			triples.add(declared.group(1));
		}
		assertThat(triples)
				.as("every triple this sheet declares must name the token it stands for, or it is "
						+ "a colour with no owner again")
				.containsExactlyInAnyOrderElementsOf(TRIPLE_OF.keySet());

		List<String> drifted = new ArrayList<>();
		Set<String> allowancesUsed = new TreeSet<>();
		for (Map.Entry<String, Map<String, String>> theme : Map.of(
				"light", light, "dark", overlay).entrySet()) {
			for (Map.Entry<String, String> pair : new TreeMap<>(TRIPLE_OF).entrySet()) {
				String triple = theme.getValue().get(pair.getKey());
				if (triple == null) {
					continue;
				}
				String expected = pair.getValue().startsWith("#")
						? pair.getValue()
						: theme.getValue().get(pair.getValue());
				if (expected == null) {
					drifted.add(pair.getKey() + "@" + theme.getKey() + ": "
							+ pair.getValue() + " is not defined in this theme");
					continue;
				}
				String allowance = pair.getKey() + "@" + theme.getKey();
				if (!asHex(triple).equals(expected.toLowerCase(Locale.ROOT))) {
					if (TRIPLE_DIFFERS.containsKey(allowance)) {
						allowancesUsed.add(allowance);
						continue;
					}
					drifted.add(allowance + ": " + triple + " is " + asHex(triple)
							+ ", but " + pair.getValue() + " is " + expected);
				}
			}
		}
		assertThat(drifted)
				.as("a triple that is not its token's colour makes every tint drawn from it a "
						+ "colour nobody chose, and the tint is usually behind the token it is "
						+ "supposed to match")
				.isEmpty();
		assertThat(allowancesUsed)
				.as("and a stated difference that is no longer a difference is a stale allowance")
				.containsExactlyInAnyOrderElementsOf(TRIPLE_DIFFERS.keySet());
	}

	/** {@code "24 95 165"} as {@code "#185fa5"}. */
	private static String asHex(String triple) {
		Matcher channel = Pattern.compile("\\d+").matcher(triple);
		StringBuilder hex = new StringBuilder("#");
		while (channel.find()) {
			hex.append(String.format("%02x", Integer.parseInt(channel.group())));
		}
		return hex.toString();
	}

	/**
	 * The shell is a scrolling pane, not a growing page.
	 *
	 * <p>Three declarations make the admin layout an app shell, and each one is
	 * useless without the other two: {@code .shell} fixes a height,
	 * {@code .main} hides its overflow, and {@code .content} scrolls. Get one wrong
	 * and the document becomes the scroller while the other two still look right.
	 *
	 * <p>That is what shipped. {@code .shell} read {@code min-height: 100vh}, so it
	 * grew to its content, {@code .content} never overflowed and so never scrolled --
	 * while remaining a scroll container, which is what a sticky descendant anchors
	 * itself to. Measured in headless Chromium at 1440x900 on the real sheets: the
	 * document scrolled 400px and both {@code .topbar} and {@code .tbl th} lost exactly
	 * 400px of viewport position. With a real height, {@code .content} carries the
	 * overflow, and the topbar holds {@code top=14} through a 600px scroll at 1440x900,
	 * 1000x600 and 768x1024.
	 *
	 * <p>Two behaviours depended on it and both were silently dead: the sticky topbar,
	 * and {@code body.nav-locked .content { overflow: hidden }} in app-responsive.css,
	 * whose own comment explains that {@code .content} is the scroller and that
	 * locking the body would do nothing -- true only once this is right.
	 *
	 * <p><b>The table header was a third, and this fix did not revive it.</b> The first
	 * version of this javadoc said it did, on a measurement taken against a page whose
	 * markup put {@code table.tbl} straight inside a card -- without {@code .table-wrap},
	 * which is the one element that decides the question. Its {@code overflow-x: auto}
	 * makes it the header's nearest scroll container on both axes and it has no height
	 * cap, so scrolling the pane by 600px still puts the {@code th} at {@code top=-478}.
	 * The topbar and the header anchor to different boxes, so one rule could never have
	 * covered both; {@link #everyStickyRuleHasAScrollportThatCanScroll} is the one that
	 * reads the other half, and #338 owns the header that sticks.
	 */
	@Test
	void theShellScrollsItsPaneAndNotTheDocument() throws IOException {
		String style = Files.readString(ASSETS.resolve("style.css"), StandardCharsets.UTF_8);

		assertThat(declarationsOf(style, ".shell"))
				.as("`.shell` must fix a height. `min-height` lets it grow, and then the pane "
						+ "below it never overflows, never scrolls, and every sticky descendant "
						+ "anchored to it stops working while still reading as sticky")
				.containsKey("height");
		assertThat(declarationsOf(style, ".main"))
				.as("`.main` hides its overflow so the pane inside it is the scroller")
				.containsEntry("overflow", "hidden");
		assertThat(declarationsOf(style, ".content"))
				.as("and `.content` is that pane")
				.containsEntry("overflow-y", "auto");
	}

	/**
	 * Sticky rules whose scrollport cannot scroll, and why that is allowed.
	 *
	 * <p>One entry. {@code .sidebar} is as tall as the shell and the shell does not
	 * scroll, so its {@code position: sticky} pins nothing -- it is legacy's, from when
	 * the document was the scroller, and it costs one line to leave correct for a page
	 * that ever puts the shell back in a scrolling document. Being inert is the point
	 * of the entry: a sticky declaration that does nothing is the defect this rule
	 * exists for, so the one instance that is deliberately inert says so here rather
	 * than passing quietly.
	 */
	private static final Map<String, String> STICKY_WHERE_NOTHING_SCROLLS = Map.of(
			".sidebar",
			"as tall as the shell, which is fixed; the declaration predates the app shell");

	/**
	 * A sticky rule sits in a box that can actually scroll.
	 *
	 * <p>{@code position: sticky} is clamped by the element's <em>nearest</em> scroll
	 * container, and any {@code overflow} other than {@code visible} makes one -- on
	 * both axes, so {@code overflow-x: auto} alone is enough. A box with no height cap
	 * never scrolls vertically, and a header clamped to a box that does not scroll does
	 * not move. Nothing about the sticky declaration looks wrong in that case, which is
	 * why it needs a rule and not a reading.
	 *
	 * <p>This branch shipped exactly that, twice. {@code .tbl th} declared
	 * {@code position: sticky} in style.css <em>and</em> in app-ui.css, which loads
	 * later and so was the one that applied, both inside {@code .table-wrap}
	 * ({@code overflow-x: auto}, no height). Measured against the real nesting at three
	 * viewports: scrolling the pane 600px put the {@code th} at {@code top=-478}. And
	 * app-responsive.css already said so, on {@code main}, in the {@code .table-wrap}
	 * rule's own comment -- "a {@code position: sticky} thead inside the wrap could
	 * never stick to anything". The tree held the correct analysis and two sheets
	 * contradicted it with nothing failing, so the analysis is now a test.
	 *
	 * <p>The map is exact in both directions: a new sticky rule with no scrollport
	 * named fails, and a named scrollport that stops capping its height fails. The
	 * live subject is {@code .form-footer} in a dialog, which does work -- {@code .modal}
	 * is {@code max-height: 88vh; overflow-y: auto} -- so the rule is pinned by
	 * something that passes for the right reason.
	 */
	@Test
	void everyStickyRuleHasAScrollportThatCanScroll() throws IOException {
		Map<String, String> found = new java.util.LinkedHashMap<>();
		for (Path sheet : sheets()) {
			String css = withoutComments(Files.readString(sheet, StandardCharsets.UTF_8));
			Matcher rule = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(css);
			while (rule.find()) {
				if (STICKY.matcher(rule.group(2)).find()) {
					found.put(rule.group(1).trim().replaceAll("\\s+", " "),
							sheet.getFileName().toString());
				}
			}
		}
		assertThat(found.keySet())
				.as("the sticky rules this repository has; a scanner that stopped matching would "
						+ "pass by checking none of them")
				.containsExactlyInAnyOrder(
						".form-footer",
						".modal .form-footer, .modal .account-form__footer",
						".sidebar");

		String style = Files.readString(ASSETS.resolve("style.css"), StandardCharsets.UTF_8);
		Map<String, String> modal = declarationsOf(style, ".modal");
		assertThat(modal)
				.as("`.modal` is the scrollport both `.form-footer` rules stick inside, so it "
						+ "must cap its height -- without the cap it never overflows and the "
						+ "footer stops being reachable, which is the whole reason it is sticky")
				.containsKey("max-height");
		assertThat(modal.get("overflow-y"))
				.as("and it must scroll")
				.isEqualTo("auto");

		assertThat(STICKY_WHERE_NOTHING_SCROLLS.keySet())
				.as("a sticky rule is either inside a scrollport that scrolls, or listed as "
						+ "deliberately inert with the reason. `.tbl th` was neither")
				.containsExactly(".sidebar");
		assertThat(found.keySet())
				.as("and the list may not name a rule that is gone")
				.containsAll(STICKY_WHERE_NOTHING_SCROLLS.keySet());

		assertThat(withoutComments(Files.readString(
						ASSETS.resolve("app-responsive.css"), StandardCharsets.UTF_8)))
				.as("`.table-wrap` is a scroll container on both axes with no height, so nothing "
						+ "may stick inside it until #338 gives the table region a height")
				.doesNotContain("sticky");

		// The sheets' own idiom is several declarations to a line, and a detector
		// anchored at a line start passed a sticky written that way.
		assertThat(STICKY.matcher(" display: flex; position: sticky; top: 0; ").find())
				.as("a sticky that shares its line")
				.isTrue();
		assertThat(STICKY.matcher("\n  position: sticky;\n").find())
				.as("and one on a line of its own")
				.isTrue();
		assertThat(STICKY.matcher(" position: relative; top: 0; ").find())
				.as("the control")
				.isFalse();
	}

	/**
	 * CSS with its comments removed.
	 *
	 * <p>Only this rule needs it, and it needs it for both directions: a sentence
	 * <em>about</em> {@code position: sticky} is not a declaration, and the prose that
	 * explains why a sticky header was removed would otherwise be read as one.
	 */
	private static String withoutComments(String css) {
		return css.replaceAll("(?s)/\\*.*?\\*/", "");
	}

	/**
	 * The declarations of the first rule whose selector list is exactly this one.
	 *
	 * <p>Exact, not a prefix: {@code .shell.nav-open} and {@code .main a:focus-visible}
	 * are different rules about different things, and a prefix match would read one of
	 * them and report on the other.
	 */
	private static Map<String, String> declarationsOf(String css, String selector) {
		Matcher rule = Pattern.compile(
				"(?m)^\\s*" + Pattern.quote(selector) + "\\s*\\{").matcher(css);
		assertThat(rule.find())
				.as("the rule for `" + selector + "` must be findable, or this checks nothing")
				.isTrue();
		Map<String, String> declarations = new java.util.LinkedHashMap<>();
		Matcher declaration = Pattern.compile("([a-z-]+)\\s*:\\s*([^;}]+)")
				.matcher(ruleBlocks(css.substring(rule.start())).get(0));
		while (declaration.find()) {
			// Last one wins, as CSS does: `height: 100vh; height: 100dvh` is one
			// property declared twice on purpose, with the fallback first.
			declarations.put(declaration.group(1), declaration.group(2).trim());
		}
		return declarations;
	}

	/** The sheets whose {@code :root} is a layer of aliases onto the design system. */
	private static final Set<String> ALIAS_LAYERS = Set.of("style.css", "app-ui.css");

	/**
	 * Every legacy alias still has a referrer, and points at the design system.
	 *
	 * <p>{@code style.css} keeps a handful of legacy names -- {@code --blue},
	 * {@code --gray} -- re-pointed at {@code --ui-*}, because sheets already written
	 * against them would otherwise all have to change at once. That is a real reason
	 * for the ones it is true of, and it was written as prose covering all of them:
	 * "these twelve exist because 18 sheets already reference them". There were
	 * thirteen, four sheets referenced any of them, and <b>eight had no referrer at
	 * all</b> -- dead names carrying an explanation of why they had to stay.
	 *
	 * <p>{@link #everyTokenUsedIsDefinedAndEveryTokenDefinedIsUsed} could not see it:
	 * it reads {@code --ui-} names, and the whole point of an alias is that it is not
	 * one. So the compatibility layer was the one part of the token system exempt from
	 * the rule that a declared token must be used, which is exactly where an unused
	 * declaration hides.
	 */
	@Test
	void noLegacyAliasIsDeclaredWithoutAReferrer() throws IOException {
		// Every `:root` block outside the token sheet, not style.css's alone:
		// app-ui.css declares its own `--app-*` layer of exactly the same kind, and
		// `everyTokenUsedIsDefinedAndEveryTokenDefinedIsUsed` reads `--ui-` names only,
		// so that layer was the sibling of the defect above with nothing gating it.
		// Three more sheets keep a `:root` of sheet-local constants -- a width, a tap
		// size, two z-indices -- which are not aliases and need not point at `--ui-*`,
		// but a dead one is dead all the same, so every block is read for referrers.
		List<String> aliases = new ArrayList<>();
		List<String> notAnAlias = new ArrayList<>();
		Set<String> sheetsWithAliases = new TreeSet<>();
		for (Path sheet : sheets()) {
			String name = sheet.getFileName().toString();
			if (TOKEN_SHEET.equals(name)) {
				continue;
			}
			String css = withoutComments(Files.readString(sheet, StandardCharsets.UTF_8));
			Matcher root = Pattern.compile("(?m)^\\s*:root\\s*\\{").matcher(css);
			while (root.find()) {
				sheetsWithAliases.add(name);
				Matcher declaration = DECLARED_PROPERTY.matcher(
						ruleBlocks(css.substring(root.start())).get(0));
				while (declaration.find()) {
					String alias = declaration.group(1);
					aliases.add(alias);
					if (ALIAS_LAYERS.contains(name)
							&& !declaration.group(2).trim().startsWith("var(--ui-")) {
						notAnAlias.add(name + ": " + alias + " = " + declaration.group(2).trim());
					}
				}
			}
		}
		assertThat(sheetsWithAliases)
				.as("the sheets that keep an alias layer; pinned so a pattern that stopped "
						+ "finding `:root` cannot pass by reading nothing")
				.containsExactlyInAnyOrder("app-responsive.css", "app-ui.css", "login.css",
						"sidebar.css", "style.css");
		assertThat(notAnAlias)
				.as("an entry here that is not `var(--ui-...)` is a colour living in this sheet "
						+ "again, which is the thing the block exists to have removed")
				.isEmpty();
		assertThat(aliases)
				.as("the aliases read; pinned so a pattern that stopped matching cannot pass")
				.hasSizeGreaterThan(3);

		List<String> unreferenced = new ArrayList<>();
		for (String alias : aliases) {
			int references = 0;
			for (Path sheet : sheets()) {
				String css = Files.readString(sheet, StandardCharsets.UTF_8);
				Matcher use = Pattern.compile(
						"var\\(\\s*" + Pattern.quote(alias) + "\\s*\\)").matcher(css);
				while (use.find()) {
					references++;
				}
			}
			if (references == 0) {
				unreferenced.add(alias);
			}
		}
		assertThat(unreferenced)
				.as("an alias nothing references is not a compatibility shim, it is a dead name "
						+ "with a paragraph explaining why it had to stay")
				.isEmpty();
	}

	/**
	 * No token is declared twice inside one rule block.
	 *
	 * <p>The rule that would have caught the defect this round found. Three tokens --
	 * {@code --ui-success-strong}, {@code --ui-danger-strong} and
	 * {@code --ui-warning-strong} -- were each declared twice in the dark block, ten
	 * lines apart. CSS takes the later one, so the shipped dark values were the light
	 * ramp's dark ends at <b>3.92</b>, <b>3.25</b> and <b>3.67</b> to one on
	 * {@code #1f1e23}, while the lifted values written for exactly that surface
	 * (10.25, 8.50, 9.53) sat above them as dead lines. A comment beside them claimed
	 * the token gate required the lift; no rule looked.
	 *
	 * <p>Nothing else could have caught it. The readability rule treats
	 * {@code -strong} as a fill rather than as text, and {@link #resolve} builds a map
	 * with {@code put}, so a duplicate is not a conflict there -- it is silently the
	 * last one, which is the browser's answer and therefore not something a resolver
	 * can flag. A duplicate declaration is only visible while the block is still
	 * text.
	 *
	 * <p>Per block, not per sheet: redefining a token in the dark block is the whole
	 * mechanism, so only a repeat <em>within</em> one block is the defect.
	 */
	@Test
	void noTokenIsDeclaredTwiceInTheSameBlock() throws IOException {
		List<String> repeated = new ArrayList<>();
		int blocks = 0;
		for (Path sheet : sheets()) {
			String css = Files.readString(sheet, StandardCharsets.UTF_8);
			for (String block : ruleBlocks(css)) {
				blocks++;
				Map<String, Integer> counts = declarationCounts(block);
				counts.forEach((token, count) -> {
					if (count > 1) {
						repeated.add(sheet.getFileName() + ": " + token + " declared " + count
								+ " times in one block; CSS keeps the last, so the others are dead "
								+ "lines that read as if they shipped");
					}
				});
			}
		}
		assertThat(repeated)
				.as("a token declared twice in one block ships whichever came last, and the other "
						+ "is a value somebody wrote, reviewed and believed")
				.isEmpty();
		assertThat(blocks)
				.as("the blocks read; pinned above zero so a splitter that stopped matching "
						+ "cannot make this pass by looking at nothing")
				.isGreaterThan(20);

		// The defect itself, so the rule is pinned by a subject rather than by the
		// tree happening to be clean.
		assertThat(ruleBlocks("""
				:root[data-theme="dark"] {
				  --ui-success-strong: #a6db73;
				  --ui-danger-strong: #f5a5a1;
				  --ui-success-strong: #4d8a1a;
				}"""))
				.as("one block")
				.hasSize(1);
		assertThat(declarationCounts(ruleBlocks("""
						:root[data-theme="dark"] {
						  --ui-success-strong: #a6db73;
						  --ui-danger-strong: #f5a5a1;
						  --ui-success-strong: #4d8a1a;
						}""").get(0)))
				.as("the shape this rule exists for, counted: the token declared twice and the "
						+ "one declared once")
				.containsEntry("--ui-success-strong", 2)
				.containsEntry("--ui-danger-strong", 1);
		assertThat(declarationCounts(ruleBlocks("""
						:root {
						  --topbar-h: var(--ui-topbar-h); --topbar-h: var(--ui-space-10);
						}""").get(0)))
				.as("and the same duplicate written on one line, which is how style.css writes "
						+ "most of its blocks and which a line-anchored count read as one")
				.containsEntry("--topbar-h", 2);
	}

	/** How many times each custom property is declared in one rule block. */
	private static Map<String, Integer> declarationCounts(String block) {
		Map<String, Integer> counts = new java.util.LinkedHashMap<>();
		Matcher definition = DECLARED_PROPERTY.matcher(block);
		while (definition.find()) {
			counts.merge(definition.group(1), 1, Integer::sum);
		}
		return counts;
	}

	/**
	 * The bodies of every brace-delimited rule in a sheet, one level deep.
	 *
	 * <p>Nested by design: an {@code @media} wrapper's own body is returned as well as
	 * each rule inside it, so a token declared twice inside a rule and a token
	 * declared once in two sibling rules are told apart.
	 */
	private static List<String> ruleBlocks(String css) {
		List<String> blocks = new ArrayList<>();
		for (int index = 0; index < css.length(); index++) {
			if (css.charAt(index) != '{') {
				continue;
			}
			int depth = 0;
			for (int scan = index; scan < css.length(); scan++) {
				char character = css.charAt(scan);
				if (character == '{') {
					depth++;
				}
				else if (character == '}') {
					depth--;
					if (depth == 0) {
						blocks.add(css.substring(index + 1, scan));
						break;
					}
				}
			}
		}
		return blocks;
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
