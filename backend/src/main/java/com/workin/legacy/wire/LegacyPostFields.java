package com.workin.legacy.wire;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;

/**
 * PHP's {@code $_POST} and {@code $_FILES}, for the legacy routes that read
 * them instead of a JSON body.
 *
 * <p>This was Wave 13.4c's, written inside {@code LegacyPeopleController} for
 * {@code employee_docs/upload.php}. Wave 13.1b needs the identical rules for
 * {@code auth/complete_company_registration.php}, and PHP has <b>one</b>
 * {@code $_POST}, not two -- so the rules moved here rather than being copied.
 * A second copy would be free to drift, and each module's own tests would agree
 * with its own copy while the two disagreed with each other.
 */
public final class LegacyPostFields {

	private LegacyPostFields() {
	}

	/**
	 * A {@code $_FILES} entry, resolved from the request rather than bound as a
	 * {@code @RequestParam} so that a non-multipart request, or a scalar
	 * {@code ?file=x}, cannot fail during argument resolution before the
	 * controller's own method check has run.
	 *
	 * <p>The name is matched after {@link #normalizeFieldName}, exactly as
	 * {@link #field} does. PHP normalizes {@code $_FILES} keys as well as
	 * {@code $_POST} ones, so a part named {@code commercial.reg} is
	 * {@code $_FILES['commercial_reg']} there. An exact lookup -- which an
	 * earlier draft used -- returned null for it, and on
	 * {@code complete_company_registration.php} that means the logo is stored
	 * and *then* the request is rejected for a missing commercial register.
	 *
	 * <p>The <b>last</b> matching part wins, as PHP's parser keeps the final
	 * duplicate.
	 */
	public static MultipartFile file(HttpServletRequest request, String name) {
		if (!(request instanceof MultipartHttpServletRequest multipart)) {
			return null;
		}
		// Wire order, not map order. getMultiFileMap() groups by RAW name, so
		// picking the last entry of each matching bucket and letting later
		// buckets win reorders interleaved aliases: for `logo=A, lo.go=B,
		// logo=C` it would choose C from the `logo` bucket and then overwrite it
		// with the earlier B from `lo.go`. PHP normalizes each part as it parses
		// and keeps the final one, which is C. So the parts are walked in the
		// order they arrived, and the winner is located by its raw name plus its
		// ordinal within that name.
		String winningName = null;
		int winningIndex = -1;
		Map<String, Integer> seen = new HashMap<>();
		try {
			for (Part part : request.getParts()) {
				if (part.getSubmittedFileName() == null) {
					continue;
				}
				int index = seen.merge(part.getName(), 0, (existing, ignored) -> existing + 1);
				if (normalizeFieldName(part.getName()).equals(name)) {
					winningName = part.getName();
					winningIndex = index;
				}
			}
		} catch (Exception ex) {
			return null;
		}
		if (winningName == null) {
			return null;
		}
		List<MultipartFile> files = multipart.getMultiFileMap().get(winningName);
		return files == null || winningIndex >= files.size() ? null : files.get(winningIndex);
	}

	/**
	 * A {@code $_POST} field: the request <b>body</b> only, never the query
	 * string.
	 *
	 * <p>{@code request.getParameter()} merges the two, which is exactly the
	 * behaviour being avoided, and the body cannot simply be re-read either --
	 * for {@code application/x-www-form-urlencoded} the container has already
	 * consumed the input stream to build the parameter map.
	 *
	 * <p>So the two sources are separated by position. The servlet spec has the
	 * container present query-string values <em>before</em> body values for the
	 * same name, so anything beyond the number of occurrences in the query
	 * string came from the body. The <b>last</b> such value is taken, because
	 * PHP's {@code parse_str()} keeps the final duplicate.
	 *
	 * <h2>One known limit: interleaved aliases in a urlencoded body</h2>
	 * <p>{@code getParameterMap()} groups values by <b>raw</b> key. Within one
	 * key the order is the wire order, but <em>across</em> keys it is lost — so
	 * a body of {@code doc_type=A&doc.type=B&doc_type=C}, whose three keys all
	 * normalize to {@code doc_type}, is reassembled here as {@code [A, C, B]}
	 * and yields {@code B}, where PHP normalizes each key as it parses and
	 * keeps {@code C}.
	 *
	 * <p>This is <b>not</b> fixable from the servlet API at this point: for a
	 * urlencoded request the container has already consumed the input stream to
	 * build that map, so the raw body is gone and with it the only record of
	 * the ordering. Recovering it needs the body captured upstream — a caching
	 * request wrapper on these routes — which is a change to the request
	 * pipeline rather than to this method, and is recorded as R-020 rather than
	 * attempted here at the end of a wave.
	 *
	 * <p>The multipart branch above has no such limit: {@code getParts()}
	 * preserves arrival order, so {@link #file} and this method's multipart
	 * path both resolve the true final duplicate. The gap is urlencoded-only
	 * and needs a client sending two different spellings of one field name in
	 * one body.
	 *
	 * <p>A multipart request needs none of that: {@code getPart()} reads the
	 * body directly and never sees the query string.
	 */
	public static String field(HttpServletRequest request, String name) {
		String contentType = request.getContentType();
		if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
			try {
				// getPart(name) is an exact-name lookup, which would skip PHP's
				// external-variable normalization -- a part named `company.name`
				// is $_POST['company_name'] in PHP and would be invisible here.
				// The parts are iterated and matched on the normalized name
				// instead, exactly as the urlencoded branch below does. PHP keeps
				// the last duplicate, so the last match wins.
				Part matched = null;
				for (Part part : request.getParts()) {
					if (!normalizeFieldName(part.getName()).equals(name)) {
						continue;
					}
					// A part carrying a filename is a FILE. PHP puts those in
					// $_FILES and never in $_POST, so reading its bytes as a form
					// value would accept input legacy does not see.
					if (part.getSubmittedFileName() != null) {
						continue;
					}
					matched = part;
				}
				if (matched == null) {
					return null;
				}
				try (InputStream in = matched.getInputStream()) {
					return new String(in.readAllBytes(), StandardCharsets.UTF_8);
				}
			} catch (Exception ex) {
				return null;
			}
		}

		// PHP normalizes dots and spaces in external field names, so a body
		// carrying `doc.type` or `doc type` populates $_POST['doc_type'] and the
		// required-field guard passes. The servlet container does not, so the
		// body is matched on the normalized name rather than the raw one.
		String[] merged = null;
		int fromQueryString = 0;
		for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
			if (!normalizeFieldName(entry.getKey()).equals(name)) {
				continue;
			}
			merged = merged == null ? entry.getValue() : concat(merged, entry.getValue());
			fromQueryString += countInQueryString(request, entry.getKey());
		}
		if (merged == null) {
			return null;
		}
		return merged.length <= fromQueryString ? null : merged[merged.length - 1];
	}

	/** {@code parse_str()}'s external-name normalization: dots and spaces become underscores. */
	public static String normalizeFieldName(String name) {
		return name.replace('.', '_').replace(' ', '_');
	}

	private static String[] concat(String[] first, String[] second) {
		String[] out = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, out, first.length, second.length);
		return out;
	}

	private static int countInQueryString(HttpServletRequest request, String rawName) {
		String query = request.getQueryString();
		if (query == null) {
			return 0;
		}
		int count = 0;
		for (String pair : query.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			String key = URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8);
			if (key.equals(rawName)) {
				count++;
			}
		}
		return count;
	}
}
