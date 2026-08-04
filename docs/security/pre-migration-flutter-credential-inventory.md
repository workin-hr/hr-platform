# Pre-Migration Flutter Credential Inventory

## Purpose

Records the credential/configuration inventory performed before any
Flutter-client Discovery reading began, per explicit instruction. This
document contains **no secret values** — only file locations, key types,
and classifications. The two Flutter client repositories
(`flutter-integration/workin_desktop/`, `flutter-integration/workin_mobile/`)
were added locally for read-only Discovery only; they are excluded from
git via `.gitignore` and were never staged, committed, or pushed to this
repository.

## Safeguard Applied

`.gitignore` was updated (before any further analysis) to explicitly
exclude both client directories:

```text
flutter-integration/workin_desktop/
flutter-integration/workin_mobile/
```

Verified via `git check-ignore -v` against files in both directories
before proceeding — confirmed both are fully ignored, and `git status`
shows neither as untracked content requiring attention.

## Inventory

| File | Key/Config Type | Classification |
|---|---|---|
| `workin_mobile/lib/firebase_options.dart` | Firebase client API key (Android + iOS/web config blocks, `apiKey` field) | Public client configuration |
| `workin_desktop/lib/firebase_options.dart` | Firebase client API key (2 platform config blocks) | Public client configuration |
| `workin_mobile/android/app/google-services.json` | Firebase client API key (`api_key[0].current_key`) | Public client configuration |
| `workin_mobile/ios/Runner/GoogleService-Info.plist` | Firebase client API key (`API_KEY` field) | Public client configuration |
| `workin_desktop/macos/Runner/GoogleService-Info.plist` | Firebase client API key (`API_KEY` field) | Public client configuration |
| `workin_desktop/lib/core/resources/constants_manager.dart` (line 10) | Google Maps Platform API key (`googleMapsApiKey`) | Public client configuration **by Google's documented pattern, but see note below** |
| `workin_desktop/lib/core/resources/constants_manager.dart` (line 8) | Google OAuth server client ID (`googleServerClientId`, `*.apps.googleusercontent.com` format) | Public OAuth client identifier, not a secret |
| `workin_desktop/dsa_pub.pem` | WinSparkle DSA public-key resource (32-byte value, base64-encoded, no PEM/ASN.1 armor) | Public key material — confirmed safe by design. See "dsa_pub.pem Provenance" below for full evidence chain (Update 2026-08-04, supersedes the earlier medium-confidence entry). |

## Classification Reasoning

**Firebase client API keys** (`apiKey`/`API_KEY` fields across
`firebase_options.dart`, `google-services.json`, `GoogleService-Info.plist`):
classified as public client configuration per Firebase's own documented
security model — these keys identify the Firebase project to Google's
backend and are explicitly designed by Google to be embedded in
distributed client app binaries. They do not themselves authorize
privileged server-side operations; real access control is enforced via
Firebase Security Rules and (optionally) GCP API key restrictions.
**Neither of those controls is visible from client source** — this
inventory can confirm the keys are the *expected type* of value to find
embedded in a Flutter app, not that the project's Security Rules are
correctly configured. That is a live GCP/Firebase Console setting, out of
scope for a source-only Discovery pass.

**Google Maps Platform API key**: same public-by-design client-embedding
pattern as Firebase, but flagged separately because Maps Platform keys
carry a distinct, well-documented risk if left *unrestricted* in the GCP
Console (by application/package and by API) — unauthorized usage driving
up billing, not data access. **Whether this specific key has
application/API restrictions applied cannot be determined from source
code** — this is the one item from this inventory worth a direct,
explicit check against the live GCP Console, separate from and lower
urgency than the account-takeover-class findings already on record in
`workin-hr/hr-legacy`.

## dsa_pub.pem Provenance (Update 2026-08-04)

The earlier entry above classified `dsa_pub.pem` as "very likely safe" on
filename inference alone, which was an insufficiently rigorous basis for a
credential-adjacent file. This section replaces that inference with direct
evidence.

**What it actually is**: the file is not a standard PEM-armored key at all
— it has no `-----BEGIN-----`/`-----END-----` header (which is why
`openssl pkey -pubin` fails to parse it), and is a single 44-character
base64 line that decodes to exactly 32 raw bytes. It is referenced and
consumed as a compiled Windows resource:

```text
workin_desktop/windows/runner/Runner.rc:128:
DSAPub      DSAPEM      "../../dsa_pub.pem"
```

This is the public-key resource for **WinSparkle**, the Windows component
of the `auto_updater` Flutter package (Sparkle-family auto-update, used to
verify the digital signature of downloaded update packages before
installing them). `workin_desktop/installer/AUTO_UPDATE.md` (written by
the same developer who first committed `dsa_pub.pem`, same commit day —
`c55907a`/`fb09278`, 2026-06-03, author Mohamed Ahmed) documents the exact
generation step: `dart run auto_updater:generate_keys` produces a
`dsa_priv.pem` ("سري — احتفظ بيه" — "secret, keep it") and this
`dsa_pub.pem`, and states the public key "لازم يكون في جذر المشروع (مرتبط
في `windows/runner/Runner.rc`)" — "must be at the project root (linked in
`windows/runner/Runner.rc`)". The macOS equivalent (`SUPublicEDKey`, an
EdDSA public key for Sparkle) is present in
`workin_desktop/macos/Runner/Info.plist` for the same auto-update purpose,
corroborating this is a real, intentional, cross-platform update-signing
setup, not a stray artifact.

**Answering each required question directly**:

- **Referenced?** Yes — compiled into the Windows binary via
  `Runner.rc`'s `DSAPub DSAPEM` resource declaration.
- **Project-owned?** Yes — first committed by a named project developer
  alongside documentation the same developer wrote explaining its purpose;
  not a copy-pasted template artifact.
- **Paired with a private key?** Yes, by design (`dsa_priv.pem` per
  `AUTO_UPDATE.md`) — but that private key is **not present anywhere in
  the repository**: a targeted search for `dsa_priv*`, any
  `BEGIN...PRIVATE KEY` PEM header, and common signing-key file extensions
  (`.p12`/`.jks`/`.keystore`) across both Flutter client trees returned
  zero matches. Only the public half was ever added to this client
  checkout, which is the correct and expected state.
- **Removable?** No — it is a live build dependency of the Windows
  target (`Runner.rc` resource compilation would fail without it), not
  dead weight.
- **Tooling artifact?** Yes, specifically an `auto_updater`/WinSparkle
  key-generation output, not a hand-authored or copied one.

**Classification**: public key material for a software-update signature
scheme is *supposed* to ship inside the distributed application — that is
how the verification model works (the app must hold the public key to
verify a signature made with the private key). This is analogous to the
already-documented Firebase/Maps client key pattern: safe to embed,
because the security property depends on the *private* counterpart staying
secret, not on the public value being hidden. No further action is
required for `dsa_pub.pem` itself. The one adjacent item worth carrying
into the migration/task backlog is operational, not a credential leak:
confirming `dsa_priv.pem` is held securely (e.g. a password manager or
signing CI secret) by whoever performs desktop releases, since this
inventory cannot see or verify that from source — tracked in the
consolidated task matrix (`docs/migration/consolidated-task-matrix.md`,
row F-11).

## Explicitly Checked And Confirmed Absent

- No Firebase Admin SDK or GCP service-account JSON (private key) files
  anywhere in either client repository.
- No Android signing keystores (`.keystore`/`.jks`) or `key.properties`
  files.
- No iOS provisioning profiles, `.p12`, or `.cer` certificate files.
- No OAuth client *secrets* (only the public client ID noted above).
- No `.env` files.
- No hardcoded custom backend API tokens or passwords in Dart source —
  the only `Bearer`/`password`/`token` pattern matches found were: a
  runtime-interpolated variable (the cached auth token, not a literal
  value) with the client's own logging code redacting it
  (`'Bearer ***'`), and plain UI/field-name string constants.

## Report Per Explicit Instruction

**No service-account keys, private keys, signing files, or definitively
unrestricted API keys were found.** The one item warranting explicit,
separate follow-up is the Google Maps API key's GCP Console restriction
status, which cannot be verified from source alone.

## Evidence

Full-tree searches of both client repositories for: Firebase/GCP config
filenames, Android/iOS signing artifact filenames, service-account-shaped
filenames, `.env` files, and common secret/token/password source
patterns. No secret values are reproduced anywhere in this document, in
any commit, in any GitHub issue, or in any chat output produced during
this Discovery pass.
