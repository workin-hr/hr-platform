# GCP / Firebase Console Manual Credential Verification Checklist

## Purpose And Boundary

Source-only Discovery of the Flutter clients
(`docs/security/pre-migration-flutter-credential-inventory.md`)
established which embedded values are public-by-design client
configuration versus privileged credentials, but explicitly could not
verify **live console settings** — restrictions, quotas, authorized
domains, OAuth configuration — because those are not visible from client
source code at all. This checklist exists to close that gap. It is a
**manual operator task list**, not something Claude can execute: it
requires interactive access to the GCP Console and Firebase Console with
appropriate project permissions, which this environment does not have
and should not be given.

**Every item below requires evidence** — a screenshot or an exported
configuration file (e.g. `gcloud` CLI JSON output) attached to the
tracking issue, not a checkbox ticked from memory. Unverified items must
stay marked `Not Verified`, not be assumed safe.

## How To Use This Checklist

1. Whoever performs this (a human with GCP/Firebase project access —
   "Manual Operator" per `docs/migration/consolidated-task-matrix.md`'s
   owner-type convention) works through each item below.
2. For each item, capture the required evidence and attach it to
   [`hr-platform#24`](https://github.com/workin-hr/hr-platform/issues/24),
   which tracks this checklist's completion.
3. Record the result inline in this document (replace `Not Verified`
   with `Verified <date>` plus a one-line summary and a link to the
   evidence) or in the tracking issue — whichever this repository's
   convention favors at execution time.
4. **Do not paste raw key values, full API responses, or unredacted
   screenshots containing adjacent unrelated secrets into this repository,
   any GitHub issue, or chat.** Screenshots should be cropped to the
   relevant setting; exported configs should be reviewed for accidental
   inclusion of unrelated sensitive fields before attaching.

## Checklist

### 1. Google Maps Platform API key — application restrictions

**Why this matters**: `docs/security/pre-migration-flutter-credential-inventory.md`
flagged this as the one credential-adjacent item that source code cannot
verify. An unrestricted Maps key allows any caller who obtains it (trivial
for a client-embedded key) to make billed requests against it from
anywhere.

- [ ] Locate the Maps Platform API key referenced by
      `workin_desktop/lib/core/resources/constants_manager.dart` line 10
      (`googleMapsApiKey`) in the GCP Console → APIs & Services →
      Credentials.
- [ ] Confirm **Application restrictions** are set (Android app with
      package name + SHA-1 fingerprint, and/or iOS app with bundle ID,
      and/or IP address restriction for any server-side use) — not "None."
- [ ] **Evidence**: screenshot of the key's restriction configuration
      panel.
- Status: **Not Verified**

### 2. Google Maps Platform API key — API restrictions

- [ ] Confirm the key is restricted to only the specific Maps
      Platform APIs actually used (e.g. Maps SDK for Android/iOS,
      Geocoding API if used) rather than left unrestricted across all
      enabled APIs on the project.
- [ ] **Evidence**: screenshot of the "Restrict key" → API restrictions
      list for this key.
- Status: **Not Verified**

### 3. Google Maps Platform — quotas

- [ ] Confirm a sane daily/per-minute quota is set on the Maps Platform
      APIs in use (Quotas & System Limits page), sized to expected
      legitimate traffic, not left at an unbounded or extremely high
      default.
- [ ] **Evidence**: screenshot or exported quota configuration.
- Status: **Not Verified**

### 4. Google Maps Platform / GCP project — billing alerts

- [ ] Confirm a budget alert is configured on the GCP project (Billing →
      Budgets & alerts) with a threshold sized to catch abnormal usage
      (e.g. a key-abuse scenario) before it becomes a large unexpected
      charge.
- [ ] **Evidence**: screenshot of the configured budget/alert threshold(s).
- Status: **Not Verified**

### 5. Firebase — authorized domains

**Why this matters**: relevant if Firebase Authentication (or any web-based
Firebase flow) is or becomes part of the system — confirms which origins
can complete auth flows against this Firebase project.

- [ ] In Firebase Console → Authentication → Settings → Authorized
      domains, confirm the list contains only expected production/staging
      domains (no stale, unrecognized, or overly broad entries).
- [ ] **Evidence**: screenshot of the Authorized domains list.
- Status: **Not Verified** — note: `docs/security/pre-migration-flutter-credential-inventory.md`
  found only `firebase_core`/`firebase_messaging` in use client-side (no
  Firebase Auth dependency in either Flutter client) — confirm whether
  Firebase Auth is enabled on the project at all before treating this
  item as high-priority; if it is not enabled, record that finding here
  instead and mark this item `Not Applicable — Firebase Auth not enabled`.

### 6. Firebase / GCP — OAuth client configuration

**Why this matters**: `workin_desktop/lib/core/resources/constants_manager.dart`
line 8 declares a Google OAuth server client ID (`googleServerClientId`).
The client ID itself is not a secret, but its associated OAuth consent
screen and authorized redirect URIs are configuration that can be
misconfigured.

- [ ] In GCP Console → APIs & Services → Credentials → OAuth 2.0 Client
      IDs, locate the client ID matching `googleServerClientId` and
      confirm its configured redirect URIs / authorized JavaScript
      origins (if any) match only expected values.
- [ ] Confirm the OAuth consent screen's configured scopes are the
      minimum needed (not overly broad, e.g. full Drive/Gmail access if
      only basic profile/email is actually used).
- [ ] **Evidence**: screenshot of the OAuth client's configuration page
      and the consent screen's scopes list.
- Status: **Not Verified**

### 7. Firebase project — API key restrictions (the `apiKey` fields)

**Why this matters**: distinct from the Maps key above. Firebase's own
`apiKey` fields (from `firebase_options.dart`, `google-services.json`,
`GoogleService-Info.plist`) are public-by-design per Firebase's security
model (real protection is Security Rules, not key secrecy) — but Google
now allows optional application restrictions on these keys too, and
confirming Security Rules are actually configured (not left in a fully
open test-mode default) is the real control that matters here.

- [ ] In Firebase Console → Project Settings → General, confirm which
      Firebase products are actually enabled for this project (expected:
      Cloud Messaging only, per source-code Discovery — no Firestore,
      Realtime Database, Storage, or Firebase Auth found in client
      dependencies).
- [ ] If any data-bearing Firebase product (Firestore, Realtime Database,
      Storage) is enabled despite not appearing as a client dependency,
      confirm its Security Rules are not in an open/test-mode default —
      this would be a real finding to escalate immediately, not just
      record here.
- [ ] **Evidence**: screenshot of the enabled-products list and, if
      applicable, the relevant Security Rules page.
- Status: **Not Verified**

### 8. Desktop auto-update signing key custody (`dsa_priv.pem`)

**Why this matters**: not a GCP/Firebase item, but the same
"can't-verify-from-source, needs a human confirmation" pattern. See
`docs/security/pre-migration-flutter-credential-inventory.md` ("dsa_pub.pem
Provenance" section) — the public counterpart (`dsa_pub.pem`) is
committed and safe by design; the private counterpart (`dsa_priv.pem`) is
correctly **not** present in the client repository, but this inventory
cannot see where it actually lives or how it's protected.

- [ ] Confirm with whoever performs `workin_desktop` releases where
      `dsa_priv.pem` (and the macOS EdDSA private key equivalent) is
      stored, and that it is not a plain unencrypted file on a
      general-purpose machine (expected: a password manager, a CI
      secret store, or a hardware-backed store).
- [ ] **Evidence**: a written confirmation from the key holder is
      sufficient here (a screenshot of a secret manager's entry existing,
      without revealing its value, is also acceptable) — this item does
      not require a GCP/Firebase Console screenshot.
- Status: **Not Verified**

## Summary

| # | Item | Status |
|---|---|---|
| 1 | Maps API key — application restrictions | Not Verified |
| 2 | Maps API key — API restrictions | Not Verified |
| 3 | Maps API — quotas | Not Verified |
| 4 | GCP project — billing alerts | Not Verified |
| 5 | Firebase — authorized domains | Not Verified |
| 6 | OAuth client configuration | Not Verified |
| 7 | Firebase — enabled products / Security Rules | Not Verified |
| 8 | `dsa_priv.pem` custody | Not Verified |

**None of these items block the technical spike or backend
implementation start** (none are code-level findings) — they are
operational/console-configuration checks, independent of the Java
rewrite's timeline. They should be completed opportunistically by
whoever holds GCP/Firebase project access, tracked via
[`hr-platform#24`](https://github.com/workin-hr/hr-platform/issues/24)
(item 8 is also cross-referenced from
`docs/migration/consolidated-task-matrix.md`, row F-11).

## Evidence

`docs/security/pre-migration-flutter-credential-inventory.md` (source of
every item above — this checklist exists specifically to verify what that
document identified as unverifiable from source code alone).
