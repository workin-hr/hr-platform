package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * No admin template writes the same element id twice (#270).
 *
 * <p>The companies page wrote {@code co_title} and {@code co_size} on its toolbar filters and again
 * on its add and edit form. Nothing failed: a browser takes the first element with an id, so the
 * form's labels focused the toolbar's selects, and a script looking the form's field up by id
 * would have found the wrong one. This reads the templates' literal ids; an id built from an
 * expression is left to the page that builds it. A template drawing one id in two exclusive
 * branches should give each branch its own id rather than be excepted here.
 */
class AdminTemplateIdsTest {

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	private static final Pattern ID = Pattern.compile("\\sid=\"([A-Za-z][\\w-]*)\"");

	@Test
	void noTemplateWritesTheSameIdTwice() throws IOException {
		Map<String, List<String>> duplicates = new LinkedHashMap<>();
		int ids = 0;
		try (var files = Files.list(TEMPLATES)) {
			for (Path template : files.filter(file -> file.toString().endsWith(".jte")).sorted().toList()) {
				Map<String, Integer> seen = new LinkedHashMap<>();
				Matcher id = ID.matcher(Files.readString(template, StandardCharsets.UTF_8));
				while (id.find()) {
					ids++;
					seen.merge(id.group(1), 1, Integer::sum);
				}
				List<String> twice = new ArrayList<>();
				seen.forEach((name, count) -> {
					if (count > 1) {
						twice.add(name + " x" + count);
					}
				});
				if (!twice.isEmpty()) {
					duplicates.put(template.getFileName().toString(), twice);
				}
			}
		}
		assertThat(ids).as("the sweep read the templates' ids").isGreaterThan(200);
		assertThat(duplicates).isEmpty();
	}
}
