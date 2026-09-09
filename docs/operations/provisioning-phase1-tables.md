# Provisioning The Phase 1 Tables

Closes the mechanical half of **R-023**: Phase 1 adds six tables to the
existing MariaDB, and until they exist the deployment is silently
incomplete. Nothing creates them at runtime — the application carries no
Flyway (ADR-0013 amendment 3; ADR-0017) — so this is a deliberate, human
step taken once, before cutover.

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
| `platform_admin_audit_events` | The platform-admin audit trail |
| `platform_admin_login_attempts` | Platform-admin login throttling |
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
unzip -p backend.jar BOOT-INF/classes/db/phase1-mysql/phase1_extensions.sql > phase1_extensions.sql
```

### And one file beside it

`slice_b_attendance_method.sql`, in the same directory and the same jar
path, adds a fourth value to `attendance.method` for device punches
(D-164, D-214).

It is separate because it **alters a table the legacy contract owns**,
where the file above only *creates* tables Phase 1 adds. That difference
is load-bearing in both directions: `phase1_extensions.sql` must stay
applicable to a database holding nothing else — `Phase1SchemaCheckTest`
proves it by applying it to an empty scratch database — and the vendored
`mysql_workin.schema.sql` must stay byte-identical to `hr-legacy`'s dump,
which `check_legacy_schema_drift.py` enforces, so the ALTER cannot live
there either.

```bash
unzip -p backend.jar BOOT-INF/classes/db/phase1-mysql/slice_b_attendance_method.sql \
  > slice_b_attendance_method.sql
```

Apply it **only after** the legacy schema is in place, and **before**
deploying code that writes `'device'`. Old PHP against the widened enum is
safe — one site reads `method` and renders it verbatim — but new Java
against the old enum has its INSERT refused, so pairing would stall with
every punch left `RECEIVED`. Loud and recoverable, but avoidable.

Unlike the file above it *is* re-runnable: it states the column's target
shape rather than a delta. On `attendance` (36,316 rows / 64 MB) a fourth
value does not change a one-byte enum's storage, so it is
`ALGORITHM=INSTANT` and does not copy the table.

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
    'legacy_refresh_tokens', 'platform_admins',
    'platform_admin_audit_events', 'platform_admin_login_attempts',
    'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES',
    'attendance_devices', 'employee_device_identities', 'device_punches',
    'unclaimed_device_sightings', 'device_operation_logs');
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
mysql -h "$HOST" -u "$USER" -p "$DATABASE" < slice_b_attendance_method.sql
```

**4. Confirm.** Re-run step 1's query; expect all eleven names. Then
check the enum took:

```sql
SELECT COLUMN_TYPE FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attendance' AND COLUMN_NAME = 'method';
```

Expect `enum('app','excel','qr','device')`.

**5. Let the application confirm it independently.** `Phase1SchemaCheck`
runs at startup and logs one line per missing table
naming the feature it disables. A correctly provisioned deployment logs:

```text
Phase 1 schema check: all 11 owned tables are present.
```

This is the authoritative check — it reads the same list the tests pin to
the DDL, so it cannot drift from step 1's hand-written names. If the two
ever disagree, believe the application.

## What an operator sees when this was skipped

The check logs at `ERROR`, once per missing table, in the first seconds
of startup:

```text
Phase 1 schema check: 11 of 11 owned tables are MISSING from this database.
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
