package com.workin.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every repository file a backend test reads from outside {@code backend/} is a declared
 * input of the Gradle {@code test} task and a path that starts Backend Validate.
 *
 * <p>Otherwise an edit to that file alone runs no test: CI's path filter never starts the
 * workflow, and locally Gradle reports the task {@code UP-TO-DATE}. A drift test that
 * guards such a file is then skipped on exactly the change it exists for.
 */
class BackendTestRepositoryInputsTest {

	/** A repository path a test reads, written as {@code Path.of("..", …)} with every segment a string literal. */
	private static final Pattern READ = Pattern.compile("Path\\.of\\(\"\\.\\.\"((?:\\s*,\\s*\"[^\"]+\")+)\\)");

	private static final Pattern SEGMENT = Pattern.compile("\"([^\"]+)\"");

	@Test
	void everyRepositoryFileATestReadsIsATaskInputAndAWorkflowTrigger() throws IOException {
		Set<String> read = new TreeSet<>();
		try (Stream<Path> sources = Files.walk(Path.of("src", "test", "java"))) {
			for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
				Matcher reads = READ.matcher(Files.readString(source));
				while (reads.find()) {
					read.add(SEGMENT.matcher(reads.group(1)).results().map(segment -> segment.group(1))
							.collect(Collectors.joining("/")));
				}
			}
		}
		assertThat(read).as("the scan finds the tests known to read outside backend/")
				.contains("deploy/e2e", "contracts/legacy-dashboard-pages.txt",
						"docs/operations/provisioning-phase1-tables.md");

		Set<String> inputs = Pattern.compile("'\\.\\./([^']+)'").matcher(Files.readString(Path.of("build.gradle")))
				.results().map(input -> input.group(1)).collect(Collectors.toCollection(TreeSet::new));
		String workflow = Files.readString(Path.of("..", ".github", "workflows", "backend-validate.yml"));
		int push = workflow.indexOf("\n  push:");
		assertThat(push).as("the workflow has a push trigger after its pull_request trigger").isPositive();
		List<String> onPullRequest = triggers(workflow.substring(0, push));
		List<String> onPush = triggers(workflow.substring(push));

		for (String path : read) {
			assertThat(inputs).as("backend/build.gradle declares a test input at or under %s", path)
					.anyMatch(input -> covers(path, input));
			assertThat(onPullRequest).as("backend-validate.yml runs on a pull request touching %s", path)
					.anyMatch(trigger -> covers(path, trigger));
			assertThat(onPush).as("backend-validate.yml runs on a push touching %s", path)
					.anyMatch(trigger -> covers(path, trigger));
		}
	}

	private static List<String> triggers(String block) {
		return Pattern.compile("(?m)^\\s+- \"([^\"]+)\"").matcher(block).results().map(MatchResult::group)
				.map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"'))).toList();
	}

	/** Whether a declared path is the file read, or lies inside the directory read. */
	private static boolean covers(String read, String declared) {
		return declared.equals(read) || declared.startsWith(read + "/");
	}
}
