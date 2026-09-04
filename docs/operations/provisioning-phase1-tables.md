# Provisioning The Phase 1 Tables

Closes the mechanical half of **R-023**: Phase 1 adds ten tables to the
existing MariaDB, and until they exist the deployment is silently
incomplete. Nothing creates them at runtime — ADR-0013 gives Flyway no
ownership of any MariaDB schema — so this is a deliberate, human step
taken once, before cutover.

## What gets added, and what does not

No frozen legacy table is touched. Every statement in the DDL is a
`CREATE TABLE` or `CREATE INDEX` for a name legacy has never used, so
running it cannot alter, lock, or rewrite a table the PHP application
reads. That is what makes this safe to run against the live database
ahead of cutover rather than during it.

| Table | Carries |
|---|---|
| `legacy_refresh_tokens` | Token refresh for every mobile and desktop client |
| `platform_admins` | The platform-admin surface at `/admin` |
| `platform_admin_refresh_tokens` | Platform-admin token refresh |
| `platform_admin_audit_events` | The platform-admin audit trail |
| `platform_admin_login_attempts` | Platform-admin login throttling |
| `platform_admin_mfa` | Platform-admin TOTP |
| `platform_admin_mfa_bootstrap_tokens` | Platform-admin MFA enrolment and recovery |
| `platform_admin_step_up_approvals` | Step-up approval for platform-admin actions |
| `SPRING_SESSION` | The platform-admin web session |
| `SPRING_SESSION_ATTRIBUTES` | That session's contents |

`docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md`
adds five more once the device work lands. This list is not maintained by
hand: `Phase1SchemaCheckTest` fails the build if it stops matching the
DDL.

## The single definition

`backend/src/main/resources/db/phase1-mysql/phase1_extensions.sql`.

The MariaDB test container applies that exact file, so the schema the
suite proves the adapter against is the schema you run. It ships inside
the jar, which is the copy to prefer — it matches the deployed code
rather than whatever a branch has since become:

```bash
unzip -p backend.jar db/phase1-mysql/phase1_extensions.sql > phase1_extensions.sql
```

It is deliberately **not** idempotent. `CREATE TABLE IF NOT EXISTS`
would accept a table that already exists with the wrong columns, which is
the failure this file exists to prevent. Verify first, then apply.

## Procedure

**1. Check what is already there.** Read-only; safe to run any time.

```sql
SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN (
    'legacy_refresh_tokens', 'platform_admins', 'platform_admin_refresh_tokens',
    'platform_admin_audit_events', 'platform_admin_login_attempts',
    'platform_admin_mfa', 'platform_admin_mfa_bootstrap_tokens',
    'platform_admin_step_up_approvals', 'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES');
```

Expect zero rows on a database that has never been provisioned. Anything
else means a partial or earlier run, and the DDL will fail on the tables
that already exist — resolve that before continuing rather than editing
the file to skip them.

**2. Back up.** `docs/operations/backup-and-restore.md`. The change is
additive and its rollback is a `DROP TABLE` per name, but a backup taken
immediately before any schema change is the cheaper of the two ways to
find that out.

**3. Apply.**

```bash
mysql -h "$HOST" -u "$USER" -p "$DATABASE" < phase1_extensions.sql
```

**4. Confirm.** Re-run step 1's query; expect all ten names.

**5. Let the application confirm it independently.** `Phase1SchemaCheck`
runs at startup under `phase1-mysql` and logs one line per missing table
naming the feature it disables. A correctly provisioned deployment logs:

```text
Phase 1 schema check: all 10 owned tables are present.
```

This is the authoritative check — it reads the same list the tests pin to
the DDL, so it cannot drift from step 1's hand-written names. If the two
ever disagree, believe the application.

## What an operator sees when this was skipped

The check logs at `ERROR`, once per missing table, in the first seconds
of startup:

```text
Phase 1 schema check: 10 of 10 owned tables are MISSING from this database.
  missing table platform_admins -- disables the platform-admin surface at /admin -- nobody can sign in
```

It does **not** refuse to start. A missing admin table must not take
`/apis/**` down for every employee — the same containment reasoning that
has `LegacyBranchService` and `LegacyEmployeeStore` tolerate an absent
device table. The consequence is that provisioning cannot be verified by
the deployment succeeding; read the log.

## Rollback

`DROP TABLE` each name, innermost first (`SPRING_SESSION_ATTRIBUTES`
before `SPRING_SESSION`). Legacy PHP never referenced any of them, so
dropping them returns the database to exactly its pre-Phase-1 shape and
cannot affect a rollback to PHP.

The one thing a drop destroys that matters is
`platform_admin_audit_events` — the record of what platform admins did.
Export it before dropping if any admin action has run.
