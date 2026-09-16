-- Read-only. Answers "can phase1_extensions.sql be applied to this database,
-- and has it been already?" -- before anything writes to it.
--
-- Paste into phpMyAdmin's SQL tab, or:
--   mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p "$DB_NAME" \
--     < backend/src/main/resources/db/phase1-mysql/verify_phase1_tables.sql
--
-- Nothing here creates, alters or deletes. Every statement is a SELECT.
-- Run it before applying, and again afterwards: the first two answers change,
-- and the rest must not.

-- 1. The engine. `phase1_extensions.sql` uses CHECK constraints (MariaDB
--    10.2+), DATETIME(6) and ROW_FORMAT=DYNAMIC. Anything from MariaDB 10.2 or
--    MySQL 8.0.16 supports all three; the port is developed and tested against
--    MariaDB 11.8. The runbook's definition comparison needs MariaDB 10.6 or
--    later, and says what to do on any other server.
SELECT 'server' AS check_name, VERSION() AS value,
       IF(VERSION() REGEXP '^(1[0-9]|[0-9]{3})' , 'ok', 'CHECK MANUALLY') AS verdict;

-- 2. Where the tables would land. InnoDB is required -- the audit table and
--    Spring Session's attributes both carry foreign keys, and MyISAM ignores
--    them silently rather than refusing.
SELECT 'database' AS check_name,
       CONCAT(DATABASE(), ' / ', @@character_set_database, ' / ', @@collation_database) AS value,
       IF(@@default_storage_engine = 'InnoDB', 'ok', CONCAT('NOT InnoDB: ', @@default_storage_engine)) AS verdict;

-- 3. Which of the fifteen already exist.
--    `--force` below means `mysql --force < phase1_extensions.sql`: the script
--    is deliberately not idempotent, so re-applying it prints one ERROR 1050
--    per existing table and one ERROR 1061 per existing index, creates only
--    what is absent, and EXITS 0. Those errors are the expected output, not a
--    failure. Re-run this script afterwards: section 3 should start `applied`.
--
--    `none` means apply the script as it is. All fifteen means it has been
--    applied -- do not re-run it. If any of them was there before this
--    provisioning began, (4) is not enough either: compare the full
--    definitions as docs/operations/provisioning-phase1-tables.md step 1
--    describes.
--
--    ANYTHING BETWEEN IS A PARTIAL APPLY, AND THE VERDICT SAYS WHAT TO DO. Do
--    NOT "drop the ones listed": the listed value is every owned table present,
--    which at counts 7-14 includes platform_admin_audit_events (retained
--    evidence, D-161) and SPRING_SESSION (every live administrator session).
--    Re-apply with --force alone, which creates what is absent and touches
--    nothing that exists. This script never tells anyone to drop anything:
--    dropping an owned table is a destructive procedure with exactly one
--    authority, docs/operations/provisioning-phase1-tables.md#rollback, which
--    drops the configs triggers FIRST. Dropping legacy_runtime_offset_history
--    while those triggers stand makes every configs write that adds, removes
--    or switches the daylight-saving setting fail while other writes still
--    succeed, so PHP's settings page breaks part-way on exactly those saves.
SELECT 'phase1 tables present' AS check_name,
       COALESCE(GROUP_CONCAT(table_name ORDER BY table_name SEPARATOR ', '), 'none') AS value,
       CASE COUNT(*)
         WHEN 0 THEN 'not applied -- apply it'
         WHEN 15 THEN 'applied -- if any table was here before this provisioning, compare definitions: runbook step 1'
         -- Fourteen WITHOUT device_agents is a database provisioned before the
         -- on-premises agent existed, not a torn apply: --force would create
         -- the agent table and leave device_punches without delivered_via, so
         -- every device upload would then fail. The upgrade file makes all three
         -- changes and is safe to re-run.
         WHEN 14 THEN IF(SUM(table_name = 'device_agents') = 0,
                         'PRE-AGENTS -- apply upgrade_device_agents_and_delivery.sql (not --force), then re-run this script',
                         CONCAT('PARTIAL. Re-apply with --force, which creates only what ',
                                'is absent and touches nothing that exists. Do NOT drop ',
                                'anything to recover: see docs/operations/provisioning-phase1-tables.md#rollback. ',
                                'Then compare definitions: runbook step 1.'))
         -- Exactly the six originals means a database provisioned before the
         -- device tables existed. Apply the script with --force so only the
         -- absent nine are created; do NOT drop these. Two of them are not
         -- yours to drop: platform_admin_audit_events is retained evidence
         -- (D-161) and SPRING_SESSION is every live administrator session.
         WHEN 6 THEN 'PRE-DEVICE-TABLES -- re-apply with --force, do NOT drop, then compare definitions: runbook step 1'
         -- NOT "drop the listed tables": the list includes the six originals,
         -- and an interrupted --force apply (which WHEN 6 above sends operators
         -- to run) lands here at 7-14. Dropping platform_admin_audit_events
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
                     'which drops the configs triggers FIRST. ',
                     'Then compare definitions: runbook step 1.')
       END AS verdict
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('legacy_refresh_tokens', 'platform_admins',
                      'platform_admin_audit_events', 'platform_admin_login_attempts',
                      'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES',
                      'attendance_devices', 'employee_device_identities',
                      'device_punches', 'unclaimed_device_sightings',
                      'device_operation_logs', 'device_malformed_punches',
                      'legacy_runtime_offset_history', 'device_assignment_history',
                      'device_agents');

-- 4. After applying: each table's column count, which (3) cannot see. It
--    catches a missing or extra column and nothing finer: a table with the
--    right count and a wrong type, default, key, foreign key or engine still
--    reads `ok (count only)`. Whenever any of the fifteen existed before provisioning
--    began, compare the full definitions as
--    docs/operations/provisioning-phase1-tables.md step 1 describes before
--    applying anything else.
SELECT 'column counts' AS check_name,
       CONCAT(table_name, '=', COUNT(*)) AS value,
       CASE
         WHEN table_name = 'legacy_refresh_tokens'          AND COUNT(*) = 7 THEN 'ok (count only)'
         WHEN table_name = 'platform_admins'                AND COUNT(*) = 4 THEN 'ok (count only)'
         WHEN table_name = 'platform_admin_audit_events'    AND COUNT(*) = 7 THEN 'ok (count only)'
         WHEN table_name = 'platform_admin_login_attempts'  AND COUNT(*) = 3 THEN 'ok (count only)'
         WHEN table_name = 'SPRING_SESSION'                 AND COUNT(*) = 7 THEN 'ok (count only)'
         WHEN table_name = 'SPRING_SESSION_ATTRIBUTES'      AND COUNT(*) = 3 THEN 'ok (count only)'
         WHEN table_name = 'attendance_devices'             AND COUNT(*) = 18 THEN 'ok (count only)'
         WHEN table_name = 'employee_device_identities'     AND COUNT(*) = 8 THEN 'ok (count only)'
         WHEN table_name = 'device_punches'                 AND COUNT(*) = 25 THEN 'ok (count only)'
         WHEN table_name = 'unclaimed_device_sightings'     AND COUNT(*) = 7 THEN 'ok (count only)'
         WHEN table_name = 'device_operation_logs'          AND COUNT(*) = 6 THEN 'ok (count only)'
         WHEN table_name = 'device_malformed_punches'       AND COUNT(*) = 6 THEN 'ok (count only)'
         WHEN table_name = 'legacy_runtime_offset_history'  AND COUNT(*) = 3 THEN 'ok (count only)'
         WHEN table_name = 'device_assignment_history'      AND COUNT(*) = 7 THEN 'ok (count only)'
         WHEN table_name = 'device_agents'                  AND COUNT(*) = 12 THEN 'ok (count only)'
         -- 24 is the shape before delivered_via: upgrade_device_agents_and_delivery.sql.
         WHEN table_name = 'device_punches'                 AND COUNT(*) = 24
              THEN 'PRE-AGENTS -- apply upgrade_device_agents_and_delivery.sql'
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
                      'legacy_runtime_offset_history', 'device_assignment_history',
                      'device_agents')
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
                          'legacy_runtime_offset_history', 'device_assignment_history',
                          'device_agents');

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
--      The session pair is the exception, and this file does NOT carry the
--      repair for it. SPRING_SESSION_ATTRIBUTES_FK is a FOREIGN KEY on a CHAR
--      column, so CONVERT TO is refused in BOTH directions: ERROR 1832 on the
--      child and 1833 on the parent on MariaDB 11.8, ERROR 3780 both ways on
--      MySQL 8. Do not reach for SET FOREIGN_KEY_CHECKS=0 -- it does not lift
--      the refusal on MariaDB, and on MySQL 8 it lets the ALTER through and
--      leaves the two columns at DIFFERENT collations under a live FK, which is
--      worse than the error.
--
--      The procedure is docs/operations/provisioning-phase1-tables.md step 4b,
--      and only there. It drops the constraint, converts both tables, sweeps
--      orphans and re-adds -- with a precondition this comment used to state
--      more weakly than the runbook does, which is why it is no longer stated
--      twice: Spring Session deletes an expired session every sixty seconds and
--      every admin logout deletes one, so an orphan appears inside the window
--      and ADD CONSTRAINT then fails with ERROR 1452, leaving the table with NO
--      foreign key at all.
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
                      'legacy_runtime_offset_history', 'device_assignment_history',
                      'device_agents')
 ORDER BY (table_collation = 'utf8mb4_unicode_ci'), table_name;

-- The columns too. A table's default can read utf8mb4_unicode_ci while its
-- columns keep another collation or character set, for example after an
-- ALTER TABLE ... DEFAULT CHARACTER SET that changed only the default.
-- phase1_extensions.sql gives no column a collation of its own, so every string
-- column should be utf8mb4_unicode_ci, and the same CONVERT TO repairs it.
SELECT 'phase1 column collation' AS check_name,
       COALESCE(GROUP_CONCAT(CONCAT(table_name, '.', column_name, ' = ', collation_name)
                             ORDER BY table_name, column_name SEPARATOR ', '), 'none') AS value,
       IF(COUNT(*) = 0, 'ok', 'WRONG -- CONVERT TO utf8mb4_unicode_ci, see the note above') AS verdict
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND collation_name IS NOT NULL
   AND collation_name <> 'utf8mb4_unicode_ci'
   AND table_name IN ('legacy_refresh_tokens', 'platform_admins',
                      'platform_admin_audit_events', 'platform_admin_login_attempts',
                      'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES',
                      'attendance_devices', 'employee_device_identities',
                      'device_punches', 'unclaimed_device_sightings',
                      'device_operation_logs', 'device_malformed_punches',
                      'legacy_runtime_offset_history', 'device_assignment_history',
                      'device_agents');
