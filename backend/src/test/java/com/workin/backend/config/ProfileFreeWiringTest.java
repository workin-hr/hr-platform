package com.workin.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * That no bean is conditioned on a Spring profile, and no test activates one.
 *
 * <p>ADR-0017 left the application with a single wiring: one database, and
 * profiles that select property files (`local`, `integration`, `prod`) rather
 * than beans. The {@code phase1-mysql} profile that used to choose between the
 * two persistence halves is gone with the half it chose against.
 *
 * <p>This is a test because of what the leftovers did rather than what they
 * were. Three beans kept {@code @Profile("phase1-mysql")} through the removal
 * -- the home page's summary and the company directory among them -- and
 * nothing activated that profile any more, so in a real deployment those pages
 * would have rendered without their content and said nothing about why. The
 * suite did not notice: eighty-two test classes still declared
 * {@code @ActiveProfiles("phase1-mysql")}, which created the beans in tests and
 * nowhere else. Both halves of that are asserted here, because either half
 * alone lets the divergence back in.
 */
class ProfileFreeWiringTest {

	@Test
	void noBeanIsConditionedOnAProfile() throws IOException {
		assertThat(sourcesContaining(Path.of("src/main/java"), "@Profile("))
				.as("the application has one wiring (ADR-0017). A bean behind a profile is a bean "
						+ "that exists in whichever context happens to name it -- and, when nothing "
						+ "names it, silently in none")
				.isEmpty();
	}

	@Test
	void noTestActivatesTheRemovedPersistenceProfile() throws IOException {
		assertThat(sourcesContaining(Path.of("src/test/java"), "\"phase1-mysql\""))
				.as("nothing activates phase1-mysql at runtime, so a test that activates it is "
						+ "testing a wiring the application does not have")
				.isEmpty();
	}

	private static List<String> sourcesContaining(Path root, String needle) throws IOException {
		List<String> hits = new ArrayList<>();
		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				if (file.getFileName().toString().equals("ProfileFreeWiringTest.java")) {
					continue;
				}
				if (Files.readString(file, StandardCharsets.UTF_8).contains(needle)) {
					hits.add(root.relativize(file).toString());
				}
			}
		}
		return hits;
	}

}
