package com.workin.legacy.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Frozen Phase-1 JWT contract from hr-legacy functions.php at d113204.
 *
 * <p>This intentionally does not use the new-platform JwtService. PHP uses a
 * hand-written HS256 token with a flat payload and no issuer, audience,
 * subject, iat, sid, jti, membership_id or tenant_id claims. Keeping the
 * implementation flat also avoids introducing a JSON dependency solely for
 * an already-frozen compatibility token.
 */
@Service
public class LegacyPhpJwtService {

	private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
	private static final String HEADER = base64Url(HEADER_JSON.getBytes(StandardCharsets.UTF_8));

	private final byte[] secret;
	private final long expiryHours;

	public LegacyPhpJwtService(
			@Value("${app.jwt.secret}") String secret,
			@Value("${app.legacy-jwt.expiry-hours:87600}") long expiryHours) {
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
		this.expiryHours = expiryHours;
	}

	/** Mirrors employee_issue_session_token() -> jwtEncode() exactly. */
	public String issueEmployeeToken(long employeeId, long companyId, String role, long tokenVersion) {
		long exp = expiryEpochSecond();
		String json = "{\"type\":\"employee\",\"employee_id\":" + employeeId
				+ ",\"company_id\":" + companyId
				+ ",\"role\":\"" + jsonEscape(role) + "\""
				+ ",\"token_version\":" + tokenVersion
				+ ",\"exp\":" + exp + "}";
		return encodeJson(json);
	}

	/** Mirrors login_desktop.php/login_company.php company-admin jwtEncode(). */
	public String issueCompanyToken(long companyId, String role) {
		long exp = expiryEpochSecond();
		String json = "{\"type\":\"company\",\"company_id\":" + companyId
				+ ",\"role\":\"" + jsonEscape(role) + "\""
				+ ",\"exp\":" + exp + "}";
		return encodeJson(json);
	}

	/**
	 * Mirrors jwtDecode(): verify HMAC-SHA256 over the original header/payload,
	 * decode the flat JSON payload, and reject only an expired token. PHP does
	 * not require iss/aud/sub or even inspect the JWT header's alg field.
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

			String json = new String(Base64.getUrlDecoder().decode(pad(parts[1])), StandardCharsets.UTF_8);
			Long exp = number(json, "exp");
			if (exp != null && exp < Instant.now().getEpochSecond()) {
				return null;
			}

			String type = string(json, "type");
			if (type == null) {
				// Both issueEmployeeToken() and issueCompanyToken() always set "type"; a transitional
				// Java token (sub/membership_id/tenant_id/token_version, no "type") is signed with the
				// same app.jwt.secret HS256 key, so the signature check above alone cannot tell the two
				// apart. Without this shape check, decode() returned a non-null DecodedToken with every
				// field empty/zero for a transitional token, and the filter took the PHP-authentication
				// branch instead of falling through to setTransitionalAuthentication() -- silently
				// authenticating the caller as identity/company 0 instead of the real transitional
				// principal (PR #120 review).
				return null;
			}
			Long employeeId = number(json, "employee_id");
			Long companyId = number(json, "company_id");
			String role = string(json, "role");
			Long tokenVersion = number(json, "token_version");

			Map<String, Object> payload = new LinkedHashMap<>();
			if (type != null) payload.put("type", type);
			if (employeeId != null) payload.put("employee_id", employeeId);
			if (companyId != null) payload.put("company_id", companyId);
			if (role != null) payload.put("role", role);
			if (tokenVersion != null) payload.put("token_version", tokenVersion);
			if (exp != null) payload.put("exp", exp);

			return new DecodedToken(
					type == null ? "" : type,
					employeeId == null ? 0L : employeeId,
					companyId == null ? 0L : companyId,
					role == null ? "" : role,
					tokenVersion,
					payload);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private String encodeJson(String json) {
		String body = base64Url(json.getBytes(StandardCharsets.UTF_8));
		String signingInput = HEADER + "." + body;
		return signingInput + "." + base64Url(hmac(signingInput.getBytes(StandardCharsets.US_ASCII)));
	}

	private long expiryEpochSecond() {
		return Instant.now().getEpochSecond() + Math.multiplyExact(expiryHours, 3600L);
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

	private static String string(String json, String key) {
		Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
				.matcher(json);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static Long number(String json, String key) {
		Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?[0-9]+)")
				.matcher(json);
		return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
	}

	private static String jsonEscape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
