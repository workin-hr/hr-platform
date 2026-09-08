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
 * <p>{@link LegacyMariaDb} owns the container and hands out databases inside
 * it. A class that constructs a container of its own pays the three-second
 * start it was meant to save, runs a second database process beside the shared
 * one, and does so invisibly: the suite still passes, only slower. This reads
 * the sources, because the invariant is about who constructs the container,
 * and that is not observable from the running JVM once every class has
 * finished starting.
 *
 * <p>It looks for the <b>type name</b> rather than for a {@code new} expression
 * spelled one particular way. A fully qualified constructor call, or a line
 * break between {@code new} and the type, slips past a check written against
 * the source text of one formatting -- and a rule that only holds while
 * everybody formats alike is not a rule. Naming Testcontainers' MariaDB type
 * at all, outside its owner, is what this forbids; nothing else needs to.
 */
class LegacyMariaDbSingletonTest {

	private static final Path ROOT = Path.of("src/test/java");

	/**
	 * Assembled rather than written out, so this file does not contain the
	 * token it forbids and need an exemption for itself -- an exemption is a
	 * hole, and the smaller the exempt set the better this holds.
	 */
	private static final String CONTAINER_TYPE = "MariaDB" + "Container";

	/** The one class allowed to name it. */
	private static final String OWNER = "LegacyMariaDb.java";

	@Test
	void onlyLegacyMariaDbConstructsAContainer() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(ROOT)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				if (OWNER.equals(file.getFileName().toString())) {
					continue;
				}
				if (Files.readString(file, StandardCharsets.UTF_8).contains(CONTAINER_TYPE)) {
					offenders.add(ROOT.relativize(file).toString());
				}
			}
		}
		assertThat(offenders)
				.as("every test takes its database from LegacyMariaDb.freshDatabase() or "
						+ "emptyDatabase(); naming the container type outside LegacyMariaDb "
						+ "means a second MariaDB and a second three-second start")
				.isEmpty();
	}

}
