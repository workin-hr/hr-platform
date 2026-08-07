package com.workin.backend.identity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;

/**
 * Issues short-lived access tokens per
 * docs/adr/ADR-0005-authentication-direction.md and
 * docs/adr/ADR-0010-authorization-model.md Dimension 6: minimal
 * identity/session context only (sub, sid, jti, membership_id,
 * tenant_id, iss, aud, iat, exp) -- no role, permission, or
 * resource-scope data is ever embedded. {@code membership_id}/
 * {@code tenant_id} are context selectors, not proof of active
 * membership; every request re-validates via
 * {@link com.workin.backend.tenancy.TenantContextService}.
 *
 * <p>Refresh-token issuance/rotation/revocation (ADR-0005's target
 * design, items 2-3) lives in {@link RefreshTokenService}; the
 * {@code sid} claim carries that service's session (family) id, so
 * every access token is correlated to the session that issued it.
 */
@Service
public class JwtService {

	private static final String ISSUER = "workin-backend";
	private static final String AUDIENCE = "workin-clients";

	private final SecretKey signingKey;
	private final long accessTokenTtlSeconds;

	public JwtService(
			@Value("${app.jwt.secret}") String secret,
			@Value("${app.jwt.access-token-ttl-seconds:900}") long accessTokenTtlSeconds) {
		this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
		this.accessTokenTtlSeconds = accessTokenTtlSeconds;
	}

	public String issueAccessToken(Long identityId, Long membershipId, Long companyId, String sessionId) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(String.valueOf(identityId))
				.claim("sid", sessionId)
				.id(UUID.randomUUID().toString())
				.claim("membership_id", membershipId)
				.claim("tenant_id", companyId)
				.issuer(ISSUER)
				.audience().add(AUDIENCE).and()
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(accessTokenTtlSeconds, ChronoUnit.SECONDS)))
				.signWith(signingKey)
				.compact();
	}

	public Claims parseAndValidate(String token) {
		Claims claims = Jwts.parser()
				.verifyWith(signingKey)
				.requireIssuer(ISSUER)
				.build()
				.parseSignedClaims(token)
				.getPayload();
		validateAudienceClaim(claims.get("aud"));
		validateRequiredNumberClaim(claims, "membership_id");
		validateRequiredNumberClaim(claims, "tenant_id");
		return claims;
	}

	private static void validateAudienceClaim(Object audienceClaim) {
		if (audienceClaim instanceof String audience && AUDIENCE.equals(audience)) {
			return;
		}
		if (audienceClaim instanceof Collection<?> audienceValues) {
			for (Object audienceValue : audienceValues) {
				if (AUDIENCE.equals(audienceValue)) {
					return;
				}
			}
		}
		throw new MalformedJwtException("Token audience is missing or invalid");
	}

	private static void validateRequiredNumberClaim(Claims claims, String claimName) {
		if (claims.get(claimName, Number.class) == null) {
			throw new MalformedJwtException("Token is missing required claim `" + claimName + "`");
		}
	}

}
