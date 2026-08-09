-- Re-runs V35's weekly-rest backfill under conditions where it can
-- actually see the rows.
--
-- V35 issued a bare UPDATE against employee_schedules. That table
-- carries FORCE ROW LEVEL SECURITY (rls/V34), and FORCE subjects the
-- table *owner* to the policy as well -- only a superuser or a role
-- with BYPASSRLS escapes it. The policy resolves
-- app.current_company_id, which no migration sets, so it evaluates to
-- NULL for every row. Under any migration role that is not superuser
-- or BYPASSRLS, V35 therefore matched zero rows and reported success.
--
-- The test suite could not catch it: Testcontainers' default Postgres
-- user is a superuser, so V35 bypassed RLS there and appeared to work.
-- That is the same masking effect ADR-0002 records from the PMR-07
-- spike (RLS silently providing zero isolation under a superuser
-- connection), reached from the migration side instead of the runtime
-- side. RlsForcedBackfillSemanticsTest now pins the behaviour this
-- migration depends on.
--
-- V35 is deliberately left untouched: it is already applied, and
-- editing an applied migration breaks Flyway's checksum validation.
-- This migration supersedes it and is idempotent -- where V35 did run
-- effectively (superuser migration roles), this one updates zero rows.
--
-- NO FORCE restores the standard "owner bypasses its own table's
-- policy" behaviour for the duration of this migration. The ALTER pair
-- is transactional DDL taking an ACCESS EXCLUSIVE lock, so no other
-- session can observe the table while it is un-forced, and any failure
-- rolls the whole migration back with FORCE intact. ALTER TABLE
-- requires ownership, so a role that has no business rewriting these
-- rows fails loudly here rather than silently skipping them again.
ALTER TABLE employee_schedules NO FORCE ROW LEVEL SECURITY;

UPDATE employee_schedules
SET exception_note = 'WEEKLY_REST'
WHERE exception_note = 'Weekly rest';

ALTER TABLE employee_schedules FORCE ROW LEVEL SECURITY;
