package com.workin.backend.platformadmin.web;

import java.util.HashMap;
import java.util.Map;

/**
 * The sidebar's SVG glyphs, first lifted from
 * {@code hr-legacy/dashboard/sidebar/icons.php} (ADR-0016).
 *
 * <p>Where legacy's drawing named the wrong thing or two pages shared one
 * glyph, the glyph is replaced rather than copied (D-287): penalties were an
 * asterisk, advances and the salaries group the same dollar sign, settings a
 * sun, and devices, fingerprints, requests and decisions drew in pairs. Every
 * page in the menu now has a glyph of its own; AdminNavTest pins that.
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
		ICONS.put("advances", "<path d=\"M11 15h2a2 2 0 1 0 0-4h-3c-.6 0-1.1.2-1.4.6L3 17\"/><path d=\"M7 21l1.6-1.4c.3-.4.8-.6 1.4-.6h4c1.1 0 2.1-.4 2.8-1.2l4.6-4.4a2 2 0 0 0-2.75-2.91l-4.2 3.9\"/><path d=\"M2 16l6 6\"/><circle cx=\"16\" cy=\"9\" r=\"2.9\"/><circle cx=\"6\" cy=\"5\" r=\"3\"/>");
		ICONS.put("app_content", "<path d=\"M4 19.5A2.5 2.5 0 0 1 6.5 17H20\"/><path d=\"M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z\"/><path d=\"M8 7h8M8 11h8M8 15h5\"/>");
		ICONS.put("asset", "<path d=\"M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z\"/><path d=\"M3.27 6.96L12 12.01l8.73-5.05M12 22.08V12\"/>");
		ICONS.put("attendance", "<rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\"/><path d=\"M16 2v4M8 2v4M3 10h18\"/>");
		ICONS.put("banners", "<rect x=\"2\" y=\"3\" width=\"20\" height=\"18\" rx=\"2\"/><path d=\"M2 8h20M8 21h8\"/><circle cx=\"8\" cy=\"13\" r=\"2\"/><path d=\"M16 11l4 2-4 2z\"/>");
		ICONS.put("branch", "<path d=\"M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z\"/><circle cx=\"12\" cy=\"10\" r=\"3\"/>");
		ICONS.put("alert", "<path d=\"M12 9v4M12 17h.01\"/><path d=\"M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z\"/>");
		ICONS.put("approved", "<path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"/><path d=\"M22 4L12 14.01l-3-3\"/>");
		ICONS.put("awaiting", "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M12 6v6l4 2\"/>");
		ICONS.put("money", "<rect x=\"2\" y=\"6\" width=\"20\" height=\"12\" rx=\"2\"/><circle cx=\"12\" cy=\"12\" r=\"2.5\"/><path d=\"M6 12h.01M18 12h.01\"/>");
		ICONS.put("trend", "<path d=\"M23 6l-9.5 9.5-5-5L1 18\"/><path d=\"M17 6h6v6\"/>");
		ICONS.put("calculator", "<rect x=\"4\" y=\"2\" width=\"16\" height=\"20\" rx=\"2\"/><path d=\"M8 6h8M8 10h2M14 10h2M8 14h2M14 14h2M8 18h8\"/>");
		ICONS.put("comms_group", "<path d=\"M4 11v4h4l5 5V6L8 11H4z\"/><path d=\"M15.54 8.46a5 5 0 0 1 0 7.07\"/><path d=\"M19.07 4.93a10 10 0 0 1 0 14.14\"/>");
		ICONS.put("companies", "<path d=\"M3 21h18M5 21V7l7-4 7 4v14\"/><path d=\"M9 21v-6h6v6\"/>");
		ICONS.put("complaints", "<path d=\"M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z\"/>");
		ICONS.put("content", "<rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"2\"/><circle cx=\"8.5\" cy=\"8.5\" r=\"1.5\"/><path d=\"M21 15l-5-5L5 21\"/>");
		ICONS.put("countries", "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z\"/>");
		ICONS.put("department", "<path d=\"M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5\"/>");
		ICONS.put("employees", "<path d=\"M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2\"/><circle cx=\"9\" cy=\"7\" r=\"4\"/><path d=\"M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75\"/>");
		ICONS.put("faqs", "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3\"/><line x1=\"12\" y1=\"17\" x2=\"12.01\" y2=\"17\"/>");
		// A play triangle in a frame: the guide-videos page, which the sidebar
		// gained when AdminNav was rebuilt from sidebar_build_menu().
		ICONS.put("guide_videos", "<rect x=\"2\" y=\"4\" width=\"20\" height=\"16\" rx=\"2\"/><path d=\"M10 9l5 3-5 3z\"/>");
		ICONS.put("home", "<path d=\"M3 10.5L12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1V10.5z\"/>");
		ICONS.put("hr", "<path d=\"M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2\"/><circle cx=\"9\" cy=\"7\" r=\"4\"/><path d=\"M22 11l-3 3-2-2\"/><path d=\"M16 3h5v5\"/>");
		ICONS.put("job", "<rect x=\"2\" y=\"7\" width=\"20\" height=\"14\" rx=\"2\"/><path d=\"M16 7V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v2\"/>");
		ICONS.put("language", "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z\"/>");
		ICONS.put("leave", "<rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\"/><path d=\"M16 2v4M8 2v4M3 10h18M8 14h.01M12 14h.01M16 14h.01\"/>");
		ICONS.put("logout", "<path d=\"M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4\"/><path d=\"M16 17l5-5-5-5\"/><path d=\"M21 12H9\"/>");
		ICONS.put("notifications", "<path d=\"M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 0 1-3.46 0\"/>");
		ICONS.put("org", "<path d=\"M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18\"/><path d=\"M6 12H4a2 2 0 0 0-2 2v6h2M18 12h2a2 2 0 0 1 2 2v6h-2\"/><path d=\"M10 6h4M10 10h4M10 14h4\"/>");
		ICONS.put("payroll", "<path d=\"M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1z\"/><path d=\"M8 7h8M8 11h8M8 15h5\"/>");
		ICONS.put("payroll_group", "<path d=\"M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1\"/><path d=\"M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4\"/>");
		ICONS.put("penalties", "<path d=\"M14.5 12.5l-8 8a2.12 2.12 0 1 1-3-3l8-8\"/><path d=\"M16 16l6-6M8 8l6-6M9 7l8 8M21 11l-8-8\"/>");
		ICONS.put("profile", "<path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/>");
		ICONS.put("reports", "<path d=\"M18 20V10M12 20V4M6 20v-6\"/>");
		ICONS.put("requests", "<path d=\"M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z\"/><path d=\"M14 2v6h6M9 13h6M9 17h6\"/>");
		ICONS.put("settings", "<path d=\"M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z\"/><circle cx=\"12\" cy=\"12\" r=\"3\"/>");
		ICONS.put("shift", "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M12 6v6l4 2\"/>");
		ICONS.put("fingerprint", "<path d=\"M12 10a2 2 0 0 0-2 2c0 1.02-.1 2.51-.26 4\"/><path d=\"M14 13.12c0 2.38 0 6.38-1 8.88\"/><path d=\"M17.29 21.02c.12-.6.43-2.3.5-3.02\"/><path d=\"M2 12a10 10 0 0 1 18-6\"/><path d=\"M2 16h.01\"/><path d=\"M21.8 16c.2-2 .131-5.354 0-6\"/><path d=\"M5 19.5C5.5 18 6 15 6 12a6 6 0 0 1 .34-2\"/><path d=\"M8.65 22c.21-.66.45-1.32.57-2\"/><path d=\"M9 6.8a6 6 0 0 1 9 5.2v2\"/>");
		ICONS.put("device", "<rect x=\"5\" y=\"2\" width=\"14\" height=\"20\" rx=\"2\"/><path d=\"M9 6h6\"/><circle cx=\"12\" cy=\"13\" r=\"3\"/><path d=\"M12 18.5h.01\"/>");
		ICONS.put("decision", "<path d=\"M5 22h14\"/><path d=\"M19.27 13.73A2.5 2.5 0 0 0 17.5 13h-11A2.5 2.5 0 0 0 4 15.5V17a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-1.5c0-.66-.26-1.3-.73-1.77z\"/><path d=\"M14 13V8.5C14 7 15 7 15 5a3 3 0 0 0-6 0c0 2 1 2 1 3.5V13\"/>");
		ICONS.put("coins", "<circle cx=\"8\" cy=\"8\" r=\"6\"/><path d=\"M18.09 10.37A6 6 0 1 1 10.34 18\"/><path d=\"M7 6h1v4\"/><path d=\"M16.71 13.88l.7.71-2.82 2.82\"/>");
		ICONS.put("shield", "<path d=\"M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z\"/><path d=\"M9 12l2 2 4-4\"/>");
		ICONS.put("workforce", "<path d=\"M18 20V10M12 20V4M6 20v-6\"/><path d=\"M3 20h18\"/>");
	}

	private AdminIcons() {
	}

	/**
	 * The same glyph under a different class, for a surface that is not the
	 * sidebar.
	 *
	 * <p>{@code OPEN} names {@code nav-icon}, which the copied sidebar CSS sizes
	 * and which means nothing on the home page's grid. The stroke attributes are
	 * the part that must not change, so only the class is substituted.
	 */
	public static String of(String name, String className) {
		String icon = of(name);
		return icon.isEmpty() ? icon : icon.replace("class=\"nav-icon\"", "class=\"" + className + "\"");
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
