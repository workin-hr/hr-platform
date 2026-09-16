-- Brings a database provisioned from the FOURTEEN-table phase1_extensions.sql
-- to the fifteen-table shape: the on-premises agent registry, how each punch
-- was delivered, and Hikvision as a registrable vendor.
--
--   mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p "$DB_NAME" \
--     < upgrade_device_agents_and_delivery.sql
--
-- Only for a database that already has the fourteen. One with none of the
-- Phase 1 tables takes phase1_extensions.sql, which carries all of this; running
-- this file there fails on the first ALTER and changes nothing.
--
-- Re-runnable: every statement is guarded, so a second run changes nothing and
-- an interrupted run is finished by running it again. The result is the same
-- SHOW CREATE TABLE a fresh phase1_extensions.sql produces -- the new column and
-- constraint come last in both -- so the runbook's definition comparison
-- (docs/operations/provisioning-phase1-tables.md step 1) still applies.
--
-- Cost: ADD COLUMN at the end of device_punches is instant on MariaDB 10.3+.
-- Adding a CHECK validates every existing row, which copies the table; size the
-- window by `SELECT COUNT(*) FROM device_punches` first. On a database where
-- ingestion was never enabled that count is zero.

ALTER TABLE device_punches
    ADD COLUMN IF NOT EXISTS delivered_via VARCHAR(8) NOT NULL DEFAULT 'PUSH';

ALTER TABLE device_punches
    DROP CONSTRAINT IF EXISTS device_punches_delivered_via_chk;
ALTER TABLE device_punches
    ADD CONSTRAINT device_punches_delivered_via_chk
        CHECK (delivered_via IN ('PUSH', 'AGENT', 'FILE'));

ALTER TABLE attendance_devices
    DROP CONSTRAINT IF EXISTS attendance_devices_vendor_chk;
ALTER TABLE attendance_devices
    ADD CONSTRAINT attendance_devices_vendor_chk CHECK (vendor IN ('zkteco', 'hikvision'));

CREATE TABLE IF NOT EXISTS device_agents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id INT UNSIGNED NOT NULL,
    name VARCHAR(100) NOT NULL,
    token_sha256 CHAR(64) NOT NULL UNIQUE,
    token_hint VARCHAR(8) NOT NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    agent_version VARCHAR(32) NULL,
    last_seen_at DATETIME NULL,
    last_seen_ip VARCHAR(45) NULL,
    last_report TEXT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS device_agents_company_idx ON device_agents (company_id);
