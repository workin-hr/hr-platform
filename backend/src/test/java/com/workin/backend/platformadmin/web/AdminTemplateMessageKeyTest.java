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

	/** A switch arm or return in a {@code messageKey}/{@code errorKey} method: {@code -> "some_key"}. */
	private static final Pattern REFUSAL_KEY = Pattern.compile("(?:->|return)\\s*\"([a-z][a-z0-9_]*)\"");

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
	 * The keys returned by each controller's {@code messageKey} or {@code errorKey} method. Only
	 * those methods are read: a literal elsewhere in a controller is a view name, a redirect or a
	 * request parameter, not something the page translates.
	 */
	private static Map<String, Set<String>> refusalKeysByController() throws IOException {
		Map<String, Set<String>> found = new LinkedHashMap<>();
		try (Stream<Path> files = Files.list(CONTROLLERS)) {
			for (Path controller : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
				String source = Files.readString(controller, StandardCharsets.UTF_8);
				Set<String> keys = new TreeSet<>();
				for (String body : methodBodies(source, "String messageKey(", "String errorKey(")) {
					Matcher matcher = REFUSAL_KEY.matcher(body);
					while (matcher.find()) {
						keys.add(matcher.group(1));
					}
				}
				if (!keys.isEmpty()) {
					found.put(controller.getFileName().toString(), keys);
				}
			}
		}
		return found;
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
