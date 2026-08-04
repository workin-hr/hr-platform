# ADR-0003: API Versioning And Flutter Compatibility

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0003 |
| Title | API Versioning And Flutter Compatibility |
| Status | Proposed |
| Date | 2026-08-02 |
| Owners | Solution Architect, Product Discovery Analyst |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

Flutter mobile and desktop clients already exist and depend on current request, response, and error behavior.

## Decision

**Approval status: Proposed — this decision has not been approved.**

Define an evidence-backed compatibility and versioning strategy before target API implementation begins.

## Alternatives Considered

- break compatibility immediately
- rely on undocumented client assumptions

## Consequences

- reduces client breakage risk
- requires endpoint and behavior inventory first
- may constrain early API design choices

## Risks

- undetected strict-parsing assumptions in the Flutter clients could cause silent breakage if compatibility work proceeds without evidence
- versioning strategy chosen too early could be incompatible with real client behavior discovered later

## Validation Evidence

**Update 2026-08-04**: both prerequisite documents now exist and are
populated with real evidence — `docs/api/existing-endpoint-inventory.md`
(all 199 endpoints) and `docs/api/flutter-request-response-compatibility.md`
(direct reads of both real Flutter clients, not inference). This ADR's
own stated precondition for moving toward `Accepted` is satisfied.

### Classification (2026-08-04 revision)

**Needs an actual decision now, informed by existing evidence — not
blocked on the spike.** The spike's original H4 hypothesis only tested
whether springdoc-openapi could generate a usable spec *mechanically* —
it never could have validated real Flutter compatibility (that was
explicitly out of reach until PMR-02 resolved). PMR-02 is now resolved
with direct evidence: `docs/api/flutter-request-response-compatibility.md`
confirms both Flutter clients are fixed (no client-side changes planned)
and enumerates every contract dependency, including exact request/response
shapes for the endpoints checked. That is precisely the evidence this
ADR needs to decide a versioning strategy (e.g. URL-path versioning vs.
header-based, and how strictly to preserve exact field names/types for
the `Yes`-marked rows in `docs/api/three-frontend-api-usage-matrix.md`).
Recommend: a human decider can accept a versioning strategy now using
this evidence; springdoc-openapi's spec-generation *mechanism* can be
validated organically while implementing the first real endpoint,
per the spike plan's revision.

## Open Questions

- whether existing clients use strict or tolerant parsing — not yet
  directly tested; `docs/api/flutter-request-response-compatibility.md`
  documents expected shapes but did not specifically probe
  strict-vs-tolerant parsing behavior
- what versioning strategy best fits the current client landscape — a
  decision now answerable from existing evidence (see above), not
  invented here
