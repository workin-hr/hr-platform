package com.workin.devices.zkteco;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import com.workin.devices.registry.AttendanceDevice;

/**
 * The {@code GET /iclock/cdata?options=all} reply: the device's operating
 * parameters, plain text, one {@code key=value} per line. Documented in the
 * PUSH SDK protocol and agreed on by the independent implementations the
 * design cites; the hardware checklist confirms it against real firmware.
 *
 * <p>{@code TransFlag} is the biometric gate: {@code AttLog} and {@code OpLog}
 * only. Enrolment, fingerprint, face and photo transfer flags are absent on
 * purpose, and {@link ZkTecoOperlogFilter} discards anything that arrives
 * regardless.
 *
 * <p>An unclaimed device receives the same shape with zero stamps. Its
 * uploads are then refused with an error status, so the terminal keeps the
 * records and retries after {@code ErrorDelay}: nothing punched before the
 * claim is lost, and nothing is stored before it either.
 */
public final class ZkTecoHandshake {

	static final String CRLF = "\r\n";

	/**
	 * The stamp this receiver asks every device to resume from: zero, meaning
	 * "send everything you still hold".
	 *
	 * <h2>Why the device's own stamp is never echoed back</h2>
	 * <p>The stamp arrives on an unauthenticated request. Anyone who knows a
	 * claimed serial can send one syntactically valid punch with an arbitrary
	 * digit-only stamp, and if that value were stored and returned here, the
	 * real terminal would be told that everything up to it had already been
	 * received -- and would drop the buffered punches it still held. Requiring
	 * an accompanying punch narrows that but does not close it, because the
	 * accompanying punch is trivial to fabricate.
	 *
	 * <p>Answering zero costs re-delivery, not correctness: idempotency here
	 * is a content hash rather than a bookmark, so a re-sent record collapses
	 * onto the row that already exists. A trusted bookmark needs to know how
	 * this firmware encodes the value, which is a §4.3 hardware question; the
	 * observed stamp is still recorded, as a diagnostic, and never used.
	 */
	static final String ALWAYS_RESEND = "0";

	private ZkTecoHandshake() {
	}

	/**
	 * <p><b>No configuration means the key is OMITTED, not zero.</b>
	 * {@code TimeZone=0} is an operating instruction -- set your clock to UTC --
	 * and an unknown or deactivated terminal used to receive it. It would then
	 * record buffered punches on a UTC wall clock, while
	 * {@code device_assignment_history} still said its original zone, because
	 * activation changes append no history row. Those punches came back
	 * misattributed by the offset between the two.
	 *
	 * <p>Leaving the key out changes nothing on the terminal, which is what
	 * "configured like an unknown device" was always supposed to mean:
	 * deactivation stops information flowing, in both directions.
	 */
	public static String response(String serialNumber, Optional<AttendanceDevice> device, Instant now) {
		String timeZoneLine = device
				.map(d -> "TimeZone=" + offsetHours(d.deviceTimeZone(), now) + CRLF)
				.orElse("");
		return "GET OPTION FROM: " + serialNumber + CRLF
				+ "ATTLOGStamp=" + ALWAYS_RESEND + CRLF
				+ "OPERLOGStamp=0" + CRLF
				+ "ATTPHOTOStamp=0" + CRLF
				+ "ErrorDelay=30" + CRLF
				+ "Delay=10" + CRLF
				+ "TransTimes=00:00;14:05" + CRLF
				+ "TransInterval=1" + CRLF
				+ "TransFlag=TransData AttLog\tOpLog" + CRLF
				+ timeZoneLine
				+ "Realtime=1" + CRLF
				+ "Encrypt=None" + CRLF;
	}

	static int offsetHours(String zone, Instant now) {
		return ZoneId.of(zone).getRules().getOffset(now).getTotalSeconds() / 3600;
	}
}
