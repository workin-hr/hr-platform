# Provisioning The Phase 1 Tables

Closes the mechanical half of **R-023**: Phase 1 adds fourteen tables to the
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

### And a third

`legacy_runtime_offset_hooks.sql` installs the triggers that record every
runtime-offset change as it happens. Step 3 applies it, so it has to be
extracted here too — and it ships separately from `phase1_extensions.sql`
because that file must run against a database holding nothing else, while
these triggers reference the legacy `configs` table.

```bash
unzip -p backend.jar BOOT-INF/classes/db/phase1-mysql/legacy_runtime_offset_hooks.sql \
  > legacy_runtime_offset_hooks.sql
```

Skipping it is not a partial success. `PunchPairingService` refuses every
pairing pass while the triggers are absent — a seeded history with no writers
looks authoritative and goes stale silently — so the whole feature stays dark
with punches accumulating in `RECEIVED`.

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
    'unclaimed_device_sightings', 'device_operation_logs',
    'device_malformed_punches', 'device_assignment_history',
    'legacy_runtime_offset_history');
```

Expect zero rows on a database that has never been provisioned. Anything
else means a partial or earlier run, and the DDL will fail on the tables
that already exist — resolve that before continuing rather than editing
the file to skip them.

**2. Back up.** `docs/operations/backup-and-restore.md`. The change is
additive and its rollback is the [Rollback](#rollback) section below -- which is
not a `DROP TABLE` per name, and the order matters -- but a backup taken
immediately before any schema change is the cheaper of the two ways to
find that out.

**3. Apply.**

```bash
mysql -h "$HOST" -u "$USER" -p "$DATABASE" < phase1_extensions.sql
mysql -h "$HOST" -u "$USER" -p "$DATABASE" < slice_b_attendance_method.sql
# Order matters: the hooks reference legacy `configs` AND write into
# legacy_runtime_offset_history, so both must exist first. The file ends by
# seeding the current offset -- that row is where trustworthy coverage BEGINS
# and asserts nothing about what was in force before it.
mysql -h "$HOST" -u "$USER" -p "$DATABASE" < legacy_runtime_offset_hooks.sql
```

**4. Confirm.** Re-run step 1's query; expect all fourteen names. Then
confirm the runtime-offset writers are installed -- pairing refuses to run
without them, because a seeded history with no writers looks authoritative
while silently going stale:

```sql
SELECT TRIGGER_NAME FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = DATABASE()
  AND TRIGGER_NAME LIKE 'configs_runtime_offset_%';
```

Expect three rows. Then check the enum took:

```sql
SELECT COLUMN_TYPE FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attendance' AND COLUMN_NAME = 'method';
```

Expect `enum('app','excel','qr','device')`.

**4b. Check the collation, which none of the above can see.** Until
2026-09-11 `phase1_extensions.sql` declared no charset or collation, so these
tables inherited the server's — and a MariaDB 11.8 not started with
`--collation-server` defaults to `utf8mb4_uca1400_ai_ci`, while every legacy
table beside them is `utf8mb4_unicode_ci`. Names and column counts are right in
that state, and `Phase1SchemaCheck` compares names only, so every other check
here reports green. The first cross-table string comparison then fails at
runtime with `Illegal mix of collations`, on that host alone.

```sql
SELECT TABLE_NAME, TABLE_COLLATION
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_COLLATION <> 'utf8mb4_unicode_ci'
  AND TABLE_NAME IN (
    'legacy_refresh_tokens', 'platform_admins',
    'platform_admin_audit_events', 'platform_admin_login_attempts',
    'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES',
    'attendance_devices', 'employee_device_identities', 'device_punches',
    'unclaimed_device_sightings', 'device_operation_logs',
    'device_malformed_punches', 'device_assignment_history',
    'legacy_runtime_offset_history')
ORDER BY TABLE_NAME;
```

Expect zero rows. For each row it does return:

```sql
ALTER TABLE <name> CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

**The session pair is the exception.** `SPRING_SESSION_ATTRIBUTES_FK` is a
foreign key on a `CHAR` column, so `CONVERT TO` is refused in *both* directions
— `ERROR 1832` converting the child, `ERROR 1833` converting the parent — and
`SET FOREIGN_KEY_CHECKS=0` does not lift it on MariaDB. Drop the constraint,
convert both, put it back:

**Stop the application first.** Between the `DROP FOREIGN KEY` and the
`ADD CONSTRAINT` there is no constraint, and anything deleted from
`SPRING_SESSION` in that window leaves an orphan attribute row — after which
`ADD CONSTRAINT` fails with `ERROR 1452` and the table is left with **no foreign
key at all**. That is not a theoretical window: Spring Session's cleanup job
deletes expired sessions every sixty seconds, and every administrator logout
deletes one. The sweep below is the belt to that braces; run it even with the
application stopped.

```sql
ALTER TABLE SPRING_SESSION_ATTRIBUTES DROP FOREIGN KEY SPRING_SESSION_ATTRIBUTES_FK;
ALTER TABLE SPRING_SESSION            CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE SPRING_SESSION_ATTRIBUTES CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- Orphans, if any session vanished while the constraint was off.
DELETE a FROM SPRING_SESSION_ATTRIBUTES a
  LEFT JOIN SPRING_SESSION s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID
 WHERE s.PRIMARY_ID IS NULL;
ALTER TABLE SPRING_SESSION_ATTRIBUTES ADD CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK
    FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE;
```

If `ADD CONSTRAINT` still fails with `ERROR 1452`, a session was deleted after
the sweep: re-run the `DELETE` and the `ADD CONSTRAINT` together, with the
application stopped.

`CONVERT TO` rebuilds the table and holds a lock while it does, so plan the
window around `device_punches` — the others are small and stay small.

The error numbers above are MariaDB's. MySQL 8 raises `ERROR 3780` in both
directions instead, and there `SET FOREIGN_KEY_CHECKS=0` *does* let the `ALTER`
through — leaving the two columns at different collations under a live foreign
key, which is worse than the error. Do not use that flag on either engine.

`verify_phase1_tables.sql` runs this check as its section 6, with the same
remediation, if you would rather run one file than paste queries.

**5. Let the application confirm it independently.** `Phase1SchemaCheck`
runs at startup and logs one line per missing table
naming the feature it disables. A correctly provisioned deployment logs:

```text
Phase 1 schema check: all 14 owned tables are present.
```

This is the authoritative check — it reads the same list the tests pin to
the DDL, so it cannot drift from step 1's hand-written names. If the two
ever disagree, believe the application.

## What an operator sees when this was skipped

The check logs at `ERROR`, once per missing table, in the first seconds
of startup:

```text
Phase 1 schema check: 14 of 14 owned tables are MISSING from this database.
  missing table platform_admins -- disables the platform-admin surface at /admin -- nobody can sign in
```

It does **not** refuse to start. A missing admin table must not take
`/apis/**` down for every employee — the same containment reasoning that
has `LegacyBranchService` and `LegacyEmployeeStore` tolerate an absent
device table. The consequence is that provisioning cannot be verified by
the deployment succeeding; read the log.

## Rollback

**Drop the runtime-offset triggers FIRST**, before any table:

```sql
DROP TRIGGER IF EXISTS configs_runtime_offset_after_insert;
DROP TRIGGER IF EXISTS configs_runtime_offset_after_update;
DROP TRIGGER IF EXISTS configs_runtime_offset_after_delete;
```

They live on the legacy `configs` table and write into
`legacy_runtime_offset_history`. Dropping that table while they are installed
leaves them pointing at nothing, and the next PHP insert, update or delete on
`configs` then fails -- so the rollback that was supposed to return the
database to PHP would be what breaks it. Confirm with the `information_schema.TRIGGERS`
query in step 4: expect zero rows.

Then `DROP TABLE` each name, innermost first: `SPRING_SESSION_ATTRIBUTES`
before `SPRING_SESSION`, and `platform_admin_audit_events` before
`platform_admins`. Those are the two foreign keys among the fourteen; naming
all fourteen in one statement in the wrong order fails with
`ERROR 1451 (23000): Cannot delete or update a parent row` part-way through,
leaving the rollback half-done. Legacy PHP never referenced any of the fourteen
TABLES, so once the triggers are gone those fourteen are back to their
pre-Phase-1 state.

That is not the whole database. Step 3 also applied
`slice_b_attendance_method.sql`, which widened `attendance.method` from
`enum('app','excel','qr')` to `enum('app','excel','qr','device')`. Nothing above
reverses it, and reversing it is the riskier half: narrowing the enum silently
coerces any row already storing `'device'`. That procedure is in that file's own
header -- repoint or delete those rows first.

The one thing a drop destroys that matters is
`platform_admin_audit_events` — the record of what platform admins did.
Export it before dropping if any admin action has run.
