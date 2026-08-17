-- Phase 1 extension schema -- NOT part of the legacy contract.
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
