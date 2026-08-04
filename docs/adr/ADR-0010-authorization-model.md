# ADR-0010: Authorization Model

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0010 |
| Title | Authorization Model |
| Status | Proposed |
| Date | 2026-08-04 |
| Owners | Solution Architect |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | `hr-legacy#2`, `#3`, `#5`, `#6`, `#8`, `#17`, `#18` |
| Supersedes | The authorization half of the original "Authentication And Authorization Direction" scope (see `docs/adr/ADR-0005-authentication-direction.md`'s Scope Correction) |
| Superseded By | None |

## Context

Split out of `docs/adr/ADR-0005-authentication-direction.md` on
2026-08-04 because authentication (proving who a caller is) and
authorization (what that caller may do) are different concerns with
different failure modes and, in `hr-legacy`, different confirmed bug
classes. **This ADR is genuinely open — it frames the decision space
with real evidence, it does not resolve it.** No authorization-model
decision has been made in any conversation to date; do not treat
anything below as decided.

`hr-legacy`'s current authorization model, as directly confirmed by
Discovery this session and earlier:

- **Role model**: `UserRoleEnum` defines four roles —
  `company_admin`, `hr`, `manager`, `employee`
  (`apis/config/enums.php`). The dashboard additionally has a
  platform-level `admin` session type with no company scope at all
  (`dashboard/includes/auth.php`'s `doAdminLogin()`).
- **Granular permissions**: a separate `hr_permissions` table carries
  **18 hardcoded boolean columns** per HR-role employee (`can_dashboard`,
  `can_branches`, `can_departments`, `can_job_titles`, `can_shifts`,
  `can_leave_balances`, `can_assets`, `can_advances`,
  `can_workforce_planning`, `can_salary_calculator`,
  `can_company_settings`, `can_employees`, `can_attendance`,
  `can_requests`, `can_payroll`, `can_penalties`,
  `can_recent_activities`, and one more — 18 total, confirmed directly
  from the schema, 2026-08-04).
- **Confirmed enforcement gap** (`hr-legacy#8`): this permission matrix
  is actually checked on roughly 21 endpoints and silently ignored on
  the other 130+ — a per-endpoint opt-in pattern, not a structural
  guarantee.
- **Confirmed tenant-isolation gaps** (`hr-legacy#2`, `#3`, `#5`, `#6`):
  several endpoints/pages fail to verify that a requested resource
  belongs to the caller's own company at all — a more basic failure
  than fine-grained permission checking, but the same structural root
  cause (authorization checks that depend on every individual endpoint
  remembering to apply them, rather than being enforced by the
  architecture itself).
- **Confirmed scope-inconsistency findings** (`hr-legacy#17`, `#18`):
  the `manager` role's actual enforced scope (company-wide for
  attendance visibility and request approval) doesn't match what
  doc-comments in the legacy code claim (branch-scoped) — evidence that
  the current model's *intended* scope and *enforced* scope have
  already drifted apart once.

## Decision

**Approval status: Proposed — this decision has not been approved.
Not yet decided in substance either** — unlike most other ADRs in this
repository, this one is not "a candidate direction awaiting sign-off,"
it is genuinely open on every dimension below. This ADR exists to make the decision space
explicit and evidence-grounded, per the six dimensions below, not to
choose an answer. `Status` remains `Proposed`; nothing in this ADR
should be implemented against until it moves to `Accepted` by a human
decider following the same review process as every other ADR in this
repository.

### Dimension 1 — Platform-admin, tenant-admin, and employee scopes

`hr-legacy` already has three de facto scope tiers (platform `admin`,
company `company_admin`/`hr`/`manager`, and `employee`), confirmed by
the dashboard's three distinct login functions and `UserRoleEnum`. Open
question: does the new system keep exactly these three tiers, or
does it need a finer-grained scope model (e.g. separating
`company_admin` from `hr` more formally, given `docs/adr/ADR-0009-dashboard-vs-desktop-admin-client.md`
already treats "platform" and "tenant/company" as structurally distinct
client-channel concerns)?

### Dimension 2 — Tenant-membership validation

How does the system verify that an authenticated caller's claimed
`company_id` (or resolved employee → company relationship) is real and
current on every request — the exact mechanism this ADR needs to define
is the authorization-layer half of the same problem
`docs/adr/ADR-0002-modular-monolith-baseline.md`'s Part B (H2 spike:
RLS vs. repository-guard) addresses at the data layer.

**Update 2026-08-05**: the H2 spike this dimension depended on has now
been executed for real, with a recorded recommendation — RLS as the
primary mechanism, on the explicit condition of a dedicated
non-superuser application database role (a real bug was found and
fixed during the spike: RLS silently provides zero protection when the
connecting role is a Postgres superuser). Full findings:
`spike/tenant-isolation-spike/SPIKE-NOTES.md`. **This dimension is now
informed, not resolved** — ADR-0002 Part B itself still requires human
acceptance before this dimension can be considered decided, and even
once accepted, this dimension still needs its own answer for
*tenant-membership validation specifically* (not just data-row
filtering): if RLS is accepted, the natural approach is that the same
per-transaction session-variable mechanism the spike proved
(`SET LOCAL app.current_company_id`, set once per request from the
validated JWT claim) becomes the tenant-membership-validation
mechanism too — this is a reasonable direction, not yet a decision.

### Dimension 3 — Roles and permissions

Whether the new system keeps an `hr_permissions`-style explicit
boolean-flag-per-capability table (18 flags today, confirmed above), a
smaller fixed role set with implied permissions (matching
`UserRoleEnum`'s four roles more directly), or a hybrid, is undecided.
**One constraint is already fixed, not open**: whichever model is
chosen must be **structurally enforced**, not per-endpoint opt-in —
this is already a mandatory acceptance criterion carried forward from
`hr-legacy#8` (see `docs/migration/consolidated-task-matrix.md`,
row for `#8`), independent of which specific model this ADR eventually
picks.

### Dimension 4 — Authorization enforcement boundaries

Where should authorization checks structurally live — a
framework-level interceptor/aspect (e.g. Spring Security method
security), a query/repository-layer guard (consistent with whichever
answer Dimension 2 lands on), a service-layer check, or some
combination? `hr-legacy#8`'s finding (checks present on ~21 endpoints,
absent on 130+) is direct evidence for *why* a single, structurally
enforced boundary matters — it is not evidence for *which* boundary to
choose.

### Dimension 5 — Immediate effect of permission or role changes

If a caller's role/permission changes (e.g. an HR employee is demoted,
or has a specific `hr_permissions` flag revoked), does that take effect
**immediately** on their next request, or only after their next token
refresh/re-login? This is a direct consequence of Dimension 6 below —
token-embedded claims are only as fresh as the token's own lifetime
(bounded by `docs/adr/ADR-0005-authentication-direction.md`'s
short-lived-access-token direction, but not instant), while
server-side-loaded authorization data can reflect a change on the very
next request. **Not yet decided which trade-off this system accepts.**

### Dimension 6 — Authorization data: embedded in tokens or loaded server-side

The central open design question, and the one Dimension 5 depends on:

- **Token-embedded**: role/permission claims are baked into the JWT
  access token at issuance. Cheap to check (no extra lookup per
  request), but stale until the token is refreshed — directly in
  tension with Dimension 5's "immediate effect" concern.
- **Server-side-loaded**: the access token carries only identity
  (who), and role/permission data is loaded (and cached, if needed) on
  each request from a server-side source of truth. Always current, at
  the cost of a lookup (or cache-invalidation design) per request.
- **Relevant existing constraint, not a predetermined answer**:
  `docs/adr/ADR-0005-authentication-direction.md` already commits to
  server-side-tracked refresh sessions (for revocation) — meaning the
  system already pays for a server-side session lookup on refresh.
  Whether authorization data rides along with that same mechanism, or
  is checked independently and more frequently (e.g. per access-token
  use, not just per refresh), is what this dimension needs to decide.

## Alternatives Considered

Not yet — alternatives will be enumerated once the six dimensions above
have enough evidence/discussion to narrow toward candidate models, per
this repository's evidence-driven ADR practice. Listing alternatives
prematurely would risk anchoring on an unexamined default.

## Consequences

Not yet assessable — depends on which model is chosen.

## Risks

- **Risk of choosing the model before ADR-0002 Part B is actually
  accepted**: the H2 spike now has a real recommendation (2026-08-05),
  but Part B remains formally `Proposed` until a human decider accepts
  it. Designing Dimension 2 as if RLS were already the accepted
  mechanism risks a mismatch if Part B is instead revised or rejected.
- **Risk of repeating `hr-legacy#8`'s failure mode**: choosing a model
  in the abstract without a structural-enforcement mechanism (Dimension
  4) would reproduce the exact "checked on some endpoints, forgotten on
  others" pattern this ADR exists to avoid.
- **Risk of over-scoping**: this ADR's six dimensions could expand into
  a much larger identity-and-access-management project than an MVP
  needs. Keep the eventual decision scoped to what `hr-legacy`'s real,
  confirmed role/permission complexity requires (four roles, one
  18-flag permission table) rather than designing for hypothetical
  future complexity.

## Validation Evidence

Real evidence exists for the *problem* (see Context above — role model,
permission table structure, the confirmed enforcement gap, the confirmed
scope-drift finding). No evidence yet exists for the *chosen model*,
because none has been chosen. This ADR cannot move to `Accepted` until:

1. A model is actually chosen for each of the 6 dimensions above, by a
   human decider.
2. `docs/adr/ADR-0002-modular-monolith-baseline.md` Part B is formally
   `Accepted` (a real recommendation now exists from the executed H2
   spike, 2026-08-05 — see `spike/tenant-isolation-spike/SPIKE-NOTES.md`
   — but acceptance itself is still pending), since it materially
   constrains Dimension 2's answer.

## Open Questions

- All six dimensions above are open. This list is intentionally the
  same as the Decision section — there is no additional hidden
  open question, and no dimension should be considered implicitly
  answered by omission.

## Evidence

`apis/config/enums.php` (`UserRoleEnum`); `hr_permissions` table schema
(`mysql_workin.schema.sql`, 18 boolean columns, confirmed directly
2026-08-04); `dashboard/includes/auth.php` (three login functions,
confirming the three-tier scope structure); `hr-legacy#2`, `#3`, `#5`,
`#6` (tenant-isolation gaps); `hr-legacy#8` (permission-enforcement
gap); `hr-legacy#17`, `#18` (Manager-role scope-drift findings);
`docs/adr/ADR-0002-modular-monolith-baseline.md` (Part B, the
tenant-isolation-mechanism dependency); `docs/adr/ADR-0005-authentication-direction.md`
(the authentication-side constraint this ADR's Dimension 6 references).
