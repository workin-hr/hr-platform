package com.workin.devices.zkteco;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code table=OPERLOG} bodies mix record kinds on one upload: {@code OPLOG}
 * lines (operations on the device), {@code USER} lines (enrolment records
 * with a name) and, when the handshake's {@code TransFlag} allows it,
 * biometric templates -- {@code FP}, {@code FACE}, {@code BIODATA},
 * {@code BIOPHOTO}, {@code USERPIC}. The handshake this receiver sends does
 * not allow them, and this filter is the second line: whatever arrives, only
 * operation lines survive to storage. Nothing from a template line is kept,
 * logged or counted beyond the fact that one was discarded (design section
 * 8; {@code AGENTS.md} on biometric data).
 */
public final class ZkTecoOperlogFilter {

	private ZkTecoOperlogFilter() {
	}

	public record Result(List<String> operationLines, int userLines, int biometricLinesDiscarded, int otherLines) {
	}

	public static Result filter(String body) {
		List<String> operations = new ArrayList<>();
		int users = 0;
		int biometric = 0;
		int other = 0;
		for (String line : body.split("\\r?\\n")) {
			String trimmed = line.strip();
			if (trimmed.isEmpty()) {
				continue;
			}
			String kind = kindOf(trimmed);
			switch (kind) {
				case "OPLOG" -> operations.add(trimmed);
				case "USER" -> users++;
				case "FP", "FACE", "BIODATA", "BIOPHOTO", "USERPIC", "FVEIN", "PALM" -> biometric++;
				default -> other++;
			}
		}
		return new Result(operations, users, biometric, other);
	}

	/** The record kind is the first whitespace-delimited token, upper-cased. */
	static String kindOf(String line) {
		int end = 0;
		while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
			end++;
		}
		return line.substring(0, end).toUpperCase(Locale.ROOT);
	}
}
