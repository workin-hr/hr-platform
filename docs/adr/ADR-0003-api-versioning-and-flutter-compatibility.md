# ADR-0003: API Versioning And Flutter Compatibility

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0003 |
| Title | API Versioning And Flutter Compatibility |
| Status | Accepted |
| Date | 2026-08-02 (accepted 2026-08-05 — see `docs/bootstrap/decision-log.md` D-021) |
| Owners | Solution Architect, Product Discovery Analyst |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

Flutter mobile and desktop clients already exist and depend on current request, response, and error behavior.

## Decision

**Accepted 2026-08-05** (`docs/bootstrap/decision-log.md` D-021).

**The new backend preserves the exact current contract at the exact
current, unversioned URL surface for MVP — no new API-versioning scheme
is introduced.** This is not a placeholder pending further discovery;
it is the direct consequence of confirmed evidence in
`docs/api/flutter-request-response-compatibility.md`:

- Both Flutter clients are **fixed, with no client-side changes
  planned**, and call a single hardcoded literal,
  `https://workin.company/apis/api/` (`ApiConstants.baseUrl`, identical
  in both apps) — there is no dev/staging/prod switching mechanism, no
  build-time environment variable, no flavor configuration in either
  client. A versioning scheme that depends on the client choosing a
  version (URL-path segment, custom header, `Accept` media-type
  negotiation) has **no client-side mechanism to select or send it** —
  the clients would need new builds distributed through app stores
  before they could use any such scheme, which is a much larger lever
  than "add a version."
- Every endpoint's exact request/response field names and types the
  clients depend on are already enumerated
  (`docs/api/flutter-request-response-compatibility.md`,
  `docs/api/three-frontend-api-usage-matrix.md`'s `Yes`-marked rows) —
  the new backend's job for MVP is to reproduce those shapes exactly at
  the same URL paths, not to design a new contract the fixed clients
  cannot speak to.
- The clients already have a real, working mechanism for coordinated
  breaking changes: `workin_desktop`'s remote-config-driven
  forced-update/maintenance-mode capability (`min*BuildNumberKey`,
  `*UnderMaintenanceKey` fields, almost certainly served via the
  legacy `configs/get` endpoint). **This existing mechanism — not a new
  URL-versioning scheme — is the migration's answer to "how do we make a
  breaking client change safely"**: gate old app builds into
  maintenance mode while a new, compatible build rolls out. The new
  backend must continue serving these fields through whatever endpoint
  replaces `configs/get`.

**Scope explicitly limited to MVP/cutover.** This decision does not
forbid a real API-versioning scheme later — once new client builds
exist that can select a version, that becomes a normal, separate design
choice. It only decides that **no version-selection scheme is needed or
buildable for the MVP cutover**, since the only clients that exist
today cannot use one.

## Alternatives Considered

- break compatibility immediately
- rely on undocumented client assumptions

## Consequences

- the new backend's first-milestone endpoints must match exact current
  field names/types for every `Yes`-marked row in
  `docs/api/three-frontend-api-usage-matrix.md`, not a redesigned shape
  — this is a real constraint on API implementation, not just a
  guideline
- no version-selection machinery (URL segment, header, media type) needs
  to be built or maintained for MVP — reduces scope, not just risk
- the legacy `configs/get`-equivalent's remote min-build-number/
  maintenance-mode fields must be preserved and actively used during
  cutover as the actual breaking-change mechanism
- a real API-versioning scheme becomes a normal future decision once new
  client builds exist that can use one — not designed now, not foreclosed
  either

## Risks

- undetected strict-parsing assumptions in the Flutter clients could still cause silent breakage even with shapes preserved exactly, since strict-vs-tolerant parsing itself was not directly tested (see Open Questions)
- if a breaking change becomes necessary before new client builds roll out, the only lever available is the maintenance-mode gate (blocking old builds entirely), not a graceful in-place version negotiation — this is a real operational constraint the migration must plan cutover timing around, not a hypothetical

## Validation Evidence

Both prerequisite documents exist and are populated with real evidence
— `docs/api/existing-endpoint-inventory.md` (all 199 endpoints) and
`docs/api/flutter-request-response-compatibility.md` (direct reads of
both real Flutter clients, not inference, including the confirmed
hardcoded unversioned `baseUrl`, the absence of any client-side
environment-switching mechanism, and the existing remote
forced-update/maintenance-mode fields). This evidence directly supports
the Decision above — the decision is a description of what the evidence
already showed is true about how the clients can and cannot receive API
changes, not a new design imposed on top of it.

### Classification (2026-08-04 revision, decision recorded 2026-08-05)

This decision did not depend on the technical spike. The spike's
original H4 hypothesis only tested whether springdoc-openapi could
generate a usable spec *mechanically* — it never could have validated
real Flutter compatibility (that was explicitly out of reach until
PMR-02 resolved). PMR-02 is resolved with direct evidence
(`docs/api/flutter-request-response-compatibility.md`), which is what
the Decision above draws on directly. springdoc-openapi's spec-generation
*mechanism* is separately validated organically while implementing the
first real endpoint, per the spike plan's revision — that is
implementation tooling, not part of this ADR's decision. Accepted by
the repository owner on 2026-08-05.

## Open Questions

- whether existing clients use strict or tolerant parsing — **remains
  genuinely open, not resolved by this acceptance**; not yet directly
  tested. `docs/api/flutter-request-response-compatibility.md` documents
  expected shapes but did not specifically probe strict-vs-tolerant
  parsing behavior. Since the Decision above commits to preserving exact
  shapes, this question mainly matters for how much slack exists if an
  exact-shape mistake slips through — worth closing before the first
  real cutover, not before this ADR's acceptance.
- whether mobile registers its FCM push token through a mechanism the
  new backend needs to replicate exactly (noted as a follow-up in
  `docs/api/flutter-request-response-compatibility.md`'s Firebase
  section) — a narrow, separate implementation-detail question, not a
  strategic blocker.
