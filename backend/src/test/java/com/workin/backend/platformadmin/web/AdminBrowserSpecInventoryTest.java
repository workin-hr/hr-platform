package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The stack-free {@code browser} Playwright project, as its config runs it and as
 * {@code deploy/e2e/README.md} tells an operator it runs.
 *
 * <p>The README's "What runs where" table is how someone decides which profile runs a
 * spec, and it fell behind twice: {@code emp-picker} and then {@code job-title-form}
 * joined the project without it. This holds the table, the config's {@code testMatch}
 * and the spec files to one list, and fails rather than passes when it cannot find
 * either list.
 */
class AdminBrowserSpecInventoryTest {

	private static final Path E2E = Path.of("..", "deploy", "e2e");

	@Test
	void theReadmeNamesEverySpecTheBrowserProjectRuns() throws IOException {
		String config = Files.readString(E2E.resolve("playwright.config.js"));
		Matcher project = Pattern.compile(
				"name: 'browser',\\s*testMatch: /\\(([a-z0-9|-]+)\\)\\\\\\.spec\\\\\\.js/").matcher(config);
		assertThat(project.find()).as("the browser project's testMatch is one alternation of spec names").isTrue();
		Set<String> run = new TreeSet<>(Arrays.asList(project.group(1).split("\\|")));

		String readme = Files.readString(E2E.resolve("README.md"));
		Matcher row = Pattern.compile("(?m)^\\| ((?:`[a-z0-9-]+`(?:, )?)+) \\|[^\\n]*--project=browser").matcher(readme);
		assertThat(row.find()).as("the README's row for the browser project").isTrue();
		Set<String> documented = Pattern.compile("`([a-z0-9-]+)`").matcher(row.group(1)).results()
				.map(MatchResult::group).map(name -> name.substring(1, name.length() - 1))
				.collect(Collectors.toCollection(TreeSet::new));

		assertThat(documented).as("deploy/e2e/README.md names what the browser project runs").isEqualTo(run);
		for (String spec : run) {
			assertThat(E2E.resolve("tests").resolve(spec + ".spec.js")).as("a spec file for %s", spec).exists();
		}
	}
}
