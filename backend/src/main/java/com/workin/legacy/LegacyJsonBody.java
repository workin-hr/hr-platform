package com.workin.legacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code body()} ({@code hr-legacy/apis/helpers/functions.php}):
 *
 * <pre>
 * function body(): array {
 *     $raw_input = file_get_contents('php://input');
 *     return json_decode($raw_input, true) ?? [];
 * }
 * </pre>
 *
 * <h2>Why the raw body, rather than {@code @RequestBody}</h2>
 * <p>Argument resolution decides malformed-body behaviour before a controller
 * runs: Spring answers a broken JSON document with its own error, while PHP
 * quietly hands the endpoint an empty array and lets {@code required()} or
 * {@code nothing_to_update} produce the response. Those are different contracts
 * for the same request, so the decoding happens here instead -- once, shared by
 * every legacy JSON endpoint.
 *
 * <h2>What the shapes mean</h2>
 * <ul>
 * <li>an empty body, malformed JSON and a literal {@code null} all decode to
 *     {@code []} -- {@code json_decode()} returns null for each, and {@code ?? []}
 *     turns that into an empty array;</li>
 * <li>a JSON object is an associative array;</li>
 * <li>a top-level JSON <em>array</em> is a real PHP array with numeric keys --
 *     not an error. Endpoints only ever ask it named questions
 *     ({@code required()}, {@code array_key_exists()}), which are all false for
 *     numeric keys, and {@code empty()} still reflects whether it had
 *     elements;</li>
 * <li>a valid JSON scalar -- string, number or boolean -- is the one shape that
 *     fails: {@code body()} declares {@code : array}, so returning a scalar is a
 *     {@code TypeError} PHP never catches. It goes out through D-084's generic
 *     500, with the detail in the log rather than the response.</li>
 * </ul>
 */
public final class LegacyJsonBody {

	private static final ObjectMapper JSON = new ObjectMapper();

	private LegacyJsonBody() {
	}

	/** Decodes the request body the way {@code body()} does. Never returns null. */
	public static Map<String, Object> read(HttpServletRequest request) {
		String raw;
		try {
			raw = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			// file_get_contents('php://input') yielding nothing is an empty
			// body, not a failure.
			return new LinkedHashMap<>();
		}
		return decode(raw);
	}

	/** The decoding itself, separated so it can be tested without a servlet. */
	public static Map<String, Object> decode(String raw) {
		if (raw == null || raw.isBlank()) {
			return new LinkedHashMap<>();
		}
		Object decoded;
		try {
			decoded = JSON.readValue(raw, Object.class);
		} catch (JacksonException ex) {
			// json_decode() returns null for malformed input, and ?? [] catches it.
			return new LinkedHashMap<>();
		}
		if (decoded == null) {
			return new LinkedHashMap<>();
		}
		if (decoded instanceof Map<?, ?> object) {
			Map<String, Object> body = new LinkedHashMap<>();
			object.forEach((key, value) -> body.put(String.valueOf(key), value));
			return body;
		}
		if (decoded instanceof List<?> array) {
			// PHP's numeric keys: "0", "1", ... Named lookups are all misses,
			// which is exactly how the endpoints then treat it.
			Map<String, Object> body = new LinkedHashMap<>();
			for (int index = 0; index < array.size(); index++) {
				body.put(String.valueOf(index), array.get(index));
			}
			return body;
		}
		throw new IllegalStateException(
				"body(): Return value must be of type array, " + phpTypeName(decoded) + " returned");
	}

	private static String phpTypeName(Object value) {
		if (value instanceof Boolean) {
			return "bool";
		}
		if (value instanceof Integer || value instanceof Long) {
			return "int";
		}
		if (value instanceof Number) {
			return "float";
		}
		return "string";
	}

}
