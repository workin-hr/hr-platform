package com.workin.legacy.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * {@link LegacyPhpModules#ALLOWED} against the vendored copy of
 * {@code ApiModule::allowedList()}.
 *
 * <p>This list is not documentation. It decides whether a path is routed or
 * refused, and {@code index.php} emits it verbatim -- {@code implode(', ',
 * $allowedModules)} -- into the {@code module_not_found} body every client
 * sees. An omitted, reordered or misspelled module changes the compatibility
 * contract silently.
 *
 * <p>{@code LegacyPhpRouterRefusalTest} cannot catch that: it builds its
 * expected message from {@link LegacyPhpModules#ALLOWED_CSV} itself, so it
 * agrees with whatever the constant happens to say. The independent reference
 * is the vendored file, which is checked against the real legacy source by
 * {@code scripts/check_legacy_modules_drift.py} -- vendored for the same
 * reason as {@code mysql_workin.schema.sql}, because CI has no
 * {@code hr-legacy} checkout.
 */
class LegacyPhpModulesDriftTest {

	private static List<String> vendored() throws IOException {
		try (InputStream in = LegacyPhpModulesDriftTest.class
				.getResourceAsStream("/legacy/allowed_modules.txt")) {
			assertThat(in).as("vendored allowed_modules.txt must be on the test classpath").isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
					.map(String::strip)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.collect(Collectors.toList());
		}
	}

	@Test
	void theAllowListMatchesLegacyExactlyAndInOrder() throws IOException {
		assertThat(LegacyPhpModules.ALLOWED)
				.as("ApiModule::allowedList() -- order included, because it is emitted "
						+ "into the module_not_found body clients read")
				.containsExactlyElementsOf(vendored());
	}

	@Test
	void theCsvUsedInTheRefusalBodyIsBuiltFromThatSameOrder() throws IOException {
		assertThat(LegacyPhpModules.ALLOWED_CSV).isEqualTo(String.join(", ", vendored()));
	}

	/**
	 * `reports` is on the list with no directory behind it (C4), so every
	 * action under it answers 501. Pinned explicitly because it looks like a
	 * transcription error and has been "corrected" out of documentation before.
	 */
	@Test
	void reportsIsAllowListedDespiteHavingNoEndpoints() {
		assertThat(LegacyPhpModules.isAllowed("reports")).isTrue();
	}

	@Test
	void aModuleThatIsNotOnTheListIsNotAllowed() {
		assertThat(LegacyPhpModules.isAllowed("time")).isFalse();
		assertThat(LegacyPhpModules.isAllowed("")).isFalse();
	}

}
