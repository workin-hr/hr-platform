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

- **Description**: The real production data dump was deliberately never
  queried — git-ignored, local-only, real customer data, consistent with
  this repository's own governance boundary. `data-quality-analysis.md`,
  `duplicate-business-key-analysis.md`, `orphan-reference-analysis.md`,
  and `invalid-date-analysis.md` remain fully open;
  `character-set-and-collation-analysis.md` and `table-volume-analysis.md`
  are only partially answerable from schema structure alone.
- **Why It Matters**: `ADR-0004`'s Validation Evidence section explicitly
  lists this full template set as a prerequisite. Real data-quality
  issues, duplicate keys, orphaned references, invalid dates, and real
  volume/growth rates directly affect migration-pattern choice and
  cutover downtime — schema structure alone cannot answer these.
- **Risk Level / Migration Impact**: High. Blocks confident
  data-migration and cutover/rollback planning specifically.
- **Blocks**: The data-migration and cutover-planning portion — not
  early architecture/backend spike work that doesn't touch real data.
- **Required Evidence To Close**: A human with legitimate production DB
  read access runs the specific queries already outlined per-template in
  `docs/migration/migration-strategy-and-sequencing.md` and the
  individual template files, and records real findings.
- **Owner**: TBD — requires a human with production DB access. Must not
  be an agent, per `CLAUDE.md`'s credential-handling boundary.
- **Dependencies**: Production database read access being granted.
- **Target / Duration**: TBD — requires human assignment.
- **Exit / Acceptance Criteria**: All listed templates populated with
  real, cited findings; `ADR-0004`'s Validation Evidence checklist fully
  satisfied.
- **Status**: Blocked — on production data access.
- **Related**: ADR-0004; `docs/migration/database-schema-inventory.md`;
  `workin-hr/hr-platform#11`.

### PMR-04: Attendance Device/Hardware Discovery Not Started

- **Description**: `docs/devices/attendance-device-model-and-firmware-inventory.md`
  and `docs/devices/vendor-capability-matrix.md` remain empty templates.
  Device vendor, firmware, integration protocol, and connectivity pattern
  are all unknown.
- **Why It Matters**: `ADR-0006` cannot move past "candidate direction
  pending vendor and device discovery" without this. The current `.NET`
  edge-gateway direction in `docs/tools/tool-decision-matrix.md` is
  explicitly marked as depending on device discovery, which has not
  happened.
- **Risk Level / Migration Impact**: High for attendance-hardware
  integration specifically.
- **Blocks**: `ADR-0006` and any attendance-hardware-integration module —
  not the rest of migration.
- **Required Evidence To Close**: Device vendor identification, protocol
  documentation, firmware version inventory, and confirmed integration
  pattern, per the existing empty templates' own field structure.
- **Owner**: TBD — requires human assignment, likely with physical
  device or vendor-contact access.
- **Dependencies**: Access to device vendor documentation, physical
  devices, or vendor contacts.
- **Target / Duration**: TBD — requires human assignment.
- **Exit / Acceptance Criteria**: Both device templates populated with
  real evidence; `ADR-0006`'s Validation Evidence requirement satisfied.
- **Status**: Blocked — on device/vendor access.
- **Related**: ADR-0006; `workin-hr/hr-platform#12`.

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
- **Status**: Blocked — awaiting human decisions. Already tracked via 5
  existing issues; no new issue created for this roll-up.
- **Related**: `workin-hr/hr-legacy#11`, `#14`, `#15`, `#18`, `#19`.

### PMR-07: Target Technology Stack Unvalidated Hands-On

- **Description**: Java 25, Spring Boot 4.x, PostgreSQL, Flyway, and
  Next.js are chosen in `docs/tools/tool-catalog.md`/`tool-decision-matrix.md`.
  Three candidate additions were recommended after this Discovery pass —
  Keycloak, springdoc-openapi, and object storage compatible with the S3
  API — but none of it has been installed or prototyped against this
  project's real shape (the EAV settings system, the polymorphic
  `notifications` reference,
  the multi-tenant model, the repeated tenant-isolation bug class).
- **Why It Matters**: There is currently zero hands-on evidence any of
  these choices survive contact with this codebase's real complexity.
- **Risk Level / Migration Impact**: High. Blocks full-scale
  implementation start.
- **Blocks**: Full-scale implementation — not further Discovery or
  documentation work.
- **Required Evidence To Close**: Execution of
  `docs/migration/technical-spike-plan.md`'s vertical-slice spike, with
  recorded hypotheses, experiment results, and an explicit
  Accept/Revise/Reject recommendation per relevant ADR.
- **Owner**: TBD — requires human assignment (Solution Architect plus
  implementation agent under supervision).
- **Dependencies**: None blocking — the spike plan is ready to execute
  once approved.
- **Target / Duration**: Time-boxed per the spike plan (see
  `docs/migration/technical-spike-plan.md`) — requires human
  confirmation before starting.
- **Exit / Acceptance Criteria**: Defined precisely in the spike plan's
  exit-criteria section.
- **Status**: Ready — plan exists, awaiting human approval to execute.
- **Related**: `docs/migration/technical-spike-plan.md`; ADR-0002,
  ADR-0003, ADR-0004, ADR-0005, ADR-0007, ADR-0008;
  `workin-hr/hr-platform#13`.

### PMR-08: All 8 Architecture ADRs Remain Proposed

- **Description**: None of `ADR-0001` through `ADR-0008` have moved to
  `Accepted`. Several have Validation Evidence requirements now closer
  to satisfied by this Discovery pass (notably ADR-0004, ADR-0005), but
  none have been formally reviewed and accepted.
- **Why It Matters**: Implementing against a `Proposed` ADR as if it
  were `Accepted` risks rework if a later formal review revises the
  direction — this repository's own governance model treats the
  distinction as load-bearing.
- **Risk Level / Migration Impact**: Medium — process/governance risk.
- **Blocks**: Full-scale implementation start.
- **Required Evidence To Close**: Human engineering leadership (each
  ADR's own Deciders field) reviews the Validation Evidence section of
  each ADR and records Accept/Reject/Revise in
  `docs/bootstrap/decision-log.md`.
- **Owner**: TBD — human engineering leadership, per each ADR's Deciders
  field.
- **Dependencies**: PMR-07 (spike) feeds evidence into several ADRs'
  acceptance (notably ADR-0002, ADR-0007, ADR-0008). PMR-02, PMR-03,
  PMR-04 feed ADR-0003, ADR-0004, ADR-0006 respectively.
- **Target / Duration**: TBD — requires human assignment.
- **Exit / Acceptance Criteria**: Every ADR's Status is updated to
  `Accepted`, `Rejected`, or `Superseded`, with a decision-log citation.
- **Status**: Blocked — awaiting spike results (PMR-07) and human
  review.
- **Related**: All 8 ADRs; `docs/bootstrap/decision-log.md`;
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
- **Status**: Blocked — on PMR-07 and PMR-08.
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
- **Status**: Blocked — on PMR-07 and a target schema existing.
- **Related**: ADR-0007; `docs/migration/migration-validation-queries.md`;
  `workin-hr/hr-legacy#12`, `#13`; `workin-hr/hr-platform#16`.

## Prioritized Gap/Task Matrix

| ID | Gap | Risk | Blocks | Status | Depends On | GitHub Issue |
|---|---|---|---|---|---|---|
| PMR-05 | Production `DEBUG` value unconfirmed | Critical | Live security, not migration | Blocked | Production access | `hr-legacy#4` |
| PMR-07 | Tech stack unvalidated hands-on | High | Full-scale implementation start | Ready | None | `hr-platform#13` |
| PMR-03 | Production data inaccessible | High | Data-migration/cutover planning | Blocked | DB access | `hr-platform#11` |
| PMR-04 | Device/hardware Discovery not started | High | ADR-0006, device modules | Blocked | Vendor/device access | `hr-platform#12` |
| PMR-10 | No correctness test plan/harness | High | Production cutover | Blocked | PMR-07, target schema | `hr-platform#16` |
| PMR-01 | Dashboard Discovery incomplete | Medium-High | Dashboard modules | Ready | None | `hr-platform#9` |
| PMR-02 | Flutter client contract — depth remaining | Medium (was Critical/High) | Full per-endpoint contract confirmation, not the whole migration | Ready | None (access resolved 2026-08-04) | `hr-platform#10` |
| PMR-08 | ADRs still Proposed | Medium | Full-scale implementation start | Blocked | PMR-07, human review | `hr-platform#14` |
| PMR-06 | Open product/business decisions | Medium | Specific modules | Blocked | Human decisions | `hr-legacy#11,14,15,18,19` |
| PMR-09 | No per-module execution plan | Medium | Implementation start per module | Blocked | PMR-07, PMR-08 | `hr-platform#15` |

Read this table as: **PMR-07 (the technical spike), PMR-01 (dashboard
Discovery), and PMR-02 (Flutter contract depth, as of 2026-08-04) are
`Ready` today with no external dependency** — everything else either
needs a human decision/access grant, or is intentionally sequenced after
the spike's results exist. All three `Ready` gaps can proceed in
parallel, independently of each other.

## Migration-Readiness Gate

**Implementation must not begin until every condition below is true.**
This gate is a proposal for human confirmation, not a decision made by
this document.

### Must be true before *any* backend implementation begins

1. PMR-07 (technical spike) is Closed, with a recorded recommendation
   (Accept/Revise/Reject) for each of ADR-0002, ADR-0005, ADR-0007, and
   ADR-0008.
2. ADR-0002 (Modular Monolith Baseline) and ADR-0005 (Authentication And
   Authorization Direction) are `Accepted` — these are the two most
   foundational decisions everything else depends on.
3. PMR-05 (production `DEBUG` value) is Closed, regardless of migration
   timing — this should not remain open while any other planning
   proceeds.
4. PMR-09 (per-module execution plan) exists and is approved for at
   least the first migration wave.

### Must be true before any module's API surface is cut over to production traffic

1. PMR-02 (Flutter contract) is Closed for that module's flows
   specifically — a module with no client-facing surface (e.g. an
   internal batch job) may be exempt from this condition; note the
   exemption explicitly if claimed.
2. PMR-10 (differential-testing harness) is validated against that
   module specifically, not just the spike's vertical slice.

### Must be true before the data-migration/cutover phase begins

1. PMR-03 (production data Discovery) is Closed.
2. ADR-0004 (MySQL-To-PostgreSQL Migration Approach) is `Accepted`.

### Must be true before attendance-hardware integration begins

1. PMR-04 (device Discovery) is Closed.
2. ADR-0006 (Attendance Edge-Gateway Direction) is `Accepted`.

### Explicitly does not block spike execution

PMR-07 itself (the spike) does not require PMR-01, PMR-02, PMR-03, or
PMR-04 to be closed first — the proposed vertical slice (tenant/company
identity plus one reference-data endpoint) is deliberately chosen to be
independent of Flutter, production data, and attendance devices. See
`docs/migration/technical-spike-plan.md`.

## Evidence

Every gap above is drawn from direct Discovery work in this session:
`docs/api/existing-endpoint-inventory.md`, `docs/legacy/business-rule-extraction.md`,
`docs/legacy/existing-php-module-inventory.md`, `docs/security/threat-model.md`,
`docs/migration/database-schema-inventory.md`,
`docs/migration/migration-strategy-and-sequencing.md`, all 8 ADRs in
`docs/adr/`, `docs/devices/`, `docs/tools/tool-catalog.md`, and
`docs/tools/tool-decision-matrix.md`. GitHub issue numbers confirmed
against the live `workin-hr/hr-platform` and `workin-hr/hr-legacy` issue
trackers at the time of writing.
