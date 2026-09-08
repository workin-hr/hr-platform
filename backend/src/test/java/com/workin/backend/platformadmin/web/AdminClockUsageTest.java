package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * That the dashboard reads legacy's clock and not the JVM's.
 *
 * <p>{@code date('Y-m-d')} in this product is neither UTC nor the JVM default:
 * PHP sets the timezone from {@code configs.is_daylight_saving} before any
 * request runs, and {@code LegacySessionDataSource} sets the same offset on
 * every legacy connection, so {@code CURDATE()} and {@code NOW()} answer in it
 * too (<b>D-083</b>, <b>D-099</b>). A page that reads {@code LocalDate.now()}
 * therefore disagrees with its own SQL for two or three hours a day.
 *
 * <p>The failure is silent and small, which is why it needs a test rather than
 * a convention: an employee hired late in the evening filed under yesterday, a
 * tenure ticking over a day early, a turnover cohort whose window starts on the
 * wrong date. Nine sites had it, and none of them looked wrong.
 *
 * <p>Templates get the value from {@link AdminViewModelAdvice#today()};
 * services and controllers inject {@code LegacyClock}.
 */
class AdminClockUsageTest {

	private static final List<Path> ROOTS = List.of(
			Path.of("src/main/java/com/workin/backend/platformadmin"),
			Path.of("src/main/jte/admin"));

	/**
	 * The advice is the one place allowed to name it: it is the fallback for
	 * the PostgreSQL profile, where there is no legacy database to ask.
	 */
	private static final Set<String> EXEMPT = Set.of("AdminViewModelAdvice.java");

	@Test
	void noAdminPageReadsTheJvmClock() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path root : ROOTS) {
			if (!Files.isDirectory(root)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(root)) {
				for (Path file : files.filter(Files::isRegularFile).toList()) {
					String name = file.getFileName().toString();
					if (EXEMPT.contains(name)) {
						continue;
					}
					String body = Files.readString(file, StandardCharsets.UTF_8);
					if (body.contains("LocalDate.now()") || body.contains("LocalDateTime.now()")) {
						offenders.add(root.relativize(file).toString());
					}
				}
			}
		}
		assertThat(offenders)
				.as("the dashboard's dates must come from LegacyClock -- through "
						+ "AdminViewModelAdvice's `today` for a template, by injection for a "
						+ "service. The JVM default zone is not the one CURDATE() runs in.")
				.isEmpty();
	}

}
