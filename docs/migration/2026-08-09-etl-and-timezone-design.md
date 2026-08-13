# ETL And Timezone Conversion — Design (2026-08-09)

## Status

Planning output. **No historical data may be moved until the rule in
"The Conversion Rule" below is accepted and its conformance test is
green.** That constraint is the whole reason this document exists
before any ETL code.

## Purpose And Authority

`AttendanceRules.java:13-22` records a divergence it could not resolve
and deferred explicitly: the new engine does all day arithmetic in UTC,
legacy pins a fixed `+02:00`/`+03:00` offset from a `configs` row, and
"that divergence predates this engine ... and is filed for a decision
before any data migration." This document settles it.

It also collects the ETL inputs, because the timezone rule is not a
standalone choice — it is the first transform in the load, and getting
it wrong is unrecoverable once historical attendance lands.

Evidence: `hr-legacy@d113204` — `apis/config/pdo.php:21-40`,
`apis/helpers/functions.php:243-261`, `mysql_workin.schema.sql`,
`dashboard/includes/db.php:7-45`; `hr-platform@d20f432` —
`AttendanceRules.java`, `AttendanceService.java`, the Flyway migrations,
`scripts/migration_diff.py`.

---

## The Finding That Decides It

Legacy's temporal columns are **not one kind of thing**. They split
three ways, and only one group is genuinely ambiguous.

| Group | Columns | Storage semantics | Convertible losslessly? |
|---|---|---|---|
| `timestamp` | `created_at`, `updated_at`, `decided_at`, `otp_codes.expires_at` | MySQL stores a **true UTC epoch** and converts on read/write via the session `time_zone` | **Yes.** The absolute instant is correct no matter which offset the writing session used. |
| `date` / `time` / `year(4)` | `from_date`, `to_date`, `from_time`, `hire_date`, `period_from`, `penalty_date`, `effective_from`, … | Naive by definition; carry no offset at all | **Yes.** Nothing to convert. |
| **`datetime`** | **`attendance.check_in`, `attendance.check_out`**, `branches.expires_at` | Literal wall-clock text, **no conversion ever applied** | **No.** See below. |

**Only three columns in the entire legacy schema are `datetime`, and two
of them are the attendance punches.** Everything else is either already
UTC or has no timezone to lose. The whole problem is those two columns.

### Why the `datetime` columns cannot be converted to true instants

The offset in force when a row was written **is not recorded anywhere**.
`configs.is_daylight_saving` is a single mutable global row: every punch
written while it was truthy meant `+03:00`, every other punch meant
`+02:00`, and **rows written on either side of a toggle are
indistinguishable**. Nothing in the row says which regime applied.

So a fixed-offset conversion is **provably wrong by exactly one hour for
some unknown subset** of the ~36,300 attendance rows, and the correct
offset cannot be recovered from the data. It would need the operational
history of when the flag was flipped — which nobody has written down.

Three things make it worse:

- **The toggle is manual**, so it never aligned with real Egypt DST
  dates. Substituting `Africa/Cairo` does not recover the truth; it
  invents a different wrong answer.
- **The dashboard writes with a third, unknown offset** — `dashboard/includes/db.php`
  issues no `SET time_zone` and sets no PHP default. **Resolved 2026-08-09,
  see "Dashboard datetime provenance" below: it does not affect
  attendance.**
- **`method='excel'` rows never had a server offset at all.** They carry
  fingerprint-device local wall clock and are ambiguous independently of
  the config flag.

---

## The Conversion Rule

> **Legacy `datetime` values are wall-clock labels, not instants. Load
> them verbatim as the same wall-clock reading in UTC. Legacy
> `timestamp` values are true instants; export them at UTC and load them
> as instants.**

Concretely:

```sql
-- datetime columns (attendance.check_in, attendance.check_out, branches.expires_at):
--   the literal reading is preserved; no shift is applied.
check_in::timestamp AT TIME ZONE 'UTC'   -- 2026-03-02 09:00:00 -> 2026-03-02T09:00:00Z

-- timestamp columns: export the session at UTC, load as the instant it is.
SET time_zone = '+00:00';                -- then created_at etc. are true UTC
```

### Why this one and not true-instant conversion

The alternative — `check_in AT TIME ZONE '+02:00'` to recover the real
instant — is the intuitive choice and it is wrong here, for three
reasons in increasing order of severity.

1. **It cannot be correct anyway.** Per the finding above, the per-row
   offset is unrecoverable, so "the real instant" is not a thing the
   data can produce. Option A performs a precise-looking transform on
   inputs that do not support it.

2. **It breaks day bucketing, which drives pay.** Every punch between
   midnight and 02:00/03:00 local would move to the *previous* UTC day.
   `AttendanceRules.dayOf()` would misfile it, the attendance-calendar
   engine would classify the wrong day, and absence figures feed payroll.

3. **It silently destroys exception days.** This is the decisive one.
   The new backend stores a category-only exception day at **UTC
   midnight**, and `AttendanceRules.isExceptionOnlyRow()` detects it by
   testing exactly that. A legacy exception day at `00:00:00` local,
   shifted by Option A, lands at 21:00 or 22:00 UTC the previous day and
   **stops being recognised as an exception day** — it is then scored as
   a real punch. No error, no warning; the day just changes meaning.

Option B keeps day bucketing identical to legacy, which is also what
makes the PMR-10 reconciliation meaningful: a difference between old and
new then indicates a migration fault rather than a definitional one.

### What the rule does not fix, and what to do instead

The rule makes the load **consistent and reversible**. It does not make
the underlying instants true, because they never were. Two consequences
must be handled rather than papered over:

- **Historical punches are wall-clock in Egypt local time, stored as if
  UTC.** Any future feature that compares a migrated punch against a
  real clock (device sync, cross-timezone reporting) must know this. The
  honest long-term fix is a per-company timezone column plus a one-off
  re-interpretation, done deliberately and separately — not smuggled
  into the migration.
- **New rows written after cutover are genuinely UTC.** The dataset is
  therefore mixed at the boundary. Record the cutover instant; it is the
  dividing line, and anything reasoning across it needs to know.

Both belong in the decision log alongside this rule.

---

## The Conformance Test — required before any data moves

The instruction is that the rule must be *settled and tested* before
historical attendance moves. The test is not "the ETL ran"; it is that
the round trip preserves the two properties the rule exists to protect.

1. **Wall-clock preservation.** For a sample of real punches, the
   `HH:mm:ss` and calendar date read back from Postgres equal the
   literal MySQL `datetime` text, byte for byte.
2. **Exception days stay exception days.** Every migrated row with an
   `exception_type_id`, no checkout, and a legacy time of `00:00:00`
   must satisfy `AttendanceRules.isExceptionOnlyRow()` after load. **A
   single failure here means Option A semantics leaked in.**
3. **Day bucketing agrees.** For every migrated punch,
   `AttendanceRules.dayOf(check_in)` equals `DATE(check_in)` as legacy
   computed it. Run this over the whole attendance table, not a sample —
   it is one comparison per row and it is the property everything else
   rests on.
4. **`timestamp` columns are true instants.** `created_at` round-trips
   to the same absolute time exported under `SET time_zone='+00:00'`.
5. **Segment `method='excel'` rows** in the report. They are ambiguous
   for a different reason and should be counted and shown separately
   rather than blended into the pass rate.

Tests 1–3 are cheap enough to run over the full table and should gate
the load, not follow it.

---

## ETL Shape

`scripts/migration_diff.py` compares CSV exports; **it does not move
data, and no extraction step exists yet.** That is the gap this design
opens, and it is the longest pole in the migration.

Load order follows foreign keys: `companies` → `identities` /
`tenant_memberships` → `branches` / `departments` / `job_titles` /
`shifts` → `employees` → everything referencing an employee
(`attendance`, `requests`, `salary_contracts`, `advances`, `penalties`)
→ payroll batches → payslips.

Two transforms are not row copies and need their own design:

- **`hr_permissions` → `membership_permission_overrides`**: legacy's
  per-employee boolean flags expand into ALLOW/DENY override rows
  against permission keys.
- **EAV `company_setting_values` → typed `company_settings`**: pivot
  from key/value rows into typed columns, with the legacy allowed-values
  tables as the vocabulary.

Identity remapping is required: the new tables use
`GENERATED ALWAYS AS IDENTITY`, so legacy integer PKs cannot be carried
over as-is. A per-table old→new id map must be materialised during load
and used for every FK, and it must be retained — reconciliation reports
in legacy ids or nobody can read them.

---

## Dashboard Datetime Provenance — resolved 2026-08-09

The open worry was that the dashboard, which sets no timezone at all,
might have written attendance punches under a third unknown offset. An
earlier pass concluded it writes no `datetime` columns. **That was
wrong** — it does write one. The conclusion still holds, for a better
reason.

**`attendance.check_in` — written, and timezone-independent.**
`dashboard/includes/request_actions_dashboard.php:114-127` inserts an
exception row per approved leave day:

```php
$date = $day->format('Y-m-d');
run('INSERT INTO attendance (employee_id, check_in, method, exception_type_id)
     VALUES (?, ?, ?, ?)', [$employee_id, $date . ' 00:00:00', 'app', $exception_type_id]);
```

The value is a **date-derived midnight literal**, built by concatenating
a `DATE` column with `' 00:00:00'`. It never reads a clock, so no
offset — the dashboard's missing timezone cannot corrupt it. It is also
exactly the category-only exception shape, which under the wall-clock
rule lands at UTC midnight and is still recognised by
`AttendanceRules.isExceptionOnlyRow()`.

The only other dashboard write to `attendance`
(`pages/company_settings/page.php:159`) sets `exception_type_id = NULL`
and touches no temporal column.

**Conclusion: historical attendance carries no third offset, and may
move under the rule above.** No `method`-based segmentation is needed
for dashboard provenance — only the separate `method='excel'`
device-clock case already noted.

**One genuinely ambiguous dashboard write remains, and it is not
attendance.** `branches.expires_at` (`includes/org_helper.php:811-823`)
is a user-entered `datetime-local` normalised through `strtotime()`
under the dashboard's unset timezone. It is QR-code expiry — a
future-dated operational value on a feature D-030 parked with no known
live caller, and it feeds neither attendance nor payroll. Migrate it as
wall-clock like the others and note it; do not let it hold up the
attendance load.

## Open Questions

- **The current value of `configs.is_daylight_saving`** is `true`,
  confirmed 2026-08-13 by querying `mysql_workin.schema.sql` +
  `mysql_workin.data.sql` (dump dated 2026-08-03) directly — see
  `scripts/etl/README.md` §"Before running a real extraction" #2 and
  `scripts/etl/export_legacy.py`'s final `SELECT` statement, which
  captures this at export time going forward. **The flip history is
  still not recorded anywhere** — a single current value says nothing
  about when the flag was last toggled, so it does not change how far
  off any specific historical instant is; it only confirms which regime
  applies to the most recent punches at dump time.
- **Per-company timezone** as the eventual correct model — out of scope
  here, named so the wall-clock load is understood as a migration
  measure rather than a permanent design.
