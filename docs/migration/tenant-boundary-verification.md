# Tenant-Boundary Verification

New template (added 2026-08-04) — not in the original discovery set.
Tracks whether the **data itself** is internally tenant-consistent
(every multi-hop relationship agrees on which company a row belongs to),
as distinct from `docs/security/threat-model.md`'s findings about
**missing authorization checks** in the API/dashboard code. This
document answers "has cross-tenant data corruption already happened,"
not "can it happen" — those are different questions with different
evidence.

## Method

Measured directly, 2026-08-04, against the real schema + data dump
loaded into a throwaway Docker MySQL container (see
`docs/migration/data-quality-analysis.md` for the full method note).
For every table with **two independent paths to a `company_id`** (e.g.
a payslip's own employee's company vs. its batch's company), checked
whether those two paths ever disagree.

## Result: Zero cross-tenant inconsistencies found across 10 independent checks

| Check | Two independent paths compared | Mismatches found |
|---|---|---|
| `payslips` | employee → company vs. payroll batch → company | 0 |
| `requests` | employee → company vs. request type → company | 0 |
| `department_branches` | branch → company vs. department → company | 0 |
| `employees` | own `company_id` vs. assigned branch → company | 0 |
| `employees` | own `company_id` vs. assigned department → company | 0 |
| `employees` | own `company_id` vs. assigned job title → company | 0 |
| `notifications` | sender employee → company vs. notification's own `company_id` | 0 |
| `notifications` | recipient employee → company vs. notification's own `company_id` | 0 |
| `complaints` | employee → company vs. complaint's own `company_id` | 0 |
| `job_titles` | own `company_id` vs. assigned department → company | 0 |

## Interpretation — Important Distinction From The Code-Level Security Findings

`workin-hr/hr-legacy` has multiple **confirmed, code-level** cross-tenant
authorization gaps (`hr-legacy#2`, `#3`, `#5`, `#6` — an HR-role user or
API caller *can* reach another company's records because the
authorization check is missing). This tenant-boundary verification shows
that, **in this data snapshot, no row's own foreign keys actually point
across a tenant boundary** — i.e. the missing checks have not (yet, in
this snapshot) resulted in a company's `employees`/`branches`/etc. rows
being mis-assigned to the wrong company at the data level. Both facts
are real and both matter: the authorization gap is a live exploit path
regardless of this result, and this result is reassuring evidence that a
migration reading this data does not additionally need to detect and
repair already-corrupted cross-tenant data — the data hygiene is
consistent even though the access control around it was not.

**Do not read this as evidence the authorization bugs are safe to
deprioritize** — it only means the specific rows in this snapshot
haven't been visibly corrupted by them, not that the vulnerability
itself is less real. `hr-legacy#2/#3/#5/#6` remain Critical/High findings
regardless.

## Findings Requiring A Fresh Production Snapshot

This result is a point-in-time snapshot (dump date 2026-08-03). If any
of the confirmed authorization gaps have been actively exploited between
the dump date and an eventual cutover, a fresh snapshot re-run of these
same 10 checks would be the way to detect it — this analysis cannot see
anything that happened after the dump was taken.

## Operational Assumptions Still Requiring Confirmation

None — this is a direct, exhaustive, proven-from-data result for the
relationships checked. It does not cover every conceivable cross-tenant
path in the schema, only the ones with a genuine second independent
`company_id` path to compare against.

## Evidence

Loaded `mysql_workin.schema.sql` + `mysql_workin.data.sql` into a
throwaway Docker MySQL 8.0 container, ran the 10 cross-check queries
directly, container destroyed after analysis, 2026-08-04. Cross-referenced
against `docs/security/threat-model.md` (`hr-legacy#2/#3/#5/#6`). No raw
customer records reproduced — only mismatch counts.
