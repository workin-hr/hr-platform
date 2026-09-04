-- Phase 1 extension schema -- NOT part of the legacy contract.
--
-- THIS IS THE PROVISIONING ARTIFACT. It is the one definition of the
-- tables Phase 1 adds to the existing MariaDB, and it is applied by hand
-- before cutover -- ADR-0013 gives Flyway no ownership of any MariaDB
-- schema, so nothing creates these at runtime. See
-- docs/operations/provisioning-phase1-tables.md for the runbook, and
-- R-023 for why an unprovisioned database is a cutover blocker rather
-- than a startup error.
--
-- It ships inside the jar (src/main/resources) so an operator can
-- extract the DDL that matches the deployed code rather than a file
-- from a branch that may have moved on:
--
--   unzip -p backend.jar db/phase1-mysql/phase1_extensions.sql
--
-- The MariaDB test container applies this same file
-- (AbstractLegacyMySqlTest), so the schema the suite proves the adapter
-- against is byte-identical to the schema an operator runs. That
-- property is the whole reason it lives here and not in test resources,
-- where it used to: a provisioning script only tests exercise is a
-- script nothing keeps honest.
--
-- Deliberately NOT idempotent -- no CREATE TABLE IF NOT EXISTS. A table
-- that already exists with the wrong columns would pass that check
-- silently, which is the failure this file exists to prevent. Verify
-- first (scripts/verify-phase1-tables.sql, read-only), then apply.
--
-- Unlike mysql_workin.schema.sql (vendored, drift-checked byte-identical
-- against hr-legacy by scripts/check_legacy_schema_drift.py), the tables
-- here do not exist in production legacy MySQL. They are new
-- infrastructure this Java application owns, so they live in their own
-- file rather than being folded into the vendored one -- keeping the
-- drift check meaningful (it only ever compares the vendored file) and
-- keeping "what legacy actually has" separate from "what Phase 1 adds".

-- Legacy's counterpart to refresh_tokens (V15__create_refresh_tokens.sql):
-- opaque rotating refresh tokens, but for a legacy-authenticated identity
-- (an employees row) rather than a PostgreSQL identity/membership pair.
--
-- Deliberately omits company_id. Postgres's refresh_tokens stores it as
-- a load-bearing but explicitly unfiltered exception, compensated by
-- keying every finder on something unguessable or already proven
-- (RefreshTokenRepository.java's own javadoc). A legacy session has a
-- simpler option available: company is always re-derivable from
-- employee_id via LegacyEmployeeRepository, and Phase 1 already
-- established re-deriving rather than caching a tenant value as the
-- standing policy (LegacyTenantContextService, ADR-0012). Omitting the
-- column also means this table needs no exemption from
-- TenantFilterCoverageTest's company_id rule -- adding one would be a
-- security-control change, not a side effect of a refresh-token table.
CREATE TABLE legacy_refresh_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    employee_id BIGINT NOT NULL,
    family_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    CONSTRAINT legacy_refresh_tokens_status_chk CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED'))
);

CREATE INDEX legacy_refresh_tokens_family_id_idx ON legacy_refresh_tokens (family_id);
CREATE INDEX legacy_refresh_tokens_employee_id_idx ON legacy_refresh_tokens (employee_id);

-- ---------------------------------------------------------------------------
-- The platform-admin surface (ADR-0015), on MySQL.
--
-- Legacy has a platform admin *web* -- dashboard/pages/companies/ -- but it has
-- no admin table: doAdminLogin() checks one shared password held in a config
-- constant (ADMIN_PASSWORD_HASH, hr-legacy#11), so there is nothing here to
-- port. These tables are the individual-identity model F-26/D-027 requires
-- instead, and they are additive: no frozen table is touched, which is why they
-- belong in this file and not the vendored one.
--
-- Column types are the MySQL equivalents of the PostgreSQL originals
-- (common/V7, V16, V17, V46-V51): DATETIME(6) for TIMESTAMPTZ -- microseconds,
-- because the step-up and TOTP windows are compared to the second and MySQL's
-- default DATETIME truncates to whole seconds; VARBINARY for BYTEA; BIGINT
-- AUTO_INCREMENT for BIGSERIAL.

CREATE TABLE platform_admins (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1
);

CREATE TABLE platform_admin_refresh_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    platform_admin_id BIGINT NOT NULL,
    family_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    family_started_at DATETIME(6) NOT NULL,
    CONSTRAINT platform_admin_refresh_tokens_admin_fk
        FOREIGN KEY (platform_admin_id) REFERENCES platform_admins (id),
    CONSTRAINT platform_admin_refresh_tokens_status_chk
        CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED'))
);

CREATE INDEX platform_admin_refresh_tokens_family_id_idx
    ON platform_admin_refresh_tokens (family_id);
CREATE INDEX platform_admin_refresh_tokens_admin_id_idx
    ON platform_admin_refresh_tokens (platform_admin_id);

-- Retained indefinitely by decision (D-161): this table is the evidence the
-- shared-password model never had.
CREATE TABLE platform_admin_audit_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    platform_admin_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    detail TEXT,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    target_type VARCHAR(64),
    target_id VARCHAR(64),
    step_up_approval_id VARCHAR(64),
    CONSTRAINT platform_admin_audit_events_admin_fk
        FOREIGN KEY (platform_admin_id) REFERENCES platform_admins (id)
);

CREATE INDEX platform_admin_audit_events_admin_id_idx
    ON platform_admin_audit_events (platform_admin_id);
CREATE INDEX platform_admin_audit_events_target_idx
    ON platform_admin_audit_events (target_type, target_id);

CREATE TABLE platform_admin_login_attempts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    identifier_hash VARCHAR(64) NOT NULL,
    attempted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE INDEX platform_admin_login_attempts_ix1
    ON platform_admin_login_attempts (identifier_hash, attempted_at);

CREATE TABLE platform_admin_mfa (
    platform_admin_id BIGINT PRIMARY KEY,
    seed_ciphertext VARBINARY(255) NOT NULL,
    seed_nonce VARBINARY(64) NOT NULL,
    seed_key_version INT NOT NULL,
    enrolled_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    bound_at DATETIME(6),
    last_accepted_time_step BIGINT,
    CONSTRAINT platform_admin_mfa_admin_fk
        FOREIGN KEY (platform_admin_id) REFERENCES platform_admins (id)
);

CREATE TABLE platform_admin_mfa_bootstrap_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    platform_admin_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6),
    revoked_at DATETIME(6),
    CONSTRAINT platform_admin_mfa_bootstrap_tokens_admin_fk
        FOREIGN KEY (platform_admin_id) REFERENCES platform_admins (id)
);

CREATE INDEX platform_admin_mfa_bootstrap_tokens_admin_idx
    ON platform_admin_mfa_bootstrap_tokens (platform_admin_id);

CREATE TABLE platform_admin_step_up_approvals (
    id VARCHAR(64) PRIMARY KEY,
    platform_admin_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6),
    CONSTRAINT platform_admin_step_up_approvals_admin_fk
        FOREIGN KEY (platform_admin_id) REFERENCES platform_admins (id)
);

CREATE INDEX platform_admin_step_up_approvals_admin_idx
    ON platform_admin_step_up_approvals (platform_admin_id);

-- Spring Session's own schema, MySQL flavour. Taken from Spring Session's
-- shipped MySQL DDL rather than translated by hand, for the same reason the
-- PostgreSQL copy was: an upgrade compares against a known baseline.
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BLOB NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;
