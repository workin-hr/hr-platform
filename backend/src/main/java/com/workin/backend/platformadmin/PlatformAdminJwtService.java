package com.workin.backend.platformadmin;

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
 * Issues and validates platform-admin access tokens -- deliberately a
 * separate token family from {@link com.workin.backend.identity.JwtService},
 * with its own issuer/audience, so a tenant-identity token can never be
 * accepted here and vice versa
 * (docs/architecture/authorization-model.md §8: platform and tenant
 * permission namespaces "never overlapping accidentally"). Shares the
 * same signing key as the tenant-identity token family -- the distinct
 * issuer already makes cross-domain forgery impossible, so a second
 * secret would only add operational burden, not security.
 *
 * <p>Claims are minimal: {@code sub} (platform-admin id), {@code sid},
 * {@code jti}, {@code iss}, {@code aud}, {@code iat}, {@code exp} -- no
 * {@code membership_id}/{@code tenant_id} (the platform domain has
 * neither), no role or permission data.
 */
@Service
public class PlatformAdminJwtService {

	private static final String ISSUER = "workin-backend-platform-admin";
	private static final String AUDIENCE = "workin-platform-admin-clients";

	private final SecretKey signingKey;
	private final long accessTokenTtlSeconds;

	public PlatformAdminJwtService(
			@Value("${app.jwt.secret}") String secret,
			@Value("${app.platform-admin.jwt.access-token-ttl-seconds:900}") long accessTokenTtlSeconds) {
		this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
		this.accessTokenTtlSeconds = accessTokenTtlSeconds;
	}

	public String issueAccessToken(Long platformAdminId, String sessionId) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(String.valueOf(platformAdminId))
				.claim("sid", sessionId)
				.id(UUID.randomUUID().toString())
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

}
