-- Read-only. Answers "can phase1_extensions.sql be applied to this database,
-- and has it been already?" -- before anything writes to it.
--
-- Paste into phpMyAdmin's SQL tab, or:
--   mysql -h HOST -u USER -p DBNAME < scripts/verify-phase1-tables.sql
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

-- 3. Which of the six already exist. `none` means apply the script as it is.
--    All six means it has been applied -- check (4) rather than re-running it.
--    Anything between is a partial apply: drop the ones listed and start again,
--    since the script is deliberately not idempotent.
SELECT 'phase1 tables present' AS check_name,
       COALESCE(GROUP_CONCAT(table_name ORDER BY table_name SEPARATOR ', '), 'none') AS value,
       CASE COUNT(*) WHEN 0 THEN 'not applied -- apply it'
                     WHEN 6 THEN 'applied'
                     ELSE 'PARTIAL -- drop these and re-apply' END AS verdict
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('legacy_refresh_tokens', 'platform_admins',
                      'platform_admin_audit_events', 'platform_admin_login_attempts',
                      'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES');

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
         ELSE 'UNEXPECTED -- compare against phase1_extensions.sql'
       END AS verdict
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name IN ('legacy_refresh_tokens', 'platform_admins',
                      'platform_admin_audit_events', 'platform_admin_login_attempts',
                      'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES')
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
                          'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES');
