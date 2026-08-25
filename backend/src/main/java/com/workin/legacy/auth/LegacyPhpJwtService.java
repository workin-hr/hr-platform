package com.workin.legacy.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workin.legacy.LegacyValues;

/**
 * Frozen Phase-1 JWT contract from hr-legacy functions.php at d113204.
 *
 * <p>This is intentionally separate from the new-platform JwtService. The
 * compatibility surface must accept tokens already issued by PHP and must
 * issue tokens the unchanged mobile/desktop clients understand. PHP uses a
 * minimal hand-written HS256 token with no issuer, audience, subject, iat,
 * sid, jti, membership_id or tenant_id claims.
 */
@Service
public class LegacyPhpJwtService {

	private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
	private static final String HEADER = base64Url(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

	private final byte[] secret;
	private final long expiryHours;
	private final ObjectMapper objectMapper;

	public LegacyPhpJwtService(
			@Value("${app.jwt.secret}") String secret,
			@Value("${app.legacy-jwt.expiry-hours:87600}") long expiryHours,
			ObjectMapper objectMapper) {
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
		this.expiryHours = expiryHours;
		this.objectMapper = objectMapper;
	}

	/** Mirrors employee_issue_session_token() -> jwtEncode() exactly. */
	public String issueEmployeeToken(long employeeId, long companyId, String role, long tokenVersion) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("type", "employee");
		payload.put("employee_id", employeeId);
		payload.put("company_id", companyId);
		payload.put("role", role);
		payload.put("token_version", tokenVersion);
		return encode(payload);
	}

	/**
	 * Mirrors jwtDecode(): signature plus optional exp are the only token-level
	 * checks PHP performs. Required auth fields are interpreted later by the
	 * request guard, just like requireAuth()/requireEmployeeSessionValid().
	 */
	public DecodedToken decode(String token) {
		try {
			String[] parts = token.split("\\.", -1);
			if (parts.length != 3) {
				return null;
			}
			byte[] expected = hmac((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
			byte[] actual = Base64.getUrlDecoder().decode(pad(parts[2]));
			if (!MessageDigest.isEqual(expected, actual)) {
				return null;
			}

			Map<String, Object> payload = objectMapper.readValue(
					Base64.getUrlDecoder().decode(pad(parts[1])), MAP_TYPE);
			Object exp = payload.get("exp");
			if (exp != null && LegacyValues.toPhpLong(exp) < Instant.now().getEpochSecond()) {
				return null;
			}

			return new DecodedToken(
					LegacyValues.toPhpString(payload.get("type")),
					LegacyValues.toPhpLong(payload.get("employee_id")),
					LegacyValues.toPhpLong(payload.get("company_id")),
					LegacyValues.toPhpString(payload.get("role")),
					payload.containsKey("token_version")
							? LegacyValues.toPhpLong(payload.get("token_version")) : null,
					payload);
		} catch (RuntimeException | java.io.IOException ex) {
			return null;
		}
	}

	private String encode(Map<String, Object> payloadWithoutExpiry) {
		try {
			Map<String, Object> payload = new LinkedHashMap<>(payloadWithoutExpiry);
			payload.put("exp", Instant.now().getEpochSecond() + Math.multiplyExact(expiryHours, 3600L));
			String body = base64Url(objectMapper.writeValueAsBytes(payload));
			String signingInput = HEADER + "." + body;
			return signingInput + "." + base64Url(hmac(signingInput.getBytes(StandardCharsets.US_ASCII)));
		} catch (java.io.IOException ex) {
			throw new IllegalStateException("Unable to encode legacy PHP JWT", ex);
		}
	}

	private byte[] hmac(byte[] value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return mac.doFinal(value);
		} catch (java.security.GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 unavailable", ex);
		}
	}

	private static String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String pad(String value) {
		int padding = (4 - value.length() % 4) % 4;
		return value + "=".repeat(padding);
	}

	public record DecodedToken(
			String type,
			long employeeId,
			long companyId,
			String role,
			Long tokenVersion,
			Map<String, Object> payload) {
	}
}
