package com.workin.legacy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
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

	/**
	 * PHP's {@code json_decode($json, true)} uses the default depth of 512, and
	 * a probe puts the boundary exactly there: 511 nested levels decode, 512
	 * fails with "Maximum stack depth exceeded" and therefore becomes
	 * {@code []}. Jackson's own default is higher, so this mapper -- and only
	 * this mapper -- is constrained to match. The application's ObjectMapper is
	 * untouched.
	 */
	private static final int PHP_MAX_NESTING_DEPTH = 511;

	private static final ObjectMapper JSON = new ObjectMapper(
			JsonFactory.builder()
					.streamReadConstraints(StreamReadConstraints.builder()
							.maxNestingDepth(PHP_MAX_NESTING_DEPTH)
							.build())
					.build());

	private LegacyJsonBody() {
	}

	/** Decodes the request body the way {@code body()} does. Never returns null. */
	public static Map<String, Object> read(HttpServletRequest request) {
		byte[] raw;
		try {
			raw = request.getInputStream().readAllBytes();
		} catch (IOException ex) {
			// file_get_contents('php://input') yielding nothing is an empty
			// body, not a failure.
			return new LinkedHashMap<>();
		}
		return decodeBytes(raw);
	}

	/**
	 * The byte-level half: {@code json_decode()} requires valid UTF-8 and
	 * returns null for anything else, so malformed input is {@code []} -- even
	 * when the rest of the document is perfectly good JSON.
	 *
	 * <p>Decoding is therefore strict. {@code new String(bytes, UTF_8)} would
	 * substitute U+FFFD for a bad sequence and hand the endpoint a document PHP
	 * never accepted, which is a different request.
	 */
	public static Map<String, Object> decodeBytes(byte[] raw) {
		if (raw == null || raw.length == 0) {
			return new LinkedHashMap<>();
		}
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		CharBuffer decoded;
		try {
			decoded = decoder.decode(ByteBuffer.wrap(raw));
		} catch (CharacterCodingException ex) {
			// json_last_error(): "Malformed UTF-8 characters" -> null -> [].
			return new LinkedHashMap<>();
		}
		return decode(decoded.toString());
	}

	/**
	 * {@code json_decode($json, true)} on its own, without {@code body()}'s
	 * {@code ?? []} and without its {@code : array} return type.
	 *
	 * <p>Needed where PHP decodes something that is <em>not</em> the request
	 * body and tests the result itself -- {@code attendance/import_excel.php}'s
	 * {@code mappings} form field is the first: it does
	 * {@code $decoded = json_decode($raw, true); if (!is_array($decoded)) fail(...)},
	 * so malformed JSON, a literal {@code null} and a bare scalar all have to be
	 * distinguishable from a decoded object. Routing that through
	 * {@link #decode} would turn the first two into an empty array and the third
	 * into a thrown {@code TypeError}, none of which is what that endpoint does.
	 *
	 * @return the decoded value -- a {@link Map}, a {@link List}, a scalar, or
	 *         {@code null} for both malformed input and a literal {@code null},
	 *         exactly as {@code json_decode()} conflates them
	 */
	public static Object decodeValue(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return JSON.readValue(raw, Object.class);
		} catch (JacksonException ex) {
			return null;
		}
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
