# Sessions, Revocation, And Audit Attribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement opaque rotating refresh tokens, reuse-detection revocation, logout, revoke-all primitives, and platform-admin audit attribution for both token domains, per `docs/superpowers/specs/2026-08-06-auth-sessions-revocation-audit-design.md`.

**Architecture:** Two structurally separate session stores (`refresh_tokens`, `platform_admin_refresh_tokens`) mirroring the two JWT issuers; services return result objects instead of throwing inside transactions so revocation side effects commit even when the HTTP answer is 401; a platform-only `platform_admin_audit_events` table written through one service.

**Tech Stack:** Spring Boot 4.1 / Java 25 / Spring Data JPA / Flyway / jjwt 0.13 / Testcontainers 2.x (PostgreSQL 17). Tests run inside WSL Ubuntu-24.04 (the only local Docker host).

## Global Constraints

- `backend/` only — D-028 authorizes no other component directory.
- Access-token claim shape is fixed by ADR-0010 Dimension 6: `sub`, `sid`, `jti`, `membership_id`, `tenant_id`, `iss`, `aud`, `iat`, `exp` (platform domain: no membership/tenant claims). No role/permission data in tokens, ever.
- Raw refresh-token values are returned to the client once and never persisted — only SHA-256 hex digests are stored.
- Rejection paths are plain `401` with no validity oracle; logout is always `204`.
- Never mutate `identities.active` or `platform_admins.active` from logout (`hr-legacy#15` regression guarantee).
- Transaction rule: any state change that must survive a 401 answer (family revocation, audit rows) must not be followed by a `RuntimeException` inside the same transaction — services return `Optional`/result objects and controllers translate to status codes.
- Indentation: tabs in Java (house style), 4 spaces in SQL.
- All git pushes are human-only (`scripts/git_guard.py`); commits stay local.

## Test Execution Environment (applies to every "Run" step)

Local Windows has no Docker; WSL `Ubuntu-24.04` has Docker 29.4.1 and, after Task 0, a Temurin JDK 25 under `~/.jdks`. Every Gradle test command in this plan means, executed from PowerShell:

```powershell
wsl -d Ubuntu-24.04 -- bash -c 'export JAVA_HOME=$(ls -d ~/.jdks/jdk-25*| head -1) && cd /mnt/d/Courses/hr-platform/backend && ./gradlew <ARGS>'
```

`build.gradle` already sets `DOCKER_HOST=unix:///var/run/docker.sock` for the `test` task, which matches WSL's daemon socket.

---

### Task 0: WSL test environment bootstrap + green baseline

**Files:** none (environment only).

**Interfaces:**

- Produces: a WSL JDK 25 at `~/.jdks/jdk-25*` and a proven-green baseline `./gradlew test` run all later tasks build on.

- [ ] **Step 1: Install a portable Temurin JDK 25 inside WSL**

```powershell
wsl -d Ubuntu-24.04 -- bash -c 'mkdir -p ~/.jdks && cd ~/.jdks && curl -fsSL -o jdk25.tar.gz "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse" && tar xzf jdk25.tar.gz && rm jdk25.tar.gz && ls -d ~/.jdks/jdk-25*'
```

Expected: prints a `jdk-25*` directory path.

- [ ] **Step 2: Baseline full test run (pre-change)**

Run: `./gradlew test` (via the WSL wrapper above).
Expected: BUILD SUCCESSFUL — all existing tests pass before any change. If this fails, stop and diagnose the environment; do not start Task 1 on a red baseline.

---

### Task 1: Opaque-token primitives, `refresh_tokens` schema, entity/repository/service

**Files:**

- Create: `backend/src/main/java/com/workin/backend/security/OpaqueTokens.java`
- Create: `backend/src/main/resources/db/migration/common/V8__create_refresh_tokens.sql`
- Create: `backend/src/main/java/com/workin/backend/identity/RefreshTokenStatus.java`
- Create: `backend/src/main/java/com/workin/backend/identity/RefreshToken.java`
- Create: `backend/src/main/java/com/workin/backend/identity/RefreshTokenRepository.java`
- Create: `backend/src/main/java/com/workin/backend/identity/RefreshTokenService.java`
- Modify: `backend/src/main/resources/application.properties` (one property)
- Test: `backend/src/test/java/com/workin/backend/identity/RefreshTokenServiceTest.java`

**Interfaces:**

- Consumes: `IdentityMembershipIndexService.findMembershipsForIdentity(Long)` → `List<MembershipSummary(Long membershipId, Long companyId)>` (existing, queries through the privileged DataSource by design — safe pre-tenant-context); `IdentityRepository` (existing).
- Produces:
  - `OpaqueTokens.newToken()` → `String` (43-char base64url of 256 random bits); `OpaqueTokens.sha256Hex(String)` → `String` (64 hex chars).
  - `RefreshTokenService.issue(Long identityId, Long membershipId, Long companyId)` → `IssuedRefreshToken(String rawToken, UUID familyId)`.
  - `RefreshTokenService.rotate(String presentedToken)` → `Optional<RotatedSession(String rawToken, UUID familyId, Long identityId, Long membershipId, Long companyId)>` — empty means 401; all revocation side effects already committed.
  - `RefreshTokenService.logout(String presentedToken)` → `void` (idempotent).
  - `RefreshTokenService.revokeAllForIdentity(Long identityId)` → `void`.

- [ ] **Step 1: Write the failing service-level test**

`backend/src/test/java/com/workin/backend/identity/RefreshTokenServiceTest.java`:

```java
package com.workin.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.identity.RefreshTokenService.IssuedRefreshToken;
import com.workin.backend.security.OpaqueTokens;

/**
 * Service-level coverage of the session state machine (rotation, reuse
 * detection, revocation). HTTP-level behavior is AuthSessionFlowTest's
 * job (Task 2); this class exercises the transitions directly, including
 * the ones that are awkward to reach through HTTP (expiry aging).
 */
class RefreshTokenServiceTest extends AbstractIntegrationTest {

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    @Qualifier("flywayDataSource")
    private DataSource flywayDataSource;

    private record Fixture(Long identityId, Long membershipId, Long companyId) {
    }

    private Fixture createIdentityWithMembership() {
        JdbcTemplate jdbc = new JdbcTemplate(flywayDataSource);
        String phone = "+2077" + System.nanoTime() % 100_000_000L;
        Long companyId = jdbc.queryForObject(
                "INSERT INTO companies (name, phone) VALUES ('Session Co', ?) RETURNING id", Long.class, phone);
        Long identityId = jdbc.queryForObject(
                "INSERT INTO identities (phone, password_hash) VALUES (?, 'x') RETURNING id", Long.class, phone);
        Long membershipId = jdbc.queryForObject(
                "INSERT INTO tenant_memberships (identity_id, company_id, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
                Long.class, identityId, companyId);
        return new Fixture(identityId, membershipId, companyId);
    }

    @Test
    void issueThenRotateReturnsANewTokenInTheSameFamily() {
        Fixture fixture = createIdentityWithMembership();
        IssuedRefreshToken issued = refreshTokenService.issue(
                fixture.identityId(), fixture.membershipId(), fixture.companyId());

        var rotated = refreshTokenService.rotate(issued.rawToken());

        assertThat(rotated).isPresent();
        assertThat(rotated.get().familyId()).isEqualTo(issued.familyId());
        assertThat(rotated.get().rawToken()).isNotEqualTo(issued.rawToken());
        assertThat(rotated.get().membershipId()).isEqualTo(fixture.membershipId());
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() {
        Fixture fixture = createIdentityWithMembership();
        IssuedRefreshToken issued = refreshTokenService.issue(
                fixture.identityId(), fixture.membershipId(), fixture.companyId());
        var rotated = refreshTokenService.rotate(issued.rawToken());

        assertThat(refreshTokenService.rotate(issued.rawToken())).isEmpty();
        // The newest token in the family must be dead too -- family
        // revocation, not just single-token rejection.
        assertThat(refreshTokenService.rotate(rotated.get().rawToken())).isEmpty();
    }

    @Test
    void anUnknownTokenIsRejected() {
        assertThat(refreshTokenService.rotate(OpaqueTokens.newToken())).isEmpty();
    }

    @Test
    void anExpiredTokenIsRejected() {
        Fixture fixture = createIdentityWithMembership();
        IssuedRefreshToken issued = refreshTokenService.issue(
                fixture.identityId(), fixture.membershipId(), fixture.companyId());
        new JdbcTemplate(flywayDataSource).update(
                "UPDATE refresh_tokens SET expires_at = now() - interval '1 day' WHERE token_hash = ?",
                OpaqueTokens.sha256Hex(issued.rawToken()));

        assertThat(refreshTokenService.rotate(issued.rawToken())).isEmpty();
    }

    @Test
    void rotationFailsClosedWhenTheMembershipIsNoLongerActive() {
        Fixture fixture = createIdentityWithMembership();
        IssuedRefreshToken issued = refreshTokenService.issue(
                fixture.identityId(), fixture.membershipId(), fixture.companyId());
        new JdbcTemplate(flywayDataSource).update(
                "UPDATE tenant_memberships SET status = 'DISABLED' WHERE id = ?", fixture.membershipId());

        assertThat(refreshTokenService.rotate(issued.rawToken())).isEmpty();
    }

    @Test
    void rotationFailsClosedWhenTheIdentityIsDeactivated() {
        Fixture fixture = createIdentityWithMembership();
        IssuedRefreshToken issued = refreshTokenService.issue(
                fixture.identityId(), fixture.membershipId(), fixture.companyId());
        new JdbcTemplate(flywayDataSource).update(
                "UPDATE identities SET active = FALSE WHERE id = ?", fixture.identityId());

        assertThat(refreshTokenService.rotate(issued.rawToken())).isEmpty();
    }

    @Test
    void logoutRevokesTheFamilyAndIsIdempotent() {
        Fixture fixture = createIdentityWithMembership();
        IssuedRefreshToken issued = refreshTokenService.issue(
                fixture.identityId(), fixture.membershipId(), fixture.companyId());

        refreshTokenService.logout(issued.rawToken());
        assertThat(refreshTokenService.rotate(issued.rawToken())).isEmpty();
        refreshTokenService.logout(issued.rawToken());
        refreshTokenService.logout(OpaqueTokens.newToken());
    }

    @Test
    void revokeAllForIdentityKillsEverySession() {
        Fixture fixture = createIdentityWithMembership();
        IssuedRefreshToken first = refreshTokenService.issue(
                fixture.identityId(), fixture.membershipId(), fixture.companyId());
        IssuedRefreshToken second = refreshTokenService.issue(
                fixture.identityId(), fixture.membershipId(), fixture.companyId());
        assertThat(first.familyId()).isNotEqualTo(second.familyId());

        refreshTokenService.revokeAllForIdentity(fixture.identityId());

        assertThat(refreshTokenService.rotate(first.rawToken())).isEmpty();
        assertThat(refreshTokenService.rotate(second.rawToken())).isEmpty();
    }

    @Test
    void rawTokensAreNeverStored() {
        Fixture fixture = createIdentityWithMembership();
        IssuedRefreshToken issued = refreshTokenService.issue(
                fixture.identityId(), fixture.membershipId(), fixture.companyId());

        Integer rawMatches = new JdbcTemplate(flywayDataSource).queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, issued.rawToken());
        Integer hashMatches = new JdbcTemplate(flywayDataSource).queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class,
                OpaqueTokens.sha256Hex(issued.rawToken()));

        assertThat(rawMatches).isZero();
        assertThat(hashMatches).isEqualTo(1);
    }

    @Test
    void familyIdBecomesTheUuidSessionIdentity() {
        Fixture fixture = createIdentityWithMembership();
        IssuedRefreshToken issued = refreshTokenService.issue(
                fixture.identityId(), fixture.membershipId(), fixture.companyId());
        assertThat(issued.familyId()).isInstanceOf(UUID.class);
    }

}
```

Note: check the real `companies` schema (`V1__create_companies.sql`) before relying on the fixture INSERT column list; adjust the INSERT to the actual NOT NULL columns.

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./gradlew test --tests 'com.workin.backend.identity.RefreshTokenServiceTest'`
Expected: compilation FAILURE (`RefreshTokenService` does not exist).

- [ ] **Step 3: Implement the migration and production code**

`backend/src/main/resources/db/migration/common/V8__create_refresh_tokens.sql`:

```sql
-- Opaque rotating refresh tokens for the tenant-identity token domain
-- (docs/adr/ADR-0005-authentication-direction.md, Target Design items
-- 1-3; docs/superpowers/specs/2026-08-06-auth-sessions-revocation-audit-design.md).
-- Global like identities (no RLS): a session belongs to an identity,
-- not to a tenant. family_id is the session identity, constant across
-- rotations; token_hash is SHA-256 hex -- the raw value is returned to
-- the client once and never stored. status is a real CHECK constraint,
-- not an app-level convention (the hr-legacy#21 lesson).
CREATE TABLE refresh_tokens (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identity_id BIGINT NOT NULL REFERENCES identities(id),
    membership_id BIGINT NOT NULL REFERENCES tenant_memberships(id),
    company_id BIGINT NOT NULL REFERENCES companies(id),
    family_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_identity_id_idx ON refresh_tokens (identity_id);
```

`backend/src/main/java/com/workin/backend/security/OpaqueTokens.java`:

```java
package com.workin.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque (non-JWT) token primitives shared by both session domains.
 * Refresh tokens are deliberately opaque random values looked up
 * server-side "so [they] can be looked up, listed, and revoked
 * individually" (docs/security/authentication-remediation-design.md,
 * Target Design item 1) -- only the SHA-256 digest is ever persisted.
 */
public final class OpaqueTokens {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private OpaqueTokens() {
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

}
```

`backend/src/main/java/com/workin/backend/identity/RefreshTokenStatus.java`:

```java
package com.workin.backend.identity;

public enum RefreshTokenStatus {
    ACTIVE,
    ROTATED,
    REVOKED
}
```

`backend/src/main/java/com/workin/backend/identity/RefreshToken.java`:

```java
package com.workin.backend.identity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One rotation link in a refresh-token family. The family
 * ({@code family_id}) is the session; rotation adds a new ACTIVE link
 * and retires the previous one as ROTATED. Presenting a retired link
 * again is the accepted design's compromise signal and revokes the
 * whole family (docs/security/authentication-remediation-design.md,
 * Target Design item 2).
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "identity_id", nullable = false)
    private Long identityId;

    @Column(name = "membership_id", nullable = false)
    private Long membershipId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefreshTokenStatus status = RefreshTokenStatus.ACTIVE;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RefreshToken() {
    }

    public RefreshToken(Long identityId, Long membershipId, Long companyId, UUID familyId, String tokenHash,
            Instant expiresAt) {
        this.identityId = identityId;
        this.membershipId = membershipId;
        this.companyId = companyId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Long getIdentityId() {
        return identityId;
    }

    public Long getMembershipId() {
        return membershipId;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public RefreshTokenStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

}
```

`backend/src/main/java/com/workin/backend/identity/RefreshTokenRepository.java`:

```java
package com.workin.backend.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Guarded rotation flip: returns 0 when a concurrent rotation
     * already retired this link, which the caller must treat as reuse.
     */
    @Modifying
    @Query("update RefreshToken t set t.status = :to where t.id = :id and t.status = :expected")
    int transitionIfStatus(
            @Param("id") Long id,
            @Param("expected") RefreshTokenStatus expected,
            @Param("to") RefreshTokenStatus to);

    @Modifying
    @Query("update RefreshToken t set t.status = :to where t.familyId = :familyId")
    int setStatusForFamily(@Param("familyId") UUID familyId, @Param("to") RefreshTokenStatus to);

    @Modifying
    @Query("update RefreshToken t set t.status = :to where t.identityId = :identityId")
    int setStatusForIdentity(@Param("identityId") Long identityId, @Param("to") RefreshTokenStatus to);

}
```

`backend/src/main/java/com/workin/backend/identity/RefreshTokenService.java`:

```java
package com.workin.backend.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.security.OpaqueTokens;
import com.workin.backend.tenancy.IdentityMembershipIndexService;

/**
 * The tenant-domain session state machine (ADR-0005 Target Design items
 * 1-3): issue, rotate-with-reuse-detection, logout, revoke-all.
 *
 * <p>Every method that must persist a revocation and <em>also</em>
 * answer 401 returns a result object instead of throwing -- a
 * RuntimeException inside the transaction would roll the revocation
 * back, silently disarming reuse detection. Controllers translate empty
 * results to 401.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final IdentityRepository identityRepository;
    private final IdentityMembershipIndexService membershipIndexService;
    private final long refreshTokenTtlSeconds;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            IdentityRepository identityRepository,
            IdentityMembershipIndexService membershipIndexService,
            @Value("${app.jwt.refresh-token-ttl-seconds:5184000}") long refreshTokenTtlSeconds) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.identityRepository = identityRepository;
        this.membershipIndexService = membershipIndexService;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    @Transactional
    public IssuedRefreshToken issue(Long identityId, Long membershipId, Long companyId) {
        String rawToken = OpaqueTokens.newToken();
        UUID familyId = UUID.randomUUID();
        refreshTokenRepository.save(new RefreshToken(
                identityId, membershipId, companyId, familyId,
                OpaqueTokens.sha256Hex(rawToken),
                Instant.now().plusSeconds(refreshTokenTtlSeconds)));
        return new IssuedRefreshToken(rawToken, familyId);
    }

    @Transactional
    public Optional<RotatedSession> rotate(String presentedToken) {
        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash(OpaqueTokens.sha256Hex(presentedToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken current = found.get();

        if (current.getStatus() != RefreshTokenStatus.ACTIVE) {
            revokeFamilyForReuse(current);
            return Optional.empty();
        }
        if (current.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        if (refreshTokenRepository.transitionIfStatus(
                current.getId(), RefreshTokenStatus.ACTIVE, RefreshTokenStatus.ROTATED) != 1) {
            revokeFamilyForReuse(current);
            return Optional.empty();
        }

        boolean identityActive = identityRepository.findById(current.getIdentityId())
                .map(Identity::isActive)
                .orElse(false);
        boolean membershipActive = membershipIndexService
                .findMembershipsForIdentity(current.getIdentityId()).stream()
                .anyMatch(membership -> membership.membershipId().equals(current.getMembershipId()));
        if (!identityActive || !membershipActive) {
            refreshTokenRepository.setStatusForFamily(current.getFamilyId(), RefreshTokenStatus.REVOKED);
            return Optional.empty();
        }

        String rawToken = OpaqueTokens.newToken();
        refreshTokenRepository.save(new RefreshToken(
                current.getIdentityId(), current.getMembershipId(), current.getCompanyId(), current.getFamilyId(),
                OpaqueTokens.sha256Hex(rawToken),
                Instant.now().plusSeconds(refreshTokenTtlSeconds)));
        return Optional.of(new RotatedSession(
                rawToken, current.getFamilyId(),
                current.getIdentityId(), current.getMembershipId(), current.getCompanyId()));
    }

    @Transactional
    public void logout(String presentedToken) {
        refreshTokenRepository.findByTokenHash(OpaqueTokens.sha256Hex(presentedToken))
                .ifPresent(token -> refreshTokenRepository
                        .setStatusForFamily(token.getFamilyId(), RefreshTokenStatus.REVOKED));
    }

    @Transactional
    public void revokeAllForIdentity(Long identityId) {
        refreshTokenRepository.setStatusForIdentity(identityId, RefreshTokenStatus.REVOKED);
    }

    private void revokeFamilyForReuse(RefreshToken presented) {
        log.warn("Refresh-token reuse detected for identity {} family {} -- revoking the whole family",
                presented.getIdentityId(), presented.getFamilyId());
        refreshTokenRepository.setStatusForFamily(presented.getFamilyId(), RefreshTokenStatus.REVOKED);
    }

    public record IssuedRefreshToken(String rawToken, UUID familyId) {
    }

    public record RotatedSession(String rawToken, UUID familyId, Long identityId, Long membershipId, Long companyId) {
    }

}
```

`application.properties` — add below the existing `app.jwt.access-token-ttl-seconds` line:

```properties
# ADR-0005: refresh-token lifetime. 60 days, inside the accepted
# design's 30-90 day candidate range; a configurable starting value,
# not a product decision (that trade-off is still an open question in
# docs/security/authentication-remediation-design.md).
app.jwt.refresh-token-ttl-seconds=5184000
```

- [ ] **Step 4: Run the test class until green**

Run: `./gradlew test --tests 'com.workin.backend.identity.RefreshTokenServiceTest'`
Expected: PASS (all tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main backend/src/test
git commit -m "feat(backend): tenant-domain refresh-token sessions -- rotation, reuse revocation, revoke-all (ADR-0005)"
```

---

### Task 2: Tenant HTTP surface — refresh/logout endpoints, widened AuthResponse, sid coherence

**Files:**

- Create: `backend/src/main/java/com/workin/backend/identity/RefreshTokenRequest.java`
- Modify: `backend/src/main/java/com/workin/backend/identity/AuthResponse.java`
- Modify: `backend/src/main/java/com/workin/backend/identity/AuthController.java`
- Modify: `backend/src/main/java/com/workin/backend/identity/JwtService.java` (sid parameter + Javadoc)
- Modify: `backend/src/test/java/com/workin/backend/identity/AuthFlowTest.java` (assert new field present)
- Modify: `backend/src/test/java/com/workin/backend/tenancy/TenantContextIsolationTest.java:54` (forged-token fixture passes a session id: add a fourth argument `java.util.UUID.randomUUID().toString()` to the `issueAccessToken` call — it forges a token deliberately, so no real session row is needed)
- Test: `backend/src/test/java/com/workin/backend/identity/AuthSessionFlowTest.java`

**Interfaces:**

- Consumes: `RefreshTokenService` (Task 1 signatures).
- Produces:
  - `AuthResponse(String accessToken, String refreshToken, Long membershipId, Long companyId)`.
  - `JwtService.issueAccessToken(Long identityId, Long membershipId, Long companyId, String sessionId)` — `sid` claim now carries the caller-supplied session (family) id.
  - `POST /api/auth/refresh` and `POST /api/auth/logout`, both taking `RefreshTokenRequest(String refreshToken)`.

- [ ] **Step 1: Write the failing HTTP-level test**

`backend/src/test/java/com/workin/backend/identity/AuthSessionFlowTest.java`:

```java
package com.workin.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.workin.backend.AbstractIntegrationTest;

class AuthSessionFlowTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtService jwtService;

    private AuthResponse register() {
        return restTemplate.postForEntity(
                "/api/auth/register",
                new RegisterCompanyRequest("Session Co", uniquePhone(), "correct horse battery staple"),
                AuthResponse.class).getBody();
    }

    @Test
    void loginAndRegisterReturnAnAccessRefreshPair() {
        AuthResponse registered = register();
        assertThat(registered.accessToken()).isNotBlank();
        assertThat(registered.refreshToken()).isNotBlank();
    }

    @Test
    void refreshRotatesTheSessionAndTheOldTokenDies() {
        AuthResponse registered = register();

        ResponseEntity<AuthResponse> refreshed = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshTokenRequest(registered.refreshToken()), AuthResponse.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody().accessToken()).isNotBlank();
        assertThat(refreshed.getBody().refreshToken()).isNotEqualTo(registered.refreshToken());
        assertThat(refreshed.getBody().companyId()).isEqualTo(registered.companyId());

        ResponseEntity<String> reuse = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshTokenRequest(registered.refreshToken()), String.class);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Reuse revoked the whole family, so the newest token is dead too.
        ResponseEntity<String> newest = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshTokenRequest(refreshed.getBody().refreshToken()), String.class);
        assertThat(newest.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theAccessTokenSidClaimIsTheSessionFamilyAndSurvivesRotation() {
        AuthResponse registered = register();
        String sidAtLogin = jwtService.parseAndValidate(registered.accessToken()).get("sid", String.class);

        AuthResponse refreshed = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshTokenRequest(registered.refreshToken()), AuthResponse.class).getBody();
        String sidAfterRefresh = jwtService.parseAndValidate(refreshed.accessToken()).get("sid", String.class);

        assertThat(sidAtLogin).isNotBlank();
        assertThat(sidAfterRefresh).isEqualTo(sidAtLogin);
    }

    @Test
    void logoutRevokesTheSessionIsIdempotentAndNeverDeactivatesTheAccount() {
        String phone = uniquePhone();
        AuthResponse registered = restTemplate.postForEntity(
                "/api/auth/register",
                new RegisterCompanyRequest("Session Co", phone, "correct horse battery staple"),
                AuthResponse.class).getBody();

        ResponseEntity<Void> logout = restTemplate.postForEntity(
                "/api/auth/logout", new RefreshTokenRequest(registered.refreshToken()), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> refreshAfterLogout = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshTokenRequest(registered.refreshToken()), String.class);
        assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Void> secondLogout = restTemplate.postForEntity(
                "/api/auth/logout", new RefreshTokenRequest(registered.refreshToken()), Void.class);
        assertThat(secondLogout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Void> unknownLogout = restTemplate.postForEntity(
                "/api/auth/logout", new RefreshTokenRequest("never-issued-token"), Void.class);
        assertThat(unknownLogout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // hr-legacy#15 regression guarantee: logout must never deactivate
        // the account -- the user can immediately log back in.
        ResponseEntity<AuthResponse> loginAgain = restTemplate.postForEntity(
                "/api/auth/login",
                new LoginRequest(phone, "correct horse battery staple"),
                AuthResponse.class);
        assertThat(loginAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void eachLoginIsItsOwnSession() {
        String phone = uniquePhone();
        restTemplate.postForEntity(
                "/api/auth/register",
                new RegisterCompanyRequest("Session Co", phone, "correct horse battery staple"),
                AuthResponse.class);
        AuthResponse firstLogin = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(phone, "correct horse battery staple"),
                AuthResponse.class).getBody();
        AuthResponse secondLogin = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(phone, "correct horse battery staple"),
                AuthResponse.class).getBody();

        // Logging out one session must not kill the other.
        restTemplate.postForEntity("/api/auth/logout", new RefreshTokenRequest(firstLogin.refreshToken()), Void.class);
        ResponseEntity<AuthResponse> refreshSecond = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshTokenRequest(secondLogin.refreshToken()), AuthResponse.class);
        assertThat(refreshSecond.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static String uniquePhone() {
        return "+2055" + System.nanoTime() % 100_000_000L;
    }

}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew test --tests 'com.workin.backend.identity.AuthSessionFlowTest'`
Expected: compilation FAILURE (`refreshToken()` / `RefreshTokenRequest` do not exist).

- [ ] **Step 3: Implement**

`AuthResponse.java` becomes:

```java
package com.workin.backend.identity;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        Long membershipId,
        Long companyId) {
}
```

`RefreshTokenRequest.java`:

```java
package com.workin.backend.identity;

import jakarta.validation.constraints.NotBlank;

/** Shared request body for the refresh and logout endpoints. */
public record RefreshTokenRequest(@NotBlank String refreshToken) {
}
```

`JwtService.issueAccessToken` — new signature; `sid` is caller-supplied (`.claim("sid", sessionId)` replaces the random UUID); update the class Javadoc's "Refresh-token issuance/rotation ... is not implemented in this first slice" paragraph to note refresh/rotation now lives in `RefreshTokenService` and `sid` is the session family id:

```java
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
```

`AuthController.java` becomes:

```java
package com.workin.backend.identity;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AuthController {

    private final RegistrationService registrationService;
    private final LoginService loginService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(
            RegistrationService registrationService,
            LoginService loginService,
            JwtService jwtService,
            RefreshTokenService refreshTokenService) {
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterCompanyRequest request) {
        RegistrationService.Registered registered = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(openSession(registered.identityId(), registered.membershipId(), registered.companyId()));
    }

    @PostMapping("/api/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        LoginService.Authenticated authenticated = loginService.login(request);
        return openSession(authenticated.identityId(), authenticated.membershipId(), authenticated.companyId());
    }

    @PostMapping("/api/auth/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        RefreshTokenService.RotatedSession session = refreshTokenService.rotate(request.refreshToken())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
        String accessToken = jwtService.issueAccessToken(
                session.identityId(), session.membershipId(), session.companyId(), session.familyId().toString());
        return new AuthResponse(accessToken, session.rawToken(), session.membershipId(), session.companyId());
    }

    @PostMapping("/api/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.logout(request.refreshToken());
    }

    private AuthResponse openSession(Long identityId, Long membershipId, Long companyId) {
        RefreshTokenService.IssuedRefreshToken session = refreshTokenService.issue(identityId, membershipId, companyId);
        String accessToken = jwtService.issueAccessToken(
                identityId, membershipId, companyId, session.familyId().toString());
        return new AuthResponse(accessToken, session.rawToken(), membershipId, companyId);
    }

}
```

`AuthFlowTest.java` — in `registerIssuesTokenAndTenantContext`, add after the `accessToken()` assertion:

```java
        assertThat(response.getBody().refreshToken()).isNotBlank();
```

- [ ] **Step 4: Run both identity test classes**

Run: `./gradlew test --tests 'com.workin.backend.identity.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): tenant refresh/logout endpoints; access-token sid becomes the session family id"
```

---

### Task 3: Platform-admin sessions — schema, service, endpoints, domain separation

**Files:**

- Create: `backend/src/main/resources/db/migration/common/V9__create_platform_admin_refresh_tokens.sql`
- Create: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminSessionStatus.java` (the platform domain gets its own status enum rather than importing `identity.RefreshTokenStatus` — the platform package must not depend on the tenant-identity package for its own state)
- Create: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminRefreshToken.java`
- Create: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminRefreshTokenRepository.java`
- Create: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminSessionService.java`
- Create: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminRefreshTokenRequest.java`
- Modify: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminAuthResponse.java`
- Modify: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminAuthController.java`
- Modify: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminJwtService.java` (sid parameter)
- Modify: `backend/src/main/java/com/workin/backend/security/SecurityConfig.java` (permitAll for refresh/logout)
- Modify: `backend/src/test/java/com/workin/backend/platformadmin/PlatformAdminAuthFlowTest.java` (assert refresh token present)
- Modify: `backend/src/main/resources/application.properties` (platform refresh TTL)
- Test: `backend/src/test/java/com/workin/backend/platformadmin/PlatformAdminSessionFlowTest.java`

**Interfaces:**

- Consumes: `OpaqueTokens` (Task 1), `PlatformAdminRepository` (existing), `RefreshTokenService`/`AuthResponse`/`RefreshTokenRequest` (Tasks 1–2, for the cross-domain tests only).
- Produces:
  - `PlatformAdminAuthResponse(String accessToken, String refreshToken, Long platformAdminId)`.
  - `PlatformAdminJwtService.issueAccessToken(Long platformAdminId, String sessionId)`.
  - `PlatformAdminSessionService.issue(Long platformAdminId)` → `IssuedRefreshToken(String rawToken, UUID familyId)`; `.rotate(String)` → `Optional<RotatedSession(String rawToken, UUID familyId, Long platformAdminId)>`; `.logout(String)` → `void`; `.revokeAllForPlatformAdmin(Long)` → `void`.
  - `POST /api/platform-admin/refresh`, `POST /api/platform-admin/logout` taking `PlatformAdminRefreshTokenRequest(String refreshToken)`.
  - Task 4 wires audit calls into `PlatformAdminSessionService.logout` / reuse / revoke-all — it needs `rotate`'s reuse branch and `logout`'s found-session branch to be discrete code paths (they are, below).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/workin/backend/platformadmin/PlatformAdminSessionFlowTest.java`:

```java
package com.workin.backend.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.RegisterCompanyRequest;

class PlatformAdminSessionFlowTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    @Qualifier("flywayDataSource")
    private DataSource flywayDataSource;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformAdminSessionService platformAdminSessionService;

    private PlatformAdminAuthResponse loginNewAdmin() {
        String phone = uniquePhone();
        createPlatformAdmin(phone, "correct horse battery staple");
        return restTemplate.postForEntity(
                "/api/platform-admin/login",
                new PlatformAdminLoginRequest(phone, "correct horse battery staple"),
                PlatformAdminAuthResponse.class).getBody();
    }

    @Test
    void loginReturnsAnAccessRefreshPair() {
        PlatformAdminAuthResponse response = loginNewAdmin();
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
    }

    @Test
    void refreshRotatesAndReuseRevokesTheFamily() {
        PlatformAdminAuthResponse login = loginNewAdmin();

        ResponseEntity<PlatformAdminAuthResponse> refreshed = restTemplate.postForEntity(
                "/api/platform-admin/refresh",
                new PlatformAdminRefreshTokenRequest(login.refreshToken()),
                PlatformAdminAuthResponse.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody().refreshToken()).isNotEqualTo(login.refreshToken());

        ResponseEntity<String> reuse = restTemplate.postForEntity(
                "/api/platform-admin/refresh",
                new PlatformAdminRefreshTokenRequest(login.refreshToken()),
                String.class);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> newest = restTemplate.postForEntity(
                "/api/platform-admin/refresh",
                new PlatformAdminRefreshTokenRequest(refreshed.getBody().refreshToken()),
                String.class);
        assertThat(newest.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutRevokesTheSessionAndIsIdempotent() {
        PlatformAdminAuthResponse login = loginNewAdmin();

        ResponseEntity<Void> logout = restTemplate.postForEntity(
                "/api/platform-admin/logout",
                new PlatformAdminRefreshTokenRequest(login.refreshToken()),
                Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> refreshAfterLogout = restTemplate.postForEntity(
                "/api/platform-admin/refresh",
                new PlatformAdminRefreshTokenRequest(login.refreshToken()),
                String.class);
        assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Void> secondLogout = restTemplate.postForEntity(
                "/api/platform-admin/logout",
                new PlatformAdminRefreshTokenRequest(login.refreshToken()),
                Void.class);
        assertThat(secondLogout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void rotationFailsClosedWhenTheAdminIsDeactivated() {
        String phone = uniquePhone();
        createPlatformAdmin(phone, "correct horse battery staple");
        PlatformAdminAuthResponse login = restTemplate.postForEntity(
                "/api/platform-admin/login",
                new PlatformAdminLoginRequest(phone, "correct horse battery staple"),
                PlatformAdminAuthResponse.class).getBody();
        new JdbcTemplate(flywayDataSource).update(
                "UPDATE platform_admins SET active = FALSE WHERE phone = ?", phone);

        ResponseEntity<String> refresh = restTemplate.postForEntity(
                "/api/platform-admin/refresh",
                new PlatformAdminRefreshTokenRequest(login.refreshToken()),
                String.class);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void revokeAllForPlatformAdminKillsEverySession() {
        String phone = uniquePhone();
        createPlatformAdmin(phone, "correct horse battery staple");
        PlatformAdminAuthResponse first = restTemplate.postForEntity(
                "/api/platform-admin/login",
                new PlatformAdminLoginRequest(phone, "correct horse battery staple"),
                PlatformAdminAuthResponse.class).getBody();
        PlatformAdminAuthResponse second = restTemplate.postForEntity(
                "/api/platform-admin/login",
                new PlatformAdminLoginRequest(phone, "correct horse battery staple"),
                PlatformAdminAuthResponse.class).getBody();

        platformAdminSessionService.revokeAllForPlatformAdmin(first.platformAdminId());

        for (String refreshToken : new String[] { first.refreshToken(), second.refreshToken() }) {
            ResponseEntity<String> refresh = restTemplate.postForEntity(
                    "/api/platform-admin/refresh",
                    new PlatformAdminRefreshTokenRequest(refreshToken),
                    String.class);
            assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void aTenantRefreshTokenIsUselessInThePlatformDomainAndViceVersa() {
        AuthResponse tenant = restTemplate.postForEntity(
                "/api/auth/register",
                new RegisterCompanyRequest("Separation Co", uniquePhone(), "correct horse battery staple"),
                AuthResponse.class).getBody();
        PlatformAdminAuthResponse admin = loginNewAdmin();

        ResponseEntity<String> tenantTokenOnPlatform = restTemplate.postForEntity(
                "/api/platform-admin/refresh",
                new PlatformAdminRefreshTokenRequest(tenant.refreshToken()),
                String.class);
        assertThat(tenantTokenOnPlatform.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> platformTokenOnTenant = restTemplate.postForEntity(
                "/api/auth/refresh",
                new com.workin.backend.identity.RefreshTokenRequest(admin.refreshToken()),
                String.class);
        assertThat(platformTokenOnTenant.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void createPlatformAdmin(String phone, String password) {
        new JdbcTemplate(flywayDataSource).update(
                "INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, TRUE)",
                phone, passwordEncoder.encode(password));
    }

    private static String uniquePhone() {
        return "+2066" + System.nanoTime() % 100_000_000L;
    }

}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew test --tests 'com.workin.backend.platformadmin.PlatformAdminSessionFlowTest'`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement**

`V9__create_platform_admin_refresh_tokens.sql`:

```sql
-- Platform-domain twin of refresh_tokens (V8), deliberately a separate
-- table: the two session stores are disjoint by construction, mirroring
-- the two JWT issuers and SecurityConfig's two filter chains
-- (docs/architecture/authorization-model.md §8 -- platform and tenant
-- namespaces never overlap accidentally). Closes the session-revocation
-- half of F-26's remaining gap.
CREATE TABLE platform_admin_refresh_tokens (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    platform_admin_id BIGINT NOT NULL REFERENCES platform_admins(id),
    family_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX platform_admin_refresh_tokens_family_id_idx
    ON platform_admin_refresh_tokens (family_id);
CREATE INDEX platform_admin_refresh_tokens_platform_admin_id_idx
    ON platform_admin_refresh_tokens (platform_admin_id);
```

`PlatformAdminSessionStatus.java`:

```java
package com.workin.backend.platformadmin;

public enum PlatformAdminSessionStatus {
    ACTIVE,
    ROTATED,
    REVOKED
}
```

`PlatformAdminRefreshToken.java` — same shape as `RefreshToken` minus membership/company (entity boilerplate mirrors Task 1's entity: id, `platform_admin_id`, `family_id`, `token_hash`, enum status defaulting to `ACTIVE`, `expires_at`; constructor `(Long platformAdminId, UUID familyId, String tokenHash, Instant expiresAt)`; getters).

`PlatformAdminRefreshTokenRepository.java` — mirrors Task 1's repository with `findByTokenHash`, `transitionIfStatus(id, expected, to)`, `setStatusForFamily(familyId, to)`, and `setStatusForPlatformAdmin(platformAdminId, to)` (`update PlatformAdminRefreshToken t set t.status = :to where t.platformAdminId = :platformAdminId`), all using `PlatformAdminSessionStatus`.

`PlatformAdminSessionService.java`:

```java
package com.workin.backend.platformadmin;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.security.OpaqueTokens;

/**
 * Platform-domain session state machine -- the individual-session
 * revocation half of F-26's remaining closure criteria. Same
 * result-object-not-exception contract as the tenant domain's
 * RefreshTokenService: revocation side effects must commit even when
 * the HTTP answer is 401.
 */
@Service
public class PlatformAdminSessionService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminSessionService.class);

    private final PlatformAdminRefreshTokenRepository refreshTokenRepository;
    private final PlatformAdminRepository platformAdminRepository;
    private final long refreshTokenTtlSeconds;

    public PlatformAdminSessionService(
            PlatformAdminRefreshTokenRepository refreshTokenRepository,
            PlatformAdminRepository platformAdminRepository,
            @Value("${app.platform-admin.jwt.refresh-token-ttl-seconds:604800}") long refreshTokenTtlSeconds) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.platformAdminRepository = platformAdminRepository;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    @Transactional
    public IssuedRefreshToken issue(Long platformAdminId) {
        String rawToken = OpaqueTokens.newToken();
        UUID familyId = UUID.randomUUID();
        refreshTokenRepository.save(new PlatformAdminRefreshToken(
                platformAdminId, familyId,
                OpaqueTokens.sha256Hex(rawToken),
                Instant.now().plusSeconds(refreshTokenTtlSeconds)));
        return new IssuedRefreshToken(rawToken, familyId);
    }

    @Transactional
    public Optional<RotatedSession> rotate(String presentedToken) {
        Optional<PlatformAdminRefreshToken> found = refreshTokenRepository
                .findByTokenHash(OpaqueTokens.sha256Hex(presentedToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PlatformAdminRefreshToken current = found.get();

        if (current.getStatus() != PlatformAdminSessionStatus.ACTIVE) {
            revokeFamilyForReuse(current);
            return Optional.empty();
        }
        if (current.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        if (refreshTokenRepository.transitionIfStatus(
                current.getId(), PlatformAdminSessionStatus.ACTIVE, PlatformAdminSessionStatus.ROTATED) != 1) {
            revokeFamilyForReuse(current);
            return Optional.empty();
        }

        boolean adminActive = platformAdminRepository.findById(current.getPlatformAdminId())
                .map(PlatformAdmin::isActive)
                .orElse(false);
        if (!adminActive) {
            refreshTokenRepository.setStatusForFamily(current.getFamilyId(), PlatformAdminSessionStatus.REVOKED);
            return Optional.empty();
        }

        String rawToken = OpaqueTokens.newToken();
        refreshTokenRepository.save(new PlatformAdminRefreshToken(
                current.getPlatformAdminId(), current.getFamilyId(),
                OpaqueTokens.sha256Hex(rawToken),
                Instant.now().plusSeconds(refreshTokenTtlSeconds)));
        return Optional.of(new RotatedSession(rawToken, current.getFamilyId(), current.getPlatformAdminId()));
    }

    @Transactional
    public void logout(String presentedToken) {
        refreshTokenRepository.findByTokenHash(OpaqueTokens.sha256Hex(presentedToken))
                .ifPresent(token -> refreshTokenRepository
                        .setStatusForFamily(token.getFamilyId(), PlatformAdminSessionStatus.REVOKED));
    }

    @Transactional
    public void revokeAllForPlatformAdmin(Long platformAdminId) {
        refreshTokenRepository.setStatusForPlatformAdmin(platformAdminId, PlatformAdminSessionStatus.REVOKED);
    }

    private void revokeFamilyForReuse(PlatformAdminRefreshToken presented) {
        log.warn("Platform-admin refresh-token reuse detected for admin {} family {} -- revoking the whole family",
                presented.getPlatformAdminId(), presented.getFamilyId());
        refreshTokenRepository.setStatusForFamily(presented.getFamilyId(), PlatformAdminSessionStatus.REVOKED);
    }

    public record IssuedRefreshToken(String rawToken, UUID familyId) {
    }

    public record RotatedSession(String rawToken, UUID familyId, Long platformAdminId) {
    }

}
```

`PlatformAdminRefreshTokenRequest.java` — platform twin of `RefreshTokenRequest` (`@NotBlank String refreshToken`).

`PlatformAdminAuthResponse.java` becomes `(String accessToken, String refreshToken, Long platformAdminId)`.

`PlatformAdminJwtService.issueAccessToken(Long platformAdminId, String sessionId)` — `sid` claim is the caller-supplied session id (same one-line change as Task 2's `JwtService`).

`PlatformAdminAuthController` — inject `PlatformAdminSessionService`; login opens a session (`issue(...)` then `issueAccessToken(admin.getId(), issued.familyId().toString())`) and returns the widened response; add:

```java
    @PostMapping("/refresh")
    public PlatformAdminAuthResponse refresh(@Valid @RequestBody PlatformAdminRefreshTokenRequest request) {
        PlatformAdminSessionService.RotatedSession session = platformAdminSessionService.rotate(request.refreshToken())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
        String accessToken = platformAdminJwtService.issueAccessToken(
                session.platformAdminId(), session.familyId().toString());
        return new PlatformAdminAuthResponse(accessToken, session.rawToken(), session.platformAdminId());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody PlatformAdminRefreshTokenRequest request) {
        platformAdminSessionService.logout(request.refreshToken());
    }
```

`SecurityConfig` platform chain — widen the permitAll line:

```java
                    .requestMatchers("/api/platform-admin/login", "/api/platform-admin/refresh",
                            "/api/platform-admin/logout").permitAll()
```

(Refresh/logout authenticate by refresh-token possession — the access token may already be expired when they are called.)

`PlatformAdminAuthFlowTest.loginWithCorrectCredentialsSucceeds` — add:

```java
        assertThat(response.getBody().refreshToken()).isNotBlank();
```

`application.properties` — add below the platform-admin TTL line:

```properties
# 7 days, deliberately shorter than the tenant default: platform-admin
# sessions are the highest-privilege surface. Configurable starting
# value, same status as app.jwt.refresh-token-ttl-seconds.
app.platform-admin.jwt.refresh-token-ttl-seconds=604800
```

- [ ] **Step 4: Run the platform test classes**

Run: `./gradlew test --tests 'com.workin.backend.platformadmin.*' --tests 'com.workin.backend.security.*'`
Expected: PASS (including the pre-existing domain-separation test).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): platform-admin sessions -- refresh, logout, revoke-all, fail-closed on deactivation (F-26)"
```

---

### Task 4: Platform-admin audit attribution

**Files:**

- Create: `backend/src/main/resources/db/migration/common/V10__create_platform_admin_audit_events.sql`
- Create: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminAuditEventType.java`
- Create: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminAuditEvent.java`
- Create: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminAuditEventRepository.java`
- Create: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminAuditService.java`
- Modify: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminLoginService.java`
- Modify: `backend/src/main/java/com/workin/backend/platformadmin/PlatformAdminSessionService.java`
- Test: `backend/src/test/java/com/workin/backend/platformadmin/PlatformAdminAuditTest.java`

**Interfaces:**

- Consumes: Task 3's `PlatformAdminSessionService` internals (reuse branch, logout branch, revoke-all), `PlatformAdminLoginService`.
- Produces: `PlatformAdminAuditService.record(Long platformAdminId, PlatformAdminAuditEventType eventType, String detail)` — the single write path every future `platform.*` business endpoint must use (F-26's standing acceptance criterion); `PlatformAdminAuditEventType` values `LOGIN`, `LOGIN_FAILED`, `LOGOUT`, `SESSION_REUSE_REVOKED`, `ALL_SESSIONS_REVOKED`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/workin/backend/platformadmin/PlatformAdminAuditTest.java`:

```java
package com.workin.backend.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.AbstractIntegrationTest;

class PlatformAdminAuditTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    @Qualifier("flywayDataSource")
    private DataSource flywayDataSource;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformAdminSessionService platformAdminSessionService;

    private Long createPlatformAdmin(String phone, String password) {
        return new JdbcTemplate(flywayDataSource).queryForObject(
                "INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, TRUE) RETURNING id",
                Long.class, phone, passwordEncoder.encode(password));
    }

    private List<String> eventTypesFor(Long adminId) {
        return new JdbcTemplate(flywayDataSource).queryForList(
                "SELECT event_type FROM platform_admin_audit_events WHERE platform_admin_id = ? ORDER BY id",
                String.class, adminId);
    }

    private PlatformAdminAuthResponse login(String phone, String password) {
        return restTemplate.postForEntity(
                "/api/platform-admin/login",
                new PlatformAdminLoginRequest(phone, password),
                PlatformAdminAuthResponse.class).getBody();
    }

    @Test
    void successfulLoginIsAttributed() {
        String phone = uniquePhone();
        Long adminId = createPlatformAdmin(phone, "correct horse battery staple");
        login(phone, "correct horse battery staple");

        assertThat(eventTypesFor(adminId)).containsExactly("LOGIN");
    }

    @Test
    void failedLoginAgainstAKnownAdminIsAttributedButUnknownPhonesAreNot() {
        String phone = uniquePhone();
        Long adminId = createPlatformAdmin(phone, "correct horse battery staple");

        restTemplate.postForEntity(
                "/api/platform-admin/login",
                new PlatformAdminLoginRequest(phone, "wrong password"), String.class);
        restTemplate.postForEntity(
                "/api/platform-admin/login",
                new PlatformAdminLoginRequest("+20000000000", "whatever"), String.class);

        assertThat(eventTypesFor(adminId)).containsExactly("LOGIN_FAILED");
        Integer total = new JdbcTemplate(flywayDataSource).queryForObject(
                "SELECT count(*) FROM platform_admin_audit_events WHERE platform_admin_id NOT IN "
                        + "(SELECT id FROM platform_admins)",
                Integer.class);
        assertThat(total).isZero();
    }

    @Test
    void logoutAndReuseRevocationAreAttributed() {
        String phone = uniquePhone();
        Long adminId = createPlatformAdmin(phone, "correct horse battery staple");
        PlatformAdminAuthResponse first = login(phone, "correct horse battery staple");

        // Rotate once, then present the stale token -> reuse revocation.
        PlatformAdminAuthResponse rotated = restTemplate.postForEntity(
                "/api/platform-admin/refresh",
                new PlatformAdminRefreshTokenRequest(first.refreshToken()),
                PlatformAdminAuthResponse.class).getBody();
        restTemplate.postForEntity(
                "/api/platform-admin/refresh",
                new PlatformAdminRefreshTokenRequest(first.refreshToken()), String.class);

        // A fresh session, ended by explicit logout.
        PlatformAdminAuthResponse second = login(phone, "correct horse battery staple");
        restTemplate.postForEntity(
                "/api/platform-admin/logout",
                new PlatformAdminRefreshTokenRequest(second.refreshToken()), Void.class);

        platformAdminSessionService.revokeAllForPlatformAdmin(adminId);

        assertThat(eventTypesFor(adminId)).containsExactly(
                "LOGIN", "SESSION_REUSE_REVOKED", "LOGIN", "LOGOUT", "ALL_SESSIONS_REVOKED");
        assertThat(rotated.refreshToken()).isNotBlank();
    }

    private static String uniquePhone() {
        return "+2088" + System.nanoTime() % 100_000_000L;
    }

}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests 'com.workin.backend.platformadmin.PlatformAdminAuditTest'`
Expected: FAIL (table/service missing → compile or SQL failure).

- [ ] **Step 3: Implement**

`V10__create_platform_admin_audit_events.sql`:

```sql
-- Individual audit attribution for platform-admin activity -- the
-- second half of F-26's remaining closure criteria (hr-legacy#11: the
-- shared-password model had no audit trail at all). Every event is
-- attributed to a real platform_admins row; deliberately no free-text
-- principal column, so an unattributable event cannot be recorded here
-- (unknown-phone login probes stay in structured logs, ADR-0008).
-- Platform-global, not tenant data: no RLS, same as platform_admins.
CREATE TABLE platform_admin_audit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    platform_admin_id BIGINT NOT NULL REFERENCES platform_admins(id),
    event_type VARCHAR(64) NOT NULL,
    detail TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX platform_admin_audit_events_admin_id_idx
    ON platform_admin_audit_events (platform_admin_id);
```

`PlatformAdminAuditEventType.java`:

```java
package com.workin.backend.platformadmin;

public enum PlatformAdminAuditEventType {
    LOGIN,
    LOGIN_FAILED,
    LOGOUT,
    SESSION_REUSE_REVOKED,
    ALL_SESSIONS_REVOKED
}
```

`PlatformAdminAuditEvent.java` — entity: `id` (IDENTITY), `platform_admin_id` (Long, nullable false), `event_type` (`@Enumerated(EnumType.STRING)` `PlatformAdminAuditEventType`, nullable false), `detail` (String, nullable), no `occurred_at` field (DB default, same treatment as `created_at` elsewhere); constructor `(Long platformAdminId, PlatformAdminAuditEventType eventType, String detail)`; getters.

`PlatformAdminAuditEventRepository.java` — `extends JpaRepository<PlatformAdminAuditEvent, Long>` (no custom methods needed; tests read via SQL).

`PlatformAdminAuditService.java`:

```java
package com.workin.backend.platformadmin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single write path for platform-admin audit attribution (F-26).
 * Every future {@code platform.*} business endpoint must record its
 * action here -- a standing acceptance criterion, enforceable by the
 * same ArchUnit mechanism as F-23 once that lands.
 *
 * <p>REQUIRES_NEW so an audit row commits even when the surrounding
 * business transaction ends in a rollback or the request answers 401 --
 * an audit trail that disappears with the failed action it recorded
 * would be no audit trail at all.
 */
@Service
public class PlatformAdminAuditService {

    private final PlatformAdminAuditEventRepository auditEventRepository;

    public PlatformAdminAuditService(PlatformAdminAuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long platformAdminId, PlatformAdminAuditEventType eventType, String detail) {
        auditEventRepository.save(new PlatformAdminAuditEvent(platformAdminId, eventType, detail));
    }

}
```

`PlatformAdminLoginService` — restructure so failures against a known admin are attributed (audit call before the throw; `record` commits in its own transaction, so the subsequent 401 cannot roll it back):

```java
    public PlatformAdmin login(PlatformAdminLoginRequest request) {
        PlatformAdmin admin = platformAdminRepository.findByPhone(request.phone())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!passwordEncoder.matches(request.password(), admin.getPasswordHash()) || !admin.isActive()) {
            auditService.record(admin.getId(), PlatformAdminAuditEventType.LOGIN_FAILED,
                    admin.isActive() ? "wrong password" : "inactive account");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        auditService.record(admin.getId(), PlatformAdminAuditEventType.LOGIN, null);
        return admin;
    }
```

`PlatformAdminSessionService` — inject `PlatformAdminAuditService`; add audit calls: in `logout`'s `ifPresent` branch record `LOGOUT`; in `revokeFamilyForReuse` record `SESSION_REUSE_REVOKED` (detail: `"family " + presented.getFamilyId()`); in `revokeAllForPlatformAdmin` record `ALL_SESSIONS_REVOKED`.

- [ ] **Step 4: Run the platform test classes**

Run: `./gradlew test --tests 'com.workin.backend.platformadmin.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): platform-admin audit attribution -- attributed session lifecycle events (F-26)"
```

---

### Task 5: Full verification, tracking-doc updates, lint

**Files:**

- Modify: `docs/migration/consolidated-task-matrix.md` (F-26 row; F-02 row note)
- Modify: `docs/superpowers/specs/2026-08-06-auth-sessions-revocation-audit-design.md` (only if implementation deviated)

- [ ] **Step 1: Full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, zero failures. Record the summary output as evidence.

- [ ] **Step 2: Update the F-26 matrix row**

Rewrite the "Still blocking" portion of row F-26: individual session revocation (per-session logout, reuse-detection family revocation, revoke-all primitive) and the audit-attribution substrate with session-lifecycle events are implemented (`backend/.../platformadmin/`, `PlatformAdminSessionFlowTest`, `PlatformAdminAuditTest`). Remaining before `platform.*` business functionality ships: every such endpoint records to `platform_admin_audit_events` via `PlatformAdminAuditService` (standing acceptance criterion, ArchUnit-enforceable alongside F-23). Also note on F-02 that the server half (refresh endpoint + rotation + revocation) now exists; the client half remains open. Update `hr-legacy#7`'s row similarly: server-side short-lived JWT + refresh + rotation + revocation now implemented for both domains; password-change/reset revocation wiring lands with those endpoints (F-27).

- [ ] **Step 3: Lint + phase-0 validator**

Run: `npx --yes markdownlint-cli2 "docs/**/*.md"` — expect 0 issues on changed files.
Run: `python scripts/validate_phase0.py` — expect no *new* failures beyond the pre-existing Windows-environment ones (exit-127 tool absences, PATH-splitting self-test artifacts).

- [ ] **Step 4: Commit**

```bash
git add docs
git commit -m "docs(migration): record F-26 session-revocation/audit progress and the standing platform.* audit criterion"
```
