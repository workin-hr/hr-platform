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

-- Attendance-device ingestion (ADR-0006 Part A core; Part B ZKTeco adapter,
-- D-156). Design: docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md
-- section 7. All five tables are Phase-1-owned: none exists in legacy MySQL,
-- so none is part of the vendored contract and TenantFilterCoverageTest's
-- structural exemption applies. No foreign keys to the vendored tables, on
-- purpose: the legacy dump adds its own FKs through ALTER TABLE after the
-- CREATEs, and this file is applied after it; coupling the two files'
-- order any further would make the extension schema fragile for no
-- integrity gain the application does not already enforce (a serial is
-- bound only to a branch of the claiming caller's own company).

-- One physical terminal, identified by the vendor serial number. The
-- company/branch binding is written by an authenticated claim and is the
-- ONLY source of tenant for anything the device sends.
CREATE TABLE attendance_devices (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id INT UNSIGNED NOT NULL,
    branch_id INT UNSIGNED NOT NULL,
    vendor VARCHAR(32) NOT NULL,
    serial_number VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    model VARCHAR(100) NULL,
    firmware VARCHAR(100) NULL,
    push_version VARCHAR(32) NULL,
    device_time_zone VARCHAR(64) NOT NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    last_seen_at DATETIME NULL,
    last_handshake_at DATETIME NULL,
    last_attlog_stamp VARCHAR(32) NULL,
    last_seen_ip VARCHAR(45) NULL,
    registered_by_employee_id INT UNSIGNED NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT attendance_devices_vendor_chk CHECK (vendor IN ('zkteco'))
);

CREATE INDEX attendance_devices_company_idx ON attendance_devices (company_id, branch_id);

-- Device PIN -> employee, per company (Q1, D-156). A PIN is unique within a
-- company and an employee holds at most one PIN. Absent a row, ingestion
-- falls back to employees.employee_code, which is what the Excel import
-- already treats as the device PIN.
CREATE TABLE employee_device_identities (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id INT UNSIGNED NOT NULL,
    employee_id INT UNSIGNED NOT NULL,
    pin VARCHAR(32) NOT NULL,
    card_no VARCHAR(32) NULL,
    source VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT employee_device_identities_pin_uq UNIQUE (company_id, pin),
    CONSTRAINT employee_device_identities_employee_uq UNIQUE (company_id, employee_id),
    CONSTRAINT employee_device_identities_source_chk CHECK (source IN ('MANUAL', 'EMPLOYEE_CODE', 'DEVICE'))
);

-- Raw, append-only punches. dedup_key is the synthesised idempotency key
-- (the protocol carries no record id): sha256(serial|pin|local time|status).
-- punched_at_local is exactly what the device said; punched_at_utc is
-- derived through the device's zone. Never updated by ingestion except
-- processing_state/employee_id, never deleted.
CREATE TABLE device_punches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NOT NULL,
    company_id INT UNSIGNED NOT NULL,
    employee_id INT UNSIGNED NULL,
    pin VARCHAR(32) NOT NULL,
    punched_at_local DATETIME NOT NULL,
    punched_at_utc DATETIME NOT NULL,
    status_code SMALLINT NULL,
    verify_code SMALLINT NULL,
    work_code VARCHAR(32) NULL,
    received_at DATETIME NOT NULL,
    dedup_key CHAR(64) NOT NULL UNIQUE,
    raw_line VARCHAR(512) NOT NULL,
    processing_state VARCHAR(16) NOT NULL,
    CONSTRAINT device_punches_state_chk
        CHECK (processing_state IN ('RECEIVED', 'UNMATCHED', 'PAIRED', 'IGNORED'))
);

CREATE INDEX device_punches_device_time_idx ON device_punches (device_id, punched_at_local);
CREATE INDEX device_punches_employee_time_idx ON device_punches (company_id, employee_id, punched_at_local);
CREATE INDEX device_punches_state_idx ON device_punches (processing_state);

-- Serials that have contacted the receiver without being claimed. Global,
-- not tenant-scoped: an unclaimed serial belongs to nobody yet. The tenant
-- API exposes it only by exact serial lookup, never as a list.
CREATE TABLE unclaimed_device_sightings (
    serial_number VARCHAR(64) PRIMARY KEY,
    first_seen_at DATETIME NOT NULL,
    last_seen_at DATETIME NOT NULL,
    last_seen_ip VARCHAR(45) NULL,
    push_version VARCHAR(32) NULL,
    device_type VARCHAR(64) NULL,
    hit_count INT UNSIGNED NOT NULL DEFAULT 1
);

-- Device operation log lines (OPLOG records only -- biometric template
-- lines that share the same upload are discarded before this table).
CREATE TABLE device_operation_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NOT NULL,
    company_id INT UNSIGNED NOT NULL,
    received_at DATETIME NOT NULL,
    raw_line VARCHAR(512) NOT NULL
);

CREATE INDEX device_operation_logs_device_idx ON device_operation_logs (device_id, received_at);
