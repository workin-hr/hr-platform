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
| `workin_desktop/dsa_pub.pem` | Single base64 value, not PEM/ASN.1-structured; filename indicates "public" | Very likely a standard, non-project-specific Flutter/Dart SDK tooling artifact — not app business data. Flagged at medium confidence (could not verify against an external reference from this environment); recommend a quick confirmation if this needs to be relied upon later. |

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
