# ADR-0001: Repository Strategy

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0001 |
| Title | Repository Strategy |
| Status | Accepted |
| Date | 2026-08-02 (accepted 2026-08-05 — see `docs/bootstrap/decision-log.md` D-019) |
| Owners | Platform engineering leadership (see CODEOWNERS `@workin-hr/platform-owners`) |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

The project needs a repository structure that supports planning, discovery, documentation, governance, and later implementation without prematurely collapsing legacy and Flutter code into one repository.

## Decision

**Accepted 2026-08-05** (`docs/bootstrap/decision-log.md` D-019).

Keep `hr-platform` as the new repository for bootstrap and future implementation, while `hr-legacy` and Flutter remain separate until discovery justifies any change. Flutter source remains **permanently** separate (not just for the duration of Discovery), enforced via pinned git submodule references (`.gitmodules`), per the Validation Evidence below.

## Alternatives Considered

- immediate monorepo including legacy and Flutter
- separate repositories for every future component from day one

## Consequences

- preserves clear boundaries during Phase 0
- reduces premature migration coupling
- requires explicit compatibility and discovery workflows

## Risks

- repository sprawl if boundaries are not revisited after discovery
- coordination overhead across repositories for changes that span legacy, Flutter, and this repository until a clearer integration contract exists

## Validation Evidence

**Update 2026-08-04**: legacy PHP discovery (`docs/legacy/`,
`docs/api/existing-endpoint-inventory.md`) is complete at the API layer,
and Flutter compatibility discovery now exists
(`docs/api/flutter-request-response-compatibility.md`,
`docs/security/pre-migration-flutter-credential-inventory.md`), performed
against real local, read-only, never-committed checkouts of
`workin_mobile`/`workin_desktop`. This is the evidence this ADR's
Validation Evidence section was waiting on.

**Repository-boundary reassessment (informed by that evidence)**: both
Flutter client repositories carry their own independent git history
(confirmed via `git log` against the local checkouts — real commits by
named project developers, unrelated to `hr-platform`'s own history) and
their own build/release tooling (e.g. `workin_desktop`'s WinSparkle
auto-update signing setup, `installer/AUTO_UPDATE.md`). Nothing found
during Discovery suggests collapsing them into `hr-platform` would
simplify anything — the opposite: `hr-platform`'s own `CLAUDE.md`
scopes this repository to planning/documentation/governance, not hosting
product source or its release tooling. **Recommendation: Flutter source
should remain permanently separate from `hr-platform`**, not just
excluded for the duration of this Discovery pass.

**Update 2026-08-04 (later same day)**: at explicit user request, the
mechanism enforcing this boundary changed from a blanket `.gitignore`
exclusion to pinned **git submodule** references
(`.gitmodules` → `flutter-integration/workin_desktop`,
`flutter-integration/workin_mobile`, pointing at
`git@github.com:m0hamed-ahmed/workin_desktop.git` and
`.../workin_mobile.git`). This is a refinement of the same boundary, not
a reversal of it: a submodule's gitlink is a commit-SHA pointer, not file
content — no Flutter source is stored as a git object in `hr-platform`'s
history either way. What changes is reproducibility (anyone with SSH
access can now check out the exact referenced commit via
`git submodule update --init`, rather than relying on an undocumented
local-only checkout) and explicitness (the pinned commit and upstream URL
are now visible in version control, not just asserted in prose). Neither
CI workflow initializes submodule content (confirmed: no
`submodules: true`/`recursive` on either `actions/checkout` step), so a
plain clone or CI run behaves exactly as it did under the `.gitignore`
approach — empty placeholder directories, no product source ever present
without an explicit, human-initiated opt-in. See
`docs/security/pre-migration-flutter-credential-inventory.md`
("Safeguard Applied") for the full mechanism record, and
`docs/legacy/existing-php-module-inventory.md` /
`docs/api/three-frontend-api-usage-matrix.md` for how documentation
continues to reference Flutter client behavior without needing the
source itself committed in this repository. This recommendation does not
by itself move this ADR to `Accepted` — that still requires human
review — but it directly answers this ADR's first Open Question below.

### Classification (2026-08-04 revision)

Nothing about this decision depended on the technical spike
(`docs/migration/technical-spike-plan.md`, now scoped to tenant-isolation
only) or on production/device access — the repository-boundary question
is answered by the Discovery already done (Flutter Discovery, submodule
conversion) and by this repository's own `CLAUDE.md` scope. Accepted by
the repository owner on 2026-08-05.

## Open Questions

- ~~whether Flutter should remain permanently separate~~ — **Resolved
  2026-08-05**: yes, permanent (see Validation Evidence update above and
  D-019).
- whether future repository boundaries should change after discovery —
  still open for the eventual Java backend implementation repository
  (not yet created; out of scope for this Discovery pass).
