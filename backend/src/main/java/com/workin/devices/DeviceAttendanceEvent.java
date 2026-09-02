package com.workin.devices;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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
 * @param punchedAtLocal the device's wall clock for this punch
 * @param punchedAtInstant the exact instant, when the device reported one
 *        (a Unix-seconds timestamp); null when it reported a wall clock,
 *        which carries no offset of its own
 * @param statusCode the vendor's in/out key value, stored raw, not trusted
 * @param verifyCode the vendor's verification method code, stored raw
 * @param rawLine the untranslated line, retained for audit
 */
public record DeviceAttendanceEvent(
		String dedupKey,
		String pin,
		LocalDateTime punchedAtLocal,
		Instant punchedAtInstant,
		Integer statusCode,
		Integer verifyCode,
		String workCode,
		String rawLine) {

	public static final DateTimeFormatter SQL_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

	/**
	 * {@code sha256(serial | pin | time | status)}, hex. A device that loses
	 * the acknowledgement re-sends the batch; a factory reset resets its stamp
	 * and re-sends everything -- both must collapse onto the same key. Status
	 * is part of the key because a firmware that reports it distinguishes a
	 * same-second in and out through it.
	 *
	 * <p>The time component is the <b>instant</b> when the device reported
	 * one. Keying an epoch-form punch by its local time instead would collide
	 * the two distinct instants that share a wall clock during an autumn
	 * daylight-saving overlap, and one of the two punches would be silently
	 * discarded as a duplicate of the other.
	 */
	public static String dedupKey(
			String serialNumber, String pin, LocalDateTime punchedAtLocal, Instant punchedAtInstant,
			Integer statusCode) {
		String when = punchedAtInstant != null
				? "@" + punchedAtInstant.getEpochSecond()
				: SQL_DATE_TIME.format(punchedAtLocal);
		String material = serialNumber + "|" + pin + "|" + when + "|"
				+ (statusCode == null ? "" : statusCode);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}
}
