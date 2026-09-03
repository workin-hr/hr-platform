-- ADR-0015 prerequisite 4, the half that bounds API tokens.
--
-- rotate() issued every successor at `now + refresh TTL` without consulting
-- where the family started, so a family slid forward indefinitely: an
-- administrator who keeps refreshing never has to authenticate again, and the
-- "non-renewable absolute cap" the UI session enforces had no counterpart on
-- the surface that hands out bearer tokens.
--
-- The origin is carried on every row rather than derived from the family's
-- earliest row. Deriving it would mean an aggregate query on every rotation,
-- and -- worse -- the origin would be destroyed by any future pruning of
-- rotated rows, silently resetting the cap it exists to enforce.
ALTER TABLE platform_admin_refresh_tokens
	ADD COLUMN family_started_at TIMESTAMPTZ;

-- Backfill: for an existing family, the earliest row's creation is the origin.
UPDATE platform_admin_refresh_tokens t
SET family_started_at = origin.started_at
FROM (
	SELECT family_id, MIN(created_at) AS started_at
	FROM platform_admin_refresh_tokens
	GROUP BY family_id
) AS origin
WHERE t.family_id = origin.family_id;

ALTER TABLE platform_admin_refresh_tokens
	ALTER COLUMN family_started_at SET NOT NULL;
