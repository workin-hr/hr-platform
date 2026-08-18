package com.workin.legacy.organization;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Literal port of {@code hr-legacy/apis/helpers/branch_location_link_parser.php}
 * (PR 12.3a). Every threshold and pattern below is copied from that file,
 * not approximated (D-058: the PHP implementation is the specification
 * for this heuristic, not an idealised geofence-validation design).
 *
 * <p>Pattern precedence and the near-{@code (0,0)}/zoom-mixup rejection
 * are exactly PHP's: a lat/lng pair that fails {@link #validPair} is
 * never silently accepted from a parsed link either -- both call sites
 * route through the same validator.
 */
final class LegacyBranchLocationResolver {

	private LegacyBranchLocationResolver() {
	}

	record Coordinates(BigDecimal latitude, BigDecimal longitude) {
	}

	private static final Pattern LOOKS_LIKE_MAPS = Pattern.compile(
			"google\\.com/maps|maps\\.google|maps\\.app\\.goo\\.gl|goo\\.gl/maps", Pattern.CASE_INSENSITIVE);

	private static final Pattern PIN_3D_4D = Pattern.compile(
			"!3d(-?\\d+(?:\\.\\d+)?)!4d(-?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
	private static final Pattern PIN_1D_2D = Pattern.compile(
			"!1d(-?\\d+(?:\\.\\d+)?)!2d(-?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
	private static final Pattern QUERY_PARAM = Pattern.compile(
			"[?&](?:q|ll|query)=(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
	private static final Pattern AT_COORD = Pattern.compile(
			"@(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)(?:,\\d+(?:\\.\\d+)?z)?", Pattern.CASE_INSENSITIVE);
	private static final Pattern PLAIN_PAIR = Pattern.compile(
			"^(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");

	/**
	 * {@code branch_location_valid_pair()}: rejects out-of-range
	 * coordinates, near-{@code (0,0)} (within 1e-6 of both axes), and a
	 * lng-looks-like-a-zoom-level (near-integer, 1-21) paired with a
	 * lat-that-looks-like-a-real-latitude (20-70 absolute) -- the common
	 * copy-paste mixup of a Maps URL's {@code zoom} parameter for {@code
	 * lng}.
	 */
	static Coordinates validPair(double lat, double lng) {
		if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
			return null;
		}
		if (Math.abs(lat) < 0.000001 && Math.abs(lng) < 0.000001) {
			return null;
		}
		boolean lngIsZoom = Math.abs(lng - Math.round(lng)) < 0.000001 && lng >= 1 && lng <= 21;
		boolean latLooksLikeLat = Math.abs(lat) >= 20 && Math.abs(lat) <= 70;
		if (lngIsZoom && latLooksLikeLat) {
			return null;
		}
		return new Coordinates(BigDecimal.valueOf(lat), BigDecimal.valueOf(lng));
	}

	/**
	 * {@code parse_branch_location_link_coords()}: tries, in order, the
	 * {@code !3d..!4d..} data-pin pattern (last match), the legacy
	 * {@code !1d..!2d..} pattern (last match), a {@code q=}/{@code ll=}/
	 * {@code query=} parameter, an {@code @lat,lng[,zoomz]} pattern, and
	 * -- only when the link does not look like a Maps URL at all -- a
	 * bare {@code lat,lng} pair. Each candidate is validated through
	 * {@link #validPair}; an out-of-range or near-{@code (0,0)}/zoom-mixup
	 * candidate is skipped, not returned, exactly like the PHP fall-through.
	 */
	static Coordinates parseLink(String value) {
		String link = value == null ? "" : value.trim();
		if (link.isEmpty()) {
			return null;
		}

		String decoded = rawUrlDecode(link);
		boolean looksLikeMaps = LOOKS_LIKE_MAPS.matcher(decoded).find();

		Coordinates fromPin3d4d = lastMatchPair(PIN_3D_4D, decoded);
		if (fromPin3d4d != null) {
			return fromPin3d4d;
		}

		Coordinates fromPin1d2d = lastMatchPair(PIN_1D_2D, decoded);
		if (fromPin1d2d != null) {
			return fromPin1d2d;
		}

		Matcher queryMatcher = QUERY_PARAM.matcher(decoded);
		if (queryMatcher.find()) {
			Coordinates pair = validPair(Double.parseDouble(queryMatcher.group(1)), Double.parseDouble(queryMatcher.group(2)));
			if (pair != null) {
				return pair;
			}
		}

		Matcher atMatcher = AT_COORD.matcher(decoded);
		if (atMatcher.find()) {
			Coordinates pair = validPair(Double.parseDouble(atMatcher.group(1)), Double.parseDouble(atMatcher.group(2)));
			if (pair != null) {
				return pair;
			}
		}

		if (!looksLikeMaps) {
			Matcher plainMatcher = PLAIN_PAIR.matcher(decoded);
			if (plainMatcher.matches()) {
				Coordinates pair = validPair(
						Double.parseDouble(plainMatcher.group(1)), Double.parseDouble(plainMatcher.group(2)));
				if (pair != null) {
					return pair;
				}
			}
		}

		return null;
	}

	/** The last regex match wins, matching PHP's {@code $pins[array_key_last($pins)]}. */
	private static Coordinates lastMatchPair(Pattern pattern, String decoded) {
		Matcher matcher = pattern.matcher(decoded);
		String lastLat = null;
		String lastLng = null;
		while (matcher.find()) {
			lastLat = matcher.group(1);
			lastLng = matcher.group(2);
		}
		if (lastLat == null) {
			return null;
		}
		return validPair(Double.parseDouble(lastLat), Double.parseDouble(lastLng));
	}

	/**
	 * PHP's {@code rawurldecode()}: percent-decodes only, unlike {@link
	 * java.net.URLDecoder#decode}, which also turns {@code +} into a
	 * space (form-encoding semantics, wrong for a URL). Decoded byte-by-byte
	 * as UTF-8, matching PHP's own byte-oriented behaviour.
	 */
	private static String rawUrlDecode(String value) {
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '%' && i + 2 < value.length()) {
				try {
					int code = Integer.parseInt(value.substring(i + 1, i + 3), 16);
					bytes.write(code);
					i += 2;
					continue;
				} catch (NumberFormatException ex) {
					// Not a valid escape -- fall through and copy the '%' literally, matching rawurldecode's tolerance.
				}
			}
			bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
		}
		return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
	}

}
