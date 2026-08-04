# Migration Strategy And Sequencing

## Status

This document synthesizes the Discovery evidence gathered across
`workin-hr/hr-legacy` (all 199 API endpoints, the highest-risk dashboard
modules, and the full database schema) into a proposed migration approach.
It does not itself constitute an approved architecture decision — ADR-0002
(Modular Monolith Baseline) and ADR-0004 (MySQL-To-PostgreSQL Migration
Approach) remain `Proposed`, and any sequencing or strategy choice below
needs to be ratified through the normal decision-log/ADR process, not
adopted by virtue of appearing in this document.

## What This Unblocks

ADR-0005 (renamed "Authentication Direction" 2026-08-04 — its former
authorization scope now lives in `docs/adr/ADR-0010-authorization-model.md`)
explicitly states it requires `docs/legacy/business-rule-extraction.md`
and `docs/legacy/production-behavior-evidence.md` to cover current
identity flows before it can move toward `Accepted`. That evidence now
exists: the full login/OTP/session lifecycle for both `employee` and
`company` auth types, the `hr_permissions` model, and the desktop/HR
login path are all documented, along with the concrete failure modes
found in each (see the "Findings Carried Into The Rewrite" table
below). This does not decide ADR-0005 — it removes the stated evidence
blocker. The `hr_permissions` model specifically now feeds
ADR-0010's authorization-model dimensions instead.

Similarly, ADR-0004's "Validation Evidence" section lists the full
`docs/migration/` template set as a prerequisite. Status per template:

| Template | Status |
|---|---|
| `database-schema-inventory.md` | Populated (all 42 tables, full constraint read) |
| `mysql-views-inventory.md` | Populated this pass — confirmed zero views exist |
| `mysql-events-inventory.md` | Populated this pass — confirmed zero events exist |
| `stored-procedure-and-function-inventory.md` | Populated this pass — confirmed zero stored procedures/functions exist |
| `trigger-inventory.md` | Populated this pass — confirmed zero triggers exist |
| `data-quality-analysis.md` | Still open — requires querying the real data dump (see below) |
| `duplicate-business-key-analysis.md` | Still open — same |
| `orphan-reference-analysis.md` | Still open — same |
| `character-set-and-collation-analysis.md` | Partially answerable from schema alone (the `configs` table collation mismatch is already documented in `database-schema-inventory.md`); full analysis still needs data |
| `table-volume-analysis.md` | Partially answerable from `AUTO_INCREMENT` watermarks (already in `database-schema-inventory.md`); real volume/growth-rate analysis needs data |
| `sequence-and-identity-mapping.md` | Mechanically derivable from the schema (every table's `id` is `int(10) UNSIGNED AUTO_INCREMENT`) — low effort, not yet written up as its own artifact |
| `invalid-date-analysis.md` | Still open — requires querying real data for `0000-00-00`/zero dates |
| `migration-validation-queries.md` | Still open — needs both schemas finalized before reconciliation queries can be written |
| `cutover-and-rollback-assumptions.md` | Still open — needs a chosen migration pattern (see Sequencing Proposal below) before RTO/RPO assumptions are meaningful |

The four confirmed-zero templates were checked by grepping the full
`mysql_workin.schema.sql` structure export for `CREATE (VIEW\|EVENT\|
PROCEDURE\|FUNCTION\|TRIGGER)` — zero matches. This is a genuinely
simplifying fact for migration: there is no MySQL-side business logic to
port, everything lives in PHP.

**Why the remaining templates are still open, deliberately:** they require
querying the real production data dump (`mysql_workin.data.sql` in the
`hr-legacy` checkout), which was intentionally never touched during this
Discovery pass — it's git-ignored, local-only, and contains real customer
data, consistent with this repository's own boundary
(`CLAUDE.md`: "Claude must not request or store production credentials or
customer-sensitive data"). Whoever has legitimate DB access should run the
data-dependent checks; this document records what to check, not the
results.

## Open Item: No ADR Designates A Backend Language

Grepped all 8 existing ADRs — none specify Java or any other backend
language. This was flagged once before, during the D-009–D-012 decision-log
review, when a citation of ADR-0002 as justification for Java was corrected
because ADR-0002 only covers architecture style (modular monolith vs.
distributed), not language choice. The direction to use Java for the
rewrite has now been stated directly, but is not recorded in any ADR. This
document does not create that ADR — flagging it as something worth a
dedicated decision-log entry / ADR before it becomes load-bearing for
downstream planning (module boundaries, build tooling, CI, hiring/staffing
assumptions all depend on this being formally recorded, not just known).

## Findings Carried Into The Rewrite

Every security and business-rule finding from this Discovery pass has a
tracked GitHub issue in `workin-hr/hr-legacy`. This table is the bridge
from "known bug in the legacy system" to "acceptance criterion for the new
Java module" — the new system should be verifiably free of each of these,
not just "hopefully better."

| # | Finding | Severity | Java-module requirement |
|---|---|---|---|
| [#2](https://github.com/workin-hr/hr-legacy/issues/2) | Any HR user can delete/view any company platform-wide | CRITICAL | Admin-only operations gated by an explicit admin role check at the framework level, not an exclusion of one other role |
| [#3](https://github.com/workin-hr/hr-legacy/issues/3) | Cross-tenant employee password takeover | CRITICAL | Every mutation of an employee record verifies tenant ownership at the service/repository layer, not per-controller |
| [#4](https://github.com/workin-hr/hr-legacy/issues/4) | OTP returned in response under DEBUG | CRITICAL (live status unconfirmed) | Never return verification codes in API responses under any config flag |
| [#5](https://github.com/workin-hr/hr-legacy/issues/5) | Cross-tenant IDOR, API `advances` | HIGH | Shared tenant-scoping mechanism for every financial-record mutation |
| [#6](https://github.com/workin-hr/hr-legacy/issues/6) | Cross-tenant IDOR, 10 dashboard modules | HIGH | Same mechanism as above, applied uniformly — this is the single clearest argument for making tenant scoping structural rather than per-endpoint |
| [#7](https://github.com/workin-hr/hr-legacy/issues/7) | 10-year JWT, no admin token revocation | HIGH | Realistic token lifetime + refresh rotation; session invalidation on credential change for both auth types |
| [#8](https://github.com/workin-hr/hr-legacy/issues/8) | `hr_permissions` enforced on ~21 of 150+ endpoints | MEDIUM | If kept, enforce via a shared interceptor/annotation, not per-endpoint discipline |
| [#9](https://github.com/workin-hr/hr-legacy/issues/9) | Unauthenticated registration-completion, guessable ID | MEDIUM | Possession token required across multi-step public flows |
| [#10](https://github.com/workin-hr/hr-legacy/issues/10) | No OTP rate limiting | MEDIUM | Attempt counter + lockout on verification, not just issuance |
| [#11](https://github.com/workin-hr/hr-legacy/issues/11) | Single shared admin password | LOW / product decision | Per-admin accounts + MFA + audit log — flag for explicit product discussion |
| [#12](https://github.com/workin-hr/hr-legacy/issues/12) | Daily-wage payslip creation drops base pay | HIGH | One shared payroll-calculation service covering both salary modes |
| [#13](https://github.com/workin-hr/hr-legacy/issues/13) | 3 divergent payslip-total implementations | MEDIUM | Same shared service as above — no endpoint re-derives payroll math inline |
| [#14](https://github.com/workin-hr/hr-legacy/issues/14) | `housing_allowance` unsettable everywhere | MEDIUM / product decision | Needs product input on intended behavior before implementing |
| [#15](https://github.com/workin-hr/hr-legacy/issues/15) | Logout deactivates employee account | HIGH / product decision | Needs product confirmation before deciding rewrite behavior |
| [#16](https://github.com/workin-hr/hr-legacy/issues/16) | QR check-in skips 2-hour gap rule | MEDIUM | One shared check-in service enforcing the rule uniformly across methods |
| [#17](https://github.com/workin-hr/hr-legacy/issues/17) | Manager unscoped in attendance | MEDIUM | Shared query-layer branch-scoping for manager role |
| [#18](https://github.com/workin-hr/hr-legacy/issues/18) | Manager unscoped in request approve/reject | MEDIUM / product decision | Needs decision on intended manager authority scope |
| [#19](https://github.com/workin-hr/hr-legacy/issues/19) | Two divergent self-registration endpoints | MEDIUM / product decision | Needs mobile-client owner to confirm which flow is canonical |
| [#20](https://github.com/workin-hr/hr-legacy/issues/20) | Employee delete cascades payroll history | LOW / product decision | Consider soft-delete/archival for financial-history-bearing records |
| [#21](https://github.com/workin-hr/hr-legacy/issues/21) | Payroll batch uniqueness is app-level only | LOW | Real DB unique constraint in the new schema |
| [#22](https://github.com/workin-hr/hr-legacy/issues/22) | Batch calculate not transactional | LOW | Wrap in a transaction like finalize/reopen |
| [#23](https://github.com/workin-hr/hr-legacy/issues/23) | "CSV" export is actually XLSX | LOW | Name/implement exports to match what they actually produce |
| [#24](https://github.com/workin-hr/hr-legacy/issues/24) | Mislabeled stats response fields | LOW | Compute correctly or remove; don't ship placeholder values |
| [#25](https://github.com/workin-hr/hr-legacy/issues/25) | Bulk attendance delete has no safety net | LOW | Dry-run/soft-delete/audit trail given financial-data blast radius |

Items marked "product decision" need a human answer before the Java module
can be built correctly — they aren't pure bugs with an obvious fix, and
guessing would just relocate the ambiguity into the new system.

## Sequencing Proposal

Not a final decision — a starting point for discussion, grounded in what
Discovery actually found rather than assumed module priority:

1. **Tenant/identity model first.** Nearly every High/Critical finding
   traces back to missing or inconsistent tenant scoping (#2, #3, #5, #6)
   or session lifecycle (#7). Building the new system's tenant-isolation
   and auth primitives correctly — and structurally, so a single omitted
   check can't reproduce #6's 10-module blast radius — has to happen
   before any business module is built on top of it, or every module
   built early inherits the risk of getting scoping wrong again.
2. **Payroll/payslips/salary_contracts/advances/penalties next**, as one
   coordinated group — not module-by-module. These are the modules with
   the worst correctness bugs (#12, #13, #14, #21, #22) *and* the worst
   security bugs (#5), and they're tightly coupled (payroll reads from
   salary_contracts, advances, and penalties; payslips duplicate payroll's
   math independently three times today per #13). Rebuilding them
   piecemeal risks recreating the same "shared logic reimplemented per
   endpoint" pattern that caused #12/#13 in the first place.
3. **Attendance third** — by far the largest table (~5.5x the next
   largest per the schema inventory) and feeds directly into payroll's
   absence/day-rate calculation, so it needs to be correct before payroll
   can be trusted, but its own bugs (#16, #17, #25) are lower severity
   than the payroll group and it has fewer cross-module dependents than
   the tenant/identity layer.
4. **Employees/profile/requests/leave_balances/workforce_planning** as a
   mid-priority group — real findings (#15, #18, #19, #20) but mostly
   product-decision-gated rather than pure engineering risk; can proceed
   in parallel with attendance once the tenant/identity layer is stable.
5. **Everything else last** — the reference/lookup modules
   (job_titles, departments, shifts, branches, company_settings,
   notifications, and the 1-file content modules) had no findings beyond
   the systemic tenant-scoping pattern already covered by fixing #6's
   root cause; standard CRUD, lowest risk, lowest priority.

The dashboard (`dashboard/`) is a separate codebase from the API with its
own auth and DB-access layer (confirmed in
`docs/legacy/production-behavior-evidence.md`) — whether the new system
keeps that split or unifies into one admin surface is an ADR-0002-adjacent
question, not decided here. Either way, dashboard-side findings (GitHub
issues 3, 6, and 11) need the same fixes as their API-side counterparts;
they are not lower priority just because they're in the other codebase.

## Evidence

Every finding referenced above is drawn from `workin-hr/hr-platform`
`docs/security/threat-model.md` and `docs/legacy/business-rule-extraction.md`,
themselves built from direct reads of `workin-hr/hr-legacy` commit
`83c326e40f68dd0d560595a6c4e465eb681f2ce8`. GitHub issue numbers confirmed
against the live `workin-hr/hr-legacy` issue tracker at the time of writing.
