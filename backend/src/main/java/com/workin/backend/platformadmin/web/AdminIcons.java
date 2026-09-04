package com.workin.backend.platformadmin.web;

import java.util.HashMap;
import java.util.Map;

/**
 * The sidebar's SVG glyphs, lifted verbatim from
 * {@code hr-legacy/dashboard/sidebar/icons.php} so the admin web renders the
 * same icons as the dashboard it replaces (ADR-0016).
 *
 * <p>Only the path bodies live here; the wrapping {@code <svg>} carries the
 * stroke attributes the copied CSS expects, so changing it changes every icon
 * at once.
 *
 * <p>Rendered through JTE's {@code $unsafe}: these are static strings from
 * this repository, never user input, and escaping them would print the markup
 * instead of drawing it. {@code of()} answers an empty string for an unknown
 * name rather than throwing -- a missing glyph must not take a page down.
 */
public final class AdminIcons {

	private static final Map<String, String> ICONS = new HashMap<>();

	private static final String OPEN =
			"<svg class=\"nav-icon\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\""
			+ " stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\""
			+ " stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\">";

	static {
		ICONS.put("advances", "<path d=\"M12 1v22M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6\"/>");
		ICONS.put("app_content", "<path d=\"M4 19.5A2.5 2.5 0 0 1 6.5 17H20\"/><path d=\"M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z\"/><path d=\"M8 7h8M8 11h8M8 15h5\"/>");
		ICONS.put("asset", "<path d=\"M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z\"/><path d=\"M3.27 6.96L12 12.01l8.73-5.05M12 22.08V12\"/>");
		ICONS.put("attendance", "<rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\"/><path d=\"M16 2v4M8 2v4M3 10h18\"/>");
		ICONS.put("banners", "<rect x=\"2\" y=\"3\" width=\"20\" height=\"18\" rx=\"2\"/><path d=\"M2 8h20M8 21h8\"/><circle cx=\"8\" cy=\"13\" r=\"2\"/><path d=\"M16 11l4 2-4 2z\"/>");
		ICONS.put("branch", "<path d=\"M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z\"/><circle cx=\"12\" cy=\"10\" r=\"3\"/>");
		ICONS.put("calculator", "<rect x=\"4\" y=\"2\" width=\"16\" height=\"20\" rx=\"2\"/><path d=\"M8 6h8M8 10h2M14 10h2M8 14h2M14 14h2M8 18h8\"/>");
		ICONS.put("comms_group", "<path d=\"M4 11v4h4l5 5V6L8 11H4z\"/><path d=\"M15.54 8.46a5 5 0 0 1 0 7.07\"/><path d=\"M19.07 4.93a10 10 0 0 1 0 14.14\"/>");
		ICONS.put("companies", "<path d=\"M3 21h18M5 21V7l7-4 7 4v14\"/><path d=\"M9 21v-6h6v6\"/>");
		ICONS.put("complaints", "<path d=\"M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z\"/>");
		ICONS.put("content", "<rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"2\"/><circle cx=\"8.5\" cy=\"8.5\" r=\"1.5\"/><path d=\"M21 15l-5-5L5 21\"/>");
		ICONS.put("countries", "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z\"/>");
		ICONS.put("department", "<path d=\"M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5\"/>");
		ICONS.put("employees", "<path d=\"M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2\"/><circle cx=\"9\" cy=\"7\" r=\"4\"/><path d=\"M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75\"/>");
		ICONS.put("faqs", "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3\"/><line x1=\"12\" y1=\"17\" x2=\"12.01\" y2=\"17\"/>");
		ICONS.put("home", "<path d=\"M3 10.5L12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1V10.5z\"/>");
		ICONS.put("hr", "<path d=\"M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2\"/><circle cx=\"9\" cy=\"7\" r=\"4\"/><path d=\"M22 11l-3 3-2-2\"/><path d=\"M16 3h5v5\"/>");
		ICONS.put("job", "<rect x=\"2\" y=\"7\" width=\"20\" height=\"14\" rx=\"2\"/><path d=\"M16 7V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v2\"/>");
		ICONS.put("language", "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z\"/>");
		ICONS.put("leave", "<rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\"/><path d=\"M16 2v4M8 2v4M3 10h18M8 14h.01M12 14h.01M16 14h.01\"/>");
		ICONS.put("logout", "<path d=\"M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4\"/><path d=\"M16 17l5-5-5-5\"/><path d=\"M21 12H9\"/>");
		ICONS.put("notifications", "<path d=\"M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 0 1-3.46 0\"/>");
		ICONS.put("org", "<path d=\"M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18\"/><path d=\"M6 12H4a2 2 0 0 0-2 2v6h2M18 12h2a2 2 0 0 1 2 2v6h-2\"/><path d=\"M10 6h4M10 10h4M10 14h4\"/>");
		ICONS.put("payroll", "<rect x=\"2\" y=\"5\" width=\"20\" height=\"14\" rx=\"2\"/><path d=\"M2 10h20M6 15h2M10 15h4\"/>");
		ICONS.put("payroll_group", "<path d=\"M12 1v22M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6\"/>");
		ICONS.put("penalties", "<path d=\"M12 3v18M5 8l14 8M19 8L5 16\"/>");
		ICONS.put("profile", "<path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/>");
		ICONS.put("reports", "<path d=\"M18 20V10M12 20V4M6 20v-6\"/>");
		ICONS.put("requests", "<path d=\"M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z\"/><path d=\"M14 2v6h6M9 13h6M9 17h6\"/>");
		ICONS.put("settings", "<circle cx=\"12\" cy=\"12\" r=\"3\"/><path d=\"M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42\"/>");
		ICONS.put("shift", "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M12 6v6l4 2\"/>");
		ICONS.put("workforce", "<path d=\"M18 20V10M12 20V4M6 20v-6\"/><path d=\"M3 20h18\"/>");
	}

	private AdminIcons() {
	}

	/** @return the wrapped SVG for {@code name}, or an empty string when there is no such icon */
	public static String of(String name) {
		String path = ICONS.get(name);
		return path == null ? "" : OPEN + path + "</svg>";
	}

	public static String chevron() {
		return "<svg class=\"nav-chevron-icon\" width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\""
				+ " stroke=\"currentColor\" stroke-width=\"2.25\" stroke-linecap=\"round\""
				+ " stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\">"
				+ "<path d=\"M6 9l6 6 6-6\"/></svg>";
	}

}
