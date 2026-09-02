package com.workin.devices;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * The vendor-neutral seam from {@code device-integration-architecture.md}:
 * one punch, as a vendor adapter hands it to ingestion. The adapter's only
 * job is producing these; nothing downstream knows which protocol produced
 * them.
 *
 * @param dedupKey the idempotency key, synthesised because the protocol has
 *        no record id -- see {@link #dedupKey(String, String, LocalDateTime, Integer)}
 * @param pin the device-side identifier of the person; resolved to an
 *        employee downstream, never assumed to be an employee id
 * @param punchedAtLocal device wall-clock time exactly as reported
 * @param statusCode the vendor's in/out key value, stored raw, not trusted
 * @param verifyCode the vendor's verification method code, stored raw
 * @param rawLine the untranslated line, retained for audit
 */
public record DeviceAttendanceEvent(
		String dedupKey,
		String pin,
		LocalDateTime punchedAtLocal,
		Integer statusCode,
		Integer verifyCode,
		String workCode,
		String rawLine) {

	public static final DateTimeFormatter SQL_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

	/**
	 * {@code sha256(serial | pin | local time | status)}, hex. A device that
	 * loses the acknowledgement re-sends the batch; a factory reset resets
	 * its stamp and re-sends everything -- both must collapse onto the same
	 * key. Status is part of the key because a firmware that reports it
	 * distinguishes a same-second in and out through it.
	 */
	public static String dedupKey(String serialNumber, String pin, LocalDateTime punchedAtLocal, Integer statusCode) {
		String material = serialNumber + "|" + pin + "|" + SQL_DATE_TIME.format(punchedAtLocal) + "|"
				+ (statusCode == null ? "" : statusCode);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}
}
