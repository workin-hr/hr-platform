-- ADR-0015 prerequisite 1 and D-152: TOTP for platform administrators, with an
-- operator-assisted bootstrap so a password alone can never bind the second
-- factor.
--
-- A separate table, not columns on platform_admins, for two reasons. Existing
-- administrators must migrate in an explicitly *unbound* state (D-152), and the
-- absence of a row says that unambiguously -- nullable columns on the parent
-- would leave "unbound" and "half-written" indistinguishable. And the seed is
-- the most sensitive value in the schema; keeping it out of the row every
-- authentication reads narrows what an accidental SELECT * exposes.
CREATE TABLE platform_admin_mfa (
	platform_admin_id BIGINT PRIMARY KEY REFERENCES platform_admins(id),
	-- Encrypted at the application boundary under a key held outside the
	-- database, bound to platform_admin_id as AEAD additional data so a
	-- ciphertext cannot be moved between rows. See TotpSeedCipher.
	seed_ciphertext BYTEA NOT NULL,
	seed_nonce BYTEA NOT NULL,
	-- Which key encrypted this row, so rotation is "add a key, re-encrypt,
	-- retire the old version" rather than "decrypt everything at once".
	seed_key_version INTEGER NOT NULL,
	enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
	-- NULL until a code has actually been verified. Enrolment is not binding:
	-- D-152 requires "normal admin access only after a successful TOTP
	-- verification, not merely after enrolment".
	bound_at TIMESTAMPTZ,
	-- ADR-0015 prerequisite 12: the last time step accepted for this
	-- administrator. A code at or below it is refused, so an observed code
	-- cannot be replayed inside its own window.
	last_accepted_time_step BIGINT
);

-- D-152's enrolment token: generated server-side, delivered out of band,
-- required *in addition to* the password before enrolment is allowed.
CREATE TABLE platform_admin_mfa_bootstrap_tokens (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	platform_admin_id BIGINT NOT NULL REFERENCES platform_admins(id),
	-- Stored hashed, like every other token in this schema. The raw value is
	-- shown to the operator once and never persisted.
	token_hash VARCHAR(64) NOT NULL UNIQUE,
	issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
	expires_at TIMESTAMPTZ NOT NULL,
	used_at TIMESTAMPTZ,
	revoked_at TIMESTAMPTZ
);

CREATE INDEX platform_admin_mfa_bootstrap_tokens_admin_idx
	ON platform_admin_mfa_bootstrap_tokens (platform_admin_id);
