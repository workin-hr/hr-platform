-- Read-only. Answers "can phase1_extensions.sql be applied to this database,
-- and has it been already?" -- before anything writes to it.
--
-- Paste into phpMyAdmin's SQL tab, or:
--   mysql -h HOST -u USER -p DBNAME \
--     < backend/src/main/resources/db/phase1-mysql/verify_phase1_tables.sql
--
-- Nothing here creates, alters or deletes. Every statement is a SELECT.
-- Run it before applying, and again afterwards: the first two answers change,
-- and the rest must not.

-- 1. The engine. `phase1_extensions.sql` uses CHECK constraints (MariaDB
--    10.2+), DATETIME(6) and ROW_FORMAT=DYNAMIC. Anything from MariaDB 10.2 or
--    MySQL 8.0.16 supports all three; the port is developed and tested against
--    MariaDB 11.8.
SELECT 'server' AS check_name, VERSION() AS value,
       IF(VERSION() REGEXP '^(1[0-9]|[0-9]{3})' , 'ok', 'CHECK MANUALLY') AS verdict;

-- 2. Where the tables would land. InnoDB is required -- the audit table and
--    Spring Session's attributes both carry foreign keys, and MyISAM ignores
--    them silently rather than refusing.
SELECT 'database' AS check_name,
       CONCAT(DATABASE(), ' / ', @@character_set_database, ' / ', @@collation_database) AS value,
       IF(@@default_storage_engine = 'InnoDB', 'ok', CONCAT('NOT InnoDB: ', @@default_storage_engine)) AS verdict;

-- 3. Which of the fourteen already exist.
--    `--force` below means `mysql --force < phase1_extensions.sql`: the script
--    is deliberately not idempotent, so re-applying it prints one ERROR 1050
--    per existing table and one ERROR 1061 per existing index, creates only
--    what is absent, and EXITS 0. Those errors are the expected output, not a
--    failure. Re-run this script afterwards: section 3 should say `applied`.
--
--    `none` means apply the script as it is. All fourteen means it has been
--    applied -- check (4) rather than re-running it.
--
--    ANYTHING BETWEEN IS A PARTIAL APPLY, AND THE VERDICT SAYS WHAT TO DO. Do
--    NOT "drop the ones listed": the listed value is every owned table present,
--    which at counts 7-13 includes platform_admin_audit_events (retained
--    evidence, D-161) and SPRING_SESSION (every live administrator session).
--    Re-apply with --force alone, which creates what is absent and touches
--    nothing that exists. This script never tells anyone to drop anything:
--    dropping an owned table is a destructive procedure with exactly one
--    authority, docs/operations/provisioning-phase1-tables.md#rollback, which
--    drops the configs triggers FIRST. Dropping legacy_runtime_offset_history
--    while those triggers stand breaks PHP's own writes to configs -- and only
--    on the daylight-saving row, so it fails silently.
SELECT 'phase1 tables present' AS check_name,
       COALESCE(GROUP_CONCAT(table_name ORDER BY table_name SEPARATOR ', '), 'none') AS value,
       CASE COUNT(*)
         WHEN 0 THEN 'not applied -- apply it'
         WHEN 14 THEN 'applied'
         -- Exactly the six originals means a database provisioned before the
         -- device tables existed. Apply the script with --force so only the
         -- absent eight are created; do NOT drop these. Two of them are not
         -- yours to drop: platform_admin_audit_events is retained evidence
         -- (D-161) and SPRING_SESSION is every live administrator session.
         WHEN 6 THEN 'PRE-DEVICE-TABLES -- re-apply with --force, do NOT drop'
         -- NOT "drop the listed tables": the list includes the six originals,
         -- and an interrupted --force apply (which WHEN 6 above sends operators
         -- to run) lands here at 7-13. Dropping platform_admin_audit_events
         -- loses retained evidence (D-161) and dropping SPRING_SESSION ends
         -- every live administrator session.
         -- The count cannot tell a torn apply from a LIVE stack that lost one
         -- table, and device_punches is the attendance punch record -- section
         -- 6 calls it the one that will not stay small. An unenforceable
         -- precondition printed to an operator's screen is still an
         -- instruction, so this verdict offers no drop at all: --force is
         -- always the answer here, and a genuine drop belongs to the runbook.
         ELSE CONCAT('PARTIAL. Re-apply with --force, which creates only what ',
                     'is absent and touches nothing that exists. Do NOT drop ',
                     'anything to recover: dropping an owned table is a ',
                     'destructive procedure with one authority, ',
                     'docs/operations/provisioning-phase1-tables.md#rollback, ',
                     'which drops the configs triggers FIRST.')
       END AS verdict
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('legacy_refresh_tokens', 'platform_admins',
                      'platform_admin_audit_events', 'platform_admin_login_attempts',
                      'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES',
                      'attendance_devices', 'employee_device_identities',
                      'device_punches', 'unclaimed_device_sightings',
                      'device_operation_logs', 'device_malformed_punches',
                      'legacy_runtime_offset_history', 'device_assignment_history');

-- 4. After applying: the shape, not just the name. A table that exists with
--    the wrong columns is the failure the non-idempotent script exists to
--    prevent, and it is invisible to (3).
SELECT 'column counts' AS check_name,
       CONCAT(table_name, '=', COUNT(*)) AS value,
       CASE
         WHEN table_name = 'legacy_refresh_tokens'          AND COUNT(*) = 7 THEN 'ok'
         WHEN table_name = 'platform_admins'                AND COUNT(*) = 4 THEN 'ok'
         WHEN table_name = 'platform_admin_audit_events'    AND COUNT(*) = 7 THEN 'ok'
         WHEN table_name = 'platform_admin_login_attempts'  AND COUNT(*) = 3 THEN 'ok'
         WHEN table_name = 'SPRING_SESSION'                 AND COUNT(*) = 7 THEN 'ok'
         WHEN table_name = 'SPRING_SESSION_ATTRIBUTES'      AND COUNT(*) = 3 THEN 'ok'
         WHEN table_name = 'attendance_devices'             AND COUNT(*) = 18 THEN 'ok'
         WHEN table_name = 'employee_device_identities'     AND COUNT(*) = 8 THEN 'ok'
         WHEN table_name = 'device_punches'                 AND COUNT(*) = 24 THEN 'ok'
         WHEN table_name = 'unclaimed_device_sightings'     AND COUNT(*) = 7 THEN 'ok'
         WHEN table_name = 'device_operation_logs'          AND COUNT(*) = 6 THEN 'ok'
         WHEN table_name = 'device_malformed_punches'       AND COUNT(*) = 6 THEN 'ok'
         WHEN table_name = 'legacy_runtime_offset_history'  AND COUNT(*) = 3 THEN 'ok'
         WHEN table_name = 'device_assignment_history'      AND COUNT(*) = 7 THEN 'ok'
         ELSE 'UNEXPECTED -- compare against phase1_extensions.sql'
       END AS verdict
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name IN ('legacy_refresh_tokens', 'platform_admins',
                      'platform_admin_audit_events', 'platform_admin_login_attempts',
                      'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES',
                      'attendance_devices', 'employee_device_identities',
                      'device_punches', 'unclaimed_device_sightings',
                      'device_operation_logs', 'device_malformed_punches',
                      'legacy_runtime_offset_history', 'device_assignment_history')
 GROUP BY table_name
 ORDER BY table_name;

-- 5. That nothing legacy owns was touched. These are the tables the clients
--    and the dashboard read; the count is what it was before the script ran,
--    because the script only ever adds.
SELECT 'legacy tables' AS check_name,
       CONCAT(COUNT(*), ' tables not owned by Phase 1') AS value,
       'unchanged by applying the script' AS verdict
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name NOT IN ('legacy_refresh_tokens', 'platform_admins',
                          'platform_admin_audit_events', 'platform_admin_login_attempts',
                          'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES',
                          'attendance_devices', 'employee_device_identities',
                          'device_punches', 'unclaimed_device_sightings',
                          'device_operation_logs', 'device_malformed_punches',
                          'legacy_runtime_offset_history', 'device_assignment_history');

-- 6. The COLLATION of each owned table, which (3) and (4) are both blind to.
--    `phase1_extensions.sql` declared no collation until 2026-09-11, so a
--    database provisioned by hand before then, on a server whose default was
--    not utf8mb4_unicode_ci, has these tables at utf8mb4_uca1400_ai_ci while
--    every legacy table beside them but `configs` is utf8mb4_unicode_ci. Nothing else here
--    notices: the names are right, the column counts are right, and
--    Phase1SchemaCheck compares names only. The first cross-table string
--    comparison -- device_punches.pin against employees.employee_code -- then
--    fails at runtime with `Illegal mix of collations`, on that host only.
--
--    To repair, per table listed below:
--      ALTER TABLE <name> CONVERT TO CHARACTER SET utf8mb4
--                         COLLATE utf8mb4_unicode_ci;
--
--      The session pair is the exception: SPRING_SESSION_ATTRIBUTES_FK is a
--      FOREIGN KEY on a CHAR column, so CONVERT TO is refused in BOTH
--      directions. On MariaDB 11.8 that is ERROR 1832 on the child and 1833 on
--      the parent, and SET FOREIGN_KEY_CHECKS=0 does not lift it; on MySQL 8 it
--      is ERROR 3780 both ways, and FOREIGN_KEY_CHECKS=0 there lets the ALTER
--      through and leaves the two columns at DIFFERENT collations under a live
--      FK, which is worse than the error. Do not use that flag. The procedure
--      below is the same one docs/operations/provisioning-phase1-tables.md
--      carries under step 4b; that document is the authority if the two ever
--      disagree. Drop the constraint, convert both, put it back:
--        ALTER TABLE SPRING_SESSION_ATTRIBUTES DROP FOREIGN KEY SPRING_SESSION_ATTRIBUTES_FK;
--        ALTER TABLE SPRING_SESSION            CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
--        ALTER TABLE SPRING_SESSION_ATTRIBUTES CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
--        -- Anything deleted from SPRING_SESSION while the constraint is off
--        -- leaves an orphan attribute row, and ADD CONSTRAINT then fails with
--        -- ERROR 1452 and the table keeps NO foreign key. Spring Session's own
--        -- cleanup job deletes expired sessions every sixty seconds, and every
--        -- admin logout deletes one, so this window is not theoretical. Stop
--        -- the application, or sweep before re-adding -- ideally both:
--        DELETE a FROM SPRING_SESSION_ATTRIBUTES a
--          LEFT JOIN SPRING_SESSION s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID
--         WHERE s.PRIMARY_ID IS NULL;
--        ALTER TABLE SPRING_SESSION_ATTRIBUTES ADD CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK
--            FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE;
--
--    CONVERT TO rebuilds the table and holds a lock for the duration, so size
--    the window for device_punches -- it is the one that will not stay small.
SELECT 'phase1 collation' AS check_name,
       CONCAT(table_name, ' = ', table_collation) AS value,
       IF(table_collation = 'utf8mb4_unicode_ci', 'ok',
          'WRONG -- CONVERT TO utf8mb4_unicode_ci, see the note above') AS verdict
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('legacy_refresh_tokens', 'platform_admins',
                      'platform_admin_audit_events', 'platform_admin_login_attempts',
                      'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES',
                      'attendance_devices', 'employee_device_identities',
                      'device_punches', 'unclaimed_device_sightings',
                      'device_operation_logs', 'device_malformed_punches',
                      'legacy_runtime_offset_history', 'device_assignment_history')
 ORDER BY (table_collation = 'utf8mb4_unicode_ci'), table_name;
