# Backend Internationalization (English/Arabic) — Design (2026-08-08)

## Purpose And Authority

The repository owner requested (2026-08-08): the platform must serve
English and Arabic now, and adding further languages later must be
easy. This document records the owner's four scoping decisions (below)
and the approved mechanism (Approach A of three presented:
Spring `MessageSource` + properties catalogs; a literal port of
legacy's `t()`/`LangKey` maps and a database-backed catalog were both
rejected — the former reinvents `MessageSource`, the latter adds
runtime infrastructure for strings that change with code).

**Owner decisions, 2026-08-08 (confirmed, not open):**

1. Localized surfaces this slice: API error messages, backend-computed
   display strings (weekly-rest label, day-of-week names), and
   bean-validation messages. Dual-language tenant-data columns are
   out (see Out).
2. Locale selection: explicit `?lang` query parameter wins, then the
   `Accept-Language` header, default English — legacy's exact
   precedence (`apis/helpers/i18n.php` `app_locale()`).
3. Error responses carry a stable machine-readable key plus the
   localized message: `{"code": "...", "message": "..."}`. This
   resolves the typed-error-code open question the CRUD-completion
   spec flagged (`2026-08-08-crud-completion-design.md`, Request-Types
   Delete section).
4. Generated schedule rows persist a stable token (`WEEKLY_REST`),
   localized at read time; manual free-text notes pass through
   verbatim. Replaces the shipped behavior of persisting the literal
   English label.

**This document is planning output only** (hr-platform `CLAUDE.md`:
planning/analysis/review; implementation is a separate, explicitly
assigned step — the owner has assigned this feature for
implementation via the spec → plan → execute flow).

Evidence: legacy mechanism read in full — `hr-legacy/apis/helpers/i18n.php`
(`app_locale()`, `t()` with `{name}` placeholders and
unknown-key passthrough, `pick_label()` for dual-column data),
`hr-legacy/apis/lang/{en,ar}.php` (~407/410 keys), ~1018 `LangKey::`
call sites. hr-platform current state confirmed by search: no
`LocaleResolver`/`MessageSource`/`Accept-Language` handling anywhere in
`backend/`; ~25 hardcoded English reason strings on
`ResponseStatusException` call sites (14 CONFLICT, 6 UNAUTHORIZED,
4 BAD_REQUEST, 1 UNPROCESSABLE_CONTENT); the schedule module persists
the literal `"Weekly rest"` label (`ScheduleService.WEEKLY_REST_LABEL`,
generate path) and serves English-only day names
(`DaysOffParser.englishLabel`) — both shipped 2026-08-08 in PR #67 and
recorded there as a localization normalization to revisit.

## Scope

**In:**

- New package `com.workin.backend.i18n`: locale-resolution filter,
  `Messages` accessor bean, `MessageKeys` constants class, and the
  properties catalogs (`src/main/resources/i18n/messages.properties`
  English base, `messages_ar.properties` Arabic).
- `ApiException` + one `@RestControllerAdvice` rendering the
  `{code, message}` error contract; migration of every
  reason-carrying `ResponseStatusException` call site to keyed
  `ApiException`; generic keys (`error.not_found`, `error.forbidden`,
  `error.unauthorized`, `error.conflict`, `error.bad_request`) for
  bare status-only throws, which keep their call sites unchanged.
- Validation-error rendering: `{code: "error.validation", message,
  fields: [{field, message}]}` with per-field messages resolved
  through Spring's `FieldError`-as-`MessageSourceResolvable` path
  (constraint keys like `NotBlank` live in the catalogs; no validator
  reconfiguration).
- Schedule-module rework: persist the public token constant
  `WEEKLY_REST` instead of localized text on generated rows; localize
  the token at every read; day names in the monthly overview resolved
  from catalog keys `day.0`..`day.6` (0=Sunday..6=Saturday, the
  established wire numbering) so Arabic forms exactly match legacy's
  labels rather than trusting JDK locale data.
- One data migration rewriting any persisted `'Weekly rest'`
  `exception_note` values to the token (the table shipped days ago;
  the update is cheap and idempotent).
- `MessageCatalogSyncTest` (the `PermissionCatalogSyncTest` pattern):
  every `MessageKeys` constant resolves in the English base, and every
  `messages_*.properties` file has exact key parity with the base.
- Integration flow tests for locale selection, the error contract,
  validation localization, and the localized schedule overview.

**Out (tracked, blockers named):**

- Dual-language tenant/platform data columns (`name_ar`-style,
  legacy's `pick_label`) — the modules that use them in legacy (FAQ,
  app content, onboarding screens) do not exist in hr-platform yet;
  the pattern gets designed with the first such module.
- Stored per-user language preference — clients already know and send
  their locale; revisit only if a server-side consumer (e.g. push
  notifications, which have no infrastructure yet) appears.
- RTL layout, number/date formatting — client concerns; the API
  returns ISO dates/times and raw numbers.
- Localizing Flyway/actuator/log output — operator-facing, stays
  English.

## Design

**Locale resolution** (`com.workin.backend.i18n.LocaleResolutionFilter`,
a `OncePerRequestFilter` registered for `/api/**`): resolve once per
request and publish via `LocaleContextHolder.setLocale(...)`, clearing
in a `finally`. Precedence, generalized from legacy's two-language
rule so new languages need no logic change:

1. `?lang=<tag>` — matched by language prefix against
   `SUPPORTED_LOCALES` (today `List.of(ENGLISH, ARABIC)`, one
   constant in the i18n package). `ar`/`ar-EG` → Arabic; unknown
   values → English. For `ar`/`en` inputs this is exactly legacy's
   behavior.
2. `Accept-Language` — parsed with `Locale.LanguageRange.parse` and
   matched against `SUPPORTED_LOCALES`; malformed headers → English.
3. Default: English.

**Catalogs**: Spring Boot's built-in `MessageSource`
auto-configuration via `spring.messages.basename=i18n/messages`,
UTF-8, `fallback-to-system-locale: false` — a key missing from a
translation falls back to the English base; a key missing everywhere
renders as the key itself (legacy `t()`'s unknown-key passthrough,
implemented with `useCodeAsDefaultMessage`). Services never touch
`MessageSource` directly: the thin `Messages` bean
(`String get(String key, Object... args)`, reading
`LocaleContextHolder`) is the single seam, and message keys exist
only in `MessageKeys` (the `PermissionKeys` rule: never introduce a
message string anywhere else).

**Error contract**: `ApiException extends RuntimeException` carrying
`HttpStatus`, the message key, and optional args. One
`@RestControllerAdvice`:

- `ApiException` → its status, body `{code, message}`.
- `ResponseStatusException` without reason (the uniform-404/403
  pattern keeps its call sites) → status-derived generic key.
- `MethodArgumentNotValidException` → 400,
  `{code: "error.validation", message, fields: [...]}`.

Every reason-carrying `ResponseStatusException` call site migrates to
`ApiException` with a module-scoped key
(`employees.phone_in_use`, `identity.invalid_credentials`,
`schedule.range_exceeds_max`, ...). **Recorded contract decision**:
error bodies change from Spring Boot's default error JSON to
`{code, message}` — a deliberate, pre-client-cutover contract choice,
stated here so it is not mistaken for an accidental wire change.

**Schedule strings**: `ScheduleService`'s persisted/compared label
becomes the public constant `WEEKLY_REST` token (also the contract the
attendance-calendar engine will read — closes the visibility seam the
PR #67 final review flagged). The generate path writes the token; both
monthly-overview read paths (persisted manual/generated rows and
live-computed rows) map
`exception_note == WEEKLY_REST` → `messages.get("schedule.weekly_rest")`
and pass any other text through verbatim. `WeeklyRestDayView` names
resolve via `day.<legacyIndex>` keys; `DaysOffParser.englishLabel`
loses its last production caller and is removed (its unit-test
assertion updates accordingly).

## Testing

`I18nFlowTest`: `?lang=ar` on a failing request → 4xx with the
expected `code` and the Arabic `message`; `Accept-Language: ar`
equivalent; `?lang=ar` beats `Accept-Language: en`; `?lang=fr` and no
signals → English; validation failure with `?lang=ar` → Arabic field
message; unauthenticated/forbidden/not-found generic bodies carry
generic codes in both languages.

`ScheduleModuleFlowTest` additions: generate persists the token (not
localized text) in `exception_note`; monthly overview with `?lang=ar`
returns Arabic day names and the Arabic weekly-rest label on the same
fixture that today asserts English; manual note text survives
localization untouched; the existing English assertions keep passing
with no locale signals (default-English regression guard).

`MessageCatalogSyncTest`: every `MessageKeys` constant (plus the
enumerable `day.0`..`day.6`) resolves in the English base; every
`messages_*.properties` on the classpath has exactly the base's key
set — a future `messages_fr.properties` with a missing key fails the
build.

## Extensibility Contract

Adding language X = add `i18n/messages_X.properties` (full key parity
enforced by the sync test) + one entry in `SUPPORTED_LOCALES`. No
other code changes. This is the design's load-bearing promise and the
sync test is what keeps it true.

## Consequences

Every API consumer gets a stable, language-independent error code
plus a display-ready message in the caller's language; the schedule
module stops baking English into rows and gains the token contract
the attendance-calendar engine needs; future notification and export
features inherit a working catalog instead of re-deriving one.
The Flutter clients must adopt the `{code, message}` error shape —
acceptable now, before any production client cutover.

## Open Questions (not decided here)

- **Native review of the Arabic catalog**: the initial
  `messages_ar.properties` will be authored during implementation
  (seeded from legacy's `apis/lang/ar.php` where a matching key
  exists). A native-speaker review pass before release is the
  owner's call to schedule; the catalog file is the single artifact
  to review.
