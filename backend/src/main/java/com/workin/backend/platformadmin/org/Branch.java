package com.workin.backend.platformadmin.org;

import java.math.BigDecimal;

/**
 * A row of {@code branches} as the dashboard's list and form need it
 * ({@code dashboard/pages/branches}).
 *
 * @param companyName  joined only in the administrator's unfiltered view, where
 *                     rows from several companies share one table; {@code null}
 *                     otherwise, and the column is not rendered
 * @param employeeCount active employees assigned to this branch
 * @param departmentCount rows in {@code department_branches} for it
 * @param qrCode       the current check-in code, or {@code null}
 * @param expiresAt    when that code stops working, as {@code Y-m-d H:i:s}
 */
public record Branch(
		long id, long companyId, String companyName, String name, String address,
		BigDecimal latitude, BigDecimal longitude, int radiusMeters, boolean active,
		String qrCode, String expiresAt, String createdAt, int employeeCount, int departmentCount) {

	/** {@code org_branch_radius_meters()}: below 1 becomes the 200 m default, above 5 km is capped. */
	public static int radiusMeters(String raw) {
		int radius = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, phpInt(raw)));
		if (radius < 1) {
			return 200;
		}
		return Math.min(radius, 5000);
	}

	/**
	 * {@code org_branch_coordinate()}: {@code null} for absent, empty or
	 * non-numeric.
	 *
	 * <p>{@code is_numeric()} is the test, not a range check -- a latitude of
	 * 999 is stored. Legacy does not validate the value and neither does this;
	 * the column is what the mobile app compares a device fix against, and
	 * rejecting here would refuse a row the live system holds.
	 */
	public static BigDecimal coordinate(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty() || !NUMERIC.matcher(trimmed).matches()) {
			return null;
		}
		try {
			return new BigDecimal(trimmed);
		} catch (NumberFormatException ex) {
			// A form of number is_numeric() accepts and BigDecimal does not:
			// hexadecimal is already excluded by the pattern, so this is the
			// leading-plus case on some inputs. PHP would cast it; so do we.
			return BigDecimal.valueOf(Double.parseDouble(trimmed));
		}
	}

	/** {@code is_numeric()} for a form field: decimal and exponent, no hex. */
	private static final java.util.regex.Pattern NUMERIC =
			java.util.regex.Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$");

	/** {@code (int) $value}: the leading integer, or 0. */
	private static long phpInt(String raw) {
		if (raw == null) {
			return 0L;
		}
		String trimmed = raw.trim();
		int end = 0;
		if (end < trimmed.length() && (trimmed.charAt(end) == '+' || trimmed.charAt(end) == '-')) {
			end++;
		}
		while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
			end++;
		}
		String digits = trimmed.substring(0, end);
		if (digits.isEmpty() || "+".equals(digits) || "-".equals(digits)) {
			return 0L;
		}
		try {
			return Long.parseLong(digits);
		} catch (NumberFormatException ex) {
			return 0L;
		}
	}

	/**
	 * {@code org_branch_qr_is_active()}: a code with an expiry still in the
	 * future.
	 *
	 * <p>Both halves are {@code empty()} tests, so a code of {@code "0"} counts
	 * as absent. That cannot arise from {@code bin2hex(random_bytes(16))},
	 * which is 32 characters, and it is reproduced rather than reasoned away
	 * because the same helper renders rows an operator may have edited by hand.
	 */
	public boolean qrActive(java.time.LocalDateTime now) {
		if (isPhpEmpty(this.qrCode) || isPhpEmpty(this.expiresAt)) {
			return false;
		}
		java.time.LocalDateTime expires = parseTimestamp(this.expiresAt);
		return expires != null && expires.isAfter(now);
	}

	private static boolean isPhpEmpty(String value) {
		return value == null || value.isEmpty() || "0".equals(value);
	}

	static java.time.LocalDateTime parseTimestamp(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return java.time.LocalDateTime.parse(raw.trim().replace(' ', 'T'));
		} catch (java.time.format.DateTimeParseException ex) {
			return null;
		}
	}

	/** {@code org_branch_qr_expires_display()}: {@code Y-m-d H:i}, or an em dash. */
	public String expiresDisplay() {
		java.time.LocalDateTime expires = parseTimestamp(this.expiresAt);
		return expires == null ? "—" : expires.format(DISPLAY);
	}

	private static final java.time.format.DateTimeFormatter DISPLAY =
			java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	/**
	 * {@code org_branch_has_coordinates()}: both set, and not the 0,0 pair.
	 *
	 * <p>The zero test is a real rule, not a null check written twice: a form
	 * submitted with empty coordinate boxes stores nothing, but a row whose
	 * columns default to 0 would otherwise link to a point in the Gulf of
	 * Guinea. Either coordinate being non-zero is enough.
	 */
	public boolean hasCoordinates() {
		if (this.latitude == null || this.longitude == null) {
			return false;
		}
		return this.latitude.signum() != 0 || this.longitude.signum() != 0;
	}

	/** {@code org_branch_map_url()}, or empty when there is nowhere to point at. */
	public String mapUrl() {
		if (!hasCoordinates()) {
			return "";
		}
		// `(float) $lat . ',' . (float) $lng` -- PHP's float-to-string, which
		// drops a trailing ".0" and any trailing zeros the column carries.
		return "https://www.google.com/maps?q=" + java.net.URLEncoder.encode(
				phpFloat(this.latitude) + "," + phpFloat(this.longitude),
				java.nio.charset.StandardCharsets.UTF_8);
	}

	/** {@code (string) (float) $value}: {@code 30.0440000} prints as {@code 30.044}. */
	private static String phpFloat(BigDecimal value) {
		double number = value.doubleValue();
		return number == Math.floor(number) && !Double.isInfinite(number)
				? String.valueOf((long) number)
				: String.valueOf(number);
	}

	/** {@code substr((string) $row['created_at'], 0, 10)}: the date, without the time. */
	public String createdDate() {
		return this.createdAt == null ? "" : this.createdAt.substring(0, Math.min(10, this.createdAt.length()));
	}

	/**
	 * {@code org_branch_qr_image_url()}: the third-party renderer legacy links
	 * to.
	 *
	 * <p>Kept because the dashboard's QR page is an image tag pointing at
	 * {@code api.qrserver.com} and reproducing the page means reproducing the
	 * tag. Worth stating plainly: this sends the branch's check-in code to a
	 * third party on every render, which is legacy's behaviour and not an
	 * improvement on it.
	 */
	public static String qrImageUrl(String code) {
		if (code == null || code.trim().isEmpty()) {
			return "";
		}
		return "https://api.qrserver.com/v1/create-qr-code/?size=220x220&data="
				+ java.net.URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8);
	}

}
