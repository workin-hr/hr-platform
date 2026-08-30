package com.workin.legacy.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code jwtEncode()} from {@code apis/helpers/functions.php:420-430},
 * reimplemented independently of {@link LegacyPhpJwtService}.
 *
 * <p>Independence is the whole point: a test that mints its "PHP" token by
 * calling the production encoder proves only that the encoder agrees with
 * itself, and would stay green through a change that broke wire compatibility
 * with the frozen PHP. Every expectation built on this class is therefore an
 * oracle, not an echo.
 *
 * <p>Shared between {@link LegacyPhpJwtWireCompatibilityTest} (the codec) and
 * {@link LegacyLoginEndToEndTest} (the same tokens over real HTTP through the
 * production authentication chain), so the oracle is written once.
 */
final class PhpJwtOracle {

	private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
	private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

	private PhpJwtOracle() {
	}

	/** PHP's payload is a plain array literal, so claim order is fixed by the source. */
	static String employeePayload(long employeeId, long companyId, String role, long tokenVersion, long exp) {
		return "{\"type\":\"employee\",\"employee_id\":" + employeeId
				+ ",\"company_id\":" + companyId
				+ ",\"role\":\"" + role + "\""
				+ ",\"token_version\":" + tokenVersion
				+ ",\"exp\":" + exp + "}";
	}

	static String companyPayload(long companyId, String role, long exp) {
		return "{\"type\":\"company\",\"company_id\":" + companyId
				+ ",\"role\":\"" + role + "\""
				+ ",\"exp\":" + exp + "}";
	}

	static long tenYearsFromNow() {
		return System.currentTimeMillis() / 1000 + 87600L * 3600L;
	}

	static String encode(String payloadJson, String secret) {
		String header = URL.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
		String payload = URL.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
		String signingInput = header + "." + payload;
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			// PHP signs the raw ASCII of "header.payload"; the signature covers the
			// encoded bytes, which is why claim order is load-bearing above.
			byte[] signature = mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
			return signingInput + "." + URL.encodeToString(signature);
		}
		catch (java.security.GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 unavailable", ex);
		}
	}

	static String decodeSegment(String token, int index) {
		return new String(Base64.getUrlDecoder().decode(token.split("\\.")[index]), StandardCharsets.UTF_8);
	}
}
