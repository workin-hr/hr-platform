-- ADR-0015 prerequisite 10: the audit model has to grow before this surface
-- performs administrative actions.
--
-- Today the row carries actor, type, a free-text `detail` and a timestamp. It
-- can record *that someone logged in*; it cannot answer "who suspended which
-- company, when, and under which step-up approval". Prose in `detail` is not an
-- answer -- it cannot be filtered, joined, or relied on, and it drifts the
-- moment two call sites word it differently.
--
-- `detail` is kept, not replaced: it remains the right place for the human note
-- ("wrong password", "family <uuid>") that accompanies an event whose subject is
-- already identified by the structured columns.
ALTER TABLE platform_admin_audit_events
	ADD COLUMN target_type VARCHAR(64),
	ADD COLUMN target_id VARCHAR(64),
	-- Nullable, and not yet a foreign key: step-up approvals (prerequisite 2)
	-- do not exist. The column is added now so the audit contract is settled
	-- before the first administrative action is written against it, rather than
	-- migrated underneath rows that already exist.
	ADD COLUMN step_up_approval_id VARCHAR(64);

-- Answering "everything that happened to this company" is the query this table
-- exists to serve, and it is not the same as "everything this admin did".
CREATE INDEX platform_admin_audit_events_target_idx
	ON platform_admin_audit_events (target_type, target_id);
