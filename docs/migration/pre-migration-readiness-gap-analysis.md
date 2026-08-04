# Pre-Migration Readiness Gap Analysis

## Purpose

This document converts every open unknown identified across the Discovery
pass into a bounded, trackable, closeable item — not a license for further
open-ended Discovery. Each gap below states exactly what evidence closes
it, who is responsible for closing it, and what "closed" looks like. Gaps
are not Discovery tasks to be pursued indefinitely; they are readiness
conditions to be satisfied once and then left closed.

This document does not authorize any migration implementation work. See
"Migration-Readiness Gate" at the end for the explicit minimum conditions
required before implementation may begin.

## How To Read Each Gap

Every gap uses the same fixed field set:

- **Description** — what is actually unknown or missing.
- **Why It Matters** — the concrete migration risk if left unresolved.
- **Risk Level / Migration Impact** — Critical/High/Medium/Low, and what
  breaks if this stays open.
- **Blocks** — whether this blocks migration entirely or only a specific
  module/decision.
- **Required Evidence To Close** — the exact artifact or action that
  closes this gap. Not "more research" — a specific, checkable output.
- **Owner** — a role or `TBD — requires human assignment` when no owner
  can be determined from available evidence. No owner is invented.
- **Dependencies** — other gaps or decisions this one waits on.
- **Target / Duration** — a date or duration, or `TBD — requires human
  assignment` when none can be determined. No deadline is invented.
- **Exit / Acceptance Criteria** — the specific, checkable condition that
  marks this gap Closed.
- **Status** — `Blocked`, `Ready`, `In Progress`, or `Closed`, as of this
  writing.
- **Related** — ADRs, GitHub issues, and existing documents.

## Gap Register

### PMR-01: Dashboard Discovery Coverage Incomplete

- **Description**: The `hr-legacy` dashboard (`dashboard/`, 92 files, a
  separate session-based codebase from `apis/`) was read only in its
  highest-risk portions: `companies`, `employees`, `advances`,
  `penalties`, `payroll`, `requests`, `leave_balances`, `assets`,
  `complaints`, `notifications`, `company_settings` sub-actions,
  `salary_calculator`, and the core `auth`/`security`/`query` includes.
  Roughly 75 files — the `attendance` dashboard page, most of
  `settings/`, `setting_templates/`, `activities/`, `profile/`,
  `content/`, `banners/`, `faqs/`, `app_content/`, `phone_countries/`,
  `join_requests/`, and several shared includes — were never read.
- **Why It Matters**: Security and business-rule patterns confirmed in
  the covered portion (e.g. the cross-tenant IDOR pattern found in 10
  modules) may or may not generalize to the unread files. Migrating
  unread code means migrating on inference.
- **Risk Level / Migration Impact**: Medium-High. Does not block
  backend/API migration.
- **Blocks**: Specific modules (dashboard admin panel) only.
- **Required Evidence To Close**: Full read-through of every remaining
  dashboard file, findings written into
  `docs/legacy/existing-php-module-inventory.md` /
  `docs/legacy/business-rule-extraction.md` /
  `docs/security/threat-model.md` with the same evidence-citation
  standard already used.
- **Owner**: TBD — requires human assignment.
- **Dependencies**: None.
- **Target / Duration**: TBD — requires human assignment. For scale
  reference only: comparable effort to the already-completed pass
  covering 199 API endpoints plus ~20 dashboard files.
- **Exit / Acceptance Criteria**: Every dashboard file is either read in
  full or explicitly confirmed as a thin wrapper with no independent
  logic; no remaining "not traced in this pass" caveats for the
  dashboard.
- **Status**: Ready.
- **Related**: `workin-hr/hr-platform#9`.

### PMR-02: Flutter Mobile Client Contract Unknown

**Update 2026-08-04: substantially progressed, not fully closed.** Both
Flutter client repositories (`workin_mobile`, `workin_desktop`) were made
available locally for read-only Discovery and read directly — see
`docs/api/flutter-request-response-compatibility.md` and
`docs/security/pre-migration-flutter-credential-inventory.md`. This
resolved several concrete open questions (confirmed `join_company` over
`register_employee`; confirmed no token-refresh mechanism and insecure
client-side token storage; confirmed the desktop app is a third,
full-admin frontend, not a mobile-app variant; confirmed several
endpoints — QR check-in, `set_employee_attendance_method` — are unused or
reference nonexistent server code) and produced 8 new compatibility
entries. **Not yet closed**: only a sample of endpoints/flows were traced
to a fully confirmed request/response contract (auth, check-in); the
remaining ~190 endpoints' client-side usage was inventoried at the
`api_constants.dart` level (confirmed *that* they're called) but not all
traced to full contract detail. Remains open as a gap for that remaining
depth, at substantially reduced severity.

- **Description**: Real Flutter mobile/desktop client source is now
  available and was read for the core flows and full endpoint inventory
  (see update above). Every "Consumer" field in
  `docs/api/existing-endpoint-inventory.md` is still labeled *inferred*
  and has not yet been mechanically updated to reflect the new evidence.
  Full field-level request/response contract detail beyond the
  already-documented flows remains unconfirmed for most endpoints.
- **Why It Matters**: Rebuilding the API without knowing the real
  client's expectations risks silently breaking the mobile app on
  cutover. `ADR-0003` explicitly needs this evidence.
- **Risk Level / Migration Impact**: Reduced to Medium from Critical/High
  now that real client source has been read — the largest unverified
  assumption in the Discovery effort is resolved; remaining risk is
  depth/completeness, not a total unknown.
- **Blocks**: Full field-level contract confirmation for the ~190
  endpoints not yet traced in detail — not early backend scaffolding,
  and no longer the whole migration's compatibility guarantee.
- **Required Evidence To Close**: Continue the read-through started in
  `docs/api/flutter-request-response-compatibility.md` to full
  request/response/error detail for the remaining endpoints, and
  mechanically update `docs/api/existing-endpoint-inventory.md`'s
  "Consumer: Inferred" labels to "Consumer: Confirmed" where resolved.
- **Owner**: TBD — requires human assignment (continuation of already-started
  work, not a fresh access problem).
- **Dependencies**: None remaining — the access dependency that
  previously blocked this gap is resolved.
- **Target / Duration**: TBD — requires human assignment. Comparable
  effort to the `hr-legacy` API Discovery pass, applied to two client
  codebases instead of one backend.
- **Exit / Acceptance Criteria**: `ADR-0003`'s Validation Evidence
  requirement is satisfied for every endpoint with real client-facing
  surface; every "Consumer: Inferred" caveat in
  `docs/api/existing-endpoint-inventory.md` is resolved to "Consumer:
  Confirmed" or explicitly noted as still unconfirmed with a reason.
- **Status**: Ready — no longer Blocked; access resolved, remaining work
  is continuation depth.
- **Related**: ADR-0003; `workin-hr/hr-platform#10`;
  `docs/api/flutter-request-response-compatibility.md`;
  `docs/security/pre-migration-flutter-credential-inventory.md`.

### PMR-03: Production Data Inaccessible

**Update 2026-08-04: substantially progressed, not fully closed.** At
explicit direction ("you already have the database schema and the
available database data provided for this project — use that material
now"), the real local schema + data dump (`mysql_workin.schema.sql`,
`mysql_workin.data.sql` — git-ignored, local-only, real customer data,
never committed) was loaded into a throwaway, isolated Docker MySQL
container, queried directly for every listed template below, and the
container destroyed afterward. This is **not** the same as live
production database access (see remaining gap below), but it is real
customer data, not schema-structure inference.

- **Description**: `data-quality-analysis.md`,
  `duplicate-business-key-analysis.md`, `orphan-reference-analysis.md`,
  `invalid-date-analysis.md`, `character-set-and-collation-analysis.md`,
  `table-volume-analysis.md`, and the new
  `tenant-boundary-verification.md` are now populated with real,
  measured findings (zero orphan references across 41 foreign keys, zero
  cross-tenant data inconsistencies across 10 checks, real duplicate-name
  counts in 4 tables, 45 invalid zero-dates, one stray collation, ~62K
  total rows). `sequence-and-identity-mapping.md` is partially populated
  (schema pattern confirmed, exact per-table high-water marks not
  reliably extracted). `migration-validation-queries.md` and
  `cutover-and-rollback-assumptions.md` remain genuinely open — the
  former needs an actual migrated target to validate against, the latter
  partially populated with the confirmed auth-cutover assumption.
- **Why It Matters**: Real data-quality issues, duplicate keys, orphaned
  references, invalid dates, and real volume directly affect
  migration-pattern choice and cutover downtime — now substantially
  answered, not schema-structure inference. `ADR-0004`'s Validation
  Evidence checklist is now satisfied for the schema/data portion.
- **Risk Level / Migration Impact**: Reduced to Medium from High — the
  data-quality unknowns are resolved; what remains is snapshot freshness
  (see below), not a total unknown.
- **Blocks**: Nothing broad any longer. The **one remaining true gap**
  is re-verifying these exact findings against a **fresh production
  snapshot** closer to actual cutover — this dump is dated 2026-08-03,
  a single point-in-time snapshot, not necessarily representative of
  current production volume/quality. This narrower re-verification step
  blocks the data-migration/cutover phase specifically, not backend
  implementation start.
- **Required Evidence To Close Fully**: Re-run every query in the
  populated templates above against a fresh production snapshot
  immediately before cutover planning, confirming the 2026-08-03
  findings still hold (or documenting what changed).
- **Owner**: TBD for the fresh-snapshot re-verification — requires a
  human with production DB access. Must not be an agent, per
  `CLAUDE.md`'s credential-handling boundary.
- **Dependencies**: Fresh production database read access, timed close
  to actual cutover (not needed now).
- **Target / Duration**: TBD — tied to cutover timeline, not urgent now.
- **Exit / Acceptance Criteria**: Fresh-snapshot re-verification
  confirms (or updates) the 2026-08-04 findings; `ADR-0004`'s Validation
  Evidence checklist remains satisfied.
- **Status**: **Ready** — no longer Blocked. Substantial real analysis
  complete; remaining work (fresh-snapshot re-verification) is a
  cutover-phase task, not a current blocker.
- **Related**: ADR-0004; `docs/migration/database-schema-inventory.md`;
  `docs/migration/data-quality-analysis.md` and siblings;
  `workin-hr/hr-platform#11`.

### PMR-04: Attendance Device/Hardware Discovery Not Started

**Update 2026-08-04**: still genuinely blocked for real vendor/hardware
evidence, but no longer blocks backend architecture or implementation
generally — see `docs/devices/device-integration-architecture.md`
(new), a vendor-neutral adapter/SPI design, event-ingestion contract,
idempotency strategy, retry/offline-sync behavior, device
authentication model, mock simulator, and test-scenario checklist, all
designed without needing real hardware.

- **Description**: `docs/devices/attendance-device-model-and-firmware-inventory.md`
  and `docs/devices/vendor-capability-matrix.md` remain empty templates
  — correctly so, still no real vendor/hardware access. Device vendor,
  firmware, and exact integration protocol per vendor remain unknown.
- **Why It Matters**: `ADR-0006`'s **final, vendor-specific** direction
  (which vendors need a local gateway vs. direct cloud API, exact
  protocol selection) cannot move past "candidate" without this. The
  **architectural pattern** that accommodates any answer, however, is
  now designed and ready — see Status below.
- **Risk Level / Migration Impact**: Reduced to Medium for the
  architecture (a real, buildable design now exists); remains High for
  final vendor-specific validation specifically.
- **Blocks**: Final attendance-hardware integration and validation only
  — **no longer blocks the rest of backend architecture or
  implementation**, per explicit direction and the new vendor-neutral
  design.
- **Required Evidence To Close**: Device vendor identification, protocol
  documentation, firmware version inventory, and confirmed integration
  pattern per vendor, per the existing empty templates' own field
  structure, plus execution of the test-scenario checklist in
  `device-integration-architecture.md`.
- **Owner**: TBD — requires human assignment, likely with physical
  device or vendor-contact access.
- **Dependencies**: Access to device vendor documentation, physical
  devices, or vendor contacts.
- **Target / Duration**: TBD — requires human assignment. Not urgent
  relative to backend implementation start, since it no longer blocks
  that.
- **Exit / Acceptance Criteria**: Both device templates populated with
  real evidence; the test-scenario checklist in
  `device-integration-architecture.md` executed against real hardware;
  `ADR-0006`'s Validation Evidence requirement satisfied for the
  vendor-specific portion.
- **Status**: Blocked for final vendor-specific validation and
  ADR-0006's final direction; **not blocked** for backend architecture
  or implementation generally (the adapter/SPI pattern is accepted-now
  per ADR-0006's revised classification).
- **Related**: ADR-0006; `docs/devices/device-integration-architecture.md`;
  `workin-hr/hr-platform#12`.

### PMR-05: Production `DEBUG` Config Value Unconfirmed

- **Description**: Whether `AppConfig::DEBUG` is `true` in the live
  production deployment was never confirmed. If it is, three endpoints
  (`forgot_password.php`, `resend_otp.php`, `register_company.php`)
  return the real OTP code in the API response — a complete,
  unauthenticated account-takeover path for any phone number.
- **Why It Matters**: This is the single highest-priority open item from
  the entire Discovery pass, independent of migration timing — it is a
  live-production question, not an architecture one.
- **Risk Level / Migration Impact**: Critical, but this is not a
  migration blocker in the architectural sense — it is an urgent
  production-security action that should happen regardless of when
  migration starts.
- **Blocks**: Nothing about migration planning directly; blocks "safe
  continued operation of the legacy system during the migration window."
- **Required Evidence To Close**: Direct confirmation of the live
  `AppConfig::DEBUG` value against the real production environment.
- **Owner**: TBD — requires a human with production access.
- **Dependencies**: None besides access.
- **Target / Duration**: Immediate — this should not wait for a
  migration-planning cadence.
- **Exit / Acceptance Criteria**: The real value is confirmed and
  recorded; if `true`, remediation is tracked via the existing linked
  issue.
- **Status**: Blocked — on production access. Already tracked; no new
  issue created (see Related).
- **Related**: `workin-hr/hr-legacy#4` (existing issue, not duplicated).

### PMR-06: Open Product/Business Decisions

- **Description**: Five behaviors found during Discovery need an
  explicit human/product decision before the Java rewrite can implement
  them correctly — these are not bugs to silently "fix," per prior
  explicit direction:
  - Whether `salary_contracts.housing_allowance` should be settable
    (`workin-hr/hr-legacy#14`).
  - Whether mobile logout deactivating the employee's account is
    intentional design (`workin-hr/hr-legacy#15`).
  - Whether Manager approval authority for requests should be
    company-wide or branch-scoped (`workin-hr/hr-legacy#18`).
  - Which of the two employee self-registration endpoints is canonical
    (`workin-hr/hr-legacy#19`).
  - Whether individual admin accounts and MFA are warranted, replacing
    the single shared platform-admin password
    (`workin-hr/hr-legacy#11`).
- **Why It Matters**: Guessing at product intent for any of these would
  relocate the ambiguity into the new system rather than resolve it.
- **Risk Level / Migration Impact**: Medium — product/UX correctness
  risk in most cases, security-adjacent for the admin-account question.
- **Blocks**: Only the specific modules involved (salary contracts,
  profile/logout, requests, auth/registration, admin identity) — not the
  whole migration.
- **Required Evidence To Close**: A human/product decision recorded in
  `docs/bootstrap/decision-log.md` (or an equivalent product decision
  record) for each of the 5 linked issues.
- **Owner**: TBD — requires a human product/business decision-maker.
- **Dependencies**: None technical.
- **Target / Duration**: TBD — requires human assignment.
- **Exit / Acceptance Criteria**: Each of the 5 linked issues has a
  recorded decision.
- **Status**: Blocked — 4 of 5 still awaiting human decisions. **1 of 5
  resolved 2026-08-04**: `#19` (which self-registration endpoint is
  canonical) was directly resolved by Flutter client evidence —
  `join_company` confirmed as the live path, `register_employee`
  unreferenced by either real client. See the comment on that issue and
  `docs/api/flutter-request-response-compatibility.md`. Already tracked
  via 5 existing issues; no new issue created for this roll-up.
- **Related**: `workin-hr/hr-legacy#11`, `#14`, `#15`, `#18`,
  ~~`#19`~~ (resolved).

### PMR-07: Target Technology Stack Unvalidated Hands-On

**Update 2026-08-04 — scope narrowed, per explicit direction not to
treat the full original plan as a blanket blocker.** Reviewed all 6
original hypotheses against whether each genuinely needs isolated
experimental validation before implementation, or is mature/low-risk
enough to adopt directly during first-milestone implementation. Result:
**only tenant isolation (RLS vs. repository-guard) remains a required
spike** — the single most consequential, hardest-to-retrofit pattern
decision, and the structural fix for the most-repeated bug class found
in Discovery. Everything else (Modulith tooling, auth implementation —
direction now decided — OpenAPI generation, the testing stack,
observability baseline) moves to "adopt directly while building the
first real module." Full reasoning and revised hypothesis-by-hypothesis
table: `docs/migration/technical-spike-plan.md`'s "Revision Summary."

- **Description**: Java 25, Spring Boot 4.x, PostgreSQL, Flyway, and
  Next.js are chosen in `docs/tools/tool-catalog.md`/`tool-decision-matrix.md`.
  Of the original 6 validation hypotheses, 1 (tenant isolation) remains
  a genuine pre-implementation spike; 5 are reclassified as safe to
  adopt directly.
- **Why It Matters**: The tenant-isolation mechanism shapes every module
  written afterward — worth verifying hands-on before committing.
  Everything else is mature, standard technology for this stack in
  2026, not something needing isolated proof.
- **Risk Level / Migration Impact**: Reduced from High to Medium — the
  remaining spike is small (3 days, not 10) and narrowly scoped.
- **Blocks**: The tenant-isolation *pattern* detail specifically —
  no longer "full-scale implementation start" broadly.
- **Required Evidence To Close**: Execution of the revised, 3-day, H2-only
  spike in `docs/migration/technical-spike-plan.md`, with a recorded
  Accept/Revise/Reject recommendation for the tenant-isolation pattern.
- **Owner**: TBD — requires human assignment (Solution Architect plus
  implementation agent under supervision).
- **Dependencies**: None blocking — ready to execute once approved.
- **Target / Duration**: 3 working days (revised down from 10), proposed
  for human confirmation.
- **Exit / Acceptance Criteria**: Defined in the spike plan's revised
  exit-criteria section.
- **Status**: Ready — plan exists and is narrowed, awaiting human
  approval to execute.
- **Related**: `docs/migration/technical-spike-plan.md`; ADR-0002
  (the one ADR this spike still feeds); `workin-hr/hr-platform#13`.

### PMR-08: All 9 Architecture ADRs Remain Proposed

**Update 2026-08-04 — per-ADR classification now exists, not a single
monolithic blocker.** Every ADR (including the new ADR-0009) was
individually reviewed for whether it needs an actual decision now,
depends on the (now-narrowed) spike, can be accepted immediately from
existing evidence, or is genuinely premature. See each ADR file's new
"Classification (2026-08-04 revision)" section for the full reasoning.
Summary:

| Classification | ADRs |
|---|---|
| **Recommended: accept now**, no dependency | ADR-0001 (repository strategy), ADR-0005 (auth direction) |
| **Recommended: decide now**, informed by existing evidence gathered this session | ADR-0003 (API versioning — real Flutter contract evidence now exists), ADR-0004 (MySQL→Postgres approach — real DB analysis now exists), ADR-0009 (dashboard vs. desktop — decision recorded, 2 items left for sign-off) |
| **Accept now for the MVP baseline**, heavier stack deliberately deferred | ADR-0007 (testing strategy), ADR-0008 (observability baseline) |
| **Split — strategic direction acceptable now, one detail still spike/access-dependent** | ADR-0002 (modular monolith — strategy now, tenant-isolation pattern detail after the 3-day spike), ADR-0006 (attendance edge-gateway — adapter/SPI pattern now, vendor-specific direction after PMR-04) |

**None of these are self-approving** — this document recommends, a
human `Status`-change is still required per each ADR's own governance
rule, consistent with this repository's practice throughout.

- **Description**: None of `ADR-0001` through `ADR-0009` have moved to
  `Accepted` yet, though 4 are recommended for immediate acceptance and
  3 more for a decision now.
- **Why It Matters**: Implementing against a `Proposed` ADR as if it
  were `Accepted` risks rework if a later formal review revises the
  direction.
- **Risk Level / Migration Impact**: Reduced to Low-Medium — mostly a
  process step (human sign-off) now, not missing evidence.
- **Blocks**: Full-scale implementation start for the 2 ADRs still
  genuinely spike/access-dependent (the tenant-isolation detail,
  final device-vendor direction); the other 7 are ready for sign-off.
- **Required Evidence To Close**: Human engineering leadership (each
  ADR's own Deciders field) reviews the Validation Evidence/Classification
  sections and records Accept/Reject/Revise in
  `docs/bootstrap/decision-log.md`.
- **Owner**: TBD — human engineering leadership, per each ADR's Deciders
  field.
- **Dependencies**: The narrowed PMR-07 spike still feeds ADR-0002's
  tenant-isolation-pattern detail. PMR-04 still feeds ADR-0006's final
  vendor-specific direction. Nothing else remains blocked.
- **Target / Duration**: The 7 non-spike-dependent ADRs: as soon as a
  human reviews. The 2 spike/access-dependent details: per PMR-07/PMR-04.
- **Exit / Acceptance Criteria**: Every ADR's Status is updated to
  `Accepted`, `Rejected`, or `Superseded`, with a decision-log citation.
- **Status**: Ready for 7 of 9 ADRs (human sign-off only); Blocked for
  ADR-0002's tenant-isolation detail (PMR-07) and ADR-0006's final
  vendor direction (PMR-04).
- **Related**: All 9 ADRs; `docs/bootstrap/decision-log.md`;
  `workin-hr/hr-platform#14`.

### PMR-09: No Detailed Per-Module Migration Execution Plan

- **Description**: `docs/migration/migration-strategy-and-sequencing.md`
  gives sequencing and rationale, not implementation-level detail (Java
  class/package structure, sprint breakdown, cutover runbook).
- **Why It Matters**: A strategy document is not an execution plan.
  Producing implementation-level detail before the spike (PMR-07) and
  ADR acceptance (PMR-08) would mean guessing at exactly what they're
  meant to answer.
- **Risk Level / Migration Impact**: Medium.
- **Blocks**: Implementation start for any specific module —
  intentionally sequenced after PMR-07/08, not parallel to them.
- **Required Evidence To Close**: A per-module implementation plan
  produced after the spike and ADR acceptance, directly informed by
  their outputs.
- **Owner**: TBD — Solution Architect.
- **Dependencies**: PMR-07, PMR-08.
- **Target / Duration**: TBD — requires human assignment; naturally
  sequenced after PMR-07/08 complete.
- **Exit / Acceptance Criteria**: A reviewed, approved detailed execution
  plan exists for at least the first migration wave (tenant/identity
  plus one module).
- **Status**: Blocked — on PMR-07 and PMR-08, both now substantially
  smaller dependencies than originally scoped (2026-08-04: PMR-07
  narrowed to a 3-day spike, PMR-08 has 7 of 9 ADRs ready for immediate
  sign-off) — this gap should close faster than its original framing
  implied, not faster than its actual dependency chain.
- **Related**: `docs/migration/migration-strategy-and-sequencing.md`;
  `workin-hr/hr-platform#15`.

### PMR-10: No Migration-Correctness Test Plan Or Differential-Testing Harness

- **Description**: `docs/migration/migration-validation-queries.md`
  remains empty (cannot be filled in until both schemas exist). No
  differential-testing harness exists comparing legacy PHP output
  against new Java output for the same inputs. No golden datasets are
  defined.
- **Why It Matters**: Without this, migration correctness cannot be
  verified systematically — especially risky given the payroll
  three-formula-duplication finding already on record
  (`workin-hr/hr-legacy#12`, `#13`).
- **Risk Level / Migration Impact**: High. Blocks production cutover of
  any migrated module.
- **Blocks**: Production cutover — not early implementation.
- **Required Evidence To Close**: A working differential-test harness
  design, golden-dataset creation strategy, and
  `migration-validation-queries.md` populated once a target schema
  exists — validated against at least the spike's (PMR-07) vertical
  slice first.
- **Owner**: TBD — Test Architect, per ADR-0007's owner.
- **Dependencies**: PMR-07 (testing-tool choices), a real target schema
  existing, partially PMR-03 (real data characteristics inform golden
  datasets).
- **Target / Duration**: TBD — requires human assignment; naturally
  sequenced after PMR-07.
- **Exit / Acceptance Criteria**: A working differential-test harness
  validated against the spike's vertical slice; `migration-validation-queries.md`
  populated with real, runnable queries.
- **Status**: Blocked — on PMR-07 (now a 3-day spike, not 10) and a
  target schema existing. `docs/migration/migration-validation-queries.md`
  now has a prepared baseline to validate against (real row counts, per
  `docs/migration/table-volume-analysis.md`) even though it can't be
  fully populated until a real migrated target exists.
- **Related**: ADR-0007; `docs/migration/migration-validation-queries.md`;
  `workin-hr/hr-legacy#12`, `#13`; `workin-hr/hr-platform#16`.

## Prioritized Gap/Task Matrix

**Revised 2026-08-04.** Status column reflects this round's substantial
progress on PMR-02, PMR-03, PMR-04 (architecture), PMR-06 (partial),
PMR-07 (narrowed), and PMR-08 (per-ADR classification).

| ID | Gap | Risk | Blocks | Status | Depends On | GitHub Issue |
|---|---|---|---|---|---|---|
| PMR-05 | Production `DEBUG` value unconfirmed | Critical | Live security, not migration — may warrant emergency action on legacy system independent of migration timing | Blocked | Production access | `hr-legacy#4` |
| PMR-07 | Tenant-isolation mechanism unvalidated hands-on | High (narrowed from "tech stack" broadly) | ADR-0002's tenant-isolation-pattern detail only — no longer full-scale implementation start | Ready | None | `hr-platform#13` |
| PMR-04 | Device/hardware Discovery not started | Medium (architecture done) / High (final vendor validation) | ADR-0006's final vendor direction, final device validation only | Ready for architecture; Blocked for vendor-specific validation | Vendor/device access (validation only) | `hr-platform#12` |
| PMR-10 | No correctness test plan/harness | High | Production cutover | Blocked | PMR-07 (now 3 days), target schema | `hr-platform#16` |
| PMR-01 | Dashboard Discovery incomplete | Medium-High | Dashboard modules | Ready | None | `hr-platform#9` |
| PMR-02 | Flutter client contract — depth remaining | Medium (was Critical/High) | Full per-endpoint contract confirmation, not the whole migration | Ready | None | `hr-platform#10` |
| PMR-03 | Production data — fresh-snapshot re-verification | Medium (was High) | Data-migration/cutover phase only — schema/data-quality analysis substantially complete | Ready | Fresh snapshot (cutover-phase timing, not now) | `hr-platform#11` |
| PMR-08 | ADRs still Proposed | Low-Medium (was Medium) | 2 of 9 ADRs' remaining details; 7 of 9 ready for sign-off | Ready for 7 ADRs; Blocked for 2 details | PMR-07 (1 ADR), PMR-04 (1 ADR) | `hr-platform#14` |
| PMR-06 | Open product/business decisions | Medium | Specific modules | Blocked — 4 of 5 remain (1 resolved 2026-08-04) | Human decisions | `hr-legacy#11,14,15,18` |
| PMR-09 | No per-module execution plan | Medium | Implementation start per module | Blocked, but dependency chain now much shorter | PMR-07 (3 days), PMR-08 (7/9 ready) | `hr-platform#15` |

**Security findings** (`hr-legacy#2/#3/#5/#6/#7/#8/#9/#10`) are
deliberately **not** in this table — reframed 2026-08-04 as mandatory
acceptance criteria for their equivalent new modules, not
migration-readiness gaps. See
`docs/migration/consolidated-task-matrix.md` Section A.

Read this table as: **PMR-01, PMR-02, PMR-03 (fresh-snapshot timing
aside), PMR-04 (architecture), PMR-07, and 7-of-9 ADRs under PMR-08 are
all `Ready` today** — the only genuine external-access blockers left are
PMR-05 (live production value) and PMR-04/PMR-08's 2 remaining
device-vendor-specific items.

## Migration-Readiness Gate (Revised 2026-08-04)

**Implementation must not begin until every condition below is true.**
This gate is a proposal for human confirmation, not a decision made by
this document. Substantially smaller than the original gate — see
"What Changed" below for the reasoning behind each removal/narrowing.

### Must be true before *any* backend implementation begins

1. **PMR-07's narrowed spike** (tenant-isolation mechanism only, 3
   days) is Closed, with a recorded Accept/Revise/Reject recommendation
   feeding ADR-0002.
2. **ADR-0002's strategic direction** (modular monolith) and
   **ADR-0005** (authentication and authorization direction) are
   `Accepted` — the two most foundational decisions. Both are
   recommended for acceptance now (ADR-0005 fully; ADR-0002's strategic
   half — the tenant-isolation *pattern* detail waits on item 1 above).
3. PMR-05 (production `DEBUG` value) is Closed, regardless of migration
   timing — unchanged from the original gate, this should not remain
   open while any other planning proceeds.

**Explicitly removed from this gate** (see "What Changed" below):
PMR-09 (per-module execution plan) is no longer a precondition for
*starting* implementation — it gates the *first module's* work
specifically, tracked below instead.

### Must be true before the first module's implementation work begins

1. PMR-09 (per-module execution plan) exists and is approved for at
   least the first migration wave — this is genuinely sequenced after
   the spike/ADR-0002, but does not block earlier scaffolding/setup work
   (environment, CI, project structure) from starting in parallel.

### Must be true before any module's API surface is cut over to production traffic

1. PMR-02 (Flutter contract) is Closed for that module's flows
   specifically — a module with no client-facing surface (e.g. an
   internal batch job) may be exempt from this condition; note the
   exemption explicitly if claimed.
2. PMR-10 (differential-testing harness) is validated against that
   module specifically, not just the spike's vertical slice.
3. **New 2026-08-04**: that module's equivalent legacy security findings
   (per `docs/migration/consolidated-task-matrix.md` Section A) have
   their acceptance-criteria test passing — the reframed, non-blocking
   treatment of security findings still requires them closed before
   *that specific module* ships, just not before implementation starts.

### Must be true before the data-migration/cutover phase begins

1. **Narrowed 2026-08-04**: PMR-03's fresh-production-snapshot
   re-verification is Closed (the schema/data-quality analysis itself is
   already substantially complete against the available dump — this is
   specifically re-confirming those findings against current production
   state close to cutover time).
2. ADR-0004 (MySQL-To-PostgreSQL Migration Approach) is `Accepted` —
   recommended for a decision now, informed by this session's real
   analysis.

### Must be true before attendance-hardware integration begins

1. PMR-04's vendor-specific validation (not the architecture, which is
   done) is Closed.
2. ADR-0006's final vendor-specific direction is `Accepted` (the
   adapter/SPI architectural pattern itself is recommended for
   acceptance now, independent of this).

### Explicitly does not block the spike or early implementation work

The narrowed PMR-07 spike does not require PMR-01, PMR-02, PMR-03, or
PMR-04 closed first — same as originally designed. **New 2026-08-04**:
initial project scaffolding (environment setup, CI wiring, the testing
stack, observability baseline — all the hypotheses moved out of the
spike) also does not require the spike to complete first; it can proceed
in parallel, since none of it depends on the tenant-isolation-pattern
decision specifically.

## What Changed In This Revision (2026-08-04)

This gate shrank substantially from its original form. Summary of every
change and why:

- **Spike narrowed 10 days → 3 days**, 6 hypotheses → 1. Removed
  hypotheses are mature/low-risk technology adopted directly during
  implementation instead of pre-validated in isolation.
  (`docs/migration/technical-spike-plan.md`)
- **Security findings removed as a blocker entirely**, reframed as
  per-module acceptance criteria. Legacy bugs get fixed once, correctly,
  in the new module that replaces the buggy legacy one — not before
  that module's work starts. (`docs/migration/consolidated-task-matrix.md`)
- **7 of 9 ADRs recommended for immediate decision/acceptance**, using
  evidence gathered this session (real Flutter contracts, real DB
  analysis, the recorded dashboard/desktop and auth decisions) instead
  of waiting on the spike. Only ADR-0002's tenant-isolation detail and
  ADR-0006's final vendor direction remain genuinely dependent on
  external work.
- **PMR-03 (production data) substantially closed** using the available
  local schema/data dump — not full production access, but real
  customer data, not inference. Only a fresh-snapshot re-verification
  remains, timed to cutover, not blocking now.
- **PMR-04 (device Discovery) split**: the vendor-neutral architecture
  is designed and ready now; only final vendor-specific validation
  remains genuinely blocked on hardware access.
- **GCP/Firebase Console verification (`hr-platform#24`) removed from
  any gate** — explicitly non-blocking, a later infrastructure task.
- **Auth backward-compatibility decided**: forced re-authentication,
  removing the "which approach" open question entirely.

## Evidence

Every gap above is drawn from direct Discovery work across this
project's sessions: `docs/api/existing-endpoint-inventory.md`,
`docs/legacy/business-rule-extraction.md`,
`docs/legacy/existing-php-module-inventory.md`, `docs/security/threat-model.md`,
`docs/migration/database-schema-inventory.md`,
`docs/migration/migration-strategy-and-sequencing.md`, all 9 ADRs in
`docs/adr/`, `docs/devices/` (including the new
`device-integration-architecture.md`), `docs/tools/tool-catalog.md`,
`docs/tools/tool-decision-matrix.md`. This 2026-08-04 revision
additionally draws on: `docs/api/flutter-request-response-compatibility.md`
and `docs/api/three-frontend-api-usage-matrix.md` (real Flutter contract
evidence); `docs/migration/data-quality-analysis.md` and its siblings
(`duplicate-business-key-analysis.md`, `orphan-reference-analysis.md`,
`invalid-date-analysis.md`, `character-set-and-collation-analysis.md`,
`table-volume-analysis.md`, `tenant-boundary-verification.md`,
`sequence-and-identity-mapping.md` — real schema+data analysis against a
throwaway, isolated Docker MySQL container loaded from the local dump,
destroyed after analysis, no customer data reproduced);
`docs/security/authentication-remediation-design.md` and
`docs/migration/cutover-and-rollback-assumptions.md` (the confirmed
forced-re-authentication decision); `docs/migration/consolidated-task-matrix.md`
(the security-findings reframing and the full finding-by-finding
tracking); `docs/migration/technical-spike-plan.md`'s Revision Summary.
GitHub issue numbers confirmed against the live `workin-hr/hr-platform`
and `workin-hr/hr-legacy` issue trackers at the time of writing.
