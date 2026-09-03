-- ADR-0015 prerequisite 3: authentication throttling for the platform-admin
-- surface, in shared state that survives a restart.
--
-- A table rather than an in-memory counter, for the reason the prerequisite
-- gives: the budget has to hold across workers and across restarts, or it is
-- not a budget -- an attacker simply spreads attempts, or waits for a deploy.
--
-- Append-only, one row per failed attempt, counted over a window. A counter
-- column would need read-modify-write under contention; counting rows in a
-- window is a single indexed read and cannot lose an increment to a lost
-- update.
--
-- The identifier is stored as a SHA-256 hex digest, never the raw phone. The
-- rows exist to be counted, not read: an unauthenticated caller can put any
-- string here, so a plaintext column would be an attacker-writable list of
-- phone numbers sitting in the database. Attribution for attempts against a
-- *known* administrator already exists, with an admin foreign key, in
-- platform_admin_audit_events.
--
-- No row-level security policy: this is platform-domain data with no
-- company_id, exactly like platform_admins and platform_admin_audit_events.
CREATE TABLE platform_admin_login_attempts (
	id BIGSERIAL PRIMARY KEY,
	-- VARCHAR, not CHAR: Postgres pads CHAR to its declared width, which would
	-- make a digest comparison depend on trailing spaces. Also what JPA maps a
	-- String to, so ddl-auto=validate agrees with it.
	identifier_hash VARCHAR(64) NOT NULL,
	attempted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The only query shape: count attempts for one identifier since a cutoff, and
-- delete rows older than the window.
CREATE INDEX platform_admin_login_attempts_ix1
	ON platform_admin_login_attempts (identifier_hash, attempted_at);
