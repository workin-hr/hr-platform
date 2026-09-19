package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Legacy reads the numbers a dashboard link or form carries with PHP's {@code (int)} cast, and
 * {@code PhpCast.intval} is that cast, held to PHP 8.3 by {@code PhpCastTest}.
 *
 * <p>A Java parse standing in for it refuses {@code "5abc"} and {@code "1e2"}, which PHP reads as
 * 5 and 100, and a parser that keeps the leading digits reads {@code "1e2"} as 1. Nineteen such
 * helpers had drifted from the cast. This fails on a new one written as {@code parseInt},
 * {@code parseLong}, {@code valueOf}, {@code decode} or {@code new BigInteger}; a digit loop
 * written by hand is not caught.
 */
class AdminPhpIntCastTest {

	private static final Path SOURCES = Path.of("src", "main", "java", "com", "workin", "backend", "platformadmin");

	private static final Pattern JAVA_PARSE = Pattern.compile(
			"\\b(?:Integer\\.(?:parseInt|valueOf|decode)|Long\\.(?:parseLong|valueOf|decode))\\(|\\bnew\\s+BigInteger\\(");

	/** A parse that is not a stand-in for PHP's cast, and why. */
	private static final Map<String, String> NOT_A_PHP_CAST = Map.of(
			"web/AdminDeviceView.java",
			"prints the id column of a row the devices store read, a number the database returned, not a request");

	@Test
	void noDashboardCodeParsesARequestNumberWithJava() throws IOException {
		List<String> offenders = new ArrayList<>();
		int read = 0;
		try (Stream<Path> files = Files.walk(SOURCES)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
				read++;
				String name = SOURCES.relativize(file).toString().replace('\\', '/');
				if (NOT_A_PHP_CAST.containsKey(name)) {
					continue;
				}
				String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n");
				for (int at = 0; at < lines.length; at++) {
					Matcher parse = JAVA_PARSE.matcher(lines[at]);
					if (parse.find()) {
						offenders.add(name + ":" + (at + 1) + ": " + lines[at].trim());
					}
				}
			}
		}
		assertThat(read).as("the dashboard's sources were read").isGreaterThan(100);
		assertThat(offenders).as("read a request number with PhpCast.intval, as legacy's (int) does").isEmpty();
	}

	@Test
	void everyAllowedParseIsStillThere() throws IOException {
		for (String name : NOT_A_PHP_CAST.keySet()) {
			assertThat(JAVA_PARSE.matcher(Files.readString(SOURCES.resolve(name), StandardCharsets.UTF_8)).find())
					.as("%s is allowed a Java parse it no longer has; remove it from the list", name).isTrue();
		}
	}

}
