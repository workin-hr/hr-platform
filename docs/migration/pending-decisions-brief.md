# Pending Decisions Brief (2026-08-07)

## Why This Exists

As of 2026-08-07 the backend implementation frontier has caught up with
the decision frontier: every module whose requirements are fully
settled is built, tested, and merged (see
`docs/migration/consolidated-task-matrix.md`), and both self-closable
Discovery gaps (PMR-01 dashboard coverage; the `salary_calculator`
question) are resolved. **Everything still open in the
Migration-Readiness Gate now needs an input only a human can supply.**

This brief converts those blockers into answerable questions. It does
not decide anything — per `CLAUDE.md`, decisions are the repository
owner's. Each item below states the **confirmed facts**, the
**options with their consequences**, and a **recommendation** only
where repository evidence supports one (otherwise it says what
evidence is missing). This separation is deliberate.

Grouped by what supplies the answer.

## A. Product / Business Decisions (owner call)

### A1 — `MANAGER` role scope (blocks the most)

- **Blocks**: F-16, F-25, the `MANAGER`-role cutover, and the
  attendance/requests manager-visibility rows (`hr-legacy#17`/`#18`).
  Highest-leverage decision in this brief.
- **Confirmed facts**: Legacy's dashboard and API give `MANAGER`
  **company-wide** read/approve access, contradicting the code's own
  doc-comments that claim branch/department scoping
  (`business-rule-extraction.md`, `hr-legacy#17`/`#18`). The
  `penalties` module already implements *real* branch-scoping
  (`sql_manager_same_branch_scope()`), proving scoped access was
  known and intended somewhere. Production currently has **0 real
  `manager`-role employees** (`hr-legacy#26`, F-12), so no live user
  is affected either way. The new platform ships `MANAGER` with an
  **empty permission bundle** today — nothing leaks while this is open.
- **Options**: (1) **Branch/department-scoped** — build
  `membership_resource_scopes` enforcement; managers see/act only
  within assigned scope. (2) **Company-wide** — carry legacy's actual
  behavior forward; simpler, no scoping engine.
- **Recommendation**: **Scoped** (option 1). The legacy code's
  company-wide behavior contradicts its own intent, the scoped pattern
  already exists in-codebase, and with 0 live managers this can be
  decided deliberately without migration pressure. Confidence:
  medium-high.

### A2 — Employee-deletion financial-history retention (`hr-legacy#20`)

- **Blocks**: the employee-deletion surface (deliberately not built
  yet; the lifecycle slice ships activate/deactivate only).
- **Confirmed facts**: Legacy `DELETE` of an employee cascade-deletes
  payroll/financial history, working *around* the schema's own
  `RESTRICT` constraint (`hr-legacy#20`). The new schema currently has
  no hard-delete path.
- **Options**: (1) **Retain / block** — employees with payroll or
  financial records cannot be hard-deleted (real `RESTRICT`, or
  soft-delete only). (2) **Cascade** — match legacy, delete history
  with the employee.
- **Recommendation**: **Retain** (option 1), but confirm against any
  applicable statutory retention requirement — this may be a
  **legal/compliance** input, not purely a product preference.
  Confidence: medium (direction is clear; the compliance dimension may
  override).

### A3 — Bulk attendance delete safety (`hr-legacy#25`)

- **Blocks**: the bulk attendance `delete_range` endpoint (not built;
  the attendance slice ships single-row CRUD only).
- **Confirmed facts**: Legacy `delete_range.php` deletes every
  attendance row for a whole company in a date range in one statement
  — no dry-run, no confirm, no audit, count returned only *after*
  deletion. Attendance directly drives payroll absence math.
- **Options**: (1) **Add safety** — dry-run/preview + explicit
  confirm + audit-log entry before the destructive delete. (2)
  **Like-for-like** — port the blast radius as-is.
- **Recommendation**: **Add safety** (option 1). Pure improvement,
  no downside, given the payroll blast radius. Confidence: high.

### A4 — `salary_contracts.housing_allowance` disposition (`hr-legacy#14`)

- **Blocks**: nothing hard — the salary-contract surface already
  shipped with `housingAllowance` **settable** (payroll group), as a
  provisional resolution.
- **Confirmed facts**: Legacy hardcodes `housing_allowance = 0` on
  every write path, yet the column and the payslip field exist —
  ambiguous between "vestigial dead field" and "designed feature never
  wired up."
- **Options**: (1) **Real field** — keep it settable (current
  behavior). (2) **Dead field** — revert to write-locked 0.
- **Recommendation**: **Real field** (option 1, already implemented).
  Harmless if unused; matches the enhancement direction. Confidence:
  medium. A one-line confirmation closes the row.

### A5 — QR check-in live caller (`hr-legacy#16` / F-04)

- **Blocks**: whether `#16` (QR check-in skips the 2-hour anti-fraud
  gap) is a real exploit to fix, and the attendance self-check-in
  slice's QR path.
- **Confirmed facts**: Legacy `check_in_qr.php` omits the 2-hour
  minimum-gap guard that GPS/manual check-in enforce. Real-world
  impact depends entirely on whether a live client (kiosk/tablet/
  dashboard) actually calls it — unknown from source. The new
  platform ships **no QR surface yet**, so nothing needs retrofitting
  until this is answered.
- **This is a factual question, not a preference** — cannot
  recommend. Needs product/ops confirmation of whether any deployed
  client calls `check_in_qr`. If yes → promote to P1 and add the gap
  check when the QR slice is built; if no → the finding is moot.

## B. Needs the Flutter Client Source (client-side engineer)

- **PMR-02** (per-endpoint Flutter contract confirmation), **F-02**
  (secure token storage + refresh), **F-07** (desktop version-gate),
  **F-08** (FCM). The `flutter-integration/workin_{mobile,desktop}`
  submodules are **empty in the current environment** (SSH URLs to a
  personal account, not clonable here), so none of these are
  actionable until the client repos are wired up and a client-side
  engineer picks them up. The server halves of F-02/F-07/F-08 are
  already built or specified.

## C. Needs Physical Hardware (manual operator)

- **PMR-04** — attendance device/hardware Discovery. Requires access
  to the real fingerprint/face devices and their protocols; no source
  evidence substitutes.

## D. Needs the Cutover Window / Production Access (manual operator)

- **F-15** legacy `hr_permissions` → override-rows conversion run;
  the **EAV → typed `company_settings`** conversion; **PMR-03** fresh
  production-snapshot re-verification; the **payroll golden-dataset**
  capture. All are timed to cutover and need a production snapshot —
  the mechanisms (`scripts/migration_diff.py`, the typed schemas) are
  already built and self-tested.

## E. Parked Factual Unknowns (from PMR-01)

- `shifts.days_off` source of truth — no dashboard UI sets this
  payroll-relevant field; where it is maintained in production is
  unconfirmed.
- `DASHBOARD_LOGIN_SHOW_TYPE_TABS` — gates whether company/HR
  dashboard login is reachable; its value lives in the git-ignored
  `constants.php`, unread. Both need someone with the deployed config
  or production DB, not inference.

## What Unblocks Fastest

Answering **A1 (manager scope)** unblocks the largest remaining
engineering wave (F-16/F-25 + manager cutover). **A3/A4** are
near-free confirmations. **A2** may need a compliance check. **A5**
needs an ops fact. Once any A-item is answered, the corresponding
slice can start immediately on the established spec → plan →
implement rhythm.
