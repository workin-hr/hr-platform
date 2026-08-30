# Cutover And Rollback Assumptions

This template tracks assumptions about the migration cutover window and
rollback path. Do not fill in real RTO/RPO figures, dates, or production
topology here until Discovery produces that evidence — record only what is
actually known, and mark everything else as an open question.

## Assumption: Forced Re-Authentication On Auth Cutover (Confirmed Decision, 2026-08-04)

> **Scope: the Phase-2 authentication cutover only. This assumption does not
> describe Phase 1.** Added 2026-08-30 per **D-143**. Under **D-111** Phase 1 is
> zero-client-change: it emits tokens byte-identical to `jwtEncode()`'s and
> validates PHP's unchanged, so no Phase-1 session is invalidated in either
> direction, provided both deployments share a signing secret (**R-024**). The
> Statement, Risk and Rollback bullets below all read as Phase-2 statements —
> applying any of them to Phase 1 would plan a mass forced logout that Phase 1
> does not cause. Evidence: `docs/operations/release-cutover-and-rollback.md`.

- **Category**: Cutover window, data-freeze scope, communication.
- **Confidence**: Evidenced — this is a confirmed product decision, not
  a hypothesis. See `docs/adr/ADR-0005-authentication-direction.md`
  and `docs/security/authentication-remediation-design.md` for the full
  design.
- **Statement**: On auth-system cutover, existing `hr-legacy` JWTs
  (mobile and desktop) are **not** migrated or dual-validated against
  the new backend. Every existing session is treated as invalid the
  moment the new backend takes over authentication; users must log in
  again with their existing phone+password credentials (credentials
  themselves migrate — only the *session tokens* do not).
- **Risk If Wrong** (i.e. if this assumption turns out operationally
  unacceptable): a simultaneous forced logout of the entire active user
  base creates a real support-load spike, timed with a specific cutover
  moment — mitigated by coordinating with the existing desktop
  forced-update/maintenance-mode mechanism (`hr-platform#21`) so users
  see a clear "please log in again" state rather than a confusing
  silent failure, and by scheduling cutover communication in advance
  (see `docs/security/authentication-remediation-design.md` for the
  full remediation design this assumption feeds).
- **Rollback Implication**: **superseded for Phase 1 as of 2026-08-30 —
  see `docs/operations/release-cutover-and-rollback.md`.** This bullet
  was written on 2026-08-04 for ADR-0005's *new* authentication design,
  before D-111 settled Phase 1 as zero-client-change. Under D-111 the
  Phase-1 port emits tokens byte-identical to `jwtEncode()`'s — same
  header, same HS256 construction, same claims in the same order, same
  ten-year expiry — so a session issued by either system authenticates
  against the other, **provided both deployments carry the same signing
  secret**. `LegacyPhpJwtWireCompatibilityTest` pins that in both
  directions. Rollback is therefore expected to be transparent, not
  disruptive, and the pre-cutover check that confirms it is recorded in
  the operations document. The original text stands below for the
  Phase-2 auth cutover, where it still applies:
  > if the new auth backend needs to be rolled back after cutover, users
  > who already re-authenticated against it hold new-system
  > credentials/tokens the old `hr-legacy` system does not recognize —
  > rollback is not silently transparent to users who already migrated.
  > This needs an explicit rollback communication plan (not designed
  > here) if rollback is a real possibility for the cutover window
  > chosen, not assumed to be a clean no-op.
- **Evidence**: Direct product-owner decision, this repository, this
  conversation, 2026-08-04.

## Open Questions (This Assumption Specifically)

- Exact cutover timing/communication lead time for the forced
  re-authentication event — not yet scheduled, depends on overall
  migration sequencing (`hr-platform#15`, PMR-09).
- Whether a rollback scenario is realistically in scope for the auth
  cutover specifically, and if so, what the user-facing rollback
  communication looks like.

## Assumption

Record the assumption in testable language. If it cannot be challenged or
validated, it is probably too vague.

## Category (cutover window, rollback trigger, data-freeze scope, communication)

Classify the assumption so reviewers can see whether it affects timing, data
correctness, operational coordination, or customer communication.

## Confidence (Assumed / Evidenced)

Use `Assumed` unless there is actual evidence supporting the statement.

## Risk If Wrong

Describe the migration or customer consequence if the assumption fails.

## Evidence

Link the discovery artifact, measurement, rehearsal, or decision record that
supports the assumption. Leave blank if the assumption is still unsupported.

## Open Questions

Record what must still be learned before the assumption can become part of a
real cutover plan.
