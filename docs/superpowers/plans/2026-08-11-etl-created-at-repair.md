# ETL `created_at` Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry legacy `created_at` through the load for the 14 entities that
currently extract it, stage it, and then discard it — so migrated rows keep
their real creation timeline instead of all claiming the cutover instant.

**Architecture:** Every affected `_load` block in `scripts/etl/load_postgres.py`
gains `created_at` in its `INSERT` column list and
`(s.created_at::TIMESTAMP AT TIME ZONE 'UTC')` in its `SELECT` — the exact form
`departments` already uses and the only one currently correct. Because this
makes 14 previously-ignored columns load-bearing, `export_legacy.py` first
gains a probe for zero-dates and NULLs in those columns, so a value that would
abort the cutover run is discovered at dump time instead.

**Tech Stack:** Python 3 stdlib only (no driver, no network installs — a Phase 0
rule), PostgreSQL, JUnit 5 + Testcontainers via `AbstractIntegrationTest`.

## Global Constraints

- **Stdlib only.** `scripts/etl/*.py` must not import anything outside the
  standard library. They emit SQL; they never connect.
- **The timezone rule.** `timestamp` columns are true UTC epochs and convert
  losslessly; `datetime` columns are wall clock and are never shifted. Every
  column in this plan is a `timestamp`, so
  `(s.created_at::TIMESTAMP AT TIME ZONE 'UTC')` is correct for all of them.
  See `docs/migration/2026-08-09-etl-and-timezone-design.md`.
- **Fail loud, invent nothing.** A value that cannot be carried faithfully
  aborts by name. Nothing is silently defaulted.
- **Restartable and rerun-safe.** Every load step keeps its
  `WHERE NOT EXISTS (...)` guard. A completed load re-run inserts nothing.
- **Commit on a feature branch.** `scripts/git_guard.py` blocks commits on
  `main` and blocks `git push` entirely. Pushing and opening the PR is a
  human step.
- **Multi-line commit messages need `git commit -F <file>`.** The guard's
  tokenizer fails closed on an apostrophe inside a heredoc.
- **`EtlLoadFixtureTest.java` is tab-indented.** Java snippets in this plan
  are shown with 4 spaces because this repo's markdownlint rejects hard tabs
  (MD010) even inside fenced code. Match the file's tabs when pasting.

---

### Task 1: Probe `created_at` for zero-dates and NULLs before relying on it

The repair makes 14 columns load-bearing that no analysis has ever examined.
`docs/migration/invalid-date-analysis.md` confirms MySQL zero-dates are real in
this dump — 22 rows on `employees.hire_date`, 2 on `birth_date`, 23 on
`salary_contracts.effective_from`, and a strict-mode rejection on first import —
but it examined only those three columns. A `0000-00-00 00:00:00` in any
`created_at` aborts the cutover run at the cast. This task finds out at dump
time instead.

**Files:**

- Modify: `scripts/etl/export_legacy.py` (append to `EXPORT_SQL` before the
  closing `"""`; add checks in `self_test`)

**Interfaces:**

- Consumes: nothing from earlier tasks.
- Produces: a probe block in `EXPORT_SQL` whose output the operator reads
  before cutover. Tasks 2-5 rely on its guarantee that every `created_at`
  casts cleanly.

- [ ] **Step 1: Write the failing self-test check**

In `export_legacy.py`, inside `self_test()`, add after the existing
`check("departments and their branch assignments are exported, ...")` call:

```python
    check(
        "created_at is probed for zero-dates before the load depends on it",
        "created_at_quality" in EXPORT_SQL
        and "0000-00-00 00:00:00" in EXPORT_SQL
        and EXPORT_SQL.count("AS zero_dates") == 1,
    )
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `python3 scripts/etl/export_legacy.py --self-test`

Expected: `FAIL created_at is probed for zero-dates before the load depends on it`,
exit code 1.

- [ ] **Step 3: Add the probe to `EXPORT_SQL`**

Insert immediately before the `is_daylight_saving` block at the end of
`EXPORT_SQL`:

```sql
-- ---------- created_at quality probe ----------
-- The load carries legacy created_at into NOT NULL TIMESTAMPTZ columns.
-- MySQL permits '0000-00-00 00:00:00' where PostgreSQL has no such value,
-- and invalid-date-analysis.md proves zero-dates are real in this dump --
-- it just never examined these columns. A single bad value aborts the
-- cutover run at the cast, so it is counted here, at dump time, while
-- there is still time to decide what to do about it.
SELECT 'created_at_quality' AS probe, t.name AS table_name,
       t.zero_dates, t.null_dates
FROM (
    SELECT 'companies' AS name,
           SUM(created_at = '0000-00-00 00:00:00') AS zero_dates,
           SUM(created_at IS NULL) AS null_dates FROM companies
    UNION ALL SELECT 'employees',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM employees
    UNION ALL SELECT 'attendance',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM attendance
    UNION ALL SELECT 'branches',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM branches
    UNION ALL SELECT 'shifts',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM shifts
    UNION ALL SELECT 'job_titles',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM job_titles
    UNION ALL SELECT 'departments',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM departments
    UNION ALL SELECT 'exception_types',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM exception_types
    UNION ALL SELECT 'request_types',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM request_types
    UNION ALL SELECT 'requests',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM requests
    UNION ALL SELECT 'company_official_holidays',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM company_official_holidays
    UNION ALL SELECT 'salary_contracts',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM salary_contracts
    UNION ALL SELECT 'payroll_batches',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM payroll_batches
    UNION ALL SELECT 'advances',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM advances
    UNION ALL SELECT 'penalties',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM penalties
) t
ORDER BY t.name;
```

- [ ] **Step 4: Run the self-test to verify it passes**

Run: `python3 scripts/etl/export_legacy.py --self-test`

Expected: every line `OK`, exit code 0.

- [ ] **Step 5: Record the probe in the operator checklist**

In `scripts/etl/README.md`, under "Before running a real extraction", add a
fourth numbered item:

```markdown
4. Read the `created_at_quality` probe rows. Every `zero_dates` and
   `null_dates` count must be 0. The load casts `created_at` into a NOT NULL
   `TIMESTAMPTZ`, and MySQL's `0000-00-00 00:00:00` has no PostgreSQL
   equivalent — a nonzero count is a decision to make before cutover, not a
   surprise during it.
```

- [ ] **Step 6: Commit**

```bash
git add scripts/etl/export_legacy.py scripts/etl/README.md
git commit -m "test(etl): probe created_at for zero-dates before the load depends on it"
```

---

### Task 2: Carry `created_at` for the org entities

`companies`, `branches`, `shifts`, `job_titles`, `exception_types`. All five
resolve `company_id` through `id_map` and none has a parent whose timestamp
could substitute — the legacy value is the only source.

**Files:**

- Modify: `scripts/etl/load_postgres.py` (the five `_load` blocks)
- Test: `backend/src/test/java/com/workin/backend/migration/EtlLoadFixtureTest.java`

**Interfaces:**

- Consumes: Task 1's guarantee that `created_at` casts cleanly.
- Produces: `companies.created_at`, `branches.created_at`, `shifts.created_at`,
  `job_titles.created_at`, `exception_types.created_at` all carrying the legacy
  instant. Task 5's `payslips` work depends on this same pattern.

- [ ] **Step 1: Write the failing test**

In `EtlLoadFixtureTest.stageFixture`, add `created_at` to the five staged
inserts. Replace the `stg_companies`, `stg_branches`, `stg_shifts`,
`stg_job_titles`, and `stg_exception_types` statements with:

```sql
INSERT INTO migration.stg_companies (id, name, phone, status, created_at)
VALUES ('1', 'Legacy Co', '+2010TOKEN01', 'active', '2025-01-15 09:00:00'),
       ('2', 'Second Co', '+2020TOKEN02', 'active', '2025-02-20 14:30:00');

INSERT INTO migration.stg_branches (id, company_id, name, is_active, created_at)
VALUES ('7', '1', 'HQ', '1', '2025-03-01 10:00:00');

INSERT INTO migration.stg_job_titles
  (id, company_id, department_id, name, work_hours, is_active, created_at)
VALUES ('4', '1', '20', 'Engineer', '7.50', '1', '2025-03-02 11:00:00');

INSERT INTO migration.stg_shifts
  (id, company_id, name, start_time, end_time, days_off, is_active, created_at)
VALUES ('9', '1', 'Day', '09:00:00', '17:00:00', 'Friday,Saturday', '1',
        '2025-03-03 12:00:00');

INSERT INTO migration.stg_exception_types (id, company_id, name, created_at)
VALUES ('3', '1', 'Sick', '2025-03-04 13:00:00');
```

Then add to `theLoadProgramRunsEndToEndAndIsSafeToRerun`, after the existing
departments assertions:

```java
            // --- created_at is the legacy instant, not the load clock.
            // Legacy timestamps are true UTC epochs (the settled rule), so
            // the staged wall clock and the stored instant are the same
            // moment.
            assertThat(scalar(st,
                    "SELECT count(*) FROM companies c "
                            + "JOIN migration.id_map m ON m.entity = 'companies' AND m.new_id = c.id "
                            + "WHERE (m.legacy_id = 1 AND c.created_at = TIMESTAMPTZ '2025-01-15 09:00:00+00') "
                            + "   OR (m.legacy_id = 2 AND c.created_at = TIMESTAMPTZ '2025-02-20 14:30:00+00')"))
                    .isEqualTo(2);
            assertThat(scalar(st,
                    "SELECT count(*) FROM branches b "
                            + "JOIN migration.id_map m ON m.entity = 'branches' AND m.new_id = b.id "
                            + "WHERE m.legacy_id = 7 AND b.created_at = TIMESTAMPTZ '2025-03-01 10:00:00+00'"))
                    .isEqualTo(1);
            assertThat(scalar(st,
                    "SELECT count(*) FROM job_titles j "
                            + "JOIN migration.id_map m ON m.entity = 'job_titles' AND m.new_id = j.id "
                            + "WHERE m.legacy_id = 4 AND j.created_at = TIMESTAMPTZ '2025-03-02 11:00:00+00'"))
                    .isEqualTo(1);
            assertThat(scalar(st,
                    "SELECT count(*) FROM shifts sh "
                            + "JOIN migration.id_map m ON m.entity = 'shifts' AND m.new_id = sh.id "
                            + "WHERE m.legacy_id = 9 AND sh.created_at = TIMESTAMPTZ '2025-03-03 12:00:00+00'"))
                    .isEqualTo(1);
            assertThat(scalar(st,
                    "SELECT count(*) FROM exception_types et "
                            + "JOIN migration.id_map m ON m.entity = 'exception_types' AND m.new_id = et.id "
                            + "WHERE m.legacy_id = 3 AND et.created_at = TIMESTAMPTZ '2025-03-04 13:00:00+00'"))
                    .isEqualTo(1);
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :backend:test --tests '*EtlLoadFixtureTest*'` from `backend/`
(or repo root if the wrapper lives there).

Expected: FAIL. The counts come back `0` because `created_at` currently
defaults to `now()`, which does not equal the staged instants.

- [ ] **Step 3: Carry `created_at` in the five load blocks**

In `scripts/etl/load_postgres.py`, change each `INSERT` to name `created_at`
last and select the cast legacy value last.

`companies`:

```sql
INSERT INTO companies (id, name, phone, active, created_at) OVERRIDING SYSTEM VALUE
SELECT m.new_id, s.name, s.phone, COALESCE(s.status, 'active') = 'active',
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

`branches`:

```sql
INSERT INTO branches (id, company_id, name, address, latitude, longitude,
                      radius_meters, is_active, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name, s.address, s.latitude::NUMERIC,
       s.longitude::NUMERIC, COALESCE(s.radius_meters::INT, 0),
       COALESCE(s.is_active, '1') = '1',
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

`job_titles`:

```sql
INSERT INTO job_titles (id, company_id, department_id, name, work_hours,
                        is_active, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, dm.new_id, s.name,
       COALESCE(s.work_hours::NUMERIC, 8), COALESCE(s.is_active, '1') = '1',
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

`shifts`:

```sql
INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off,
                    is_active, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name, s.start_time::TIME, s.end_time::TIME,
       s.days_off, COALESCE(s.is_active, '1') = '1',
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

`exception_types`:

```sql
INSERT INTO exception_types (id, company_id, name, created_at) OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

Leave every `FROM`, `JOIN`, and `WHERE NOT EXISTS` clause exactly as it is.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :backend:test --tests '*EtlLoadFixtureTest*'`

Expected: PASS, both test methods.

- [ ] **Step 5: Commit**

```bash
git add scripts/etl/load_postgres.py backend/src/test/java/com/workin/backend/migration/EtlLoadFixtureTest.java
git commit -m "fix(etl): carry legacy created_at for the org entities"
```

---

### Task 3: Carry `created_at` for `employees` and `attendance`

The two largest tables — 2,871 and 36,316 rows. `attendance` matters most:
`created_at` is when the punch was *recorded*, which is distinct from
`check_in`, the wall-clock moment being recorded. Collapsing the first to the
cutover instant destroys the only evidence of when a row was entered, which is
exactly what a backdated-attendance investigation would need.

**Files:**

- Modify: `scripts/etl/load_postgres.py` (the `employees` and `attendance`
  `_load` blocks)
- Test: `backend/src/test/java/com/workin/backend/migration/EtlLoadFixtureTest.java`

**Interfaces:**

- Consumes: Task 1's probe guarantee.
- Produces: `employees.created_at`, `attendance.created_at` carrying the legacy
  instant, with `attendance.check_in` provably unchanged.

- [ ] **Step 1: Write the failing test**

In `stageFixture`, replace the `stg_employees` and `stg_attendance` inserts:

```sql
INSERT INTO migration.stg_employees
  (id, company_id, branch_id, department_id, job_title_id, expected_daily_hours, first_name,
   last_name, phone, password_hash, role, is_active, created_at)
VALUES
  ('11', '1', '7', '20', '4', '6.00', 'Sara', 'Ali', '+2011TOKEN11', '$2y$hash', 'hr', '1',
   '2025-04-01 08:00:00'),
  ('12', '1', '7', NULL, '4', NULL,   'Omar', 'Nabil', '+2012TOKEN22', '$2y$hash2', 'employee', '1',
   '2025-04-02 08:00:00'),
  ('13', '2', NULL, NULL, NULL, NULL, 'Laila', 'Fathy', '+2011TOKEN11', '$2y$hash3', 'employee', '1',
   '2025-04-03 08:00:00'),
  ('14', '1', NULL, NULL, NULL, NULL, 'Nabil', 'Omar', '+2012TOKEN22', '$2y$hash4', 'employee', '1',
   '2025-04-04 08:00:00');

-- created_at is when the punch was RECORDED; check_in is the wall-clock
-- moment being recorded. They are deliberately different values here so a
-- regression that conflates them fails.
INSERT INTO migration.stg_attendance
  (id, employee_id, check_in, check_out, method, exception_type_id, created_at)
VALUES
  ('101', '11', '2026-03-02 09:00:00', '2026-03-02 17:00:00', 'app', NULL,
   '2026-03-02 17:05:00'),
  ('102', '11', '2026-03-03 00:00:00', NULL, 'app', '3',
   '2026-03-04 06:30:00');
```

Add to `theLoadProgramRunsEndToEndAndIsSafeToRerun`:

```java
            assertThat(scalar(st,
                    "SELECT count(*) FROM employees e "
                            + "JOIN migration.id_map m ON m.entity = 'employees' AND m.new_id = e.id "
                            + "WHERE m.legacy_id = 11 AND e.created_at = TIMESTAMPTZ '2025-04-01 08:00:00+00'"))
                    .isEqualTo(1);
            // The recorded-at instant is carried AND the wall-clock rule still
            // holds: check_in is untouched, created_at is its own value.
            assertThat(scalar(st,
                    "SELECT count(*) FROM attendance a "
                            + "JOIN migration.id_map m ON m.entity = 'attendance' AND m.new_id = a.id "
                            + "WHERE m.legacy_id = 101 "
                            + "AND a.created_at = TIMESTAMPTZ '2026-03-02 17:05:00+00' "
                            + "AND a.check_in = TIMESTAMPTZ '2026-03-02 09:00:00+00'"))
                    .isEqualTo(1);
            assertThat(scalar(st,
                    "SELECT count(*) FROM attendance a "
                            + "JOIN migration.id_map m ON m.entity = 'attendance' AND m.new_id = a.id "
                            + "WHERE m.legacy_id = 102 "
                            + "AND a.created_at = TIMESTAMPTZ '2026-03-04 06:30:00+00' "
                            + "AND a.check_in = TIMESTAMPTZ '2026-03-03 00:00:00+00'"))
                    .isEqualTo(1);
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :backend:test --tests '*EtlLoadFixtureTest*'`

Expected: FAIL on the three new assertions, count `0`.

- [ ] **Step 3: Carry `created_at` in both load blocks**

`employees`:

```sql
INSERT INTO employees (id, company_id, first_name, last_name, phone, role, active,
                       branch_id, department_id, job_title_id, expected_daily_hours,
                       created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.first_name, COALESCE(s.last_name, ''),
       CASE WHEN pa.anchor_employee_id = s.id::BIGINT THEN s.phone ELSE NULL END,
       UPPER(COALESCE(s.role, 'employee')), COALESCE(s.is_active, '1') = '1',
       bm.new_id, dm.new_id, jm.new_id, s.expected_daily_hours::NUMERIC,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

`attendance`:

```sql
INSERT INTO attendance (id, employee_id, company_id, check_in, check_out, method,
                        latitude, longitude, exception_type_id, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id,
       (s.check_in::TIMESTAMP AT TIME ZONE 'UTC'),
       (s.check_out::TIMESTAMP AT TIME ZONE 'UTC'),
       CASE WHEN s.exception_type_id IS NULL THEN COALESCE(s.method, 'app') ELSE NULL END,
       s.latitude::NUMERIC, s.longitude::NUMERIC, ex.new_id,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :backend:test --tests '*EtlLoadFixtureTest*'`

Expected: PASS. Confirm the existing wall-clock and midnight-exception
assertions still pass — they prove `check_in` was not disturbed.

- [ ] **Step 5: Commit**

```bash
git add scripts/etl/load_postgres.py backend/src/test/java/com/workin/backend/migration/EtlLoadFixtureTest.java
git commit -m "fix(etl): carry legacy created_at for employees and attendance"
```

---

### Task 4: Carry `created_at` for the requests group

`request_types`, `requests`, `company_official_holidays`. `requests` already
carries `decided_at` — added by #84 after the same class of defect — so this
completes that table's timestamp handling.

**Files:**

- Modify: `scripts/etl/load_postgres.py` (three `_load` blocks)
- Test: `backend/src/test/java/com/workin/backend/migration/EtlLoadFixtureTest.java`

**Interfaces:**

- Consumes: Task 1's probe guarantee.
- Produces: `request_types.created_at`, `requests.created_at`,
  `company_official_holidays.created_at` carrying the legacy instant.

- [ ] **Step 1: Write the failing test**

`stageFixture` currently stages no holidays. Replace the `stg_request_types`
and `stg_requests` inserts and add a holiday:

```sql
INSERT INTO migration.stg_request_types (id, company_id, name, created_at)
VALUES ('1', '1', 'Sick Leave', '2025-05-01 09:00:00');

INSERT INTO migration.stg_requests
  (id, employee_id, request_type_id, from_date, to_date, notes, status, reply,
   approver_id, decided_at, created_at)
VALUES
  ('200', '12', '1', '2026-03-05', '2026-03-05', 'Doctor appointment', 'approved',
   'Get well soon', '11', '2026-03-04 10:00:00', '2026-03-01 07:45:00');

INSERT INTO migration.stg_company_official_holidays
  (id, company_id, name, holiday_date, created_at)
VALUES ('310', '1', 'Eid', '2026-03-20', '2025-12-01 08:00:00');
```

Add assertions:

```java
            assertThat(scalar(st,
                    "SELECT count(*) FROM request_types rt "
                            + "JOIN migration.id_map m ON m.entity = 'request_types' AND m.new_id = rt.id "
                            + "WHERE m.legacy_id = 1 AND rt.created_at = TIMESTAMPTZ '2025-05-01 09:00:00+00'"))
                    .isEqualTo(1);
            // created_at and decided_at are distinct instants and both survive.
            assertThat(scalar(st,
                    "SELECT count(*) FROM requests r "
                            + "JOIN migration.id_map m ON m.entity = 'requests' AND m.new_id = r.id "
                            + "WHERE m.legacy_id = 200 "
                            + "AND r.created_at = TIMESTAMPTZ '2026-03-01 07:45:00+00' "
                            + "AND r.decided_at = TIMESTAMPTZ '2026-03-04 10:00:00+00'"))
                    .isEqualTo(1);
            assertThat(scalar(st,
                    "SELECT count(*) FROM company_official_holidays h "
                            + "JOIN migration.id_map m ON m.entity = 'company_official_holidays' AND m.new_id = h.id "
                            + "WHERE m.legacy_id = 310 "
                            + "AND h.created_at = TIMESTAMPTZ '2025-12-01 08:00:00+00' "
                            + "AND h.holiday_date = DATE '2026-03-20'")).isEqualTo(1);
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :backend:test --tests '*EtlLoadFixtureTest*'`

Expected: FAIL on all three new assertions.

- [ ] **Step 3: Carry `created_at` in the three load blocks**

`request_types`:

```sql
INSERT INTO request_types (id, company_id, name, is_active, deduct_balance,
                           counts_as_paid_leave, add_attendance_exception,
                           exception_type_id, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name, COALESCE(s.is_active, '1') = '1',
       COALESCE(s.deduct_balance, '0') = '1',
       COALESCE(s.counts_as_paid_leave, '1') = '1',
       COALESCE(s.add_attendance_exception, '0') = '1', em.new_id,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

`requests`:

```sql
INSERT INTO requests (id, employee_id, company_id, request_type_id, from_date, to_date,
                      from_time, to_time, notes, status, reply,
                      approver_membership_id, decided_at, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id, rt.new_id, s.from_date::DATE,
       s.to_date::DATE, s.from_time::TIME, s.to_time::TIME, s.notes,
       UPPER(COALESCE(s.status, 'pending')), s.reply, am.new_id,
       (s.decided_at::TIMESTAMP AT TIME ZONE 'UTC'),
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

`company_official_holidays`:

```sql
INSERT INTO company_official_holidays (id, company_id, name, holiday_date, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name, s.holiday_date::DATE,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :backend:test --tests '*EtlLoadFixtureTest*'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/etl/load_postgres.py backend/src/test/java/com/workin/backend/migration/EtlLoadFixtureTest.java
git commit -m "fix(etl): carry legacy created_at for the requests group"
```

---

### Task 5: Carry `created_at` for the payroll group

`salary_contracts`, `payroll_batches`, `advances`, `penalties`. None of these
four is currently staged by the fixture at all, so their load blocks run
against zero rows today — this task gives them their first real coverage as
well as the timestamp fix. `payroll_batches` is the one the four-table ETL
slice depends on: without it, deriving `payslips.created_at` from the parent
batch yields the load clock.

**Files:**

- Modify: `scripts/etl/load_postgres.py` (four `_load` blocks)
- Test: `backend/src/test/java/com/workin/backend/migration/EtlLoadFixtureTest.java`

**Interfaces:**

- Consumes: Task 1's probe guarantee.
- Produces: `payroll_batches.created_at` carrying the legacy instant — the
  precondition for `payslips.created_at` in
  `docs/superpowers/specs/2026-08-11-etl-payroll-history-design.md` (decision
  D-b). Also `salary_contracts.created_at`, `advances.created_at`,
  `penalties.created_at`.

- [ ] **Step 1: Write the failing test**

Add to `stageFixture`, after the requests block:

```sql
INSERT INTO migration.stg_salary_contracts
  (id, employee_id, salary_mode, basic_salary, daily_wage, effective_from, created_at)
VALUES ('500', '11', 'monthly', '5000.00', '0.00', '2026-01-01',
        '2025-12-15 10:00:00');

INSERT INTO migration.stg_payroll_batches
  (id, company_id, month, year, period_from, period_to, status, created_at)
VALUES ('90', '1', '3', '2026', '2026-03-01', '2026-03-31', 'finalized',
        '2026-04-01 12:00:00');

INSERT INTO migration.stg_advances
  (id, employee_id, amount, remaining, reason, status, request_date, created_at)
VALUES ('600', '11', '1000.00', '250.00', 'Emergency', 'approved', '2026-02-10',
        '2026-02-10 09:30:00');

INSERT INTO migration.stg_penalties
  (id, employee_id, penalty_type, penalty_days, reason, penalty_date,
   applied_to_payroll, created_at)
VALUES ('650', '11', 'late', '0.5', 'Late arrival', '2026-02-12', '0',
        '2026-02-12 11:00:00');
```

Add assertions:

```java
            // --- the payroll group: first fixture coverage, and the batch
            // timestamp the payslips slice derives from.
            assertThat(scalar(st,
                    "SELECT count(*) FROM salary_contracts sc "
                            + "JOIN migration.id_map m ON m.entity = 'salary_contracts' AND m.new_id = sc.id "
                            + "WHERE m.legacy_id = 500 "
                            + "AND sc.created_at = TIMESTAMPTZ '2025-12-15 10:00:00+00'")).isEqualTo(1);
            assertThat(scalar(st,
                    "SELECT count(*) FROM payroll_batches b "
                            + "JOIN migration.id_map m ON m.entity = 'payroll_batches' AND m.new_id = b.id "
                            + "WHERE m.legacy_id = 90 "
                            + "AND b.created_at = TIMESTAMPTZ '2026-04-01 12:00:00+00' "
                            + "AND b.status = 'FINALIZED'")).isEqualTo(1);
            assertThat(scalar(st,
                    "SELECT count(*) FROM advances a "
                            + "JOIN migration.id_map m ON m.entity = 'advances' AND m.new_id = a.id "
                            + "WHERE m.legacy_id = 600 "
                            + "AND a.created_at = TIMESTAMPTZ '2026-02-10 09:30:00+00'")).isEqualTo(1);
            assertThat(scalar(st,
                    "SELECT count(*) FROM penalties pn "
                            + "JOIN migration.id_map m ON m.entity = 'penalties' AND m.new_id = pn.id "
                            + "WHERE m.legacy_id = 650 "
                            + "AND pn.created_at = TIMESTAMPTZ '2026-02-12 11:00:00+00'")).isEqualTo(1);
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :backend:test --tests '*EtlLoadFixtureTest*'`

Expected: FAIL on all four new assertions.

- [ ] **Step 3: Carry `created_at` in the four load blocks**

`salary_contracts`:

```sql
INSERT INTO salary_contracts (id, employee_id, company_id, salary_mode, basic_salary,
                              daily_wage, housing_allowance, transport_allowance,
                              food_allowance, risk_allowance, incentives,
                              insurance_deduction, tax_deduction, advances_deduction,
                              fund_deduction, penalty_deduction, effective_from,
                              created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id, UPPER(COALESCE(s.salary_mode, 'monthly')),
       COALESCE(s.basic_salary::NUMERIC, 0), COALESCE(s.daily_wage::NUMERIC, 0),
       COALESCE(s.housing_allowance::NUMERIC, 0),
       COALESCE(s.transport_allowance::NUMERIC, 0),
       COALESCE(s.food_allowance::NUMERIC, 0), COALESCE(s.risk_allowance::NUMERIC, 0),
       COALESCE(s.incentives::NUMERIC, 0), COALESCE(s.insurance_deduction::NUMERIC, 0),
       COALESCE(s.tax_deduction::NUMERIC, 0), COALESCE(s.advances_deduction::NUMERIC, 0),
       COALESCE(s.fund_deduction::NUMERIC, 0), COALESCE(s.penalty_deduction::NUMERIC, 0),
       s.effective_from::DATE,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

`payroll_batches`:

```sql
INSERT INTO payroll_batches (id, company_id, month, year, period_from, period_to,
                             status, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.month::SMALLINT, s.year::SMALLINT,
       s.period_from::DATE, s.period_to::DATE,
       CASE WHEN LOWER(COALESCE(s.status, 'draft')) IN ('finalized', 'final')
            THEN 'FINALIZED' ELSE 'DRAFT' END,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

`advances`:

```sql
INSERT INTO advances (id, employee_id, company_id, amount, remaining, reason,
                      rejection_reason, status, request_date, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id, COALESCE(s.amount::NUMERIC, 0),
       COALESCE(s.remaining::NUMERIC, 0), s.reason, s.rejection_reason,
       UPPER(COALESCE(s.status, 'pending')), s.request_date::DATE,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

`penalties`:

```sql
INSERT INTO penalties (id, employee_id, company_id, penalty_type, penalty_days, reason,
                       penalty_date, applied_to_payroll, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id, s.penalty_type,
       COALESCE(s.penalty_days::NUMERIC, 0), s.reason, s.penalty_date::DATE,
       COALESCE(s.applied_to_payroll, '0') = '1',
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :backend:test --tests '*EtlLoadFixtureTest*'`

Expected: PASS, both test methods. The `anUnmappedPermissionFlagAbortsTheLoad`
test must still pass — the new fixture rows must not change its behavior.

- [ ] **Step 5: Verify no entity still drops `created_at`**

Run this audit; it must print only `CARRIES` lines for the 15 entities:

```bash
python3 - <<'PY'
import re
src = open('scripts/etl/load_postgres.py', encoding='utf-8').read()
targets = {'companies','branches','job_titles','shifts','exception_types','departments',
           'employees','attendance','request_types','requests','company_official_holidays',
           'salary_contracts','payroll_batches','advances','penalties'}
for m in re.finditer(r'INSERT INTO (\w+) \(([^)]*)\)', src):
    if m.group(1) in targets:
        print(('CARRIES ' if 'created_at' in m.group(2) else 'DROPS   ') + m.group(1))
PY
```

Expected: 15 `CARRIES` lines, zero `DROPS`.

- [ ] **Step 6: Run the full ETL self-test suite**

```bash
python3 scripts/etl/export_legacy.py --self-test
python3 scripts/etl/load_postgres.py --self-test
python3 scripts/etl/export_target_postgres.py --self-test
```

Expected: all three exit 0.

- [ ] **Step 7: Commit**

```bash
git add scripts/etl/load_postgres.py backend/src/test/java/com/workin/backend/migration/EtlLoadFixtureTest.java
git commit -m "fix(etl): carry legacy created_at for the payroll group"
```

---

## Handoff

Pushing and opening the PR are human steps — `scripts/git_guard.py` blocks
`git push` for any agent.

Once this merges, the four-table slice in
`docs/superpowers/specs/2026-08-11-etl-payroll-history-design.md` rebases on it
and decision D-b becomes real: `payslips.created_at` derived from
`payroll_batches.created_at` now yields the legacy batch instant rather than
the cutover clock.
