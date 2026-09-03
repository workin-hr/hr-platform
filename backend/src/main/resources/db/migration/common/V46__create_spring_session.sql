-- Spring Session JDBC's schema, for the platform-admin web surface
-- (ADR-0015 prerequisite 11).
--
-- Taken from Spring Session's own PostgreSQL DDL rather than invented, so an
-- upgrade compares against a known baseline. Two departures from the shipped
-- script, both deliberate:
--
--   * No `CREATE INDEX ... spring_session_ix1` on SESSION_ID -- the shipped
--     script creates it as a plain index alongside a UNIQUE constraint on the
--     same column, which is redundant on Postgres because the constraint is
--     already backed by a unique index.
--   * Unquoted lower-case identifiers, matching every other table in this
--     schema. Spring Session resolves table and column names through its own
--     configurable query set, and Postgres folds unquoted names to lower case,
--     so the defaults still resolve.
--
-- No row-level security policy. RLS on this schema is tenant isolation
-- (rls/V5 onwards, `company_id = current_setting('app.current_company_id')`),
-- and a platform-admin session has no company_id -- it is deliberately outside
-- the tenant domain (docs/architecture/authorization-model.md). A policy keyed
-- on a column that does not exist here would either fail closed on every read,
-- breaking sessions, or need a permissive exception, which is worse than not
-- claiming the protection at all. Isolation for this table is that only the
-- application connects to the database.
CREATE TABLE spring_session (
	primary_id CHAR(36) NOT NULL,
	session_id CHAR(36) NOT NULL,
	creation_time BIGINT NOT NULL,
	last_access_time BIGINT NOT NULL,
	max_inactive_interval INT NOT NULL,
	expiry_time BIGINT NOT NULL,
	principal_name VARCHAR(100),
	CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
	session_primary_id CHAR(36) NOT NULL,
	attribute_name VARCHAR(200) NOT NULL,
	attribute_bytes BYTEA NOT NULL,
	CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
	CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
		REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
