package com.workin.legacy.uploads;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code uploadFile($input_name, $sub_directory)}
 * ({@code hr-legacy/apis/helpers/functions.php:636-664}).
 *
 * <p>Shared rather than employee-specific: the same helper serves the company
 * logo, the commercial register and employee documents, and each of those waves
 * will need exactly this behaviour.
 *
 * <p>The order is the contract, and it is the awkward part: the file is written
 * to its final location <em>before</em> any database work, and nothing ever
 * removes it again. A failed update, a missing employee, a rejected re-read --
 * none of them delete the file legacy has already moved. Reproduced rather than
 * repaired.
 */
@Component
public class LegacyFileUploads {

	/** {@code $allowed_mime_types} ({@code functions.php:641}) -- PDF included, despite the endpoint's name. */
	private static final List<String> ALLOWED_MIME_TYPES =
			List.of("image/jpeg", "image/png", "image/webp", "application/pdf");

	private final Path uploadPath;
	private final String uploadUrl;

	public LegacyFileUploads(
			@Value("${app.legacy-uploads.path:uploads}") String uploadPath,
			@Value("${app.legacy-uploads.url:/uploads/}") String uploadUrl) {
		this.uploadPath = Path.of(uploadPath);
		this.uploadUrl = uploadUrl;
	}

	/**
	 * @param file the multipart part named by the endpoint -- {@code null} when
	 *        it is absent or arrived with an upload error, which is PHP's
	 *        {@code !isset($_FILES[...]) || error !== UPLOAD_ERR_OK} returning
	 *        null rather than failing
	 * @param subdirectory {@code UploadSubdir}'s value, e.g. {@code photos}
	 * @return the stored URL, or {@code null} when there was nothing to store
	 * @throws LegacyApiException 400 {@code invalid_file_type} for a MIME type
	 *         outside the allowlist, 500 {@code file_save_failed} if the
	 *         directory cannot be created or the file cannot be moved
	 */
	public String store(MultipartFile file, String subdirectory) {
		if (file == null || file.isEmpty()) {
			return null;
		}
		String mimeType = detectMimeType(file);
		if (!ALLOWED_MIME_TYPES.contains(mimeType)) {
			throw new LegacyApiException(400, "invalid_file_type");
		}

		Path directory = uploadPath.resolve(stripLeadingSlash(subdirectory));
		try {
			Files.createDirectories(directory);
		} catch (IOException ex) {
			throw new LegacyApiException(500, "file_save_failed");
		}

		// Deliberately NOT a faithful port: frozen PHP takes the extension from
		// pathinfo($name, PATHINFO_EXTENSION) -- the client-supplied filename,
		// entirely unrelated to the sniffed MIME type -- so a file whose bytes
		// are sniffed as image/pdf but named "x.php" is stored as "<id>.php" on
		// the same webroot the frozen stack serves /uploads from. That is a
		// real upload-based RCE/XSS path, not a quirk worth reproducing: for
		// every legitimate upload the extension implied by the detected type
		// already matches what a real client sends, so this changes nothing
		// for real traffic and only closes the mismatched-extension case.
		String extension = extensionForMimeType(mimeType);
		String storedName = uniqueId() + "." + extension;
		try {
			file.transferTo(directory.resolve(storedName));
		} catch (IOException | IllegalStateException ex) {
			throw new LegacyApiException(500, "file_save_failed");
		}
		return uploadUrl + stripLeadingSlash(subdirectory) + "/" + storedName;
	}

	/**
	 * {@code mime_content_type()} looks at the bytes, not the filename, so this
	 * sniffs the same four signatures rather than trusting a declared content
	 * type a client controls. Anything else is simply not in the allowlist.
	 */
	private static String detectMimeType(MultipartFile file) {
		byte[] header = new byte[16];
		int read;
		try (InputStream stream = file.getInputStream()) {
			read = stream.readNBytes(header, 0, header.length);
		} catch (IOException ex) {
			return "";
		}
		if (read >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
			return "image/jpeg";
		}
		if (read >= 8 && (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') {
			return "image/png";
		}
		if (read >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
				&& header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
			return "image/webp";
		}
		if (read >= 5 && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F') {
			return "application/pdf";
		}
		return "";
	}

	/** Maps a detected, allowlisted MIME type to its stored extension -- see {@link #store}'s note. */
	private static String extensionForMimeType(String mimeType) {
		return switch (mimeType) {
			case "image/jpeg" -> "jpg";
			case "image/png" -> "png";
			case "image/webp" -> "webp";
			case "application/pdf" -> "pdf";
			default -> throw new IllegalStateException("unreachable: mimeType already validated against the allowlist");
		};
	}

	/**
	 * {@code uniqid('', true)}: thirteen hex characters of timestamp followed by
	 * a dot and an entropy suffix. The value is random in PHP too, so what is
	 * reproduced is the shape a stored {@code photo_url} has.
	 */
	private static String uniqueId() {
		long microseconds = System.currentTimeMillis() * 1000L;
		String timePart = HexFormat.of().toHexDigits(microseconds).substring(3);
		return timePart + "." + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
	}

	/** {@code ltrim($sub_directory, '/')}. */
	private static String stripLeadingSlash(String subdirectory) {
		String value = subdirectory == null ? "" : subdirectory;
		return value.startsWith("/") ? value.substring(1) : value;
	}

}
