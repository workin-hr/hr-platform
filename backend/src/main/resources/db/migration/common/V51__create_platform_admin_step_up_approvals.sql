-- ADR-0015 prerequisite 2: step-up approval for destructive operations.
--
-- The ADR is specific about the four bounds, and each closes a different
-- replay:
--
--   * maximum age  -- an approval minted this morning must not authorise an
--     action this evening;
--   * single use   -- one approval, one action;
--   * action bound -- an approval for "suspend" must not authorise "delete";
--   * target and request-digest bound -- "an approval bound to 'suspend' but
--     not to *which company* is consumable against a different tenant". The
--     digest goes further and covers the rest of the security-relevant
--     parameters, so an approval for "suspend company 42 with reason X" cannot
--     be spent on a materially different request.
--
-- The digest is stored, never the parameters: it exists to be compared against
-- a value the server recomputes from the request it is actually about to
-- perform. Storing the parameters would invite comparing against what the
-- client sent instead, which is the thing being defended against.
CREATE TABLE platform_admin_step_up_approvals (
	id VARCHAR(64) PRIMARY KEY,
	platform_admin_id BIGINT NOT NULL REFERENCES platform_admins(id),
	-- The canonical operation, not a URL: URLs change and are attacker-shaped.
	action VARCHAR(64) NOT NULL,
	target_type VARCHAR(64) NOT NULL,
	target_id VARCHAR(64) NOT NULL,
	request_digest VARCHAR(64) NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
	expires_at TIMESTAMPTZ NOT NULL,
	consumed_at TIMESTAMPTZ
);

CREATE INDEX platform_admin_step_up_approvals_admin_idx
	ON platform_admin_step_up_approvals (platform_admin_id);
