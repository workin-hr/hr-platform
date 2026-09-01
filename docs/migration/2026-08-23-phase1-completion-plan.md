# Phase 1 Completion Plan v2

## Status and authority

Planning and research only. This document implements nothing and authorizes
nothing; it is written under this repository's [`CLAUDE.md`](../../CLAUDE.md)
planning boundary and indexes `decision-log.md`, `ADR-0011` and the wave
discoveries rather than replacing them.

It **supersedes [`2026-08-17-phase1-punch-list.md`](2026-08-17-phase1-punch-list.md)
as the operational Phase-1 tracker.** That document's history of items 1–11 and
Waves 12.1–12.4 remains accurate and is not restated here; its Item-12 wave
table and its "Next, in order" section are stale and are corrected below. No
historical decision is rewritten.

**Owner dispositions O-1 … O-7 were accepted on 2026-08-23** and are applied
throughout. §8 records each one and what it changed; nothing in this document
is left as an open question that a disposition settled.

### Repository state measured for this document

| Source | Commit |
|---|---|
| `hr-platform` | `85bb216e95f80fc5d3b4488db98da31e23130f6e` |
| `hr-legacy` | `d113204c8a2cf83b997c5e65c6c86e4f59b3f8f6` |

Every count below was recomputed from those two trees, not copied from an
existing document. Where a recomputed number disagrees with a published one, §6
records the disagreement and its disposition rather than silently adopting
either.

**Out of scope, explicitly.** No PostgreSQL migration, no ETL execution, no
target-schema redesign, no data cleanup — Phase 2, frozen under D-040. No
migration of the server-rendered PHP dashboard (O-5, §4.9). No first-time FCM
push delivery (O-1, §4.8).

---

## 1. Item 12 — reconciled status and the remaining order

### 1.1 Accepted history (not restated, not changed)

| Wave | Content | State |
|---|---|---|
| 12.1 | `attendance_exception_types` + P-2/P-3/P-6..P-9 | complete |
| 12.2 | tenancy policies P-1a/P-1b/P-1c + P-4 (no modules) | complete |
| 12.3a | `branches` | complete |
| 12.3b | `departments` (+ `department_branches`) | complete |
| 12.3c | `job_titles` | complete |
| 12.4 | `employees` + `hr_employees` | complete |
| 12.5 | `shifts`, `request_types`, `company_official_holidays` | complete |
| 12.6.1a | `attendance` one/delete/delete_range/create/update | complete |
| 12.6.1b | `attendance/import_excel` | complete |

Wave 12.6 legacy-route inventory stood at **13 of 18** when this section was
written. **As of 2026-08-27 it is 16 of 18**: Wave 12.7 landed and 12.6.4b
delivered its three. The two remaining are Wave 12.6.6's `overall_report` and
`export`. **Both are to be delivered** — the owner's C9 disposition, recorded
2026-08-28 as **D-120** and **O-8** (§8) — see §1.2 and C9 (§6).

### 1.2 Wave 12.6's remaining twelve endpoints

| Slice | Endpoints | Count | State |
|---|---|---|---|
| 12.6.2 | `schedules/assign_employee_schedule` | 1 | complete |
| 12.6.3 | `attendance/check_in`, `check_in_qr`, `check_out` | 3 | complete |
| 12.6.4a | `attendance/analyze_excel` | 1 | complete |
| 12.6.5 | `schedules/employee_monthly_schedule`, `generate_employee_schedule` | 2 | complete |
| 12.6.4b | `attendance/list`, `stats`, `employee_monthly_attendance` | 3 | complete — delivered 2026-08-27 after Wave 12.7 (§1.5) |
| 12.6.6 | `attendance/overall_report`, `export` | 2 | **complete** — both delivered 2026-08-28 (12.6.6a–d). `export` returns a workbook download, so Java owes the same reader-observable workbook and headers — **not** the same archive bytes, per D-085 (§5 G3); `overall_report` is a JSON endpoint misclassified as binary — C9, §6 |

**18 of 18 delivered.** Wave 12.6.6 closed on 2026-08-28, with nothing in this
wave excluded — C9, §6, D-120.

### 1.5 Ordering correction — three more endpoints depend on Wave 12.7

**Status 2026-08-27: discharged.** Wave 12.7 landed and 12.6.4b delivered its
three endpoints. The section is kept because it records why the order was set,
and because its closure trace is still the evidence for `attendance/list`,
`stats` and `employee_monthly_attendance` reading `requests`. Of the five
endpoints it names, three are delivered and Wave 12.6.6's two remain — §1.2.

**Recorded 2026-08-23, from a closure trace taken before implementing Wave
12.6.4.** This is a dependency correction, not a scope reduction: all five
endpoints remain Phase-1 scope and Item-12 scope, and none is deferred out of
the phase.

The Wave 12.6 discovery recorded the `requests` dependency for
`overall_report.php` and `export.php` only. Tracing the rest of 12.6.4's
closure found the same table reached by three more endpoints, through a
different path:

```text
attendance/list.php:276                       ─┐
attendance/employee_monthly_attendance.php:104 ├─→ attendance_row_worked_minutes()
attendance_calendar_helper.php:739             ┘   (attendance_employee_period_stats,
                                                    i.e. stats.php's per-employee branch)
                                                          │
attendance_calendar_helper.php:161 ───────────────────────┴─→ attendance_approved_timed_request_for_day()
                                                                    │
                                                                    └─→ SELECT ... FROM requests
```

It is the **first statement** of `attendance_row_worked_minutes()` and is
unconditional. `attendance_build_employee_range_calendar()`, which
`list.php?fill_days=1` uses, reaches it three more times (`:310`, `:342`,
`:391`) and additionally reaches `weekly_rest_credit_helper`.

**Owner disposition, accepted 2026-08-23.** Reorder rather than pull the
dependency forward. No bounded pre-12.7 `requests` reader is created, and
`weekly_rest_credit_helper` is not pulled forward to preserve the old 12.6.4
grouping. This applies the same ownership principle that already placed
`overall_report`/`export` after Wave 12.7.

The operational order is therefore:

```text
12.6.3  check_in / check_in_qr / check_out                     [complete]
12.6.4a analyze_excel                                          [complete]
12.6.5  employee_monthly_schedule / generate_employee_schedule [complete]
   |
   v
12.7    requests + leave_balances
   |
   v
12.6.4b list / stats / employee_monthly_attendance
12.6.6  overall_report / export
```

That was expected to move Wave 12.6 from 13/18 to 18/18 once Wave 12.7 supplied
the request boundary. It moved it to **16/18**: 12.6.4b's three landed and no new
dependency appeared in their closures, while 12.6.6's two remain open on their
own work rather than on the `requests` boundary.

### 1.3 The remaining order, and why each step sits where it does

Every ordering constraint below is quoted from an already-accepted document.
None is introduced here for tidiness.

```text
12.6.2  schedules/assign_employee_schedule
   |
   |  gates: D-091 evidence, narrow payroll extraction, D-083, D-092/D-093
   |  — all closed; D-083 by D-099
   v
12.6.3  check_in / check_in_qr / check_out
12.6.4a analyze_excel
12.6.5  employee_monthly_schedule / generate_employee_schedule
   |
   v
12.7    requests + leave_balances
   |
   |  gate: broad J.2 resolution (12.6.6 only) — closed 2026-08-27, §4.5
   v
12.6.4b list / stats / employee_monthly_attendance   (§1.5)
12.6.6  overall_report / export
   |
   v
12.8    salary_contracts -> advances -> penalties
   |
   v
12.9    payroll_batches -> payslips
   |
   v
12.10   companies/profile column completion + the three company/* endpoints
   |
   v
12.R    D-074 wire-contract retrofit  (O-6 — closure boundary, §4.1)
```

**Everything above the Wave 12.7 line is delivered.** 12.6.2, 12.6.3, 12.6.4a
and 12.6.5 are complete, and their gates are all closed: D-091's evidence and
the narrow `payroll_is_weekly_rest_day` extraction landed alongside D-099,
D-083 was closed by D-099 itself, and D-092/D-093 are implemented and pinned in
`LegacyCheckInEndToEndTest`.

**What remains in Wave 12.6 is Wave 12.6.6's two endpoints.** §1.5's five
request-dependent endpoints are three delivered plus these two; the `requests`
boundary they waited on is closed, and both are to be delivered under D-120.

**12.7 precedes 12.6.6.** This is the one place where the wave numbers do not
run in order, and it is forced by evidence, not preference. Wave 12.6 discovery
§M: `requests` is "Reached by `overall_report` and `export` through the payroll
helpers — a further reason those two are unscheduled." §J.2 says the same from
the other side: the six DB-backed payroll functions those two endpoints need
"additionally read Wave 12.7's `requests` table". So 12.6.6 cannot precede the
wave that owns the table it reads.

**12.6.6 also waited for broad J.2 — no longer.** §4.5 is now closed by
evidence (2026-08-27): every payroll function that boundary named is already in
`main`. The original constraint is preserved below as the reason the ordering was
set, not as a live blocker. §J.2 was explicitly *partially* resolved:
`payroll_is_weekly_rest_day` may be extracted, and "the broader subset — the six
DB-backed functions `overall_report` and `export` need, two of which reach
`company_settings` and the holiday helper D-090 excluded ... **remains
blocked**. ... the final J.2 decision is deliberately **not** recorded yet."

**12.8 before 12.9.** Item-12 specification §8: "Payslips remain last in the
payroll cluster because they aggregate salary, advances, penalties, and
attendance-derived days." Within 12.8, `salary_contracts` precedes `advances`
and `penalties` for the same aggregation reason.

**12.10 last among the module waves.** Item-12 specification §8: "The last
hub-table gap (F-5). Isolated at the end so it never blocks the mechanical
waves." Its scope is now larger — see §1.4.

**12.R closes Item 12, and the order is fixed.** *(Overtaken by events —
§1.6 records the deviation. Wave 12.R merged with PR #120 while the three owed
endpoints were still believed excluded, so it landed before the remaining
Item-12 work. O-6's two invariants still hold; what survives is that Item 13
does not begin until Item 12 is complete.)* The approved engineering order
is **remaining Item 12 implementation → Wave 12.R → Item 13**, with no
conditional branch and no parallel path. It touches only already-merged modules,
so it has no data dependency on any wave; it is placed at the end of Item 12 so
that Item 12's entire delivered surface is D-074 compliant before Item 13
begins, so the correction cannot be quietly absorbed into an unrelated module
wave, and so no endpoint is ever owned simultaneously by the retrofit wave and
an Item-13 implementation wave. See §4.1 and §3.3.

### 1.4 Wave 12.10 is renamed and now carries the `company/*` endpoints (O-2)

The Item-12 specification §1.1 lists `company` as shared-table row 1 with 3
endpoints, so they are Item-12 scope — but the D-073 sequence described 12.10 as
"`companies` / `profile` column completion", a column-mapping task, and no wave
delivered the endpoints. They were orphaned.

**Disposition O-2, accepted 2026-08-23.** Wave 12.10 is renamed and explicitly
covers both:

> **Wave 12.10 — `companies` hub completion: `companies`/profile column
> completion **and** the three legacy `company/*` API endpoints.**

| In Wave 12.10 | What it is |
|---|---|
| `companies`/profile column completion | F-5. The `LegacyCompany` mapping is extended beyond `id`/`status` to whatever the hub table's consumers require. No endpoints. |
| `company/update.php` | Company-admin write against the hub table. |
| `company/upload_commercial_reg.php` | File upload. |
| `company/upload_logo.php` | File upload. |

These are **not** the `profile` module. That is nine Item-13 endpoints — see
§2.4, which exists because the two names collide.

### 1.6 The three owed endpoints — who owns each, and where they sit in the order

**Added 2026-08-28, applying O-8/D-120.** The disposition selects delivery for
all three. Delivery needs an owning slice and a place in the order, and two gaps
in this document had to be closed for that to be true.

| Endpoint | Owning slice | Status |
|---|---|---|
| `attendance/overall_report.php` | **Wave 12.6.6c** | **delivered 2026-08-28** |
| `attendance/export.php` | **Wave 12.6.6d** | **delivered 2026-08-28** |
| `payslips/export.php` | **Wave 12.9** | **delivered 2026-08-28**, closing that wave at 16 of 16 |

**Wave 12.6.6 delivered in four slices**, because the two endpoints share one
builder (`overall_attendance_report_build()`, whose docblock says so and which
`data_export_attendance_csv()` calls as its first statement):

| Slice | Content |
|---|---|
| 12.6.6a | `LegacyPayrollPeriod` for the `as_of`/`in_progress` clamp, and three `LegacyPayrollAttendanceFigures` helpers widened for reuse. No behaviour change. |
| 12.6.6b | `LegacyAttendanceReportDetails` — the five helpers that had no Java port. |
| 12.6.6c | `LegacyOverallReportStore` + `LegacyOverallReportService` + the `overall_report.php` handler. |
| 12.6.6d | `attendance/export.php` — the workbook response, the per-sheet config gate, and the `type=fingerprints\|details\|days` sheet with its own employee query. |

**`payslips/export.php` had no owner, was given one, and shipped.** §1.2's 12.6.6
covers only the two attendance endpoints, and Wave 12.9 had been recorded complete
on a `payslips` count of 5 of 6 that treated the sixth as excluded. With C9
retracting that exclusion the endpoint returned to the wave that owns its module
rather than being bolted onto an attendance slice — the same ownership principle
that kept the payroll functions out of Wave 12.6 (§4.5) — and was **delivered
2026-08-28**. **Wave 12.9 is 16 of 16**: `payroll_batches` 10/10 plus `payslips`
6/6.

**The O-6 order was overtaken by events, and the deviation is recorded here.**
O-6 fixed the engineering order as **remaining Item 12 → Wave 12.R → Item 13**
(§1.3). Wave 12.R merged with PR #120 while these three endpoints were still
believed excluded, so 12.R in fact landed *before* the remaining Item-12 work,
and that half of the order can no longer be executed as written.

Recorded as a factual deviation, not a re-decision, because **both invariants
O-6 existed to protect still hold**:

- *Item 12's delivered surface is D-074 compliant before Item 13 begins.* The
  three are built on their literal `/apis/**` URLs with the correct response
  contract from the first commit, so they never enter the retrofit's problem
  class and there is nothing for a later 12.R to correct.
- *No endpoint is owned by both 12.R and an Item-13 wave.* None of the three was
  ever among 12.R's 22 (D-110's own route note says so).

**What survives from O-6 is its real constraint: Item 13 does not begin until
Item 12 is complete**, which now means until Waves 12.6.6 and 12.9 close. The
operational order for what remains is therefore:

**Item 12 is delivered.** All three of C9's endpoints shipped on 2026-08-28, so
`ITEM12_REMAINING` is empty and no endpoint stands between the repository and
G2's *numerator*. What remains before G2 can be declared closed is Item 13's
outstanding endpoints — **counted once, in §3.2's `ITEM13_REMAINING`**, and not
repeated here, because a second copy of that number drifts every time a wave
lands. The other gates — G3's per-endpoint contract evidence, G6's differential
floor, and G7's full suite — read on their own terms.

The two are independent — different modules, different helper closures — so
nothing forces one before the other.

---

## 2. Item 13 — recomputed inventory

### 2.1 Why the old phrase no longer describes the boundary (C5)

ADR-0011 recorded "Nineteen of 38 legacy API modules have no Java counterpart at
all", and the punch list turned that into "Implement the 19 missing legacy
modules". That was a true statement about the repository on 2026-08-16. It is
**not** a description of Item 13's delivery boundary today, and the two must be
kept apart:

- **D-4** removed `company_settings` from Item 12 and placed it in Item 13
  "together with its two dependency tables", i.e. with `setting_definitions` and
  `setting_allowed_values`. `company_settings` *has* a Java counterpart
  (`CompanySettings`, schema-incompatible), so it was never one of ADR-0011's
  nineteen — it is an **addition** to Item 13.
- **Wave 12.4** delivered `hr_employees`, which has no entity of its own and was
  most likely inside ADR-0011's nineteen — a **removal**.
- **O-3** excludes `time` as unreachable dead surface (§2.3) — a second
  **removal**.

ADR-0011's nineteen is preserved as history. Item 13's current membership is
recomputed from the trees and is **18 modules, 71 endpoint files**. The two
numbers must never be substituted for one another.

### 2.2 The inventory

Endpoint counts from `hr-legacy@d113204`; "Java counterpart" measured against
`hr-platform@85bb216`.

| Legacy module | Endpoints | Java counterpart? | Bucket | Dependencies | Client consumer(s) | Security findings | Item-13 wave |
|---|---|---|---|---|---|---|---|
| `configs` | 1 | none (the *table* is already read by `LegacyClock`) | Item 13 | none | desktop (`hr-platform#21`); also the runtime-timezone flag behind D-083 | unauthenticated | **13.0 — first, §2.3** |
| `auth` | 14 | **partial** — `login_employee` only, at the drifted `/api/legacy/auth/login_employee` | Item 13 | `employees`, `companies`, OTP helper, refresh-token store | dashboard, desktop, mobile | `forgot_password`, `resend_otp`, `register_company` are unauthenticated OTP issuers; `complete_company_registration` is unauthenticated and accepts a caller-supplied id. The OTP rate limiter's per-IP cap degrades to a **platform-wide** 20-per-hour cap against the frozen schema (R-014) | **complete** — four OTP routes in 13.1a, nine account-lifecycle routes in 13.1b |
| `profile` | 9 | **7 delivered in Wave 13.2**; the two phone-change routes move to 13.1 | Item 13 | `employees`, `companies`, push tokens, and (for the two deferred routes) the OTP helper | mobile (primary), desktop (partial) | `delete_account` is destructive and self-service and has no rollback path; `register_push_token` is the client half of `hr-platform#22` — now a later workstream, not a gate (O-1) — and **cannot succeed against the frozen schema** (R-013) | 13.2 (7) + 13.1a (2) |
| `notifications` | 6 | **delivered in Wave 13.2** on the seam `LegacyNotifications` + `LegacyPushDelivery` already provided (D-082) | Item 13 | `employees` | mobile, desktop (likely) | five of the six take a bare `requireAuth()` with no active-company gate; `delete.php?id=<non-numeric>` empties the caller's own inbox (D-133) | 13.2 |
| `company_settings` | 6 | entity exists but is schema-incompatible (EAV vs five typed columns) | Item 13 (D-4) | `setting_definitions`, `setting_allowed_values` | dashboard, desktop | gated by `can_company_settings` in the 17-flag matrix | 13.3 |
| `setting_definitions` | 1 | none | Item 13 (D-4) | none | platform administration | `COMPANY_ADMIN`/`HR` only | 13.3 |
| `setting_allowed_values` | 1 | none | Item 13 (D-4) | none | shared read, all clients | unauthenticated | 13.3 |
| `workforce_planning` | 7 | none | Item 13 | `employees`, `departments`, `job_titles` | dashboard page directory confirmed; desktop (headcount targets) | **cross-tenant disclosure in this API module** (D-131, `hr-legacy#33`): two of the three write paths (`save_target.php` and `update.php`; `create.php` does validate all three) do not validate the foreign ids they store, and the read joins carry no tenant predicate, so a user of company A can store company B's `branch_id`/`department_id`/`job_title_id` and read the names back. The same unscoped department join is in `dashboard/stats.php`, delivered in Wave 13.5, which also returns that department's headcount. A separate edit-hijack/bare-delete finding is on `dashboard/pages/workforce_planning/page.php` — that one is the PHP dashboard, not this module (§4.9) | 13.4 |
| `assets` | 5 | none | Item 13 | `employees` | desktop consumes all five, mobile consumes `list` — **C8 discharged 2026-08-29**, call sites traced in `three-frontend-api-usage-matrix.md` | `hr_permissions` **not** enforced (recorded inconsistency) | 13.4 |
| `administrative_decisions` | 5 | none | Item 13 | `employees` | desktop consumes four (`list`, `create`, `update`, `delete`), mobile consumes `list`; **`one` is declared by neither** — **C8 discharged 2026-08-29**, call sites traced in `three-frontend-api-usage-matrix.md` | `hr_permissions` enforced on all 5 | 13.4 |
| `employee_docs` | 4 | none | Item 13 | `employees`, upload slots | mobile (confirmed), dashboard/desktop likely | file upload surface | 13.4 |
| `complaints` | 4 | none | Item 13 | `employees` | mobile (submit), dashboard (handling) | partly undocumented — C3 | 13.4 |
| `company_join_requests` | 3 | none | Item 13 | `employees`, `companies` | dashboard | `accept`/`reject` confirmed correctly company-scoped | 13.4 |
| `app_content` | 1 | none | Item 13 | none | all clients | unauthenticated | 13.5 |
| `banners` | 1 | none | Item 13 | none | mobile | any authenticated session | 13.5 |
| `faqs` | 1 | none | Item 13 | none | mobile | any authenticated session | 13.5 |
| `phone_countries` | 1 | none | Item 13 | none | all clients | unauthenticated | 13.5 |
| `dashboard` | 1 | none | Item 13 | `employees`, `attendance`, `workforce_planning`, `departments` | dashboard/desktop summary widget | `COMPANY_ADMIN`/`HR` only. **Traced in full** — `stats.php` is the second D-131 surface: its `workforce_planning`→`departments` join carries no tenant predicate and returns a foreign department's name and active headcount | 13.5 |
| **Total** | **71** | | **18 modules** | | | | |

**71 total scope, 70 currently unimplemented.** `auth/login_employee` is already
implemented and is retrofitted by Wave 12.R (§3.3), so it appears in Item 13's
module scope but in the retrofit bucket of the ledger. It is never owned by two
waves at once, because 12.R always completes before Wave 13.1 begins (§1.3).

### 2.3 `apis/api/time/now.php` is excluded as dead surface (O-3, C4)

**Disposition O-3, accepted 2026-08-23.** `time/now.php` is removed from the
live Phase-1 endpoint obligation and recorded as an explicit exclusion.

Evidence, all three parts verified against `hr-legacy@d113204`:

1. the directory and file exist — `apis/api/time/now.php`;
2. `time` is **not** in `ApiModule::allowedList()`
   (`apis/config/http_api.php:70-110`) — verified by set-differencing the 38
   directory names against the 38 constant names;
3. `apis/api/index.php:34-42` resolves the module against
   `app_allowed_modules()` and answers `module_not_found` (**404**) before it
   ever looks for an action file, so the router cannot expose it.

Phase 1 therefore has nothing to reproduce: a request to `/api/time/now` returns
404 in legacy and must return 404 after cutover, which requires no endpoint. It
is counted once, in `EXPLICITLY_EXCLUDED_WITH_DECISION`, and excluded from the
live total.

> **Amended 2026-08-31 — the evidence above stands, "dead surface" does not,
> and the 404 requirement is currently unmet.**
>
> Points 1–3 were re-verified and remain correct: `time` is not allow-listed and
> PHP answers `module_not_found` before looking for an action file. But
> *unreachable* here means unreachable **server-side**. It does not mean no
> client calls it, which is how "dead surface" has been read since. The mobile
> client calls it from the **home screen** — `home_provider.dart:79` →
> `GetServerTimeUsecase` → `repository.getServerTime()` →
> `remote_data_source.dart:179` → `ApiConstants.getServerTimeEndpoint`
> (`'time/now'`). The chain is fully wired; the client simply absorbs PHP's 404
> today.
>
> That makes this clause load-bearing rather than academic: *"must return 404
> after cutover"*. Measured against the parity harness on 2026-08-31, Java does
> not meet it — **401** when unauthenticated (the security chain rejects before
> routing, where PHP's router rejects before authenticating), and a 404 in
> **Spring's** envelope rather than PHP's when authenticated. Both are
> properties of every unmatched `/apis/api/**` path, not of this endpoint. See
> `docs/migration/2026-08-31-php-java-parity-harness.md`, "`time/now`, measured
> precisely".
>
> The exclusion itself is unaffected — no endpoint needs building. What needs
> building is the router's unmatched-path behaviour.

The mirror-image anomaly is recorded with it: **`reports` is in
`allowedList()` and has no directory at all.** It contributes zero endpoints, so
it changes no count — but it is an advertised module on which every action 404s,
and no document previously recorded either half of the mismatch.

### 2.4 `configs` is not an ordinary reference module

`configs` is one endpoint, and it should be built first in Item 13 rather than
last with the other single-endpoint reference modules. Three accepted documents
converge on it:

- it serves the desktop forced-update/maintenance-mode version gate
  (`hr-platform#21`), which is how a client release is forced;
- ADR-0011's own Open Questions already ask "Whether `configs` ... is built
  early enough to communicate cutover, given the circularity noted in `#72`";
- `configs.is_daylight_saving` is the flag `LegacyClock` already reads, so the
  table is live in Phase 1 even though the endpoint is not ported.

The circularity is real and is the reason for the recommendation: the Flutter
refresh-token gap (`hr-platform#18`) can only be closed by shipping new client
builds, and new client builds are forced through the mechanism `configs` serves.

### 2.5 `profile` is not Wave 12.10

Stated explicitly because the names collide. The `profile` **module** is nine
Item-13 endpoints (`company.php`, `employee.php`, `change_password.php`,
`logout.php`, `delete_account.php`, `delete_account_preview.php`,
`request_phone_change.php`, `confirm_phone_change.php`,
`register_push_token.php`). Wave **12.10** is the `companies` hub table's column
completion plus the three `company/*` endpoints (§1.4). They share a word and
nothing else.

---

## 3. The endpoint ledger

### 3.1 Deriving the live total (C2)

The published headline "199 endpoint files, 38 modules" reconciles at the
current `hr-legacy` commit — but not for the reason the source documents give.
Recomputed:

| Measure | `hr-legacy@83c326e4` (the pinned inventory commit) | `hr-legacy@d113204` (current) |
|---|---|---|
| `.php` files under `apis/api/` | 199 | 200 |
| ...of which the router `apis/api/index.php` | 1 | 1 |
| **module endpoint files** | **198** | **199** |
| module directories | 38 | 38 |

**C2, confirmed:** the published 199 was a count that **included
`apis/api/index.php`**, over a tree holding 198 real endpoints. Since then
`apis/api/complaints/delete.php` was added and the real count rose to 199. The
headline is correct today by coincidence, not by derivation. Everything below
counts module endpoint files and excludes the router.

Applying **O-3**:

```text
199   module endpoint files (router excluded)
 -1   apis/api/time/now.php — EXPLICITLY_EXCLUDED_WITH_DECISION (§2.3)
----
198   LIVE Phase-1 API endpoint obligation
```

Module accounting follows the same subtraction: **38 directories = 37 live
endpoint-bearing modules + 1 excluded**, of which 19 live modules are Item 12
and 18 are Item 13. `reports` is an allow-list entry with no directory and no
endpoint files, so it carries zero delivery obligation and adds nothing to
either count.

### 3.2 The ledger

Every one of the 198 live endpoints is in exactly one bucket.

**Restated 2026-08-27**, after Wave 12 merged to `main` (`4caff98`, PR #120) and
after C9 (§6) corrected `attendance/overall_report.php`'s classification. The
live total and the one-row exclusion list are **unchanged** from the original
table; only the distribution across buckets has moved.

**What `FINAL_COMPATIBLE` counts, and what it does not.** It counts endpoints
whose Java implementation faithfully reproduces the frozen PHP — nothing more.
It is **not** a statement that a module is ready to cut over. Several delivered
endpoints reproduce legacy defects that remain explicit cutover blockers in
`docs/migration/consolidated-task-matrix.md`: `#9` (the onboarding endpoint's
guessable `company_id`, R-016), `#10` (no rate limiting on OTP verification,
R-018), `#8` (the `hr_permissions` gap, R-010), and `F-27` (password minimums).
A parity port neither satisfies nor waives any of them — closing them requires
the upstream change and its port, because a Java-only fix would make the two
systems answer differently for the same request, which is the divergence Phase 1
exists to prevent.

Two independent-review findings on Wave 13.1 read "complete" as "cutover-ready",
which is why this paragraph is here rather than implied.

| Status | Endpoints | What it covers |
|---|---|---|
| `FINAL_COMPATIBLE` | **198** | Every delivered route, on its literal `/apis/api/**` URL. Exactly the set `LegacyPhpRouteInventoryTest` asserts bidirectionally (`hasSize(198)`). Waves 12.4 through 12.10, the Wave 12.R retrofit, Wave 12.6.6's two attendance endpoints, Wave 12.9's `payslips/export.php`, **Item 13.0's `configs/get.php`** — the first endpoint delivered outside Item 12 — **Item 13.5's five reference endpoints**, **Wave 13.3's eight settings endpoints**, **Wave 13.4a's ten records endpoints**, **Wave 13.4b's seven workforce-planning endpoints**, **Wave 13.4c's eleven people endpoints, which complete Item 13.4**, **Wave 13.2's six `notifications` endpoints plus seven of the nine `profile` endpoints**, **Wave 13.1a's four public OTP endpoints plus the two `profile` phone-change routes**, and **Wave 13.1b's nine account-lifecycle `auth` endpoints, which complete Item 13**. |
| `IMPLEMENTED_BUT_REQUIRES_D074_RETROFIT` | **0** | Closed by Wave 12.R (D-107/D-108/D-110/D-111). No `/api/legacy/**` business route remains mapped. |
| `ITEM12_REMAINING` | **0** | **Empty as of 2026-08-28.** All three of C9's endpoints were delivered rather than excluded, exactly as O-8/D-120 dispositioned. |
| `ITEM13_REMAINING` | **0** | Item 13 is complete. §2.2's 71 endpoints are all delivered: `auth/login_employee` (Wave 12.R), `configs/get.php` (Item 13.0), Wave 13.5's five, Wave 13.3's eight, Wave 13.4a's ten, Wave 13.4b's seven, Wave 13.4c's eleven, Wave 13.2's thirteen, Wave 13.1a's six and Wave 13.1b's nine (D-135). |
| **Live total** | **198** | 198 + 0 + 0 + 0 |
| `EXPLICITLY_EXCLUDED_WITH_DECISION` | **1** | `apis/api/time/now.php` (O-3, §2.3). Outside the live total. |
| **Endpoint files** | **199** | 198 live + 1 excluded |

**Why the three sit in `ITEM12_REMAINING` and not in the exclusion bucket.**
That bucket is named `EXPLICITLY_EXCLUDED_WITH_DECISION`, and the qualifier is
load-bearing: `time/now.php` is in it because **O-3 is a recorded owner
disposition that names it**. The owner disposition that names these three —
**O-8/D-120, accepted 2026-08-28** — puts them the other way: all three are
delivered. Before it existed, the owning decisions already said the opposite of
exclusion, in their own words:

- **D-101 Follow-up**: "`overall_report.php` and `export.php` remain **blocked**
  on the broader D-09x payroll boundary and are not part of this slice."
- **D-106 Follow-up**: "`payslips/export.php` (XLSX) **remains open**, to be
  picked up alongside or after Wave 12.R depending on whether binary-export
  support is prioritized before the retrofit audit."

Blocked and open are wave-scheduling states. Neither is an exclusion from the
Phase-1 obligation, and only an owner decision can make one. Recording them as
excluded would let G2 close at a reduced total without either implementing two
frozen client routes or obtaining that decision — which is the same
deferred-read-as-excluded error C9 exists to correct, repeated one bucket over.
**That decision has now been made and it is not an exclusion** (D-120): the
three move to `FINAL_COMPATIBLE` by being implemented, and the live total of 198
is reached with the exclusion list still one row long.

**The binary-response observation stands, and is not a disposition.**
`attendance/export.php` and `payslips/export.php` do terminate in helpers
declared `: never` rather than returning the `ok()` JSON envelope. That is a true
and useful statement about how much work they are and what shape the work takes —
it is **not** a decision that Phase 1 need not serve them. Legacy serves both to
real clients today.

**The two exports emit XLSX through one shared mechanism**, despite the `_csv`
names (the template downloads in §5 G3 are a different, already-delivered pair of
streamers):
`data_export_attendance_csv()` and `data_export_payslips_csv()` are row builders
that both end in `api_xlsx_export_send()` (`xlsx_writer.php:318`), which writes
`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`,
an `attachment` disposition with a sanitized `.xlsx` filename, `Content-Length`,
and the bytes. Neither endpoint produces CSV. There is one binary mechanism to
port, not two — and `api_xlsx_export_send` itself falls back to `fail()`'s JSON
envelope with a 500 when the workbook cannot be built, so even these endpoints
have a JSON error path.

Under D-120 that shape is now the **specification**, not an obstacle: Java emits
the same reader-observable workbook with the same content type, disposition,
filename and status — not the same archive bytes, which D-085 already ruled out
as a compatibility requirement (§5 G3). Binary-response support is a Phase-1
implementation obligation.

### 3.3 Three notes on bucket boundaries

**Why `auth/login_employee` is in the retrofit bucket and not in Item 13.** It
is genuinely implemented — real controller, real JWT, proven end-to-end against
MariaDB — but at `/api/legacy/auth/login_employee` rather than
`/apis/api/auth/login_employee.php`, and with the flat envelope. That is the
same drift D-074 named for Waves 12.1/12.3, so it belongs with them in Wave
12.R.

This creates no ambiguity, because the engineering order is fixed (§1.3):
**remaining Item 12 → Wave 12.R → Item 13.** Wave 12.R always completes before
Wave 13.1 begins, so by the time Item 13 starts, `auth/login_employee` is
already `FINAL_COMPATIBLE` and Wave 13.1 builds the other thirteen:

| `auth` at the start of Item 13 | Endpoints |
|---|---|
| module total | 14 |
| `auth/login_employee` — already `FINAL_COMPATIBLE` via Wave 12.R | 1 |
| **to build in Wave 13.1** | **13** |

Owning that endpoint in 12.R does **not** make `auth` an Item-12 module.
`login_employee` exists because of the pre-Item-12 authentication work (punch
list item 9); Wave 12.R merely finishes its literal PHP wire-contract retrofit.
`auth` remains an Item-13 module throughout.

Wave 12.R is not to be split into 21 + 1 unless new evidence requires it.

**`analyze_excel.php` appears three times in the corpus and must not be
conflated.** `employees/analyze_excel.php` is delivered (Wave 12.4,
`FINAL_COMPATIBLE`); `attendance/analyze_excel.php` is Wave 12.6.4a and is
now delivered too; `leave_balances/analyze_excel.php` is Wave 12.7 and is
**also delivered** — it is inside `LegacyPhpRouteInventoryTest`'s 125-route
assertion. All three are mapped; the three-way name collision is the reason to
keep them distinct, not a difference in status.

**The PHP dashboard's 92 page files are not in any bucket** and are not part of
the 198 (O-5, §4.9).

---

## 4. Cross-cutting Phase-1 items

Classification key: **[wave]** blocks a specific wave · **[cutover]** blocks
only production cutover · **[closed]** · **[later workstream]** explicitly
outside Phase 1 · **[open]** still needs evidence, owner already decided the
disposition.

### 4.1 D-074 retrospective wire-contract retrofit — **Wave 12.R** — **[cutover]** (O-6)

22 endpoints (§3.2) answer on `/api/legacy/**` with a flat envelope. D-074 makes
the literal PHP route and `{success,message,data?,meta?}` envelope authoritative
and rules the earlier surface drift rather than precedent.

**Disposition O-6, accepted 2026-08-23.** The correction is its own explicit
engineering wave and closure boundary — **Wave 12.R** — and is **not** to be
distributed through unrelated future module waves.

| Wave 12.R covers | Endpoints |
|---|---|
| `attendance_exception_types` (Wave 12.1) | 5 |
| `branches` (Wave 12.3a) | 6 |
| `departments` (Wave 12.3b) | 5 |
| `job_titles` (Wave 12.3c) | 5 |
| `auth/login_employee` (item 9) | 1 |
| **Total** | **22** |

Not a wave blocker: nothing downstream depends on those URLs. It is a hard
cutover blocker, because a client pointed at the new backend would get 404s on
every branch, department, job-title, exception-type and login call.

Scope worth stating before it is scheduled. The retrofit is not a route rename.
It is route **plus** envelope, status codes, message keys and `data`/`meta`
shape, per module, against the PHP source — closer to a re-port with the
business logic already written than to a routing change. It must be reconciled
with the accepted divergences those waves shipped (D-057, D-060's uniform 404,
D-071's four fixes), which the retrofit does **not** re-open. §4.2's D-071 probe
is attached to this wave.

Its closure condition is mechanical: `LegacyPhpRouteInventoryTest` asserts the
22 literal routes bidirectionally, and no `/api/legacy/**` business route
remains mapped.

### 4.2 D-071 `LegacyBranchService` numeric coercion follow-up — **[open, attached to Wave 12.R]**

D-071 records that `branches/create.php` and `update.php` apply no PHP
`(int)`/`(float)` cast to `radius_meters`/`latitude`/`longitude` at all: the raw
JSON value binds to a PDO parameter and MariaDB's non-strict coercion decides
what is stored. Routing them through `LegacyValues.toPhpLong` would reproduce a
cast PHP does not perform.

What is missing is a measurement, not an opinion: what MariaDB 11.8 actually
stores for a non-numeric string bound into `INT`/`DECIMAL` under the legacy
`sql_mode`, and whether Java's binding produces the same value. Small, bounded,
the same probe shape D-096 used. It belongs with Wave 12.R because both touch
the same already-merged branch module.

### 4.3 D-083 per-connection database timezone — reclassified to **[wave: 12.6.3]** (O-4)

`LegacyClock` reproduces the application-side half
(`applyRuntimeTimezoneFromConfigs()`). The other half is not reproduced:
`getDB()` runs `SET time_zone = ?` on **every** legacy connection
(`config/pdo.php:23-36`), and Java's connections do not.

**Disposition O-4, accepted 2026-08-23.** D-083 is reclassified from
cutover-only to an **implementation prerequisite for Wave 12.6.3**, where
attendance auto-close and time semantics require it.

The evidence that forced the reclassification: Wave 12.6 discovery §F.4 shows
the application clock deciding whether an attendance row is auto-closed and what
synthetic `check_out` is written, against `check_in` values the database wrote
with `NOW()`. §M already called it "materially more urgent". D-081 adds that
`employees/stats.php` and `my_team.php` evaluate `CURDATE()`/`CURRENT_DATE` in
the database.

It remains a cutover concern as well — reclassifying pulls it earlier, it does
not remove it from the release packet.

### 4.4 D-091 bounded `WEEKLY_OFF_DAYS` reader — **[wave: 12.6.3/4/5]**

Accepted and bounded: Wave 12.6 may port a read-only compatibility reader for
`company_setting_selected_values($company_id, WEEKLY_OFF_DAYS)` and nothing
else. What remains is the **evidence** — the slice table lists "D-091 evidence"
as a blocker on 12.6.3, 12.6.4 and 12.6.5, meaning the reader's behaviour must
be measured against real EAV rows before those slices proceed. Not a cutover
item; it gates three slices and nothing else.

### 4.5 Broad Wave-12.6 J.2 payroll boundary — **[closed]**

**Closed 2026-08-27 by evidence, not by decision** —
`2026-08-27-broad-j2-settlement-discovery.md`.

This section previously read: partially resolved, with the broad subset "blocked"
and the final decision "deliberately **not** recorded yet", answerable only once
Wave 12.7 landed. Wave 12.7 landed, and so did 12.8 and 12.9.

Measured at `hr-platform@e112ebc`, **all seven payroll functions §G.2 enumerated
are already in `main`**. They were never pulled forward into Wave 12.6 — they
landed in their own waves as part of the payroll engine (D-105), which is exactly
the ownership principle J.2 was protecting. The question "may Wave 12.6 pull these
forward?" is moot: there is nothing left to pull.

Two specifics worth keeping, because both are narrower than the original phrasing:

- **The `company_settings` reach is D-091's existing key.**
  `official_holidays_working_days_in_range()` reads exactly
  `CompanySettingEnum::WEEKLY_OFF_DAYS` — the same single key D-091 authorized a
  bounded reader for and D-103 fixed the case defect on. Not a new dependency, not
  a second key, not a step toward Item 13's settings endpoints.
- **The `requests` dependency closed with Wave 12.7**, and `request_types` with
  12.5. Both consuming functions are ported against them.

What remains for `overall_report.php` is **five unported helpers — 244 lines of
PHP** — plus the report builder: four in `attendance_calendar_helper.php`
(`attendance_exception_details_for_period`, `attendance_absent_details_for_period`,
`attendance_void_weekly_rest_absent_details_for_period`,
`attendance_period_work_minutes`, 221 lines) and
`official_holidays_credit_days_for_employee`
(`official_holidays_helper.php:129–151`, 23 lines), which an earlier revision
wrongly recorded as ported. A normal slice, not a boundary decision. Most of the
ported helpers it reuses are `private` to `LegacyPayrollAttendanceFigures`, so the
slice starts with an extraction pass — settlement discovery §2.1.

`attendance/export.php` is released by exactly the same evidence. §G.2 records all
six non-extracted payroll functions as reached by "`overall_report`, `export`",
and §K assigned both to slice 12.6.6 blocked on J.2; an earlier revision wrongly
said its constraint was never J.2. It reaches them the same way `overall_report`
does — through `overall_attendance_report_build()`, which
`data_export_attendance_csv()` calls as its first statement
(`data_export_helper.php:321`). Its workbook response was always additional to the
boundary rather than instead of it. `payslips/export.php` genuinely was never
J.2-constrained.

This closes the *dependency* question only. Whether the endpoints are delivered,
excluded or deferred was C9's disposition, **decided 2026-08-28 as O-8/D-120:
all three are delivered** (§8.1).

### 4.6 Flutter token refresh / `hr-platform#18` — **[cutover]**, with a dependency

D-042 keeps legacy's login semantics but replaces the 10-year JWT with a
short-lived access token plus refresh-token rotation. Both real Flutter clients
store the token in plain `SharedPreferences` and have **no token-refresh code
path at all**. On cutover day every mobile and desktop session would expire at
the first token lifetime and never recover.

This cannot be closed backend-side. It needs client releases, and forcing client
releases is what `hr-platform#21`/`configs` does — which is why §2.4 puts
`configs` first in Item 13. Sequence: `configs` ported → forced-update channel
live → client builds with refresh shipped → cutover.

### 4.7 Forced update + maintenance configs / `hr-platform#21` — **[cutover]**, prerequisite for §4.6

F-07 in the consolidated task matrix, P1, "Blocking: desktop-facing module
cutover", closing when "the new backend's `configs`-equivalent surface serves
the same version-gate fields, verified against a real desktop build". One
Item-13 endpoint plus a verification against a real client. Its cost is small
and its position in the order is not.

### 4.8 FCM push delivery / `hr-platform#22` — **[later workstream]**, no longer a Phase-1 gate (O-1, C7)

**Disposition O-1, accepted 2026-08-23.** Real FCM delivery is **not** a Phase-1
completion requirement. Phase 1 preserves the D-082 notification
persistence/delivery seam and does not introduce working push delivery for the
first time. Functional FCM is tracked as a later capability workstream, not as a
parity cutover blocker.

The evidence behind the disposition — both sides are currently non-functional
end-to-end:

- **mobile**: `docs/api/three-frontend-api-usage-matrix.md` records a direct
  read of the real Flutter source. The `register_push_token` call is
  **commented out** in both `shared_provider.dart` and
  `authentication_provider.dart`, and the token fetch itself is stubbed to an
  empty string;
- **server**: the legacy `FCM_SERVER_KEY` is a confirmed placeholder.

The matrix's own conclusion is that this is "build for the first time," not
"port existing behavior." ADR-0011's Phase 1 is strict parity with the running
system — reproduce what legacy does, decline to reproduce its defects, add no
new capability — so requiring delivery would have put a from-scratch
integration *and* a mobile client change on the critical path of a parity
release, to replace a no-op with a no-op.

**What stays Phase-1 work, unchanged.** D-082's call boundary, its ordering, and
its swallowed-failure semantics are parity behaviour and are already ported;
`LegacyPushDeliveryUnavailable` remains the bound implementation. Every later
wave that notifies reuses `LegacyNotifications` rather than re-porting the
insert. `profile/register_push_token.php` is still an Item-13 endpoint (13.2) —
storing a token is a legacy behaviour; delivering to it is not.

**Consequence for the documents.** D-082, D-089 and both the Wave 12.5 and 12.6
discoveries describe `hr-platform#22` as "a Phase 1 cross-cutting exit
requirement" / "still a cutover blocker". Those statements are superseded by
O-1. They are historical decision text and are **not** rewritten; this section
is the current classification, and §5's gate omits FCM accordingly.

### 4.9 The PHP dashboard is outside Phase 1 (O-5)

**Disposition O-5, accepted 2026-08-23.** Phase-1 completion covers replacement
of the legacy REST API backend — `apis/`, the allow-listed API modules — and
**not** migration of the 92-page server-rendered PHP dashboard. The dashboard
remains a legacy consumer/operator surface against the same MariaDB during
Phase 1 unless a separate migration decision replaces it. Its 92 pages are not
counted in the module or endpoint ledger.

This closes a genuine gap: ADR-0011 never mentions the dashboard at all, so
"Phase 1 replaces PHP with Java" had no recorded boundary.

**Risks kept visible, separately, because they do not disappear.** The dashboard
has its own session auth and its own `constants.php`/`db.php`/`auth.php`, and
the threat model records critical findings in it — cross-tenant account takeover
via `dashboard/pages/employees/page.php`, any-HR-user-deletes-any-company via
`dashboard/pages/companies/`, and the `workforce_planning` page's edit-hijack and
bare delete. **Phase 1 neither fixes nor inherits those**, and they continue to
apply to the same database that the Java backend will be writing to. They belong
in the release packet's risk evidence (§5, G13/G15) as accepted, visible,
unmitigated Phase-1 risk — not in the completion ledger.

### 4.10 `hr_permissions` enforcement inconsistency — **[closed]**

D-044 deliberately reproduces `hr-legacy#8`'s ~21-of-150-endpoint enforcement
gap rather than closing it, and the threat model records the same inconsistency
across most of the API. That is a settled Phase-1 posture, not an open blocker.
Recorded here so a reviewer does not re-raise it as one.

---

## 5. The final Phase-1 exit gate

ADR-0011 leaves this open in as many words: "What acceptance threshold ends
Phase 1 — how much of the differential harness must be green, and who signs it
off." The criteria below reconcile `release-readiness.md` (the evidence packet
and the human gate), `release-cutover-and-rollback.md`,
`production-smoke-and-post-deployment-validation.md` and `test-strategy.md` into
one Phase-1-specific gate. **G6 is accepted under O-7**; the rest are proposed.

**G1 — Module accounting: 37 / 37 live endpoint-bearing legacy modules are
accounted for.** Every one is either delivered or excluded by a decision that
names it.

Recorded separately, because neither is a delivery obligation:

- **`time`** has a physical endpoint file (`apis/api/time/now.php`) but is
  unreachable, because `time` is absent from `ApiModule::allowedList()` and the
  router therefore answers `module_not_found` before it looks for an action
  file. **Explicitly excluded under O-3** (§2.3).
- **`reports`** is the inverse router/filesystem anomaly: it is present in
  `ApiModule::allowedList()` but has **no endpoint directory and no files**, so
  it carries **zero endpoint delivery obligation**. Recorded, not delivered.

The PHP dashboard is out of scope entirely (O-5) and is not part of this count.

**G2 — Endpoint accounting: 198 / 198 live Phase-1 API endpoints are exactly
accounted for.** The §3.2 ledger holds with `FINAL_COMPATIBLE` = 198 and every
other live bucket at zero.

Plus, separately, **1 physical endpoint file is explicitly excluded**:
`/apis/api/time/now.php`.

```text
198 live  +  1 excluded  =  199 physical endpoint files
```

The gate is **never** to be stated as "199 / 199 implemented". That wording
would contradict O-3, which removed `time/now.php` from the live obligation
precisely because the router cannot expose it — implementing it would add a
route legacy does not serve.

**Note added 2026-08-27 (C9), completed 2026-08-28 (D-120).** An earlier
revision of this correction restated G2 as 196 / 196 by moving
`attendance/export.php` and `payslips/export.php` into the exclusion bucket, and
claimed the 198 figure was "arithmetically unreachable". **Both were wrong and
are retracted.** 198 is reachable, and under **D-120 it is reachable in exactly
one way: by delivering all three of the endpoints below.** The owner's
disposition excludes none of them, so the live denominator does not move and
this gate has no second arithmetic. Any future exclusion would require its own
numbered decision and would have to recompute both the live total and the
exclusion ledger before this gate could be read again.

Three endpoints stand between the repository and G2:

- `attendance/overall_report.php` — a JSON endpoint, misclassified as binary;
- `attendance/export.php` — binary streaming response, open per D-101;
- `payslips/export.php` — binary streaming response, open per D-106.

**G3 — Exact PHP URL and wire contract.** Every live endpoint answers on
**the URL clients actually request** — `/apis/api/{module}/{action}`, without
the suffix — with **the response contract its own PHP file emits**. **Wave 12.R
is complete and no `/api/legacy/**` business route remains mapped.**

> **Corrected 2026-08-31 (R-028, D-147).** This gate previously required the
> *literal `.php`* URL, which is a file name and not a URL any client sends:
> `api_constants.dart` joins `https://workin.company/apis/api/` with paths like
> `auth/login_employee`, and requesting the `.php` file directly returns **500**
> from PHP. As written, G3 could have been satisfied in full while every Flutter
> request 404'd — which is precisely the state the port was in until
> `LegacyPhpRouterFilter` landed.
>
> **Extended 2026-09-01.** G3 covers the **live endpoint set**, which
> by construction excludes the paths the router does *not* serve — so the
> unmatched-path behaviour had no gate at all. That gap was not theoretical:
> `time/now` is excluded under O-3 as dead surface, the mobile client calls it
> from its home screen, and Java answered it with the wrong status *and* the
> wrong envelope while every completion gate stayed green.
>
> **G3 therefore also requires**, as a cutover obligation in its own right,
> that a request to a path under `/apis/api/` which no endpoint serves answers
> as `apis/api/index.php` does: unknown module → **404** `module_not_found`
> naming the allow-list, allow-listed module with no action file → **501**
> `module_not_implemented`, missing action → **404** `unknown_action`, all in
> the D-074 envelope and all decided **before authentication**. The owner is
> the router, not the individual endpoints, and the evidence is the
> parity harness's unauthenticated sweep rather than any per-endpoint test.
>
> O-3's exclusion of the physical `time/now.php` file is unchanged and remains
> correct — no endpoint needs building. What was missing is that "excluded"
> was being read as "unowned", and a client-visible mismatch sat in that gap.
>
> The router change that satisfies this obligation, and the decision recording
> it, are on a **separate stacked branch** and are deliberately not cited by
> number here — a reference that resolves to nothing is worse than none. This
> gate states the requirement; the branch that meets it carries its own record.

**What enforces what.** `LegacyPhpRouteInventoryTest`'s bidirectional assertion
is an **internal-mapping** check, not a URL-surface one: it compares the set of
mapped controller patterns against `EXPECTED_ROUTES`, at 125 routes today and
198 when G2 closes. It never exercises a method, a guard order, a status code,
an envelope, a header or a body, so a route with an incompatible wire
implementation still satisfies it.

> It is also blind to the thing R-028 turned out to be. Both sides of that
> assertion are written in `.php` paths, so it agreed with itself perfectly
> while no client-reachable URL resolved at all. **Reachability on the client's
> URL form is evidenced by the parity harness**
> (`docs/migration/2026-08-31-php-java-parity-harness.md`, 188/190 measured
> against both stacks), not by this test — an inventory of a codebase against
> itself cannot establish a client contract. The wire contract itself is carried by each endpoint's own contract and
end-to-end tests, with **G6** as the floor that guarantees none reaches cutover
with zero measured evidence. Do not cite the inventory as proof of the URL
surface: it evidences the **internal controller mappings only**, and URL
reachability must come from the parity harness. Citing it for the URL surface
is what let R-028 through.

The response contract is per-endpoint, not repository-wide (D-120). The 170
delivered routes split **165 / 4 / 1**, and one endpoint's shape depends on a
query parameter:

| Shape | Live today | PHP terminates in | Java answers |
|---|---|---|---|
| **Envelope only** | **193** of the 198 | `ok()` / `fail()` (`apis/helpers/functions.php`) | D-074's JSON envelope — including `attendance/overall_report.php`, delivered by Wave 12.6.6c |
| **Download only** | **4**: `employees/template_excel.php`, `leave_balances/template_excel.php`, `attendance/export.php`, `payslips/export.php` | `stream_employee_template_xlsx()` / `leave_balance_excel_stream_template()` — write to output and `exit`; `api_xlsx_export_send()` for the export | the same reader-observable file, `Content-Type`, `attachment` disposition and filename. **All delivered** — `LegacyEmployeeController.templateExcel` writes the bytes itself, `LegacyLeaveBalanceController.template` returns `ResponseEntity<byte[]>`, and `LegacyAttendanceController.export` returns the workbook for either sheet |
| **Conditional** | **1**: `penalties/report.php` | `?format=csv` → the file's **own local** `streamCSV()` (`penalties/report.php:24`), which `exit`s; anything else falls through to `ok()` | both shapes from one handler. **Delivered** — `LegacyPenaltyController.report` returns `ResponseEntity<?>`: the workbook on the `csv` branch, `LegacyApiResponse.ok` otherwise |
| **Owed** | **none** — Item 12's last endpoint shipped 2026-08-28 | — | — |

**`penalties/report.php`'s `format` parameter selects the wire contract**, so its
evidence has to cover both branches — an envelope response and a workbook
download from the same URL. Two traps in it are already handled and must stay
handled: the local `streamCSV()` **shadows** the global one in
`functions.php:398`, which really does emit `text/csv`; and it rewrites the
`.csv` filename it is handed to `.xlsx`, so the response is a workbook named
`.xlsx` despite every name in the call path saying CSV.

**Every download path keeps the envelope on its failure path.** Each terminator
calls `fail()` when generation throws — a 500 with the JSON envelope — and the
delivered routes keep their auth and validation guards answering in the envelope
too. Only the success path is a file.

**The binary shape is not new territory.** Three of these routes shipped in Waves
12.4, 12.7 and 12.8 and are inside `LegacyPhpRouteInventoryTest`'s 125. The owed
endpoints extend an established pattern rather than introducing one.

This enumeration is exhaustive as measured, and **enforced rather than
hand-maintained**: `LegacyPhpRouteInventoryTest`'s
`everyRouteAnsweringOutsideTheD074EnvelopeIsInventoried` derives the
classification from the live handler mappings — a route answers in the envelope
iff its handler returns `LegacyApiResponse` or `ResponseEntity<LegacyApiResponse>`
— and asserts the non-envelope set is exactly these three.
`theResponseShapePartitionMatchesTheCompletionPlan` pins the 193/4/1 arithmetic to
the same inventory. Adding a download route, or converting one back to the
envelope, fails those tests instead of staling this table, which it has already
done twice.

Those two are **type-level**, so they cannot see `penalties/report.php` losing its
`format=csv` branch while still declaring `ResponseEntity<?>`.
`LegacyPenaltyReportBranchesEndToEndTest` covers that at the request level: it
asserts the default branch answers the JSON envelope with no disposition, and that
`?format=csv` answers the workbook content type, an `attachment` disposition whose
filename ends `.xlsx` rather than `.csv`, and a body beginning with ZIP's `PK`
magic — so the two legacy traps above are pinned by assertion rather than
description.

**Not byte-for-byte, and deliberately so.** D-085 already settled this for the
one XLSX generator Phase 1 has shipped: "ZIP timestamps, compression metadata
and entry CRC representation are archive incidentals, not compatibility
requirements, and no binary invariant is promised"
(`LegacyXlsxWriter`'s javadoc records the same). A byte-equality gate would be
unsatisfiable against `java.util.zip` and would gate on something no client can
observe. Parity is defined on what a spreadsheet reader and an HTTP client see.

Requiring the JSON envelope of the two exports would make the gate unsatisfiable
the other way: an export cannot both stream a workbook to its frozen clients and
answer in an envelope. D-111's zero-client-change invariant decides which wins.

**G4 — Approved divergence ledger.** Every behavioural difference from PHP is a
numbered decision. No endpoint diverges without one. Published as a single list,
not scattered across wave discoveries.

One entry is not a *behavioural* difference but belongs in the same list because
it is a deliberate departure from the frozen SQL: **D-124** drops the two columns
`overall_attendance_report_build()`'s query computes and never reads, on a
measured three-fold saving. It is invisible through the API — same fields, same
values, same ordering, same row count — and visible only in the query the
endpoint emits.

**G5 — Cross-tenant and security review.** Every tenant-owned entity carries
exactly one named policy and fails closed (`TenantFilterCoverageTest`,
`DerivedTenancyPoliciesFailClosedTest`). Every module has a recorded
cross-tenant test. §4.10's `hr_permissions` posture is restated as accepted
rather than re-litigated.

**G6 — Differential coverage floor (accepted, O-7).** *Every live
response-bearing legacy endpoint has at least one measured differential
assertion against the authoritative PHP + MariaDB behaviour.* A percentage
threshold is **rejected**: it cannot distinguish a passing endpoint from an
untested one.

G6 is a **minimum floor, not sufficient coverage by itself.** Endpoint-specific
high-risk branches still require deeper matrices — the shape already used for
`LegacyPhpStrtotime`, `LegacySimpleXlsReader`, `LegacyPdoException` and the
attendance import. Each wave remains responsible for identifying its own
high-risk branches; G6 only guarantees that no endpoint reaches cutover with
zero measured evidence. Every compatibility primitive (`LegacyValues`,
`LegacyPhpStrtotime`, `LegacyPhpDateYear`, `LegacySimpleXlsReader`,
`LegacyXlsxReader`, `LegacyCsvReader`, `LegacyPdoException`) carries a
measured-oracle test class.

**G7 — Full backend test gate.** `./gradlew check` green on the release commit,
with class/test/failure/error/skip counts recorded in the packet. Baseline at
`85bb216`: 117 classes, 1415 tests, 0 failures, 0 errors, 0 skipped.

**G8 — `phase2Test` still compiles and is green on demand.** D-040's
freeze-not-delete obligation. It stays out of `check` — a Phase-2 asset must
never gate a Phase-1 build — but `compilePhase2TestJava` runs in CI and
`./gradlew phase2Test` passes on demand.

**G9 — Flutter compatibility.** Both real clients authenticate, refresh and
operate against the Java backend on a real build. Closes §4.6; depends on §4.7.

**G10 — Configs and cutover capability.** The `configs` version-gate surface is
served by Java and verified against a real desktop build (F-07's own closing
condition).

**G11 — Rollback.** `release-cutover-and-rollback.md`'s procedure rehearsed, not
merely written. Phase 1 has a genuinely cheap rollback — the database is
unchanged and PHP still runs — and that property must be verified rather than
assumed, because it is the main reason Phase 1's risk profile is acceptable.

> **Partially verified 2026-08-30; still open, with three named blockers.** The
> session half is verified in code: Java and PHP tokens are mutually valid, at
> the codec and over real HTTP through the production filter chain. The database
> half is **not true as worded** — Phase 1 adds `legacy_refresh_tokens` to the
> legacy MariaDB (D-043 amendment 3), and its provisioning against a real
> instance is undecided (**R-023**). Session continuity also depends on one
> unverified config value, the shared signing secret (**R-024**). And the gate's
> other half, *"PHP still runs"*, has not been examined at all: nothing shows the
> rollback target is restorable (**R-025**). Three blockers, all open; see D-143
> and D-144 and the pre-cutover steps in `release-cutover-and-rollback.md`.

**G12 — Smoke and post-deployment validation.**
`production-smoke-and-post-deployment-validation.md`'s checks defined against
real endpoints and executed against the cutover deployment.

**G13 — Monitoring and standing risk.** Alert routing live per
`monitoring-and-alerting.md`, with the endpoints carrying a known divergence
explicitly watched, and §4.9's unmitigated dashboard findings recorded as
accepted standing risk.

**G14 — Communication.** Internal and customer communication per
`customer-communication.md`, sequenced with G9/G10's forced client update.

**G15 — Human sign-off.** `release-readiness.md`'s gate: release owner,
engineering owner, test/quality owner and operations owner each record a
go/no-go against the assembled packet. The gate is human-controlled and no
amount of green CI substitutes for it.

**Explicitly not in this gate:** PostgreSQL migration, ETL execution,
target-schema redesign, data cleanup (Phase 2, D-040); PHP dashboard
replacement (O-5); FCM push delivery (O-1); `apis/api/time/now.php` (O-3).

---

## 6. Factual contradictions — corrected and dispositioned

Each was verified against the two pinned commits. Source documents are **not**
rewritten; this section is the correction of record.

| # | Contradiction | Correction |
|---|---|---|
| **C1** | Item-12 specification §1.1's endpoint totals are arithmetically wrong. It states the 19 shared-table modules "hold **128 of the 200**" and that "Item 12 therefore delivers 20 tables and **122** endpoint files". | Summing its own per-module rows gives **133**, and Item 12 after D-4 removes `company_settings` is **127**. Both stated totals are short by exactly 5. **The per-module rows are correct and are the reliable part**; only the two totals are wrong. Item-12 scope is **127 endpoints across 19 modules**. |
| **C2** | "199 endpoint files across 38 module directories" counted the router. | At `83c326e4` there were **198** module endpoints plus `apis/api/index.php`. `complaints/delete.php` was added later, taking the current count to **199**. The headline is right today by coincidence, not derivation. Affects `docs/legacy/existing-php-module-inventory.md` and `docs/api/existing-endpoint-inventory.md`. |
| **C3** | `existing-endpoint-inventory.md` claims "All 199 ... endpoint files ... have now been read", but its section headings account for **195**. | The entire shortfall is one heading: "Employee Docs, Company Join Requests, HR Employees, Complaints, Schedules, Company (**16** endpoints)", where those six modules hold **20** at the current commit (19 at the pinned one). Four endpoints in that group are uncovered, and the section's prose names no `employee_docs` or `complaints` endpoint individually. **Discovery evidence debt — narrowed, not discharged, 2026-08-29** (`docs/migration/2026-08-29-c3-c8-bounded-discovery.md`). The heading is corrected to **19**, matching the inventory's own pinned source `83c326e`; the twentieth endpoint, `complaints/delete.php`, exists only in the later `d113204` tree and is counted there, not here. The eight endpoints in `employee_docs` and `complaints` — the two modules named zero times — were read, and both yielded a finding: `complaints/create.php` is a **third unauthenticated endpoint and the first that writes**, storing anonymous rows with a null `company_id` that `complaints/list.php` can never return; and `employee_docs` authenticates MANAGER but honours it on `list`/`upload` while denying it on `update`/`delete`. Neither is filed upstream — C3-a needs an owner answer before it is a defect, C3-b may be intended. **The other four modules in the heading were not re-read** and remain owed (§8.1). |
| **C4** | 38 directories on disk ≠ the 38 names in `allowedList()`. | `time` is on disk and **not** allow-listed → unreachable, 404 (O-3, §2.3). `reports` is allow-listed and has **no directory** → advertised module, zero endpoints, every action 404s. Neither was previously recorded. Neither changes the 199. |
| **C5** | ADR-0011's "nineteen modules with no Java counterpart" is quoted as if it described Item 13. | It described the repository on 2026-08-16 and is preserved as history. Item 13's current membership is **18 modules / 71 endpoints** (§2.1–§2.2), after D-4 added `company_settings` + its two dependency tables, Wave 12.4 delivered `hr_employees`, and O-3 excluded `time`. The two numbers must never be substituted for one another. Separately, the specification's own shared-table table implies **18** modules without a counterpart, not 19 — most likely `hr_employees`, which shares the `employees` table but has no entity. Neither "19" should be quoted as a scope figure without recomputation. |
| **C6** | The punch list's Item-12 wave table shows 12.5 as "Discovery/specification only" and 12.6 as "Not started"; its "Next, in order" section still describes 12.4 as "in discovery". | Corrected in `2026-08-17-phase1-punch-list.md` to point at this document and to state the current wave status. History and decision references are unchanged. |
| **C7** | `hr-platform#22`'s "Phase 1 cross-cutting exit requirement" / "cutover blocker" classification contradicts the client-side evidence that push works on neither side today. | Resolved by **O-1**: FCM delivery is not a Phase-1 completion requirement (§4.8). D-082/D-089 and the Wave 12.5/12.6 discovery text are historical and are not rewritten; §4.8 is the current classification. |
| **C8** | `assets` and `administrative_decisions` have no row in `three-frontend-api-usage-matrix.md` — 10 endpoints with no recorded client consumer. | **Discovery evidence debt, not an implementation blocker.** Both are covered in the endpoint inventory's Reference/Lookup section, so the surface is read; only consumer attribution is missing. **Discharged 2026-08-29** (`docs/migration/2026-08-29-c3-c8-bounded-discovery.md`) — and C8's premise was wrong. The consumers exist and were simply never recorded: desktop declares all five `assets` endpoints and four of five `administrative_decisions` endpoints in `api_constants.dart`, with full feature directories for both; mobile consumes `assets/list` and `administrative_decisions/list`. **Nine of ten have a confirmed consumer.** The tenth, `administrative_decisions/one`, is declared by neither client and is **not** dispositioned on that basis — C4 already showed that reasoning from one artifact's silence gets reachability wrong. The pass also found that `assets` is gated by an `hr_permissions` flag in the desktop sidebar while the server enforces none, so a faithful port reproduces client-only authorization and must record that deliberately. |
| **C9** | `attendance/overall_report.php` is recorded as a "binary/report exclusion" in `docs/legacy/WAVE12_COMPLETION_AUDIT.md`, in D-110's route note, and in `LegacyPhpRouteInventoryTest`'s assertion name. It is not binary. | **Verified against frozen `hr-legacy@d113204`.** The file ends at `ok(LangKey::OK, $report, 200)` — the same D-074 envelope helper (`apis/helpers/functions.php:380`) every delivered route uses. It has no streaming path and calls no `: never` helper, unlike `attendance/export.php` and `payslips/export.php`, both of which genuinely are binary — each through a `_csv`-named row builder (`data_export_attendance_csv`, `data_export_payslips_csv`) that ends in the single XLSX terminator `api_xlsx_export_send`. The two were blocked *together* on the broad J.2 payroll boundary (§4.5) and `export.php`'s rationale was applied to both. **Consequence: Item 12 is not closed** — Wave 12.6.6 stands at 0 of 2 and three live endpoints remain owed. **All three are open, not excluded**: D-101 calls its two "blocked", D-106 calls `payslips/export.php` "open", and neither is an owner disposition removing them from the Phase-1 obligation. The live total stays **198** and the exclusion bucket stays at **1** (`time/now.php`, O-3); only the bucket distribution changes (§3.2). **Dispositioned 2026-08-28 — O-8/D-120: all three are delivered.** None is excluded, none is deferred, and the two exports are delivered as the binary responses PHP serves. |

---

## 7. Change made to the existing punch list

`2026-08-17-phase1-punch-list.md` is **not** rewritten. Applied changes:

1. a banner at the top naming this document as the authoritative Phase-1
   completion tracker;
2. the Item-12 wave table corrected — 12.5 complete, 12.6 in progress at 6 of
   18, and Waves 12.7–12.10 pointed at §1.3 here;
3. the two cross-cutting obligations updated to name Wave 12.R (O-6) and D-083's
   reclassification (O-4);
4. the "Next, in order" section marked superseded, with item 13's "19 missing
   modules" phrase corrected to the recomputed 18 modules / 71 endpoints (C5).

Items 1–11, the Wave 12.1–12.4 history, and every decision reference stay
exactly as written.

---

## 8. Owner dispositions (O-1..O-7 accepted 2026-08-23; O-8 accepted 2026-08-28)

| # | Disposition | Applied in |
|---|---|---|
| **O-1** | Real FCM delivery is **not** a Phase-1 completion requirement. Both sides are non-functional end-to-end today — mobile token fetch/registration stubbed and commented out, legacy server FCM key a placeholder. Phase 1 preserves the D-082 seam and does not introduce working push delivery for the first time. Functional FCM is a later capability workstream, not a parity cutover blocker. | §4.8, §5 exclusions, C7 |
| **O-2** | The three legacy `company/*` endpoints are assigned to **Wave 12.10**, which is renamed to cover `companies`/profile column completion **and** those endpoints. They are no longer orphaned from the Item-12 sequence. | §1.3, §1.4, §3.2 |
| **O-3** | `apis/api/time/now.php` is unreachable legacy dead surface — file exists, `time` absent from `ApiModule::allowedList()`, router therefore cannot expose it. Removed from the live Phase-1 endpoint obligation and recorded as an explicit exclusion with evidence. Ledger recomputed rather than leaving it inside `ITEM13_REMAINING`. | §2.3, §3.1, §3.2, C4 |
| **O-4** | D-083 reclassified from cutover-only to an **implementation prerequisite for Wave 12.6.3**, where attendance auto-close/time semantics require it. | §1.3, §4.3 |
| **O-5** | Phase-1 completion covers the legacy REST API backend (`apis/`, allow-listed modules), **not** the 92-page PHP dashboard, which remains a legacy consumer/operator surface against the same MariaDB. Its pages are not counted in the ledger; its risks stay visible separately. | §4.9, §3.3, §5 G1/G13 |
| **O-6** | The D-074 retrospective correction becomes its own explicit engineering wave and closure boundary — **Wave 12.R**, 22 endpoints including `auth/login_employee` — rather than being distributed through unrelated module waves. The engineering order is fixed: **remaining Item 12 → Wave 12.R → Item 13**, so no endpoint is ever owned by both 12.R and an Item-13 wave. Owning `auth/login_employee` does not make `auth` an Item-12 module. Not to be split into 21 + 1 unless new evidence requires it. | §1.3, §3.3, §4.1, §3.2 |
| **O-7** | **G6 accepted**: every live response-bearing legacy endpoint must carry at least one measured differential assertion against authoritative PHP + MariaDB behaviour. A percentage threshold is rejected. G6 is a minimum floor; endpoint-specific high-risk branches still require deeper matrices. | §5 G6 |
| **O-8** | **The three open Item-12 endpoints are to be delivered — the C9 disposition, accepted 2026-08-28 as D-120.** The disposition selects delivery for `attendance/overall_report.php`, `attendance/export.php` and `payslips/export.php`: none is formally excluded and none is deferred out of Phase 1. **It does not implement them.** All three remain in `ITEM12_REMAINING` and unmapped in `LegacyPhpRouteInventoryTest`; the work is owed, and §1.2/§1.6 name the slices that owe it. The governing rule is that Java reproduces what PHP does per endpoint: the JSON envelope where PHP calls `ok()`, and — where PHP terminates in a download helper — the same reader-observable workbook, headers and filename, **not** the same archive bytes, which D-085 rules out as a compatibility requirement (§5 G3). Binary-response support becomes a Phase-1 implementation obligation. The live total stays 198 and the exclusion bucket stays at one row. | §1.1, §1.2, §3.2, §5 G2/G3, §6 C9, §8.1 |

### 8.1 What remains genuinely open

Not decisions — evidence and sequencing owed by the waves that own them.

- **D-071 numeric-coercion probe** (§4.2) — attached to Wave 12.R.
- **D-091 reader evidence** (§4.4) — gates 12.6.3/4/5.
- **D-083 settlement** (§4.3) — now gates 12.6.3.
- **Broad J.2** (§4.5) — **evidence complete 2026-08-27, blocker gone.** See
  `2026-08-27-broad-j2-settlement-discovery.md`. All seven payroll functions §G.2
  enumerated are already in `main`, delivered by Waves 12.8/12.9 in their own waves
  rather than pulled forward; the `company_settings` reach turned out to be D-091's
  existing single `WEEKLY_OFF_DAYS` key, and the `requests` dependency closed with
  Wave 12.7. No decision is required to unblock `overall_report.php` **or
  `attendance/export.php`** on dependency grounds — §G.2 names both, and the same
  evidence releases both. What remained for `overall_report.php` was five unported
  helpers (244 lines) plus the report builder — a normal slice, not a boundary
  decision, and delivered as Wave 12.6.6b–c on 2026-08-28.
- **The three Item-12 endpoints** (C9, §6) — **delivered 2026-08-28.** Nothing
  is owed here any more: O-8/D-120 dispositioned all three for delivery rather
  than exclusion, and Waves 12.6.6a–d and 12.9 shipped them.
  `attendance/overall_report.php` answers D-074's envelope;
  `attendance/export.php` and `payslips/export.php` answer the workbook their PHP
  emits, matching its reader-observable content, headers and filename rather than
  its archive bytes (D-085, §5 G3). `ITEM12_REMAINING` is empty and
  `FINAL_COMPATIBLE` stands at 198 -- the whole live surface (§3.2), after Item
  13.0's `configs/get.php`, Wave 13.5's five, Wave 13.3's eight, Item 13.4's
  twenty-eight across waves 13.4a, 13.4b and 13.4c, **Wave 13.2's thirteen** and
  **Wave 13.1's fifteen**, which complete Item 13. The contributor list must add
  up to the figure beside it: 170 + 13 + 15 = 198.
  **G2 is not closed by that.** Its numerator is; the gate covers all 198 live
  endpoints and Item 13's remainder still stands — see §3.2's
  `ITEM13_REMAINING` for the current figure, which is the only place it is
  maintained. G3, G6 and G7 read on their own terms.
- **C3's re-read pass** — **narrowed, not discharged** (2026-08-29). The bounded
  pass read the **eight** endpoints in `employee_docs` and `complaints`, the two
  modules the inventory section named zero times, and found two contract issues
  there. The other four modules in that heading — `company_join_requests`,
  `hr_employees`, `schedules`, `company` — were **not** re-read; the section
  already names most of their endpoints individually, which is why they were
  deprioritised. That remainder is still owed before Waves 12.10 and 13.4 touch
  them.
- **C8's consumer attribution** for `assets` and `administrative_decisions` —
  **discharged** (2026-08-29): nine of ten endpoints have a confirmed client,
  recorded per endpoint in `docs/api/three-frontend-api-usage-matrix.md`. The
  tenth, `administrative_decisions/one`, is declared by neither Flutter client
  and is deliberately **not** dispositioned on that evidence alone.

---

## 9. Standing references

- Sequencing and scope: `docs/adr/ADR-0011-phase-sequencing.md`, D-040
- Wire contract: **D-074** (literal PHP routes/envelope authoritative;
  `/api/legacy/**` is drift) — retrofit owned by Wave 12.R
- Item-12 engineering sequence: `2026-08-18-item-12-specification.md` §7–§8,
  D-054, D-073 — with C1's totals correction
- Wave 12.6 dependency evidence: `2026-08-22-wave-12.6-attendance-discovery.md`
  §G.1, §J.2, §K, §M
- Item-13 boundary: **D-4** (Item-12 specification, open question D-4), **D-091**
  (its one bounded exception)
- Cutover items: **D-083** (now 12.6.3), `hr-platform#18`, `hr-platform#21`
- Later workstream, not Phase 1: **D-082** / `hr-platform#22` push delivery
- Release gate: `docs/operations/release-readiness.md`,
  `release-cutover-and-rollback.md`,
  `production-smoke-and-post-deployment-validation.md`,
  `docs/testing/test-strategy.md`
- Phase 2 freeze: D-040; `phase2Test` compiles in CI, runs on demand
