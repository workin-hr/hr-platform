package com.workin.spike.identity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Minimal JWT issuance/parsing for the H2 spike -- short-lived access
 * token, company_id claim only (no refresh-token rotation here; that is
 * ADR-0005's own scope, already decided, and orthogonal to what H2
 * compares). Real access-token TTL is a separate, still-open refinement
 * per ADR-0005's Open Questions -- the spike uses a placeholder value
 * from application.properties, not a chosen final number.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessTokenTtl;

    public JwtService(
            @Value("${spike.jwt.secret}") String secret,
            @Value("${spike.jwt.access-token-ttl-minutes}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.accessTokenTtl = Duration.ofMinutes(ttlMinutes);
    }

    public String issueAccessToken(Long companyId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(companyId))
                .claim("company_id", companyId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key)
                .compact();
    }

    public Long extractCompanyId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.get("company_id", Long.class);
    }
}
