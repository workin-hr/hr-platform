-- Capture legacy runtime-offset changes at the DATABASE mutation boundary.
--
-- Separate from phase1_extensions.sql on purpose: that file must execute
-- against a database with no legacy tables, and these triggers reference the
-- vendored `configs` table. Same ordering rule as slice_b_attendance_method.sql
-- (D-214) -- a statement that depends on legacy structure ships as its own step.
--
-- Why triggers rather than writing history from the application: the flag can
-- be changed by PHP, by hand, or by any other path. Recording when THIS
-- application first observed a new value would store the observation time, not
-- the change time, and a punch delivered between those two moments would still
-- be converted with the wrong offset.
--
-- The DERIVED offset is recorded, not the raw spelling. 'true' -> 'dst' is not
-- a runtime transition: both mean +03:00, and writing a row for it would invent
-- a boundary that never existed.
--
-- Deployment order (see provisioning-phase1-tables.md):
--   1. phase1_extensions.sql        -- creates legacy_runtime_offset_history
--   2. THIS FILE                    -- installs the writers
--   3. the seed statement below     -- begins trustworthy coverage
--
-- UTC_TIMESTAMP(), never NOW(): the session's NOW() is in the legacy runtime's
-- own offset, which is precisely the value under change here.

-- Refuse to install the writers if their target is absent, BEFORE any side
-- effect. Step 1 above is documented but was not enforced, and the failure it
-- allows is silent and lands on production: if phase1_extensions.sql aborted
-- (ERROR 1050 on a database that already has some of the tables, say) and this
-- file ran anyway, three triggers end up on the legacy `configs` table pointing
-- at a table that does not exist. PHP's writes to the daylight-saving row then
-- fail with ERROR 1146 while every other config key still succeeds -- so nothing
-- looks broken. This SELECT touches no rows and raises ERROR 1146 itself when
-- the table is missing, which stops a client that halts on error.
--
-- It does NOT protect a run under `--force`, which continues past errors by
-- design; there the operator's own step ordering is the only control.
SELECT 1 FROM legacy_runtime_offset_history LIMIT 0;

DROP TRIGGER IF EXISTS configs_runtime_offset_after_insert;
DROP TRIGGER IF EXISTS configs_runtime_offset_after_update;
DROP TRIGGER IF EXISTS configs_runtime_offset_after_delete;

DELIMITER $$

-- The same rule LegacyRuntimeOffset.of() applies: a small set of truthy
-- spellings means +03:00, everything else -- including an unreadable value --
-- means the +02:00 default PHP falls back to.
CREATE TRIGGER configs_runtime_offset_after_insert
AFTER INSERT ON configs FOR EACH ROW
BEGIN
    IF NEW.config_key = 'is_daylight_saving' THEN
        INSERT INTO legacy_runtime_offset_history (effective_from_utc, offset_seconds)
        VALUES (UTC_TIMESTAMP(),
                IF(LOWER(TRIM(NEW.config_value)) IN ('1', 'true', 'yes', 'summer', 'dst'), 10800, 7200));
    END IF;
END$$

CREATE TRIGGER configs_runtime_offset_after_update
AFTER UPDATE ON configs FOR EACH ROW
BEGIN
    -- Renaming INTO or OUT OF the key changes the effective offset too, so the
    -- comparison is between "what the runtime offset was" and "what it now is",
    -- not between two config_value strings.
    DECLARE old_offset INT;
    DECLARE new_offset INT;
    SET old_offset = IF(OLD.config_key = 'is_daylight_saving',
            IF(LOWER(TRIM(OLD.config_value)) IN ('1', 'true', 'yes', 'summer', 'dst'), 10800, 7200),
            NULL);
    SET new_offset = IF(NEW.config_key = 'is_daylight_saving',
            IF(LOWER(TRIM(NEW.config_value)) IN ('1', 'true', 'yes', 'summer', 'dst'), 10800, 7200),
            NULL);
    IF NOT (old_offset <=> new_offset) THEN
        INSERT INTO legacy_runtime_offset_history (effective_from_utc, offset_seconds)
        VALUES (UTC_TIMESTAMP(), COALESCE(new_offset, 7200));
    END IF;
END$$

-- Deleting the key returns legacy to its +02:00 default, which is a real
-- transition and has to be recorded as one.
CREATE TRIGGER configs_runtime_offset_after_delete
AFTER DELETE ON configs FOR EACH ROW
BEGIN
    IF OLD.config_key = 'is_daylight_saving' THEN
        INSERT INTO legacy_runtime_offset_history (effective_from_utc, offset_seconds)
        VALUES (UTC_TIMESTAMP(), 7200);
    END IF;
END$$

DELIMITER ;

-- Seed: where trustworthy coverage BEGINS. It asserts the offset in force from
-- this instant onward and says nothing whatever about what came before -- a
-- punch older than this row is PRE_HISTORY, not "probably the same".
INSERT INTO legacy_runtime_offset_history (effective_from_utc, offset_seconds)
SELECT UTC_TIMESTAMP(),
       IF(LOWER(TRIM(COALESCE(
           (SELECT config_value FROM configs WHERE config_key = 'is_daylight_saving' LIMIT 1), '')))
          IN ('1', 'true', 'yes', 'summer', 'dst'), 10800, 7200)
WHERE NOT EXISTS (SELECT 1 FROM legacy_runtime_offset_history);
