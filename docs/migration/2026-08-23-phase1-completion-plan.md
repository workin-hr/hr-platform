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

**Repository state measured for this document**

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

Wave 12.6 legacy-route inventory stands at **6 of 18**.

### 1.2 Wave 12.6's remaining twelve endpoints

| Slice | Endpoints | Count | State |
|---|---|---|---|
| 12.6.2 | `schedules/assign_employee_schedule` | 1 | complete |
| 12.6.3 | `attendance/check_in`, `check_in_qr`, `check_out` | 3 | complete |
| 12.6.4a | `attendance/analyze_excel` | 1 | complete |
| 12.6.5 | `schedules/employee_monthly_schedule`, `generate_employee_schedule` | 2 | complete |
| **12.6.4b** | `attendance/list`, `stats`, `employee_monthly_attendance` | 3 | **after Wave 12.7 — §1.5** |
| 12.6.6 | `attendance/overall_report`, `export` | 2 | after Wave 12.7 |

**13 of 18 delivered.** The remaining five all wait on the same Wave 12.7
dependency.

### 1.5 Ordering correction — three more endpoints depend on Wave 12.7

**Recorded 2026-08-23, from a closure trace taken before implementing Wave
12.6.4.** This is a dependency correction, not a scope reduction: all five
endpoints remain Phase-1 scope and Item-12 scope, and none is deferred out of
the phase.

The Wave 12.6 discovery recorded the `requests` dependency for
`overall_report.php` and `export.php` only. Tracing the rest of 12.6.4's
closure found the same table reached by three more endpoints, through a
different path:

```
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

```
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

Wave 12.6 moves 13/18 → 18/18 once Wave 12.7 supplies the request boundary,
subject only to a genuinely new dependency in those five closures.

### 1.3 The remaining order, and why each step sits where it does

Every ordering constraint below is quoted from an already-accepted document.
None is introduced here for tidiness.

```
12.6.2  schedules/assign_employee_schedule
   |
   |  gate: D-091 evidence + narrow payroll extraction (J.2 partial)
   |  gate: D-083 settled  (O-4 — prerequisite for 12.6.3)
   |  gate: D-092 guard order + D-093 edge-case matrix (12.6.3 only)
   v
12.6.3  check_in / check_in_qr / check_out
12.6.4a analyze_excel
12.6.5  employee_monthly_schedule / generate_employee_schedule
   |
   v
12.7    requests + leave_balances
   |
   |  gate: broad J.2 resolution (12.6.6 only)
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

**12.6.2 is next and is not blocked.** Wave 12.6 discovery §K: the slice is
"Also clean, also different: it performs repeated schedule upserts
(`schedule_upsert_employee_day`) against `uniq_employee_schedule_date` and then
crosses the D-082 notification boundary." Its readiness column reads "after 1b
review", which is now satisfied.

**12.6.3/4/5 are gated on D-091 evidence and the narrow payroll extraction**,
and 12.6.3 additionally on D-092's guard order, §K.2's D-093 coordinate
edge-case matrix, and — newly, under **O-4** — on D-083 being settled (§4.3).

**12.7 precedes 12.6.6.** This is the one place where the wave numbers do not
run in order, and it is forced by evidence, not preference. Wave 12.6 discovery
§M: `requests` is "Reached by `overall_report` and `export` through the payroll
helpers — a further reason those two are unscheduled." §J.2 says the same from
the other side: the six DB-backed payroll functions those two endpoints need
"additionally read Wave 12.7's `requests` table". So 12.6.6 cannot precede the
wave that owns the table it reads.

**12.6.6 also waits for broad J.2.** §J.2 is explicitly *partially* resolved:
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

**12.R closes Item 12, and the order is fixed.** The approved engineering order
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
| `auth` | 14 | **partial** — `login_employee` only, at the drifted `/api/legacy/auth/login_employee` | Item 13 | `employees`, `companies`, OTP helper, refresh-token store | dashboard, desktop, mobile | `forgot_password`, `resend_otp`, `register_company` are unauthenticated OTP issuers; `complete_company_registration` is unauthenticated and accepts a caller-supplied id | 13.1 |
| `profile` | 9 | none | Item 13 | `employees`, `companies`, push tokens | mobile (primary), desktop (partial) | `delete_account` is destructive and self-service; `register_push_token` is the client half of `hr-platform#22` — now a later workstream, not a gate (O-1) | 13.2 |
| `notifications` | 6 | **partial** — the write/call-boundary seam `LegacyNotifications` + `LegacyPushDelivery` exists (D-082); no endpoints | Item 13 | `employees` | mobile, desktop (likely) | — | 13.2 |
| `company_settings` | 6 | entity exists but is schema-incompatible (EAV vs five typed columns) | Item 13 (D-4) | `setting_definitions`, `setting_allowed_values` | dashboard, desktop | gated by `can_company_settings` in the 17-flag matrix | 13.3 |
| `setting_definitions` | 1 | none | Item 13 (D-4) | none | platform administration | `COMPANY_ADMIN`/`HR` only | 13.3 |
| `setting_allowed_values` | 1 | none | Item 13 (D-4) | none | shared read, all clients | unauthenticated | 13.3 |
| `workforce_planning` | 7 | none | Item 13 | `employees`, `departments`, `job_titles` | dashboard page directory confirmed; desktop (headcount targets) | the recorded edit-hijack/bare-delete finding is on `dashboard/pages/workforce_planning/page.php` — the PHP dashboard, **not** this API module (§4.9) | 13.4 |
| `assets` | 5 | none | Item 13 | `employees` | **no confirmed consumer evidence** — C8 | `hr_permissions` **not** enforced (recorded inconsistency) | 13.4 |
| `administrative_decisions` | 5 | none | Item 13 | `employees` | **no confirmed consumer evidence** — C8 | `hr_permissions` enforced on all 5 | 13.4 |
| `employee_docs` | 4 | none | Item 13 | `employees`, upload slots | mobile (confirmed), dashboard/desktop likely | file upload surface | 13.4 |
| `complaints` | 4 | none | Item 13 | `employees` | mobile (submit), dashboard (handling) | partly undocumented — C3 | 13.4 |
| `company_join_requests` | 3 | none | Item 13 | `employees`, `companies` | dashboard | `accept`/`reject` confirmed correctly company-scoped | 13.4 |
| `app_content` | 1 | none | Item 13 | none | all clients | unauthenticated | 13.5 |
| `banners` | 1 | none | Item 13 | none | mobile | any authenticated session | 13.5 |
| `faqs` | 1 | none | Item 13 | none | mobile | any authenticated session | 13.5 |
| `phone_countries` | 1 | none | Item 13 | none | all clients | unauthenticated | 13.5 |
| `dashboard` | 1 | none | Item 13 | `employees`, `attendance` | dashboard/desktop summary widget | `COMPANY_ADMIN`/`HR` only; call site not traced past its company-scoped entry | 13.5 |
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

```
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

| Status | Endpoints | What it covers |
|---|---|---|
| `FINAL_COMPATIBLE` | **38** | Delivered on the literal `/apis/api/**` URL with the D-074 envelope: Wave 12.4 (17), Wave 12.5 (15), Wave 12.6 slices 1a-i/1a-ii/1b (6). Exactly the set `LegacyPhpRouteInventoryTest` asserts bidirectionally. |
| `IMPLEMENTED_BUT_REQUIRES_D074_RETROFIT` | **22** | Shipped on `/api/legacy/**` with the flat envelope, which D-074 rules implementation drift: `attendance_exception_types` (5), `branches` (6), `departments` (5), `job_titles` (5), `auth/login_employee` (1). Owned by **Wave 12.R** (O-6, §4.1). |
| `CURRENT_WAVE` | **12** | Wave 12.6's remaining twelve, per §1.2. |
| `ITEM12_REMAINING` | **56** | Wave 12.7 (17), 12.8 (20), 12.9 (16), plus the three `company/*` endpoints now in Wave 12.10 (§1.4). |
| `ITEM13_REMAINING` | **70** | §2.2's 71 less `auth/login_employee`, which is counted once, in the retrofit bucket. |
| **Live total** | **198** | 38 + 22 + 12 + 56 + 70 |
| `EXPLICITLY_EXCLUDED_WITH_DECISION` | **1** | `apis/api/time/now.php` (O-3, §2.3). Outside the live total. |
| **Endpoint files** | **199** | 198 live + 1 excluded |

The ledger reconciles exactly; no number was forced.

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
`FINAL_COMPATIBLE`); `attendance/analyze_excel.php` is Wave 12.6.4 and is
explicitly asserted *not* mapped; `leave_balances/analyze_excel.php` is Wave
12.7.

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

### 4.5 Broad Wave-12.6 J.2 payroll boundary — **[wave: 12.6.6]**

Partially resolved: `payroll_is_weekly_rest_day` may be extracted. The broad
subset — six DB-backed functions, two reaching `company_settings` and the
holiday helper D-090 excluded, all reading Wave 12.7's `requests` — remains
blocked, and §J.2 says the final decision is "deliberately **not** recorded
yet". It gates 12.6.6 only, and cannot honestly be settled before Wave 12.7
lands, because the answer depends on what 12.7 makes available.

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

```
198 live  +  1 excluded  =  199 physical endpoint files
```

The gate is **never** to be stated as "199 / 199 implemented". That wording
would contradict O-3, which removed `time/now.php` from the live obligation
precisely because the router cannot expose it — implementing it would add a
route legacy does not serve.

**G3 — Exact PHP URL and wire contract.** Every live endpoint answers on its
literal `/apis/api/{module}/{action}.php` URL with D-074's envelope. Enforced
mechanically by `LegacyPhpRouteInventoryTest`'s bidirectional assertion at 198
routes. **Wave 12.R is complete and no `/api/legacy/**` business route remains
mapped.**

**G4 — Approved divergence ledger.** Every behavioural difference from PHP is a
numbered decision. No endpoint diverges without one. Published as a single list,
not scattered across wave discoveries.

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
| **C3** | `existing-endpoint-inventory.md` claims "All 199 ... endpoint files ... have now been read", but its section headings account for **195**. | The entire shortfall is one heading: "Employee Docs, Company Join Requests, HR Employees, Complaints, Schedules, Company (**16** endpoints)", where those six modules hold **20** at the current commit (19 at the pinned one). Four endpoints in that group are uncovered, and the section's prose names no `employee_docs` or `complaints` endpoint individually. **Discovery evidence debt**: a re-read pass over those six modules is owed before their waves (12.10, 13.4) begin. |
| **C4** | 38 directories on disk ≠ the 38 names in `allowedList()`. | `time` is on disk and **not** allow-listed → unreachable, 404 (O-3, §2.3). `reports` is allow-listed and has **no directory** → advertised module, zero endpoints, every action 404s. Neither was previously recorded. Neither changes the 199. |
| **C5** | ADR-0011's "nineteen modules with no Java counterpart" is quoted as if it described Item 13. | It described the repository on 2026-08-16 and is preserved as history. Item 13's current membership is **18 modules / 71 endpoints** (§2.1–§2.2), after D-4 added `company_settings` + its two dependency tables, Wave 12.4 delivered `hr_employees`, and O-3 excluded `time`. The two numbers must never be substituted for one another. Separately, the specification's own shared-table table implies **18** modules without a counterpart, not 19 — most likely `hr_employees`, which shares the `employees` table but has no entity. Neither "19" should be quoted as a scope figure without recomputation. |
| **C6** | The punch list's Item-12 wave table shows 12.5 as "Discovery/specification only" and 12.6 as "Not started"; its "Next, in order" section still describes 12.4 as "in discovery". | Corrected in `2026-08-17-phase1-punch-list.md` to point at this document and to state the current wave status. History and decision references are unchanged. |
| **C7** | `hr-platform#22`'s "Phase 1 cross-cutting exit requirement" / "cutover blocker" classification contradicts the client-side evidence that push works on neither side today. | Resolved by **O-1**: FCM delivery is not a Phase-1 completion requirement (§4.8). D-082/D-089 and the Wave 12.5/12.6 discovery text are historical and are not rewritten; §4.8 is the current classification. |
| **C8** | `assets` and `administrative_decisions` have no row in `three-frontend-api-usage-matrix.md` — 10 endpoints with no recorded client consumer. | **Discovery evidence debt, not an implementation blocker.** Both are covered in the endpoint inventory's Reference/Lookup section, so the surface is read; only consumer attribution is missing. It becomes a blocker **only if** an endpoint contract in Wave 13.4 turns out to depend on which client calls it. |

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

## 8. Owner dispositions (accepted 2026-08-23)

| # | Disposition | Applied in |
|---|---|---|
| **O-1** | Real FCM delivery is **not** a Phase-1 completion requirement. Both sides are non-functional end-to-end today — mobile token fetch/registration stubbed and commented out, legacy server FCM key a placeholder. Phase 1 preserves the D-082 seam and does not introduce working push delivery for the first time. Functional FCM is a later capability workstream, not a parity cutover blocker. | §4.8, §5 exclusions, C7 |
| **O-2** | The three legacy `company/*` endpoints are assigned to **Wave 12.10**, which is renamed to cover `companies`/profile column completion **and** those endpoints. They are no longer orphaned from the Item-12 sequence. | §1.3, §1.4, §3.2 |
| **O-3** | `apis/api/time/now.php` is unreachable legacy dead surface — file exists, `time` absent from `ApiModule::allowedList()`, router therefore cannot expose it. Removed from the live Phase-1 endpoint obligation and recorded as an explicit exclusion with evidence. Ledger recomputed rather than leaving it inside `ITEM13_REMAINING`. | §2.3, §3.1, §3.2, C4 |
| **O-4** | D-083 reclassified from cutover-only to an **implementation prerequisite for Wave 12.6.3**, where attendance auto-close/time semantics require it. | §1.3, §4.3 |
| **O-5** | Phase-1 completion covers the legacy REST API backend (`apis/`, allow-listed modules), **not** the 92-page PHP dashboard, which remains a legacy consumer/operator surface against the same MariaDB. Its pages are not counted in the ledger; its risks stay visible separately. | §4.9, §3.3, §5 G1/G13 |
| **O-6** | The D-074 retrospective correction becomes its own explicit engineering wave and closure boundary — **Wave 12.R**, 22 endpoints including `auth/login_employee` — rather than being distributed through unrelated module waves. The engineering order is fixed: **remaining Item 12 → Wave 12.R → Item 13**, so no endpoint is ever owned by both 12.R and an Item-13 wave. Owning `auth/login_employee` does not make `auth` an Item-12 module. Not to be split into 21 + 1 unless new evidence requires it. | §1.3, §3.3, §4.1, §3.2 |
| **O-7** | **G6 accepted**: every live response-bearing legacy endpoint must carry at least one measured differential assertion against authoritative PHP + MariaDB behaviour. A percentage threshold is rejected. G6 is a minimum floor; endpoint-specific high-risk branches still require deeper matrices. | §5 G6 |

### 8.1 What remains genuinely open

Not decisions — evidence and sequencing owed by the waves that own them.

- **D-071 numeric-coercion probe** (§4.2) — attached to Wave 12.R.
- **D-091 reader evidence** (§4.4) — gates 12.6.3/4/5.
- **D-083 settlement** (§4.3) — now gates 12.6.3.
- **Broad J.2** (§4.5) — gates 12.6.6, answerable only after Wave 12.7.
- **C3's re-read pass** over the six under-documented modules — owed before
  Waves 12.10 and 13.4.
- **C8's consumer attribution** for `assets` and `administrative_decisions` —
  evidence debt; a blocker only if a Wave 13.4 endpoint contract depends on it.

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
