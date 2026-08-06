# Advances Module — First Slice Design (2026-08-07)

## Purpose And Authority

Second business module by the employees template
(`docs/superpowers/specs/2026-08-07-employees-module-first-slice-design.md`),
chosen because its F-18 negatives retire the risk class of the
**confirmed live cross-tenant IDOR** in legacy's advances module
(`hr-legacy#5`: approve/reject/pay/delete/create reachable across
tenants). Anchored to ADR-0010, the V12 schema (already merged), and
V4's three-key split for this domain: `advances.read`,
`advances.manage` (create/edit), `advances.approve`
(approve/reject/pay). Assigned by the repository owner 2026-08-07
("proceed" on the recommendation naming the advances module).

## Scope

**In** (`/api/tenant/advances`, all via the employees-template service
pattern — tenant session variable re-applied per transaction, explicit
`company_id` filters, §8's indistinguishable 404s):

- `GET` list + `GET /{id}` — `advances.read`.
- `POST` — `advances.manage`: `employeeId`, `amount` (positive),
  optional `reason`. The employee is looked up **tenant-scoped**: a
  nonexistent or other-company `employeeId` is the same 404
  (`hr-legacy#5`'s create shape). Created as `PENDING`,
  `remaining = amount`, `request_date` = server date; deduction columns
  keep their schema defaults.
- `POST /{id}/approve` — `advances.approve`: `PENDING → APPROVED`;
  non-pending → 409; cross-tenant → 404.
- `POST /{id}/reject` — `advances.approve`: optional `rejectionReason`;
  `PENDING → REJECTED`; same 409/404 rules.
- Read/manage/approve separation proven: `advances.manage` alone can
  create but not approve; `advances.read` alone can list but not
  create.

**Out (tracked, not dropped)**:

- "Pay"/deduction application — that is the payroll module's finalize
  side effect (`advances.remaining`, per
  `docs/legacy/business-rule-extraction.md`'s batch-finalize rule);
  the new schema deliberately has no `PAID` status.
- Update/edit and delete — edit semantics and deletion/retention need
  the same lifecycle discussion as employees (`hr-legacy#20`'s
  adjacency); nothing in this surface mutates an advance after a
  terminal state.
- Installments detail — V12's own comment parks the legacy
  `deduction_type` redundancy as a product decision before the
  business-logic slice; this slice never sets deduction fields.
- Employee self-service advance requests (mobile surface) — arrives
  with the employee/identity linkage.

## Design

Package `com.workin.backend.advances`: `AdvanceStatus` enum
(`PENDING`/`APPROVED`/`REJECTED`), `Advance` entity (maps `id`,
`employee_id`, `company_id`, `amount`, `remaining`, `reason`,
`rejection_reason`, `status`, `request_date` — deduction columns
unmapped, DB defaults apply), `AdvanceRepository`
(`findByCompanyIdOrderById`, `findByIdAndCompanyId`),
`AdvanceService`, `AdvanceController`, records
(`CreateAdvanceRequest(@NotNull Long employeeId, @NotNull @Positive
BigDecimal amount, String reason)`, `RejectAdvanceRequest(String
rejectionReason)`, `AdvanceView(id, employeeId, amount, remaining,
reason, rejectionReason, status, requestDate)`).

State transitions live in the entity (`approve()`, `reject(reason)`
throwing `IllegalStateException` if not `PENDING` — translated to 409
by the service, which checks status first). The service validates the
employee reference through `EmployeeRepository.findByIdAndCompanyId`
(tenant-scoped, RLS-backed).

## Testing

`AdvanceModuleFlowTest`: admin create→list→get→approve round-trip
(status/remaining asserted); reject with reason; approve/reject on
non-pending → 409; create with negative amount → 400; create with
other-company employee → 404 (the `hr-legacy#5` create shape); cross-
tenant get/approve/reject → 404; B's list excludes A's advances;
manage-without-approve → 403 on approve; read-without-manage → 403 on
create; unauthenticated → non-2xx.

## Consequences

`hr-legacy#5`'s operation class (minus pay/delete, which don't exist
yet by design) is covered by structural tenant isolation with named
tests, and the module template is confirmed reusable — the remaining
payroll-group modules (penalties, payslips, batches) follow
mechanically.
