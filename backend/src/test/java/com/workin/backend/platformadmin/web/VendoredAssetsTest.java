package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Third-party files the admin dashboard serves live in {@code _assets/vendor/},
 * outside the sheets the design-token gates read: a library's own colours are
 * not this repository's to tokenise, and {@code ui-tools.css} restyles them
 * with the tokens instead (D-288).
 *
 * <p>Being outside those gates is only safe if the directory holds exactly the
 * upstream bytes it claims to. {@code VENDORED.txt} names each file with its
 * package, version and SHA-256; this fails on a file it does not list, a listed
 * file that is missing, or one whose bytes changed -- a hand edit, a botched
 * upgrade, or anything else that is no longer the audited release.
 */
class VendoredAssetsTest {

	private static final Path VENDOR = Path.of("src/main/resources/static/admin/_assets/vendor");

	@Test
	void everyVendoredFileIsListedWithTheBytesItWasAuditedAt() throws IOException {
		Map<String, String> listed = new TreeMap<>();
		for (String line : Files.readAllLines(VENDOR.resolve("VENDORED.txt"), StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] fields = line.split("\\s*\\|\\s*");
			assertThat(fields).as("file | package@version | source | sha256: %s", line).hasSize(4);
			listed.put(fields[0].strip(), fields[3].strip());
		}
		assertThat(listed).as("the manifest lists something; an empty one would check nothing").isNotEmpty();

		Map<String, String> actual = new TreeMap<>();
		try (Stream<Path> files = Files.list(VENDOR)) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString();
				if (name.equals("VENDORED.txt") || name.endsWith(".LICENSE.txt")) {
					continue;
				}
				actual.put(name, sha256(file));
			}
		}
		assertThat(actual).as("vendor/ holds exactly the listed files, byte for byte").isEqualTo(listed);
	}

	@Test
	void everyVendoredPackageCarriesItsLicence() throws IOException {
		for (String line : Files.readAllLines(VENDOR.resolve("VENDORED.txt"), StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String pkg = line.split("\\s*\\|\\s*")[1].split("@")[0];
			assertThat(VENDOR.resolve(pkg + ".LICENSE.txt")).as("redistribution needs %s's licence text", pkg)
					.exists();
		}
	}

	private static String sha256(Path file) throws IOException {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

}
