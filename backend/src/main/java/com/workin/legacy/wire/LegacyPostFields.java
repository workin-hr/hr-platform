package com.workin.legacy.wire;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
	 */
	public static MultipartFile file(HttpServletRequest request, String name) {
		if (request instanceof MultipartHttpServletRequest multipart) {
			return multipart.getFile(name);
		}
		return null;
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
