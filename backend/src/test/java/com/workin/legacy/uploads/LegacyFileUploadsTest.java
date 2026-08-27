package com.workin.legacy.uploads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code uploadFile()} ({@code functions.php:636-664}): {@code !isset($_FILES[...]) ||
 * error !== UPLOAD_ERR_OK} decides whether there was anything to upload at all, and PHP's
 * {@code error} has nothing to do with byte count -- a part with a real filename and zero
 * bytes still has {@code error === UPLOAD_ERR_OK} and falls through to {@code
 * mime_content_type()}, which then fails allowlist validation like any other unrecognized
 * type (PR #120 review).
 */
class LegacyFileUploadsTest {

	@TempDir
	Path tempDir;

	private LegacyFileUploads uploads(Path root) {
		return new LegacyFileUploads(root.toString(), "/uploads/");
	}

	@Test
	void nullFileReturnsNull() {
		assertThat(uploads(tempDir).store(null, "photos")).isNull();
	}

	/** Browser "no file chosen" submission shape: an empty filename, PHP's {@code UPLOAD_ERR_NO_FILE}. */
	@Test
	void noFileChosenReturnsNullRatherThanFailing() {
		MultipartFile file = new MockMultipartFile("photo", "", "application/octet-stream", new byte[0]);
		assertThat(uploads(tempDir).store(file, "photos")).isNull();
	}

	/**
	 * A real filename with zero bytes is a genuinely-submitted, error-free upload in PHP
	 * terms -- it must reach MIME validation and fail there, not be silently treated as
	 * "nothing to upload."
	 */
	@Test
	void genuinelyEmptyFileWithAFilenameFailsMimeValidationRatherThanBeingSkipped() {
		MultipartFile file = new MockMultipartFile("photo", "empty.png", "image/png", new byte[0]);
		assertThatThrownBy(() -> uploads(tempDir).store(file, "photos"))
				.isInstanceOf(LegacyApiException.class)
				.hasFieldOrPropertyWithValue("messageKey", "invalid_file_type");
	}

	@Test
	void unrecognizedMimeTypeFails() {
		MultipartFile file = new MockMultipartFile("photo", "notes.txt", "text/plain", "hello".getBytes());
		assertThatThrownBy(() -> uploads(tempDir).store(file, "photos"))
				.isInstanceOf(LegacyApiException.class)
				.hasFieldOrPropertyWithValue("messageKey", "invalid_file_type");
	}

	@Test
	void validImageIsStoredAndReturnsAUrlUnderTheSubdirectory() throws IOException {
		byte[] pngHeader = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
		MultipartFile file = new MockMultipartFile("photo", "avatar.png", "image/png", pngHeader);

		String url = uploads(tempDir).store(file, "photos");

		assertThat(url).startsWith("/uploads/photos/").endsWith(".png");
		String storedName = url.substring(url.lastIndexOf('/') + 1);
		assertThat(Files.exists(tempDir.resolve("photos").resolve(storedName))).isTrue();
	}
}
