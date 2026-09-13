# Provisioning The Phase 1 Tables

Closes the mechanical half of **R-023**: Phase 1 adds fourteen tables to the
existing MariaDB, and until they exist the deployment is silently
incomplete. Nothing creates them at runtime — the application carries no
Flyway (ADR-0013 amendment 3; ADR-0017) — so this is a deliberate, human
step taken once, before cutover.

## What gets added, and what does not

No frozen legacy table is touched by `phase1_extensions.sql`. Every statement
in it is a `CREATE TABLE` or `CREATE INDEX` for a name legacy has never used,
so running it cannot alter, lock, or rewrite a table the PHP application
reads. That is what makes it safe to run against the live database ahead of
cutover rather than during it. The two files beside it do touch legacy tables
— one alters `attendance`, the other installs triggers on `configs` — and
their sections below say what that means.

| Table | Carries |
|---|---|
| `legacy_refresh_tokens` | Token refresh for every mobile and desktop client |
| `platform_admins` | The platform-admin surface at `/admin` |
| `platform_admin_audit_events` | The platform-admin audit trail |
| `platform_admin_login_attempts` | Platform-admin login throttling |
| `SPRING_SESSION` | The platform-admin web session |
| `SPRING_SESSION_ATTRIBUTES` | That session's contents |

`docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md`
adds eight more for the device work -- fourteen in total, which is what step 1
below checks for. The six-row table above is not maintained by hand: `Phase1SchemaCheckTest` fails the build if it stops matching the
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

It is deliberately **not** idempotent. `CREATE TABLE IF NOT EXISTS`
would accept a table that already exists with the wrong columns, which is
the failure this file exists to prevent. Verify first, then apply.

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
safe — one site reads `method` and renders it verbatim. New Java against the
old enum would not be refused: every connection runs `sql_mode=''`, under
which MariaDB stores a blank `method` and only warns. So `PunchPairingService`
checks the enum before it writes, and refuses to pair until this file is applied, leaving
device punches `RECEIVED`. Nothing calls it yet; once something does, skipping
this file is loud and recoverable, but avoidable.

Unlike the file above it *is* re-runnable: it states the column's target
shape rather than a delta. On `attendance` (36,316 rows / 64 MB) a fourth
value does not change a one-byte enum's storage, so it is
`ALGORITHM=INSTANT` and does not copy the table.

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

Skipping it is not a partial success. `PunchPairingService` refuses to pair
while the triggers are absent — a seeded history with no writers looks
authoritative and goes stale silently — and says so at `ERROR`. Nothing calls
it yet, so today a database without the triggers shows no symptom at all, and
step 4 is where you find out; once pairing runs, it pairs nothing and device
punches stay `RECEIVED`.

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
the file to skip them. Take step 2's backup first, because the recovery
changes the schema. Then run `verify_phase1_tables.sql`, which names the state
and what to do about it. For a partial apply, re-apply `phase1_extensions.sql`
with `mysql --force`, which creates only what is absent and reports one
`ERROR 1050` per existing table and one `ERROR 1061` per existing index. Where
all fourteen already exist, there is nothing for `--force` to create. Either
way, the tables that were already there were not made by this procedure, and
neither `--force` nor the script's column counts check their shape. So before
step 3, run `verify_phase1_tables.sql` again, then compare every owned table's
definition with the one `phase1_extensions.sql` creates (below). Then run step 3
with `SKIP_TABLES=1` set in the same shell, which skips `phase1_extensions.sql`:
the loop would otherwise stop at it again. Never use `--force` on
`legacy_runtime_offset_hooks.sql`: it skips that file's check that its target
table exists.

Run the check from the same jar as the DDL, with the connection settings step 2
describes:

```bash
unzip -p backend.jar BOOT-INF/classes/db/phase1-mysql/verify_phase1_tables.sql \
  > verify_phase1_tables.sql
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p "$DB_NAME" < verify_phase1_tables.sql
```

Whenever step 1's query found any of the fourteen, section 4's column counts are not
enough: a table with the right number of columns and a wrong type, default,
key, foreign key or engine still reads `ok (count only)`. So compare the definitions
themselves, on a machine with Docker. The query below is read-only; run it
against the live database and against a throwaway MariaDB of the live server's
version that holds only `phase1_extensions.sql`. It runs only against MariaDB
10.6 or later: it reads whether each index is `IGNORED`, which older MariaDB and
MySQL do not report (it stops at `ERROR 1054` and compares nothing), and its
throwaway server is a `mariadb` image. On any other server, compare by hand: run
`SHOW CREATE TABLE` for each of the fourteen, and the same on a scratch database
of that server holding only `phase1_extensions.sql`, and stop on any difference
other than collation, table-name case, or the `AUTO_INCREMENT=` value, which
counts rows rather than describing the table. The query below compares table
names in lower case: a server with `lower_case_table_names=1` reports
`SPRING_SESSION` as `spring_session`, and the application accepts either.

The block ends with one of three verdicts. `every owned table matches` needs
nothing more. `only collation differs` may go on to step 3, and step 4b repairs
the collation: step 3's triggers compare `configs` values only with literals, so
a table at another collation does not stop them. The block decides that by
comparing again with each table's collation set aside, and a column's only where
it is its own table's, not by the size of the difference. So a wrong type on a
table at the old collation is not mistaken for one, and neither is a column in
another character set or collation, which step 4b's table check alone would not
see. Anything after `STOPPED` is an owned table that differs from
what the application expects in more than collation: stop before step 3. Each
difference there names one
object, such as an index, a default, a foreign key or an engine, and needs a
repair for that object alone: an `ALTER` that gives it the definition
`phase1_extensions.sql` creates, written and reviewed before it runs, followed
by this comparison again. It is never an edit to the DDL, and never
[Rollback](#rollback), which drops all fourteen tables and whatever a partial
deployment has already stored in them.

A difference in `row_format` alone, on one of the twelve tables that
`phase1_extensions.sql` creates without naming one, comes from the live
server's `innodb_default_row_format` rather than from the table: check that
setting before writing any repair. On `compact` or `redundant` the file cannot
create `platform_admins` (`ERROR 1709`) or, after it,
`platform_admin_audit_events`, and the comparison reports both as missing.

The commands run in a subshell that stops at the first command to fail,
including a failed query on either side. They report a match only when the
throwaway database described all fourteen tables. A run that ends with an
error, and no verdict, compared nothing: do not continue to step 3. The
throwaway container has a name of its own and is removed when the subshell
exits, including after Ctrl-C.

```bash
names="'legacy_refresh_tokens','platform_admins','platform_admin_audit_events',
  'platform_admin_login_attempts','SPRING_SESSION','SPRING_SESSION_ATTRIBUTES',
  'attendance_devices','employee_device_identities','device_punches',
  'unclaimed_device_sightings','device_operation_logs','device_malformed_punches',
  'device_assignment_history','legacy_runtime_offset_history'"
cat > phase1-shape.sql <<SQL
SELECT 'table', LOWER(table_name), engine, row_format, table_collation, '', '', '', ''
  FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name IN ($names)
UNION ALL
SELECT 'column', LOWER(table_name), column_name, ordinal_position, column_type, is_nullable,
       COALESCE(column_default, '(no default)'), extra, COALESCE(collation_name, '')
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name IN ($names)
UNION ALL
SELECT 'index', LOWER(table_name), index_name, seq_in_index, column_name, non_unique,
       COALESCE(sub_part, ''), CONCAT(index_type, IF(s.ignored = 'YES', ' IGNORED', '')),
       COALESCE(s.collation, '')
  FROM information_schema.statistics s
 WHERE table_schema = DATABASE() AND table_name IN ($names)
UNION ALL
SELECT 'check', LOWER(table_name), constraint_name, check_clause, '', '', '', '', ''
  FROM information_schema.check_constraints
 WHERE constraint_schema = DATABASE() AND table_name IN ($names)
UNION ALL
SELECT 'foreign key', LOWER(table_name), constraint_name, LOWER(referenced_table_name),
       update_rule, delete_rule,
       IF(unique_constraint_schema = constraint_schema, 'same database', unique_constraint_schema), '', ''
  FROM information_schema.referential_constraints
 WHERE constraint_schema = DATABASE() AND table_name IN ($names)
UNION ALL
SELECT 'foreign key column', LOWER(table_name), constraint_name, ordinal_position,
       column_name, referenced_column_name,
       IF(referenced_table_schema = table_schema, 'same database', referenced_table_schema), '', ''
  FROM information_schema.key_column_usage
 WHERE table_schema = DATABASE() AND table_name IN ($names)
   AND referenced_table_name IS NOT NULL;
SQL
(
  set -euo pipefail
  mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p -N -B "$DB_NAME" < phase1-shape.sql \
    | LC_ALL=C sort > live-shape.txt
  version=$(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p -N -B -e 'SELECT VERSION()' | cut -d- -f1)
  name="phase1-shape-$(date +%s)-$RANDOM"
  trap 'docker rm -f "$name" > /dev/null 2>&1' EXIT
  trap 'exit 130' INT TERM
  docker run -d --name "$name" -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 -e MARIADB_DATABASE=shape \
    "mariadb:$version" --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci > /dev/null
  tries=0
  until docker exec "$name" healthcheck.sh --connect --innodb_initialized > /dev/null 2>&1; do
    tries=$((tries + 1))
    [ "$tries" -lt 90 ] || { echo "STOPPED: the throwaway MariaDB was not ready after 3 minutes" >&2; exit 1; }
    sleep 2
  done
  docker exec -i "$name" mariadb shape < phase1_extensions.sql
  docker exec -i "$name" mariadb -N -B shape < phase1-shape.sql | LC_ALL=C sort > expected-shape.txt
  [ "$(grep -c '^table' expected-shape.txt)" = 14 ] ||
    { echo "STOPPED: the throwaway database did not describe the fourteen tables; nothing was compared" >&2; exit 1; }
  if diff -q expected-shape.txt live-shape.txt > /dev/null; then
    echo "every owned table matches phase1_extensions.sql"
    exit 0
  fi
  # Step 4b converts a table with every column in it: set aside each table's
  # collation (field 5 of a table row), and a column's (field 9 of a column row)
  # only where it is its own table's. Any other column collation stays in.
  for side in expected live; do
    awk -F'\t' -v OFS='\t' 'NR == FNR { if ($1 == "table") own[$2] = $5; next }
      $1 == "table" { $5 = "" } $1 == "column" && $9 == own[$2] { $9 = "" } 1' \
      "${side}-shape.txt" "${side}-shape.txt" > "${side}-nocollation.txt"
  done
  if diff -q expected-nocollation.txt live-nocollation.txt > /dev/null; then
    echo "only collation differs: step 3 may go ahead, and step 4b repairs the collation"
  else
    echo "STOPPED: these differ in more than collation:" >&2
    diff expected-nocollation.txt live-nocollation.txt >&2 || true
    exit 1
  fi
)
```

**Do not drop anything to "start clean".** `platform_admin_audit_events` is
retained evidence (D-161) and `SPRING_SESSION` is every live administrator
session; neither is recreated with its contents. Nor are the device tables a
safe exception: `legacy_runtime_offset_history` is one of them, and the
triggers step 3 installs survive a drop and break PHP's own `configs` writes.
"Nothing has written to them yet" is a precondition nobody can check from the
outside, and a precondition printed next to a drop is read as permission.
`--force` is the answer here; if a drop is genuinely required, it is
[Rollback](#rollback), which drops the triggers first.

**2. Back up.** Most of the change is additive, but not all of it:
`slice_b_attendance_method.sql` alters `attendance`, and
`legacy_runtime_offset_hooks.sql` puts triggers on `configs`. Its rollback is
the [Rollback](#rollback) section below -- which is not a `DROP TABLE` per
name, and the order matters -- so a backup taken immediately before the change
is the cheaper of the two ways to find that out. This is a one-off safety copy
for this change, not the production backup method:
`docs/operations/backup-and-restore.md` still leaves that pending Discovery and
an ADR, and where backups are stored, who may read them and how they are
restored are decided there, not here. Until then, treat this file as production
personal data. The command writes it to your home directory, readable by you
alone rather than into a checkout; dispose of it when this change no longer
needs it. Nobody has yet tested restoring from it. Set `DB_HOST`, `DB_PORT`, `DB_USER` and `DB_NAME` first -- the names
`deploy/env.remote-db.example` uses. Do not rely on `HOST` or `USER`: many
shells already set them, to this machine and to you.

```bash
backup="$HOME/before-phase1-$(date +%F-%H%M%S).sql"
( umask 077 && set -o noclobber && mysqldump -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p \
    --single-transaction --routines --triggers --events --hex-blob \
    --default-character-set=utf8mb4 \
    "$DB_NAME" > "$backup" ) &&
  tail -n 1 "$backup" &&  # "-- Dump completed on ..."; anything else is a truncated dump
  ls -l "$backup"         # -rw------- : readable by you alone
```

`umask 077` sets the mode only of a file the dump creates. A dump written over
a file that already exists, such as one left by an earlier attempt, keeps that
file's permissions. So `noclobber` makes the shell refuse a path that exists,
with `cannot overwrite existing file` (`file exists` in zsh), and nothing after
it runs. Check that file's permissions, dispose of it, and run the command
again.

`--single-transaction` is what keeps PHP writing while it runs. Without it the
dump holds `LOCK TABLES … READ` on every table until it finishes, and PHP's
writes to them wait. With it the dump reads one consistent snapshot and takes
no table lock, which is sound here because every legacy table is InnoDB. A DDL
statement run alongside it can still break that snapshot, so do not start step
3 until the dump has finished. On a MariaDB 11 client the programs are
`mariadb-dump` and `mariadb` rather than `mysqldump` and `mysql`.

**3. Apply**, in this order, stopping at the first file that fails:

```bash
# Order matters: the hooks reference legacy `configs` AND write into
# legacy_runtime_offset_history, so both must exist first. The file ends by
# seeding the current offset -- that row is where trustworthy coverage BEGINS
# and asserts nothing about what was in force before it.
# Where the fourteen tables already exist (after step 1's recovery, or when
# step 4 sends you back here), set SKIP_TABLES=1 first: phase1_extensions.sql
# would stop this loop at its first CREATE TABLE.
rc=0
for ddl in phase1_extensions.sql slice_b_attendance_method.sql legacy_runtime_offset_hooks.sql; do
  if [ "$ddl" = phase1_extensions.sql ] && [ "${SKIP_TABLES:-0}" = 1 ]; then
    echo "--- $ddl skipped (SKIP_TABLES=1)"; continue
  fi
  if [ "$ddl" = legacy_runtime_offset_hooks.sql ]; then
    # The file drops its triggers before recreating them and seeds only an empty
    # history, so it runs only where neither loses a change. See the notes below.
    hooks=$(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p -N -B "$DB_NAME" -e \
      "SELECT (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE()
                 AND TRIGGER_NAME IN ('configs_runtime_offset_after_insert',
                   'configs_runtime_offset_after_update', 'configs_runtime_offset_after_delete')),
              EXISTS (SELECT 1 FROM legacy_runtime_offset_history)") || { rc=$?; echo "STOPPED at $ddl" >&2; break; }
    installed=$(printf '%s\n' "$hooks" | cut -f1); has_rows=$(printf '%s\n' "$hooks" | cut -f2)
    if [ "$installed" = 3 ] && [ "$has_rows" = 1 ]; then
      echo "--- $ddl skipped (its three triggers are installed and the history has rows)"; continue
    fi
    if [ "$has_rows" = 1 ]; then
      rc=1; echo "STOPPED at $ddl: its triggers are missing but legacy_runtime_offset_history has rows" >&2; break
    fi
  fi
  echo "--- $ddl"
  mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p "$DB_NAME" < "$ddl" || { rc=$?; echo "STOPPED at $ddl" >&2; break; }
done
(exit "$rc")   # the failing file's exit status, for anything that checks this block's status
```

Each file assumes the ones before it succeeded. `legacy_runtime_offset_hooks.sql`
refuses to install its triggers when their target table is missing, but only
for a client that stops on error; under `--force` the order above is the only
control.

`SKIP_TABLES=1` belongs wherever the fourteen tables already exist: after step
1's recovery, or when step 4 sends you back to step 3. Left set on a database
step 1 found empty, it skips the tables, `slice_b_attendance_method.sql` still
widens the enum, and the loop then stops at `legacy_runtime_offset_hooks.sql`,
whose history table does not exist. Run `unset SKIP_TABLES` before step 3 on such
a database.

Step 3 also skips `legacy_runtime_offset_hooks.sql` when its three triggers are
already installed and the history has rows. The file drops them before
recreating them, so a daylight-saving change PHP saved in between would go
unrecorded, and nothing afterwards could tell. Replacing installed triggers with
a newer definition is not part of this procedure: do it only while nothing
writes to `configs`. The check is by the three exact trigger names, the ones
pairing looks for, so another trigger named like them does not count.

If the triggers are installed but the history is empty, for example after an
earlier run stopped before the file's final seed, step 3 runs the file again.
Nothing is lost that way: the seed runs after the triggers are recreated and
reads `configs` as it then is.

**If step 3 stops because the triggers are missing while
`legacy_runtime_offset_history` already has rows, do not reinstall them.**
Something removed them after an earlier install, so any daylight-saving change
PHP saved since then is in `configs` but not in the history. The hooks file seeds
only an empty history, so reinstalling would report success and leave pairing
resolving those punches against the old offset. There is no repair procedure
yet; issue #208 tracks it.

**4. Confirm.** Re-run step 1's query; expect all fourteen names. Then
confirm the runtime-offset writers are installed -- pairing is written to
refuse without them, because a seeded history with no writers looks
authoritative while silently going stale, and nothing else here checks for
them:

```sql
SELECT TRIGGER_NAME FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = DATABASE()
  AND TRIGGER_NAME IN ('configs_runtime_offset_after_insert',
    'configs_runtime_offset_after_update', 'configs_runtime_offset_after_delete');
```

Expect three rows. Then confirm the history has its starting row:

```sql
SELECT COUNT(*) FROM legacy_runtime_offset_history;
```

Expect at least one. With none, pairing treats every punch as before the
history began (`PRE_HISTORY`) and ignores it: run step 3 again with
`SKIP_TABLES=1`, which seeds an empty history. Then check the enum took:

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

A table's default can be right while its columns are not, for example after an
`ALTER TABLE ... DEFAULT CHARACTER SET` that changed only the default. So check
the columns too; `phase1_extensions.sql` gives no column a collation of its own:

```sql
SELECT TABLE_NAME, COLUMN_NAME, CHARACTER_SET_NAME, COLLATION_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND COLLATION_NAME IS NOT NULL
  AND COLLATION_NAME <> 'utf8mb4_unicode_ci'
  AND TABLE_NAME IN (
    'legacy_refresh_tokens', 'platform_admins',
    'platform_admin_audit_events', 'platform_admin_login_attempts',
    'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES',
    'attendance_devices', 'employee_device_identities', 'device_punches',
    'unclaimed_device_sightings', 'device_operation_logs',
    'device_malformed_punches', 'device_assignment_history',
    'legacy_runtime_offset_history')
ORDER BY TABLE_NAME, COLUMN_NAME;
```

Expect zero rows from both. For each table either of them returns:

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

> **Extract the DDL from a jar built at or after the commit that corrected this
> runbook.** An older jar still carries a `verify_phase1_tables.sql` whose
> PARTIAL verdict told operators to drop the eight device tables --
> `legacy_runtime_offset_history` among them, with the `configs` triggers left
> standing. Check with `unzip -p app.jar BOOT-INF/classes/db/phase1-mysql/verify_phase1_tables.sql | grep -c 'Do NOT drop'`;
> zero means the jar predates the fix, and its advice must not be followed.

## Rollback

**Drop the runtime-offset triggers FIRST**, before any table:

```sql
DROP TRIGGER IF EXISTS configs_runtime_offset_after_insert;
DROP TRIGGER IF EXISTS configs_runtime_offset_after_update;
DROP TRIGGER IF EXISTS configs_runtime_offset_after_delete;
```

They live on the legacy `configs` table and write into
`legacy_runtime_offset_history`. Dropping that table while they are installed
leaves them pointing at nothing. Measured on MariaDB 11.8.8 with PHP's own
upsert statement:

| Write to `configs`, with the triggers installed and the table dropped | Result |
|---|---|
| another setting, new value | succeeds |
| `is_daylight_saving` saved with the value it already has | succeeds |
| `is_daylight_saving` switched | `ERROR 1146` |
| `is_daylight_saving` row added, or removed | `ERROR 1146` |

PHP's settings page writes every setting on every save, each in its own
autocommitted statement, and does not catch the error. A save that switches
daylight saving therefore stops at that setting: the settings before it keep
their new values, the ones after it are not saved, and the page never reports
success. Where the setting has never been saved, every save has to add it, so
every save fails. Once it exists, saves that leave it alone keep working,
which is what makes this easy to miss -- and why the rollback that was supposed
to return the database to PHP would be what breaks it. Confirm with the
`information_schema.TRIGGERS` query in step 4: expect zero rows.

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
