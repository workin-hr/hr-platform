package com.workin.legacy;

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
 * That the suite starts one MariaDB, not one per test class.
 *
 * <p>{@link LegacyMariaDb} owns the container and hands each class its own
 * database inside it. A class that constructs a {@code MariaDBContainer} of
 * its own pays the three-second start it was meant to save, runs a second
 * database process beside the shared one, and does so invisibly: the suite
 * still passes, only slower. This reads the sources, because the invariant is
 * about who constructs the container, and that is not observable from the
 * running JVM once every class has finished starting.
 */
class LegacyMariaDbSingletonTest {

	private static final Path ROOT = Path.of("src/test/java");

	/**
	 * The owner, and this test -- which necessarily contains the pattern it
	 * forbids, in the line below that looks for it.
	 */
	private static final List<String> EXEMPT = List.of(
			"LegacyMariaDb.java", "LegacyMariaDbSingletonTest.java");

	@Test
	void onlyLegacyMariaDbConstructsAContainer() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(ROOT)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				if (EXEMPT.contains(file.getFileName().toString())) {
					continue;
				}
				if (Files.readString(file, StandardCharsets.UTF_8).contains("new MariaDBContainer")) {
					offenders.add(ROOT.relativize(file).toString());
				}
			}
		}
		assertThat(offenders)
				.as("every test takes its database from LegacyMariaDb.freshDatabase() or "
						+ "emptyDatabase(); a container of its own is a second MariaDB and a "
						+ "second three-second start")
				.isEmpty();
	}

}
