package com.workin.devices.zkteco;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.workin.devices.DeviceAttendanceEvent;
import com.workin.devices.DeviceInput;

/**
 * {@code table=ATTLOG} bodies: one punch per line, tab-separated
 * {@code PIN, time, status, verify, workcode, reserved...}. The field count
 * varies by firmware (five to eight observed), so anything after the fifth
 * field is ignored and only the first two are required.
 *
 * <p>Pure and hostile-input safe: a malformed line is quarantined and
 * counted, never thrown, because a device re-sends a batch the server did
 * not acknowledge and one bad line must not hold the good ones hostage. PIN
 * and timestamp are bounded and shape-checked before anything is built from
 * them; nothing in a line is ever interpreted as more than text or a number.
 */
public final class ZkTecoAttlogParser {

	/** A Unix-seconds timestamp, the one alternative to the wall-clock form some firmwares send. */
	private static final Pattern EPOCH_SECONDS = Pattern.compile("^\\d{9,11}$");

	/**
	 * {@code status_code}/{@code verify_code} are {@code SMALLINT}. Accepting a
	 * wider number would either be clamped silently (production is non-strict)
	 * or, under strict mode, fail the INSERT -- and since the device re-sends a
	 * batch it did not see acknowledged, one such line would block every good
	 * punch beside it on every retry.
	 */
	private static final Pattern SMALL_INT = Pattern.compile("^-?\\d{1,5}$");

	private static final int SMALLINT_MIN = -32768;

	private static final int SMALLINT_MAX = 32767;

	/** {@code DATETIME}'s own range. */
	private static final int MIN_YEAR = 1000;

	private static final int MAX_YEAR = 9999;

	/**
	 * Strict, not the default SMART resolution: SMART silently rewrites an
	 * impossible date such as {@code 2024-02-30} to the last valid day of the
	 * month, so a firmware fault would be stored as a real punch on a
	 * different day -- and could collide with a genuine punch's dedup key.
	 */
	private static final DateTimeFormatter WALL_CLOCK =
			DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

	private static final int MAX_WORK_CODE = 32;

	private ZkTecoAttlogParser() {
	}

	public record Result(List<DeviceAttendanceEvent> events, int malformed) {
	}

	/**
	 * @param deviceZone the zone the device's clock is set to, from its
	 *        registry row. Only the epoch form needs it -- a wall-clock value
	 *        is already in this zone by definition -- but it must be the same
	 *        zone ingestion later uses, or the two forms would disagree.
	 */
	public static Result parse(String serialNumber, String body, ZoneId deviceZone) {
		List<DeviceAttendanceEvent> events = new ArrayList<>();
		int malformed = 0;
		for (String line : body.split("\\r?\\n")) {
			String trimmed = line.strip();
			if (trimmed.isEmpty()) {
				continue;
			}
			DeviceAttendanceEvent event = parseLine(serialNumber, trimmed, deviceZone);
			if (event == null) {
				malformed++;
			} else {
				events.add(event);
			}
		}
		return new Result(events, malformed);
	}

	static DeviceAttendanceEvent parseLine(String serialNumber, String line, ZoneId deviceZone) {
		String[] fields = line.split("\t", -1);
		if (fields.length < 2) {
			return null;
		}
		String pin = fields[0].strip();
		if (!DeviceInput.isValidPin(pin)) {
			return null;
		}
		Punched punched = parseTime(fields[1].strip(), deviceZone);
		if (punched == null) {
			return null;
		}
		Integer status = fields.length > 2 ? smallInt(fields[2]) : null;
		Integer verify = fields.length > 3 ? smallInt(fields[3]) : null;
		String workCode = fields.length > 4 ? fields[4].strip() : "";
		if (workCode.isEmpty()) {
			workCode = null;
		} else if (workCode.length() > MAX_WORK_CODE) {
			workCode = workCode.substring(0, MAX_WORK_CODE);
		}
		return new DeviceAttendanceEvent(
				DeviceAttendanceEvent.dedupKey(serialNumber, pin, punched.local(), punched.instant(), status),
				pin, punched.local(), punched.instant(), status, verify, workCode, line);
	}

	/**
	 * Both forms resolve to the device's wall clock, which is what the rest of
	 * the pipeline treats {@code punched_at_local} as.
	 *
	 * <p>The two forms are <b>not</b> interchangeable and getting this wrong is
	 * silent: a Unix-seconds value is an absolute instant, so it becomes a wall
	 * clock only through the device's own zone. Reading it as UTC and letting
	 * ingestion apply the zone afterwards converts it twice -- the stored local
	 * time is then behind the device's real clock by the offset and the stored
	 * UTC is ahead of the true instant by it, which near midnight files a punch
	 * on the wrong attendance day.
	 *
	 * <p>A value outside {@code DATETIME}'s year range is refused here rather
	 * than at the INSERT: the legacy database is non-strict, so it would store
	 * a zero date instead of failing.
	 */
	/** A punch's time: always a wall clock, plus the instant when the device gave one. */
	record Punched(LocalDateTime local, Instant instant) {
	}

	static Punched parseTime(String text, ZoneId deviceZone) {
		LocalDateTime parsed;
		Instant instant = null;
		if (EPOCH_SECONDS.matcher(text).matches()) {
			instant = Instant.ofEpochSecond(Long.parseLong(text));
			parsed = LocalDateTime.ofInstant(instant, deviceZone);
		} else {
			try {
				parsed = LocalDateTime.parse(text, WALL_CLOCK);
			} catch (DateTimeParseException ex) {
				return null;
			}
		}
		return parsed.getYear() >= MIN_YEAR && parsed.getYear() <= MAX_YEAR ? new Punched(parsed, instant) : null;
	}

	/** Out of range is dropped like an unparseable value, never stored. */
	private static Integer smallInt(String text) {
		String trimmed = text.strip();
		if (!SMALL_INT.matcher(trimmed).matches()) {
			return null;
		}
		int value = Integer.parseInt(trimmed);
		return value >= SMALLINT_MIN && value <= SMALLINT_MAX ? Integer.valueOf(value) : null;
	}
}
