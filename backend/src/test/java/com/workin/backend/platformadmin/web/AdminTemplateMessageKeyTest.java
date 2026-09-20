package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every label key a JTE template asks for must exist in one of the bundles
 * {@code spring.messages.basename} chains.
 *
 * <p>{@code t} resolves with the key itself as the default -- legacy's
 * {@code t()} passthrough, and the right behaviour for a missing translation
 * on a live page. It also means a typo, or a key that was never added, renders
 * as {@code branch_qr_expires} on screen with nothing failing anywhere. That
 * has now happened four times in this port at different layers: twice in the
 * API catalog, once in the generated dashboard catalog, and once in a template
 * of this surface. The first three are covered by drift gates against
 * hr-legacy; this covers the fourth, which those gates cannot see because the
 * key is invented on the Java side.
 *
 * <p>Deliberately a test rather than a startup check. A missing key is a
 * mistake in a template, and a template is not something a deployment can fix
 * -- the right time to hear about it is before it ships.
 *
 * <p>A page's error key does not come from the template at all: a controller's
 * {@code messageKey} maps a refusal to a key, the page renders
 * {@code t.apply(errorKey)}, and the scanner above sees only the variable. So
 * the keys those methods return are collected here too (#293's review round 1).
 */
class AdminTemplateMessageKeyTest {

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	private static final Path BUNDLES = Path.of("src/main/resources/i18n");

	private static final Path CONTROLLERS =
			Path.of("src/main/java/com/workin/backend/platformadmin/web");

	/**
	 * A key inside a {@code messageKey}/{@code errorKey} method: the arm's value, a returned
	 * literal, or a {@code case} label the method passes through as the key itself.
	 */
	private static final Pattern REFUSAL_KEY =
			Pattern.compile("(?:->|return)\\s*\"([a-z][a-z0-9_.]*)\"");

	/**
	 * A whole {@code case} label list, which may carry several keys: {@code case "a", "b" ->}. The
	 * list is read to its arrow across line breaks, because a formatter wraps a long one and
	 * round 3 read every label of a wrapped list past a gate that stopped at the first newline.
	 */
	private static final Pattern CASE_LABELS = Pattern.compile("case\\s+(\"[\\s\\S]*?)->");

	private static final Pattern QUOTED_KEY = Pattern.compile("\"([a-z][a-z0-9_.]*)\"");

	/** Where a controller puts a key straight into the model, bypassing those methods. */
	private static final String MODEL_KEY = "addAttribute(\"errorKey\",";

	/** Where a controller spells the key into the redirect itself: {@code "?error=no_data"}. */
	private static final Pattern REDIRECT_KEY = Pattern.compile("[?&]error=([a-z][a-z0-9_.]*)");

	/** Where a controller translates the key itself: {@code AdminFlash.t(model).apply("saved_ok")}. */
	private static final Pattern APPLIED_KEY = Pattern.compile("\\.apply\\(\"([a-z][a-z0-9_.]*)\"\\)");

	/** The one family built by concatenation, in {@code AdminDevicesController.errorKey}. */
	private static final String DEVICE_FAMILY = "device_error_";

	/** {@code t.apply("some_key")}, the only form the templates use. */
	private static final Pattern LOOKUP = Pattern.compile("t\\.apply\\(\"([a-zA-Z0-9_.]+)\"\\)");

	/**
	 * The chain from {@code spring.messages.basename}, English side. A key
	 * present in any of them resolves.
	 */
	private static final List<String> ENGLISH_BUNDLES =
			List.of("messages.properties", "admin-messages.properties", "admin-own.properties");

	private static final List<String> ARABIC_BUNDLES =
			List.of("messages_ar.properties", "admin-messages_ar.properties", "admin-own_ar.properties");

	@Test
	void everyKeyTheTemplatesAskForResolvesInEnglish() throws IOException {
		assertThat(unresolved(ENGLISH_BUNDLES))
				.as("a key with no entry renders as itself, on screen, with nothing failing")
				.isEmpty();
	}

	@Test
	void everyKeyTheTemplatesAskForResolvesInArabic() throws IOException {
		// The dashboard defaults to Arabic, so a key present only in English
		// would be visible to almost every operator and to almost no test.
		assertThat(unresolved(ARABIC_BUNDLES)).isEmpty();
	}

	@Test
	void theScannerActuallyFindsKeys() throws IOException {
		// A regex that silently stops matching would make the two tests above
		// pass by finding nothing at all.
		Map<String, Set<String>> byTemplate = keysByTemplate();
		assertThat(byTemplate).as("templates scanned").isNotEmpty();
		assertThat(byTemplate.values().stream().mapToInt(Set::size).sum())
				.as("keys found across all templates").isGreaterThan(100);
		assertThat(byTemplate.get("sidebar.jte")).contains("app_name");
	}

	@Test
	void everyKeyAControllerHandsThePageResolvesInBothLanguages() throws IOException {
		Map<String, Set<String>> byController = refusalKeysByController();
		assertThat(byController).as("controllers with a messageKey or errorKey method").isNotEmpty();
		assertThat(byController.values().stream().mapToInt(Set::size).sum())
				.as("keys found across them").isGreaterThan(20);
		assertThat(byController.get("AdminBranchesController.java"))
				.as("the scanner reads the arms, not only the method")
				.contains("error_db", "no_data");
		assertThat(byController.get("AdminDevicesController.java"))
				.as("a case label the method passes through is a key too")
				.contains("device_import_no_file", "device_import_too_large");
		assertThat(byController.get("PlatformAdminCompaniesController.java"))
				.as("and a key put straight into the model")
				.contains("error_not_found", "company_delete_mismatch");
		for (List<String> bundles : List.of(ENGLISH_BUNDLES, ARABIC_BUNDLES)) {
			Properties available = load(bundles);
			List<String> missing = new java.util.ArrayList<>();
			for (Map.Entry<String, Set<String>> entry : byController.entrySet()) {
				for (String key : entry.getValue()) {
					if (!available.containsKey(key)) {
						missing.add(entry.getKey() + " -> " + key);
					}
				}
			}
			assertThat(missing).as("a refusal key with no entry renders as itself on the page, "
					+ "and no template gate can see it").isEmpty();
		}
	}

	/**
	 * {@code AdminDevicesController.errorKey} turns a {@code devices.<suffix>} code into
	 * {@code device_error_<suffix>}, so no literal exists to scan. Whatever the family holds must
	 * hold in both languages: an operator reading Arabic would otherwise see the key itself.
	 */
	@Test
	void everyDeviceErrorKeyExistsInBothCatalogues() throws IOException {
		Set<String> english = family(load(ENGLISH_BUNDLES));
		Set<String> arabic = family(load(ARABIC_BUNDLES));
		assertThat(english).as("the family the devices page builds by concatenation").isNotEmpty();
		assertThat(arabic).as("the same codes, in the language the dashboard defaults to")
				.containsExactlyInAnyOrderElementsOf(english);
	}

	private static Set<String> family(Properties catalogue) {
		Set<String> keys = new TreeSet<>();
		for (String key : catalogue.stringPropertyNames()) {
			if (key.startsWith(DEVICE_FAMILY)) {
				keys.add(key);
			}
		}
		return keys;
	}

	/**
	 * The keys this scan can see a controller hand a page: the arms, returns and {@code case}
	 * labels of its {@code messageKey}, {@code errorKey} and {@code messageFor} methods, a key it
	 * puts into the model as {@code errorKey}, a key it spells into a redirect as {@code ?error=},
	 * and a key it translates itself with {@code apply("...")}.
	 *
	 * <p>It is a scan of source text, so it is a floor and not a census: it reads the shapes
	 * listed above and no others, and every round of #293's review found a shape it could not
	 * read -- a method under another name, a key chosen by a ternary, a key a service returns, a
	 * key built by concatenation. This comment does not say which shapes remain, because three
	 * such lists have been written here and each was falsified by the next one found. What it
	 * catches, it catches; **#296** carries the work of following a key instead of matching its
	 * shape, and **#294** the built {@code device_error_} family, which no scan can reach.
	 */
	private static Map<String, Set<String>> refusalKeysByController() throws IOException {
		Map<String, Set<String>> found = new LinkedHashMap<>();
		try (Stream<Path> files = Files.list(CONTROLLERS)) {
			for (Path controller : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
				String source = Files.readString(controller, StandardCharsets.UTF_8);
				Set<String> keys = new TreeSet<>();
				for (String body : methodBodies(source, "String messageKey(", "String errorKey(", "String messageFor(")) {
					Matcher matcher = REFUSAL_KEY.matcher(body);
					while (matcher.find()) {
						keys.add(matcher.group(1));
					}
					Matcher labels = CASE_LABELS.matcher(body);
					while (labels.find()) {
						Matcher label = QUOTED_KEY.matcher(labels.group(1));
						while (label.find()) {
							keys.add(label.group(1));
						}
					}
				}
				for (String argument : modelArguments(source)) {
					Matcher literal = QUOTED_KEY.matcher(argument);
					while (literal.find()) {
						keys.add(literal.group(1));
					}
				}
				Matcher redirected = REDIRECT_KEY.matcher(source);
				while (redirected.find()) {
					keys.add(redirected.group(1));
				}
				Matcher applied = APPLIED_KEY.matcher(source);
				while (applied.find()) {
					keys.add(applied.group(1));
				}
				if (!keys.isEmpty()) {
					found.put(controller.getFileName().toString(), keys);
				}
			}
		}
		return found;
	}

	/**
	 * The second argument of every {@code addAttribute("errorKey", ...)}, read to its closing
	 * bracket so an inline {@code switch} is read whole rather than to its first arm.
	 */
	private static List<String> modelArguments(String source) {
		List<String> arguments = new java.util.ArrayList<>();
		int at = source.indexOf(MODEL_KEY);
		while (at >= 0) {
			int from = at + MODEL_KEY.length();
			int depth = 1;
			int to = from;
			while (to < source.length() && depth > 0) {
				char character = source.charAt(to);
				depth += character == '(' ? 1 : character == ')' ? -1 : 0;
				to++;
			}
			arguments.add(source.substring(from, Math.max(from, to - 1)));
			at = source.indexOf(MODEL_KEY, to);
		}
		return arguments;
	}

	/** Each named method's text, from its signature to the line that closes it at one tab. */
	private static List<String> methodBodies(String source, String... signatures) {
		List<String> bodies = new java.util.ArrayList<>();
		for (String signature : signatures) {
			int at = source.indexOf(signature);
			while (at >= 0) {
				int end = source.indexOf("\n\t}", at);
				bodies.add(end < 0 ? source.substring(at) : source.substring(at, end));
				at = source.indexOf(signature, at + signature.length());
			}
		}
		return bodies;
	}

	private static Properties load(List<String> bundles) throws IOException {
		Properties available = new Properties();
		for (String bundle : bundles) {
			Path path = BUNDLES.resolve(bundle);
			if (Files.exists(path)) {
				try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
					available.load(reader);
				}
			}
		}
		return available;
	}

	private static List<String> unresolved(List<String> bundles) throws IOException {
		Properties available = load(bundles);
		List<String> missing = new java.util.ArrayList<>();
		for (Map.Entry<String, Set<String>> entry : keysByTemplate().entrySet()) {
			for (String key : entry.getValue()) {
				if (!available.containsKey(key)) {
					missing.add(entry.getKey() + " -> " + key);
				}
			}
		}
		return missing;
	}

	private static Map<String, Set<String>> keysByTemplate() throws IOException {
		Map<String, Set<String>> found = new LinkedHashMap<>();
		try (Stream<Path> templates = Files.walk(TEMPLATES)) {
			for (Path template : templates.filter(path -> path.toString().endsWith(".jte")).toList()) {
				Set<String> keys = new TreeSet<>();
				Matcher matcher = LOOKUP.matcher(Files.readString(template, StandardCharsets.UTF_8));
				while (matcher.find()) {
					keys.add(matcher.group(1));
				}
				if (!keys.isEmpty()) {
					found.put(template.getFileName().toString(), keys);
				}
			}
		}
		return found;
	}

}
