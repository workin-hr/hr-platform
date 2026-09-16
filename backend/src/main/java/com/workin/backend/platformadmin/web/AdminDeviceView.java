package com.workin.backend.platformadmin.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the devices page needs to print a row read as a map: a dash for a value
 * that is absent, and the few codes that become a badge. Kept out of the
 * template so a null never renders as the word {@code null}.
 */
public final class AdminDeviceView {

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private AdminDeviceView() {
	}

	public static String text(Object value) {
		if (value == null) {
			return "—";
		}
		String text = String.valueOf(value);
		return text.isBlank() ? "—" : text;
	}

	public static String text(Map<String, Object> row, String column) {
		return text(row.get(column));
	}

	public static long id(Map<String, Object> row, String column) {
		Object value = row.get(column);
		return value == null ? 0L : Long.parseLong(String.valueOf(value));
	}

	public static boolean isActive(Map<String, Object> row) {
		return "1".equals(String.valueOf(row.get("is_active")));
	}

	/**
	 * An agent's last heartbeat, one line per terminal: whether the agent reached
	 * it, what it is, and how full its log is. The stored report is JSON the
	 * agent service wrote from known fields only; anything unreadable shows as a
	 * dash rather than as the raw text.
	 */
	public static List<String> reportLines(String report) {
		if (report == null || report.isBlank()) {
			return List.of();
		}
		List<String> lines = new ArrayList<>();
		try {
			for (JsonNode device : JSON.readTree(report).path("devices")) {
				StringBuilder line = new StringBuilder(device.path("serial").asString(""));
				line.append(device.path("reachable").asBoolean(false) ? " ✓" : " ✗");
				append(line, device.path("model").asString(""));
				long records = device.path("records").asLong(-1);
				long capacity = device.path("record_capacity").asLong(-1);
				if (records >= 0) {
					line.append(" · ").append(records).append(capacity > 0 ? "/" + capacity : "");
				}
				append(line, device.path("error").asString(""));
				lines.add(line.toString());
			}
		} catch (RuntimeException ex) {
			return List.of("—");
		}
		return lines;
	}

	private static void append(StringBuilder line, String value) {
		if (!value.isBlank()) {
			line.append(" · ").append(value);
		}
	}
}
