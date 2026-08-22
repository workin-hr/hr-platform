package com.workin.legacy.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The literal legacy JSON envelope (D-074), as {@code respond()} builds it in
 * {@code hr-legacy/apis/helpers/functions.php:354-376}:
 *
 * <pre>
 * $response_body = [ 'success' =&gt; $success, 'message' =&gt; t($message, $replace) ];
 * if ($data !== null) { $response_body['data'] = $data; }
 * if ($meta !== null) { $response_body['meta'] = $meta; }
 * </pre>
 *
 * <p>Key order is PHP's insertion order, so it is this record's component
 * order. {@code data} and {@code meta} are omitted when null rather than
 * serialized as {@code null} -- {@link JsonInclude} reproduces the two
 * {@code !== null} guards. {@code success} and {@code message} are always
 * present.
 *
 * <p>This is deliberately not {@code com.workin.backend.i18n.ApiErrorBody}'s
 * flat {@code {code, message}} shape: D-074 records that shape as
 * implementation drift for the legacy surface, not precedent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LegacyApiResponse(boolean success, String message, Object data, Object meta) {

	/** {@code ok($message, $data)} with no meta. */
	public static LegacyApiResponse ok(String message, Object data) {
		return new LegacyApiResponse(true, message, data, null);
	}

	/** {@code ok($message, $data, 200, [], $meta)}. */
	public static LegacyApiResponse ok(String message, Object data, Object meta) {
		return new LegacyApiResponse(true, message, data, meta);
	}

	/** {@code fail($message, $status, $data)}. */
	public static LegacyApiResponse fail(String message, Object data) {
		return new LegacyApiResponse(false, message, data, null);
	}

}
