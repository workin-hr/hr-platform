# ETL Real-Data Findings — Decision Brief (2026-08-13)

## Status: Q1–Q6 answered 2026-08-13; registered as D-035

Q1–Q6 were answered by the repository owner on 2026-08-13, recorded
verbatim below their questions and registered as **D-035** in
[`decision-log.md`](../bootstrap/decision-log.md). `companies.status`
moved from `PENDING` to `SCHEDULED` in `coverage_audit.py` as a direct
result. Q2–Q6 have no coverage-ledger entry to move — they're row-level
data defects, not column-coverage gaps — so their "done" state is a
`load_postgres.py` change plus tests, tracked in the punch list.

**10 of the 11 `PENDING` coverage-ledger gaps below (Finding I) remain
undecided** — the 7 `employees` columns plus
`attendance`/`advances`/`requests.updated_at`. Per the repository
owner's explicit sequencing: OQ-1/OQ-2/OQ-3 and the A/B/J tooling fixes
wait for those decisions too, then a clean ETL re-run, before any
implementation begins.

## Purpose

`docs/migration/2026-08-13-etl-next-steps-punch-list.md` ran the
complete ETL — extraction, load, and reconciliation — against the real
~62K-row legacy dataset (`hr-legacy/mysql_workin.schema.sql` +
`mysql_workin.data.sql`, dated 2026-08-03) for the first time. That run
found 10 things wrong (labeled A–J in the punch list): 4 are ETL/tooling
bugs with no product content, and 6 are real data-quality defects that
each need a decision the way `etl-coverage-decisions-brief.md`'s
Q1–Q8 did. This document is that brief for the 6, plus the full
accounting for the 7th finding (I) that turned out to widen to 11 real
gaps once fixed, and the prepared-not-applied engineering fixes for the
3 tooling bugs (A, B, J).

**The questions in this document are planning output**
(hr-platform `CLAUDE.md`). The answers are the repository owner's.
Nothing below has been implemented — every affected row was patched in
a scratch copy of the extracted CSVs only, to observe the load past
each failure to the next one, never in the repository.

## How To Use This

Same pattern as `etl-coverage-decisions-brief.md`: each question is
answerable yes/no or in one sentence. Answering one lets engineering
either write the remediation or record where it lands in
`coverage_audit.py`. A "no" / "drop it" answer is a fine answer — it
just has to be written down, the same as the earlier 47.

---

## Q1. What does a migrated `pending`-signup company with no name become?

**The gap.** `companies.company_name` is `NULL` or empty for 61 of 269
companies (22.7%) in the dump. Broken down by legacy `status`: `active`
0/198, `rejected` 0/2, `suspended` 0/3, **`pending` 61/66 (92%)**. Target
`companies.name` is `NOT NULL`; the load aborts on the first such row —
this is not a cosmetic gap, it blocks the entire `companies` table from
loading, which blocks everything downstream of it.

**Why it matters.** This is not corrupt data — it reads as a real
product state: a company that started signing up but never submitted a
business name. Legacy tolerates that (`company_name` is nullable
there); the target schema, as currently designed, does not represent
"signup in progress" as a distinct state at all.

**A second, related gap the same run found**: `companies.status` itself
has no target column. `load_postgres.py` currently computes
`COALESCE(s.status, 'active') = 'active'` into a single target `active`
boolean, which collapses legacy's four states (`active`, `pending`,
`rejected`, `suspended`) into two. After migration, a `pending`,
`rejected`, and `suspended` company are indistinguishable from each
other — both are `active = false`. Registered as `companies.status` in
`coverage_audit.py`'s `PENDING`.

**The question.** Three sub-questions, answerable together:
1. Do `pending` (incomplete-signup) companies migrate at all, or are
   they excluded from this migration pass as not-yet-real tenants?
2. If they migrate, what does `name` become — a placeholder string
   (e.g. the phone number, or a literal "(pending, no name)"), or does
   the row get a migration-invalid flag (same shape as OQ-2's phone
   handling) blocking activation until corrected?
3. Does `companies.status`'s four legacy states need a real target
   column (not a boolean) to survive migration distinctly, or is
   collapsing `pending`/`rejected`/`suspended` into "not active"
   acceptable?

**Recommended default** (not a decision): migrate `pending` companies
with a migration-invalid flag rather than excluding them (excluding
silently loses that 61 companies exist at all, which is worse than a
flagged placeholder), and give `companies` a real status column instead
of a boolean — the three-way distinction is cheap to keep now and
expensive to reconstruct later if it turns out to matter operationally.

### A1 (2026-08-13)

> Migrate all legacy companies, including incomplete pending signups.
> Preserve the four legacy lifecycle states in an explicit target
> status field. A pending company may have no name; do not fabricate a
> placeholder name and do not classify this valid legacy lifecycle
> state as migration-invalid. Require a non-null name before
> activation.

**All three sub-questions resolved**: (1) `pending` companies migrate,
no exclusion. (2) `name` stays nullable at the data layer — no
placeholder, no migration-invalid flag; this is explicitly *not* the
same shape as OQ-2's phone-normalization failures, it's a legitimate
in-progress state. "Non-null name before activation" is an
application-layer rule, not a migration-time fabrication — not yet
implemented anywhere. (3) `status` gets a real target column, not a
boolean — `companies.status` moved from `PENDING` to `SCHEDULED` in
`coverage_audit.py`, citing D-035.

---

## Q2. `salary_contracts.effective_from` zero-dates — what remediation value?

**The gap.** 23 of 2,829 `salary_contracts` rows have
`effective_from = '0000-00-00'`. The column is `NOT NULL` with no
default. **Already found** by `docs/migration/invalid-date-analysis.md`
(2026-08-04) via direct query; **this run adds nothing new except
proof it is a hard abort**, not just a query-level count — the load
aborts with `ERROR: date/time field value out of range: "0000-00-00"`
the first time it tries to load one of these 23 rows.

**Why it matters.** Blocks the entire `salary_contracts` table (2,829
rows), which blocks `payroll_batches`/`advances`/`penalties` after it in
`LOAD_ORDER`.

**The question** (already open in `invalid-date-analysis.md`, repeated
here because it now has load-abort evidence behind it, not just a
count): what does `effective_from` become for these 23 rows — a
sentinel date (the contract's own `created_at` date, or the company's
earliest known operational date), or are these 23 rows excluded
pending manual correction before migration?

**Recommended default**: sentinel = the row's own `created_at` date.
This is what the scratch run used to proceed past this row and is
consistent with `invalid-date-analysis.md`'s own suggestion — repeated
here as a recommendation, not a decision, since that document already
flagged it needs explicit confirmation this doesn't collide with
anything payroll-period-calculation-dependent (`hr-legacy#12`, `#13`).

### A2 (2026-08-13)

> For the 23 zero-date salary_contracts.effective_from values, migrate
> the contracts and use the row's created_at date as the deterministic
> fallback. Record the repair in migration remediation/audit output so
> the synthesized value remains traceable.

Matches the recommended default. **New requirement beyond the earlier
recommendation**: the fallback must be recorded in a migration
remediation/audit output — this mechanism does not exist yet in
`load_postgres.py` and is shared infrastructure Q3/Q5/Q6 also depend
on (see D-035's Follow-up).

---

## Q3. `attendance`: 13 rows have both a checkout AND an exception category — which wins?

**The gap.** `docs/legacy/business-rule-extraction.md` documents
attendance rows as either real punches (`check_in`/`check_out`) or a
category-only exception day (`exception_type_id` set, no checkout) —
never both. The target schema enforces this with `CHECK
(exception_type_id IS NULL OR check_out IS NULL)`. **13 of 36,316 real
rows violate it** — both fields are set simultaneously. Example: legacy
attendance id 376, employee legacy id 331: `check_in = 2026-07-01
11:35:29`, `check_out = 2026-07-01 16:27:16`, `exception_type_id = 50`,
`method = app`.

**Why it matters.** Blocks the entire `attendance` table (36,316 rows —
the single largest table) on its own `INSERT`, since the `CHECK`
constraint fires mid-statement.

**The question.** Either the documented "punches XOR exception" rule
has an exception legacy's own code allows (worth re-reading
`business-rule-extraction.md`'s source for this specific case), or
these 13 rows are themselves a legacy data-quality defect (e.g. an
exception category applied to a day after a punch already existed,
with nothing in legacy code preventing that). Which field should win —
keep the real punch and clear the exception category, keep the
exception and clear the punch, or exclude these 13 rows pending manual
review?

**Recommended default**: keep `check_out` (a real, timestamped punch is
stronger evidence of what actually happened than a category applied
after the fact), clear `exception_type_id`. This is what the scratch
run did to proceed — a test-only choice, not a considered
recommendation; the "or re-read legacy's business logic" option above
may be the better answer and needs someone who can actually check
before this is treated as settled.

### A3 (2026-08-13)

> Treat the 13 rows containing both a completed punch and an exception
> category as legacy data-quality defects. Preserve the real
> check_in/check_out timestamps, clear exception_type_id for those
> rows, and record each remediation. Keep the target XOR constraint.

Resolves the open question in favor of "these 13 rows are wrong," not
"the documented rule is incomplete." Matches the recommended default's
field choice (keep punch, clear exception). **Explicitly keeps the
target `CHECK` constraint** — the constraint was correct; the data
wasn't. Each of the 13 remediations must be individually recorded, same
shared audit-output dependency as A2.

---

## Q4. `leave_balance.year = '0000'` for 6 rows — what remediation value?

**The gap.** 6 of 2,980 `leave_balance` rows have `year = '0000'`.
Affected: employee legacy ids 4731, 4732, 4772, 4779, 4806, 4808.
`year` is not nullable per V25's schema. The load's synthesized
`created_at` (`make_timestamptz(s.year::INT, 1, 1, 0, 0, 0, 'UTC')` —
see `load_postgres.py`'s own comment: "the balance IS the year, so the
row cannot predate it") fails with `ERROR: date field value out of
range: 0-01-01` on these rows specifically. **Not previously
documented** — `invalid-date-analysis.md` doesn't cover this table.

**Why it matters.** Blocks the entire `leave_balances` table (2,980
rows).

**The question.** What does a zero-year leave-balance row become — a
sentinel year (which one — the employee's `hire_date` year, if that
survives Q's related decisions; the row's containing payroll period; a
fixed epoch), or exclusion of these 6 rows pending manual correction?

**Recommended default**: exclude these 6 rows from the initial
migration pass rather than guess a sentinel year — unlike
`effective_from` (where "roughly right now" is a defensible fallback
for a contract start date), a leave balance's `year` is load-bearing
for what the balance actually means, and a wrong guess here
misrepresents an employee's actual leave entitlement.

### A4 (2026-08-13)

> Do not synthesize a year for the six leave_balance.year = '0000'
> rows. Preserve them in the migration remediation/quarantine output
> and exclude them from the operational leave_balances load until the
> correct year is supplied.

Matches the recommended default (exclude, don't guess) — the one
finding of the six where fabricating a value was explicitly rejected
rather than a fallback chosen. Introduces a **quarantine** concept
beyond A2/A3/A5's "repair and record" pattern: these 6 rows need to be
held somewhere retrievable, not merely logged, pending a human supplying
the real year. No such mechanism exists yet.

---

## Q5. `employee_shift_assignments.effective_from` zero-dates — 23 of 3,568 rows

**The gap.** Same defect class as Q2, different table:
`effective_from = '0000-00-00'` for 23 of 3,568 rows. Not `NULL`-able.
**Not previously documented** — `invalid-date-analysis.md` never
checked this column. `ERROR: date/time field value out of range:
"0000-00-00"` on load.

**Why it matters.** Blocks the entire `employee_shift_assignments`
table (3,568 rows).

**The question.** Same as Q2, same table shape: sentinel date (the
row's own `created_at`) or exclusion pending correction?

**Recommended default**: sentinel = the row's own `created_at` date,
same reasoning as Q2 — a shift assignment's effective date being
slightly off is lower-stakes than a leave balance's year being wrong.

### A5 (2026-08-13)

> For the 23 zero-date employee_shift_assignments.effective_from
> values, use the row's created_at date as the migration fallback and
> record the repair explicitly.

Matches the recommended default and A2's pattern exactly — same
fallback, same explicit-recording requirement, same shared audit-output
dependency.

---

## Q6. 3 `exception_types` rows reference a company that does not exist — drop them?

**The gap.** Legacy `exception_types` ids 1, 3, 4 all have
`company_id = 19`; no `companies` row with `id = 19` exists anywhere in
this dump. `load_postgres.py`'s own fail-fast guard caught this
correctly and by design: `ETL: 3 exception_types rows were allocated an
id but never loaded -- a parent was missing from the export`. **This is
the guard working as intended, not a script defect** — it is a genuine
orphaned foreign key in the real legacy data. **Not previously
documented** in `orphan-reference-analysis.md`.

**Why it matters.** Blocks the entire `exception_types` table (111
rows) until resolved, since the guard aborts the whole load rather than
silently dropping the 3 orphans.

**The question.** Confirmed nothing downstream references these 3 ids
(zero `attendance` or `request_types` rows point to legacy exception
type ids 1, 3, or 4), so dropping them is low-risk — but is dropping
the right call, or does company legacy id 19's disappearance need
investigating first (e.g. was it hard-deleted somewhere legacy's own
schema should have cascaded and didn't)?

**Recommended default**: drop the 3 rows. Nothing depends on them, and
chasing why company 19 vanished from a snapshot dump is unlikely to be
answerable from the dump itself.

### A6 (2026-08-13)

> Exclude legacy exception_types IDs 1, 3, and 4 from the operational
> migration because their parent company does not exist and no
> downstream rows reference them. Record the three exclusions
> explicitly in migration reconciliation/remediation output.

Matches the recommended default (drop, no investigation required first
— the "nothing references them" confirmation already done in Q6 above
was sufficient). Same explicit-recording requirement as A2/A3/A5,
extending the shared audit-output dependency to reconciliation output
specifically, not just remediation.

---

## Finding I — `coverage_audit.py`'s detection blind spot: enumeration, root cause, and current status

**Status: code fix applied and self-tested; 11 gaps now registered
`PENDING`, replacing the previous "0 pending" state.** This is the one
finding from the 2026-08-13 run that was *fixed*, not just documented —
per instruction, because leaving the ledger's blind spot in place would
have meant every other finding in this brief kept sitting in a
`--check` that still claimed "0 pending" while these were silently
invisible.

### The bug, precisely

`find_gaps()` (`scripts/etl/coverage_audit.py`) had exactly two
detection branches per legacy column:

1. **`UNEXTRACTED_COLUMN`** — fires when a column is absent from its
   table's `SELECT` in `export_legacy.py`.
2. **`UNLOADED_COLUMN`** — fires when a column *is* staged in
   `load_postgres.py`'s `STAGING`, *does* have a matching target
   column, but is absent from the final `INSERT`.

A column that **is** selected, **is** staged, has **no** target column
at all, and is (necessarily, since it has nowhere to go) absent from
the `INSERT` — satisfies neither branch's precondition. Branch 1
requires "not selected"; branch 2 requires "has a target column." This
combination fell through invisibly. It was found not by reading the
detection code first, but by tracing the real, generated `INSERT INTO
employees (...)` line by line against `STAGING`'s declared column list
during the 2026-08-13 load run, and only then explained by reading
`find_gaps()`.

### The fix

Added a third branch, `UNTARGETED_COLUMN`: fires when a column is
staged and has no target column, checked *before* the `UNLOADED_COLUMN`
branch so the two remain mutually exclusive. Covered by a new
self-test fixture column (`kept.selected_untargeted`) and assertion.
`--report`'s fixed-kind iteration tuple updated to include it (it was
previously hardcoded to the two old kinds, and would have silently
omitted the new class from `--report`'s output the same way `find_gaps`
omitted it from detection).

### All 11 columns this uncovered, and why each was invisible before

The fix is general — it runs against every table, not just
`employees` — so it surfaced 4 more instances beyond the 7 found by
manual inspection of `employees` alone:

| Column | Registered as | Why |
|---|---|---|
| `employees.employee_code` | `PENDING` | No target column. Was a *known* exclusion — `V8__create_employees.sql`'s own comment calls it "intentionally omitted... tracked follow-up" — but was never registered in the ledger, so the ledger and the migration's own SQL comment disagreed about whether this was decided. |
| `employees.country_code` | `PENDING` | No target column. Never flagged as a decision before this run. |
| `employees.national_id` | `PENDING` | No target column. Never flagged. Legal-identity field, likely compliance-relevant. |
| `employees.birth_date` | `PENDING` | No target column. `invalid-date-analysis.md` already assumed this would migrate as `NULL` for 2 zero-date rows — there is currently nowhere for it to land at all. |
| `employees.gender` | `PENDING` | No target column. Never flagged. |
| `employees.hire_date` | `PENDING` | No target column. Same shape as `birth_date` — `invalid-date-analysis.md` already assumed `NULL` remediation for 22 zero-date rows, with nowhere to load. |
| `employees.updated_at` | `PENDING` | No target column. Directly relevant to OQ-3 (D-033), which requires database-enforced `updated_at` on every mutable business entity. |
| `attendance.updated_at` | `PENDING` | No target column (confirmed directly against `V21__create_attendance.sql`). Same OQ-3 family. |
| `advances.updated_at` | `PENDING` | No target column. Same OQ-3 family. |
| `requests.updated_at` | `PENDING` | No target column — `V25__create_requests_and_leave_balances.sql`'s own comment already says "no updated_at" explicitly, another case (like `employee_code`) of a known-at-the-time omission never carried into the ledger as a registered decision. Same OQ-3 family. |
| `companies.status` | `PENDING` | See Q1 above — not a clean drop, a lossy collapse into a boolean. |

Two more instances the same fix caught turned out **not** to be real
gaps, and are registered `ACCEPTED` instead, each with the target
column it actually lands in:

| Column | Registered as | Where it actually goes |
|---|---|---|
| `employees.is_active` | `ACCEPTED` | Target `employees.active` (boolean) — `COALESCE(s.is_active, '1') = '1'`. Clean rename, no information loss. |
| `requests.approver_id` | `ACCEPTED` | Target `requests.approver_membership_id` — the legacy employee id is resolved through the `tenant_memberships` id map. The role-split architecture (ADR-0009/ADR-0010) represents "who approved this" as a membership, not a bare employee id; the rename is deliberate. |

`employees.password_hash` looked like it might be a third case of the
same shape — it is not; it was already correctly registered `ACCEPTED`
before this run, because a target column genuinely exists for it and
the credential is deliberately carried onto `identities.password_hash`
instead, not silently dropped.

### Ledger state, before and after

Before 2026-08-13: `47 gaps — 8 accepted, 39 scheduled, 0 pending`.
**This was never an accurate "nothing is undecided" — it was 11 real
gaps the tool could not see, reported as if they did not exist.**

After the fix: `60 gaps — 10 accepted, 39 scheduled, 11 pending a
decision`. `--self-test` and `--check` both pass. This is the number to
treat as current; do not cite "47 gaps, 0 pending" anywhere going
forward without the 2026-08-13 correction attached.

---

## Prepared fixes — NOT YET APPLIED (findings A, B, J)

These three are ETL/tooling bugs with no product decision attached —
each is prepared here as a concrete, minimal patch plus the test that
would prove it, for a human to review and apply. **None of this has
been run against the repository.** Applying any of these still requires
someone to actually make the edit; nothing here executes automatically.

### Fix A — `export_legacy.py`'s documented extraction procedure doesn't work

**Problem**, precisely: `scripts/etl/README.md` and `export_legacy.py`'s
own header comment (lines 5-7) document
`mysql --defaults-file=... --batch --raw workin < export_legacy_mysql.sql`,
redirecting each `SELECT` to its matching manifest file. Tried literally
against `requests` (which has a free-text `notes` column): tab-separated
output (`\copy` wants comma-delimited `FORMAT csv`), `NULL` rendered as
the literal string `NULL` (not `\N`), and — the load-bearing part — 21
of 213 `requests.notes` rows and 4 of 213 `requests.reply` rows contain
real embedded newlines that `--raw` emits unescaped and unquoted,
corrupting row boundaries for any line-based reader (confirmed: `wc -l`
on a 213-row export returned 247 lines).

**Minimal fix**: replace the documented procedure with MySQL's own
`SELECT ... INTO OUTFILE`, which handles CSV quoting/embedded-newlines
correctly:

```sql
-- per table, in place of the current --batch --raw redirect:
SELECT <columns>
INTO OUTFILE '/var/lib/mysql-files/<table>.csv'
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"'
LINES TERMINATED BY '\n'
FROM <table> ORDER BY <key>;
```

verified empirically to produce output `\copy ... FORMAT csv ... NULL
'\N'` accepts directly, with default (backslash) `ESCAPED BY` giving
the correct `\N` for `NULL`. **Caveat that must be re-verified, not
assumed**: this default escaping is only safe because a scan of every
extracted free-text column in this snapshot found zero embedded
double-quote characters. If a future snapshot has a text field
containing a literal `"`, the default backslash-escaping would not
match PostgreSQL's CSV dialect (which expects doubled quotes, not
backslash-escaped ones) — the fix should either re-verify this
assumption against each new dump, or use `ESCAPED BY '"'` and handle
that `NULL` then renders as `"N` instead of `\N` (MySQL ties the NULL
marker to whatever `ESCAPED BY` is set to), which needs its own
handling.

`INTO OUTFILE` requires `secure_file_priv` write access and produces no
header row, so the emitted script would also need to prepend the
column-name header line per table (available from `STAGING` in
`load_postgres.py`, already kept in sync with `export_legacy.py`'s
`SELECT` lists by convention).

**Test to add**: an `export_legacy.py --self-test` case asserting the
emitted SQL uses `INTO OUTFILE`/`ENCLOSED BY`, not `--batch`/`--raw`
prose, would be weak (it can't run MySQL). Stronger: extend
`EtlLoadFixtureTest`'s fixture with a `requests.notes` value containing
an embedded newline, and assert the staged value round-trips intact —
this proves the *loading* side tolerates embedded newlines even though
the fixture bypasses the extraction step entirely (it populates staging
directly). That doesn't test extraction itself; genuinely testing
extraction needs a real MySQL instance, which is exactly why this class
of bug survived until a real dump was tried.

### Fix B — `export_legacy.py`'s `SELECT *` no longer matches `STAGING`

**Problem**: `SELECT * FROM setting_definitions` emits 11 columns
against `STAGING`'s declared 2 (`id`, `setting_key`); `SELECT * FROM
setting_allowed_values` emits 7 against `STAGING`'s declared 4. The
legacy schema grew (`label_ar`/`label_en`/`description_ar`/
`description_en`/`icon_data`/`is_multi`/`is_required`/`updated_at` on
the first; `label_ar`/`label_en`/`updated_at` on the second) since
`STAGING` was written. `\copy` rejects both with `ERROR: extra data
after last expected column`.

**Minimal fix**: two options, and this is really a Q7-shaped decision
in miniature, not a pure mechanical fix — someone needs to say which:
1. Replace `SELECT *` with an explicit column list matching `STAGING`'s
   current 2/4 columns exactly (accepts today's drop of the 9 newer
   columns as deliberate, matching however Q4/Q6 of the earlier
   `etl-coverage-decisions-brief.md` characterized these EAV tables).
2. Widen `STAGING`'s declared columns for both tables to the full
   current legacy shape, and decide (a genuine new small decision) what
   happens to the label/description/icon/multi/required columns —
   migrate them for real UI use, or formally accept the drop in
   `coverage_audit.py`.

**Recommended default**: option 1 (match `SELECT *` to `STAGING`'s
existing shape) is the smaller, safer patch and doesn't block anything;
option 2 is more correct if these newer legacy columns are actually
used by the current legacy dashboard UI (not verified either way here).

**Test to add**: a `coverage_audit.py`-style structural check —
`export_legacy.py --self-test` already asserts things like "every
manifest entry declares a key"; add "every `SELECT *` table's staged
column count matches its live column count," which would have caught
this the moment the legacy schema changed, without needing a real dump.
This can run against `mysql_workin.schema.sql` directly (already
available, no live MySQL needed) since it only needs column *names*,
not data.

### Fix J — `migration_diff.py` can't reconcile 18 of 21 tables (header mismatch)

**Problem**: `export_legacy.py` and `export_target_postgres.py` select
different column subsets for the same table by design — the target
export can only select columns that exist on the target table, and
several target tables are deliberately minimal (e.g.
`V1__create_companies.sql`'s comment: only enough for FK integrity and
RLS, `status`/`created_at` intentionally not carried over yet).
`migration_diff.py`'s `compare_table` requires `source_header ==
target_header` before comparing a single cell — for the 18 tables where
the two scripts' column lists differ at all, reconciliation never runs;
it reports a structural mismatch instead. The 3 tables with matching
headers (`attendance_days`, `departments`, `department_branches`)
passed with a genuine checksum match, proving the load itself is
correct where it's even comparable.

**Minimal fix**: `compare_table` (`scripts/migration_diff.py`) should
compare on the **intersection** of the two headers, not require
equality — report which columns exist on only one side (informational,
not necessarily a failure, since a target table missing a column by
design is not the same class of problem as a target table with wrong
*data*), then run the existing key/count/cell/checksum comparison over
the shared columns only:

```python
# sketch, not applied:
shared = [c for c in source_header if c in target_header]
only_source = [c for c in source_header if c not in target_header]
only_target = [c for c in target_header if c not in source_header]
if only_source or only_target:
    report.finding(f"column set differs: source-only={only_source} target-only={only_target}")
# ...then key/read/compare using `shared` in place of the full header
```

This changes the tool's contract (a "finding" for a column-set
difference becomes informational-severity, not the same as a value
mismatch) — worth a second look before applying, since silently
downgrading a real missing-column problem to "informational" could hide
something that matters. The safer version keeps `only_source`/
`only_target` as a loud finding, just a *different* one from
`header mismatch`, and still runs the value comparison on `shared`
underneath it.

**Test to add**: a `migration_diff.py --self-test` case with two
fixture CSVs that share a key column and 2 of 3 other columns, asserting
the differ (a) still compares the 2 shared columns for real mismatches,
and (b) still reports the column-set difference as a finding rather
than silently ignoring it.
