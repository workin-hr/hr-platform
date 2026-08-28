# C3 and C8: bounded discovery before Wave 13.4

**Date:** 2026-08-29
**Scope:** deliberately bounded. Enough to find contract ambiguity and hidden
consumers before Wave 13.4 plans against these modules — **not** a full
endpoint-by-endpoint read, which those waves will do in their own right.
**Source:** `hr-legacy@d113204`, and the two Flutter clients under
`flutter-integration/`.

Both entries were recorded in the completion plan's §7 as *discovery evidence
debt* rather than implementation blockers. This pass discharges C8 outright and
narrows C3 to a stated, four-endpoint gap with two findings attached.

## C8 — the two modules with "no confirmed consumer evidence"

**C8 as written is wrong, and this is the correction.** It says `assets` and
`administrative_decisions` have "10 endpoints with no recorded client
consumer". The consumers exist and are unambiguous; they were simply never
recorded in `three-frontend-api-usage-matrix.md`. Both modules have complete
feature directories in the desktop client, and both are declared in its
`api_constants.dart`.

| Endpoint | Desktop | Mobile |
|---|---|---|
| `assets/list` | yes | yes |
| `assets/one` | yes | — |
| `assets/create` | yes | — |
| `assets/update` | yes | — |
| `assets/delete` | yes | — |
| `administrative_decisions/list` | yes | yes |
| `administrative_decisions/create` | yes | — |
| `administrative_decisions/update` | yes | — |
| `administrative_decisions/delete` | yes | — |
| `administrative_decisions/one` | **none declared** | **none declared** |

Evidence: `workin_desktop/lib/core/network/api_constants.dart:120-121,190-192`
and the surrounding constants; the desktop feature directories
`presentation/features/_/administrative_decisions/` and `.../assets/`; the
mobile screens under `presentation/features/profile/administrative_decisions/`.

**Nine of ten have a confirmed consumer.** The tenth,
`administrative_decisions/one`, is declared by neither client — the same shape
as F-05's orphan finding, but inverted: server-present and client-absent rather
than client-declared and server-nonexistent. It is **not** proposed for
exclusion here. A dashboard or an undiscovered caller may use it, and C4 has
already shown that reasoning from one artifact's silence produces wrong
conclusions about reachability.

### A finding this turned up: `assets` is authorized on the client only

The desktop sidebar gates the Assets screen on an `hr_permissions` flag:

```dart
SidebarItem(labelKey: StringsManager.assets, ..., hrPermission: HrPermissionFlag.assets, ...)
```

The endpoint inventory records `assets` as one of the modules where the
`hr_permissions` matrix is **not enforced server-side** (unlike
`administrative_decisions`, which enforces it on all five). So the flag hides
the menu item while any authenticated user in the company can call
`assets/create`, `assets/update` and `assets/delete` directly.

That is client-side-only authorization, and it is the reason this matters
before 13.4 rather than during it: a Java port that reproduces the PHP
faithfully **reproduces the gap**, which is correct under D-058 but must be a
recorded decision rather than an accident. It is exactly the "recorded
inconsistency" §2.2 already notes against `assets` — now with a demonstrated
client that relies on it.

## C3 — the four endpoints the inventory heading does not account for

The heading reads *"Employee Docs, Company Join Requests, HR Employees,
Complaints, Schedules, Company (16 endpoints)"* and its body opens *"All 16
endpoints across these 6 small modules read"*. The six modules hold **20**:

| Module | Endpoints | Named individually in the section body |
|---|---|---|
| `employee_docs` | 4 | **none** |
| `complaints` | 4 | **none** |
| `company_join_requests` | 3 | 2 (`accept`, `reject`) |
| `hr_employees` | 3 | 1 (`update_permissions`) |
| `schedules` | 3 | 2 (`generate_*`, `assign_*`) |
| `company` | 3 | 3 |
| **Total** | **20** | |

The shortfall of four is arithmetic, but the *evidence* gap is larger and
differently shaped than the number suggests: the two modules named zero times
hold **eight** endpoints between them. Those eight are what this pass read, and
both yielded a finding.

### Finding C3-a — `complaints/create.php` is a third public endpoint, and it writes

Every other endpoint in these six modules opens with an unconditional
`requireAuth([...])`. This one does not:

```php
required($body, [Column::NAME, Column::PHONE, Request::MESSAGE]);

$employee_id = null;
$company_id  = null;
$source      = 'employee';

if ($auth = getAuth()) {          // optional, not required
    requireEmployeeSessionValid($auth);
    ...
}
```

An unauthenticated caller can submit a complaint. It is stored with
`employee_id = null` and `company_id = null`.

**And nothing can then read it.** `complaints/list.php` filters
`company_id = ? AND source = 'employee'`, so a row with a null `company_id`
matches no company's list. An unauthenticated complaint is written and is
unreachable through the API by any role.

Two consequences for 13.4, stated now rather than discovered mid-wave:

1. It is a **third** entry for the public category in
   `LegacyPhpRoutes` — after `auth/login_employee.php` and `configs/get.php`
   (D-126) — and the first that **mutates**. The security-boundary reasoning
   those two carry ("public because legacy enforces nothing") holds, but the
   data argument does not transfer: this one accepts caller-supplied `name`,
   `phone` and `message` from an anonymous source and persists them. Rate
   limiting, spam and PII retention are live questions for it that do not arise
   for a read-only config lookup.
2. The null-`company_id` row is a **contract ambiguity, not obviously a defect**.
   It may be intentional (a public "contact us" form whose rows are read by the
   dashboard or directly in the database) — the completion plan's C-series has
   twice found an apparent defect to be a deliberate legacy contract. It is
   **not** filed as a legacy issue on this evidence. What is owed before 13.4 is
   one question to the owner: *is an anonymous complaint meant to be readable by
   anyone, and if so, through what?*

### Finding C3-b — `employee_docs` grants MANAGER a role it does not honour

All four `employee_docs` endpoints authenticate
`[COMPANY_ADMIN, HR, MANAGER, EMPLOYEE]`. The scope checks then split the roles
two different ways:

| Endpoint | Scope check | Effect on MANAGER |
|---|---|---|
| `list.php`, `upload.php` | `role === EMPLOYEE && target !== self → 403` | **passes** — may list and upload for any employee in the company |
| `update.php`, `delete.php` | `role not in [COMPANY_ADMIN, HR]` → must own the document | **blocked** — may only touch their own |

So a manager can upload a document to another employee's file and then cannot
update or delete it. The asymmetry is legacy's, it is reachable, and a port that
"tidies" the two checks into one shape would change behaviour for the one role
that sits between the two branches. Recorded here so 13.4 preserves it
deliberately.

## What is deliberately not done

- No endpoint-by-endpoint contract capture for the eight — that is 13.4's work.
  This pass establishes *whether* ambiguity exists, and found two instances.
- `administrative_decisions/one` is **not** dispositioned. Absence from two
  clients is not proof of no consumer (C4's lesson).
- Neither C3 finding is filed upstream in `hr-legacy`. C3-a needs an owner
  answer before it can be called a defect, and C3-b is an asymmetry that may be
  intended. Contrast the two that *were* filed (hr-legacy #31, #32), where the
  code contradicts itself on its own terms.

## Owed next

| # | Owed | Blocking? |
|---|---|---|
| 1 | Owner answer: is an anonymous complaint meant to be readable, and through what? | Before 13.4 plans `complaints` |
| 2 | Decision recording that `assets` reproduces client-only authorization | Before 13.4 delivers `assets` |
| 3 | `three-frontend-api-usage-matrix.md` rows for `assets` and `administrative_decisions` | Discharged by this document's table; the matrix should cite it |
| 4 | The inventory heading's arithmetic (16 → 20) | Corrected in this pass |
