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

## Not yet covered by this pass

- create/update/delete through the UI, uploads through the file picker,
  downloads to disk, and logout: the journey here is read-and-navigate;
- the mobile client, which needs an Android emulator — `/dev/kvm` exists but
  is `root:kvm 660` and this account is not in the `kvm` group;
- the app runs against a lockfile that differs from the shipped one:
  `--enforce-lockfile` fails under Flutter 3.47.2 and a plain `pub get` moved
  5 dependencies. Recorded because it is a real deviation from the built app.
