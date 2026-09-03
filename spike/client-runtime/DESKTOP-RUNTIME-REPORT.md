# Desktop runtime verification

The **real desktop application**, built from unmodified source and executed
against the local Java backend through its own hardcoded
`https://workin.company/apis/api/` URL. Setup and containment evidence are in
`README.md`.

## Result

| | Java | PHP |
|---|---|---|
| application launches | yes | yes |
| login through the real UI | yes | yes |
| distinct endpoints exercised | 22 | 22 |
| HTTP responses | 30 | 30 |
| non-2xx responses | 0 | 0 |
| exceptions in the app log | 0 | 0 |
| screens captured | 11 | 11 |

**Identical**: the same fixed click journey produced the same endpoint set,
the same call counts, 30 × HTTP 200 on each, and no exception on either.

**10 of the 11 screens are pixel-identical.** The eleventh, the dashboard,
differs by 17.44% of its pixels, and the difference is confined entirely to
the rotating promotional banner — every statistics card is pixel-identical.
The banner auto-advances, so the two screenshots caught different slides.
Classified as a **test-environment artifact**, not a parity defect.

## What the UI actually did

Login was typed into the real form and submitted with a real click:

```text
POST https://workin.company/apis/api/auth/login_desktop
BODY {country_code: +20, phone: 01555781818, password: ..., login_as: company}
Response 200 {success: true, message: تم تسجيل الدخول بنجاح, data: {company: {id: 214, ...}}}
```

The home screen then rendered the seeded company with its real figures, and
the dashboard rendered 14 employees, 3 branches, 80,000 total salaries and the
turnover percentages — from Java.

Endpoints exercised through the UI:

- `administrative_decisions/list`
- `assets/list`
- `attendance/list`
- `attendance/stats`
- `banners/list`
- `branches/list`
- `complaints/list`
- `configs/get`
- `dashboard/stats`
- `departments/list`
- `employees/list`
- `employees/stats`
- `job_titles/list`
- `leave_balances/list`
- `notifications/list`
- `notifications/unread_count`
- `penalties/list`
- `penalties/stats`
- `phone_countries/list`
- `profile/company`
- `request_types/list`
- `requests/list`

## Why this matters for D-156

`dashboard/stats`, `employees/stats` and `configs/get` are the three client
parsers that write `getModel(...)!` and therefore **throw** if `data` is not a
JSON object. D-156 changed how Java renders an empty structure, so this was the
change most able to break the client at runtime. All three rendered.

## Mutations, uploads and logout through the real UI

Each of these was performed by clicking and typing in the running application,
against Java, and verified in Java's database afterwards.

| flow | request the UI produced | result |
|---|---|---|
| create | `POST departments/create {name, branch_ids:[292]}` | 201 `تم إنشاء القسم`, row 969 written, list refreshed with the new row first |
| update | `PUT departments/update?id=969 {name: ... RENAMED}` | 200 `تم تحديث القسم`, list row renamed |
| delete | `DELETE departments/delete?id=969` | 200 `تم تعطيل القسم`, `is_active` 1 -> 0, row disappears from the list |
| upload | `POST company/upload_logo` (multipart, real GTK file chooser) | 200 `تم رفع الشعار`, file written under `java-uploads/logos/` |
| image load | the stored `logo_url` | the current logo renders in the profile page |
| logout | `POST profile/logout` | 200, app returns to a cleared login screen |

The writes landed in `workin_java` only; the PHP copy was checked and unchanged,
which also confirms the two databases stay isolated during a UI session.

All three legs act on the **same** record. An earlier run's delete hit id 932
instead, because the script clicked a fixed screen position and the list had
re-sorted underneath it; that was a defect in the driving script, not in either
stack. `drive-desktop-crud.py` now takes the id from the create response, forces
a server refetch through the in-app refresh button before each action, and
asserts that the `update` and `delete` requests the app actually sent carry that
id -- the run above is the re-run, and it reports `update carried id=969` and
`delete carried id=969`. Two things had to stop being assumed to get there: the
kebab-menu column moves horizontally when a name grows (the rename shifted it
from x=467 to x=410 and the delete click landed on empty canvas), so the script
locates the glyph by its colour instead; and the app does **not** re-fetch after
a create, it splices the new row in locally.

Row 1 is the right row by the endpoint's own contract, not by luck:
`departments/list.php` ends `ORDER BY created_at DESC, id DESC`, and the rename
does not touch `created_at`.

## A legacy behaviour the runtime run exposed (not a divergence)

The departments screen has a search box. Typing in it and submitting produces
`GET departments/list?page=1&limit=20&search=<term>` -- and **both** stacks
return the full, unfiltered list. Checked directly against each:

| request | PHP | Java |
|---|---|---|
| `search=Marketing` | 10 rows, unfiltered | 11 rows, unfiltered |
| `search=RuntimeVerify` | 10 rows, unfiltered | 11 rows, unfiltered |

(The row-count difference is only the extra department this journey created in
`workin_java`; the two databases are seeded separately.)

`departments/list.php` builds `$where_conditions` from `is_active`, `company_id`
and the branch filters and never reads `search`, so the parameter is accepted and
ignored. Java reproduces that faithfully. Classification: **existing client
defect against legacy behaviour that the port preserves correctly** -- not a Java
parity defect, and not something to "fix" in Java without a product decision,
since doing so would change behaviour the PHP stack has never had.

It is recorded here because it was found at runtime and it is the reason the CRUD
driver cannot use search to isolate a row.

## The one divergence the real client exposed

`company/upload_logo` is an **accepted divergence, D-154**, and this is the first
time it has been demonstrated with the client's own request rather than a
harness fixture.

The desktop client does not upload the file the user picked. Its image pipeline
**compresses the selection to JPEG and writes it back over the same path**,
keeping the original `.png` name -- verified on disk: the picked file was a
69-byte PNG before the upload and a 618-byte JPEG afterwards, while the original
fixture was untouched. So the client sends **JPEG bytes under a `.png`
filename**, which is exactly the case D-154 is about:

```text
php   200  stored /uploads/logos/6a987cb5900d10.70935098.png   (extension from the FILENAME)
java  200  stored /uploads/logos/65a8544ea4d20.24573040.jpg    (extension from the SNIFFED TYPE)
```

Classified as **accepted divergence**, not a defect: D-154 records that PHP's
filename-derived extension is an upload-based code-execution path, deliberately
not reproduced. Client-visible impact is none -- the app renders whatever
`logo_url` comes back, and it rendered on both. The contract layer could not
have found this: its fixtures name a PNG `.png` and a PDF `.pdf`, so the
filename always agreed with the content.

## Not yet covered by this pass

- opening a downloaded document from the profile page: the affordance is
  present and the stored PDF is listed, but the open-in-external-viewer path was
  not exercised;
- the mobile client, which needs an Android emulator — `/dev/kvm` exists but
  is `root:kvm 660` and this account is not in the `kvm` group;
- the app runs against a lockfile that differs from the shipped one:
  `--enforce-lockfile` fails under Flutter 3.47.2 and a plain `pub get` moved
  5 dependencies. Recorded because it is a real deviation from the built app.
