package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every {@code message(request, "key")} in the legacy controllers must resolve
 * the way frozen PHP resolves it.
 *
 * <p>A key absent from the bundle is not a compile error and not a runtime
 * error: the resolver echoes the key, so the client is shown
 * {@code department_deleted} where PHP shows "Department deactivated". Nothing
 * fails, and the response still looks structurally correct. The parity harness
 * found two of these; this test is the class-level guard so the next one cannot
 * ship.
 *
 * <p>The allowlist below is the subtle half. PHP's own {@code apis/lang/en.php}
 * does not translate every key its {@code LangKey} enum defines, and where PHP
 * echoes the key, Java must echo it too — <b>adding</b> a translation on the
 * Java side would create a divergence rather than remove one. Those keys are
 * listed explicitly, with PHP's behaviour as the reason, instead of being
 * silently tolerated.
 */
class LegacyMessageKeyCoverageTest {

	private static final Pattern MESSAGE_CALL =
			Pattern.compile("message\\(\\s*request\\s*,\\s*\"([a-z0-9_]+)\"");

	/**
	 * Keys PHP itself leaves untranslated in {@code apis/lang/en.php}, so both
	 * stacks echo the raw key and the responses agree. Verified against the
	 * frozen checkout, not assumed.
	 */
	private static final Map<String, String> UNTRANSLATED_IN_PHP_TOO = Map.of(
			"payslip_created", "LangKey::PAYSLIP_CREATED exists but apis/lang/en.php has no entry",
			"payslip_deleted", "LangKey::PAYSLIP_DELETED exists but apis/lang/en.php has no entry");

	@Test
	@DisplayName("every message key a legacy controller uses is in the bundle, or is one PHP does not translate either")
	void everyMessageKeyResolves() throws IOException {
		Properties bundle = new Properties();
		try (var in = Files.newInputStream(Path.of("src/main/resources/legacy/lang/en.properties"))) {
			bundle.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
		}
		assertThat(bundle).as("the bundle itself must load and be non-trivial").hasSizeGreaterThan(300);

		Set<String> used = new TreeSet<>();
		try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/workin/legacy"))) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				Matcher matcher = MESSAGE_CALL.matcher(Files.readString(file));
				while (matcher.find()) {
					used.add(matcher.group(1));
				}
			}
		}
		assertThat(used).as("the scan must actually find message keys").hasSizeGreaterThan(50);

		Set<String> unresolved = new TreeSet<>(used);
		unresolved.removeIf(bundle::containsKey);
		unresolved.removeAll(UNTRANSLATED_IN_PHP_TOO.keySet());

		assertThat(unresolved)
				.as("these keys are echoed raw to clients instead of a message; either add them to "
						+ "legacy/lang/en.properties, or list them in UNTRANSLATED_IN_PHP_TOO with "
						+ "evidence that PHP echoes them too")
				.isEmpty();
	}

	@Test
	@DisplayName("the untranslated allowlist stays honest: an entry that IS in the bundle must be removed from it")
	void theAllowlistDoesNotHideKeysThatAreTranslated() throws IOException {
		Properties bundle = new Properties();
		try (var in = Files.newInputStream(Path.of("src/main/resources/legacy/lang/en.properties"))) {
			bundle.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
		}
		Set<String> contradictory = new HashSet<>(UNTRANSLATED_IN_PHP_TOO.keySet());
		contradictory.removeIf(key -> !bundle.containsKey(key));

		assertThat(contradictory)
				.as("a key listed as untranslated but present in the bundle means the list is stale; "
						+ "the bundle wins and the entry should go")
				.isEmpty();
	}

	@Test
	@DisplayName("the two keys the parity harness caught resolve to PHP's wording")
	void theDeactivationKeysMatchPhp() throws IOException {
		Properties bundle = new Properties();
		try (var in = Files.newInputStream(Path.of("src/main/resources/legacy/lang/en.properties"))) {
			bundle.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
		}
		// departments/delete.php and job_titles/delete.php answer
		// LangKey::DEPARTMENT_DEACTIVATED / JOB_TITLE_DEACTIVATED -- deactivated,
		// not deleted. Java asked for *_deleted, which is in neither bundle, so
		// the raw key reached the client.
		assertThat(bundle.getProperty("department_deactivated")).isEqualTo("Department deactivated");
		assertThat(bundle.getProperty("job_title_deactivated")).isEqualTo("Job title deactivated");

		List<String> sources = List.of(
				"src/main/java/com/workin/legacy/organization/php/LegacyDepartmentPhpController.java",
				"src/main/java/com/workin/legacy/organization/php/LegacyJobTitlePhpController.java");
		for (String source : sources) {
			assertThat(Files.readString(Path.of(source)))
					.as("%s must not go back to the _deleted key", source)
					.doesNotContain("_deleted\"");
		}
	}
}
