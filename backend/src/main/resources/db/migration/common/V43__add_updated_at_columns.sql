-- D-036, citing D-033/OQ-3: employees.updated_at, attendance.updated_at,
-- advances.updated_at, requests.updated_at were the four
-- coverage-ledger gaps closed alongside D-036's six employees business
-- fields -- the owner directed these be moved citing OQ-3 (D-033)
-- directly, not treated as a fresh product decision.
--
-- Column only: nullable, no DEFAULT now(), NO TRIGGER. OQ-3 requires a
-- database-enforced updated_at whose trigger is suppressed during
-- load -- otherwise every migrated row would silently carry the load
-- timestamp instead of its real legacy value, the same trap
-- created_at fell into with DEFAULT now() (D-033's Impact). Adding
-- that enforcement now, before a load path exists to suppress it,
-- would repeat the exact defect OQ-3 exists to prevent. Enforcing
-- OQ-3 (trigger + suppression) is separate, later, tracked work
-- (2026-08-13 punch list, P0 item 8, "Enforce OQ-3 (updated_at) at
-- the database, suppressed correctly during load") -- this migration
-- only adds the column so a future load can write real legacy values
-- into it.
ALTER TABLE employees ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE attendance ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE advances ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE requests ADD COLUMN updated_at TIMESTAMPTZ;
