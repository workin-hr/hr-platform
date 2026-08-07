# Employees Lifecycle — Design (2026-08-07)

## Purpose And Authority

The last engineering-ready matrix item: the activate/deactivate
surface the employees module's first slice deliberately deferred
(`Employee`'s own Javadoc). Design approved inline by the repository
owner 2026-08-07 ("proceed"). Legacy evidence
(`docs/api/existing-endpoint-inventory.md`): `deactivate.php`/
`reactivate.php` are company-scoped PUTs that toggle `is_active`
rather than deleting. Deletion stays out — `hr-legacy#20`'s
financial-history retention question is product-gated.

## Scope

**In** (no schema change — `employees.active` exists since V8):

- `PUT /api/tenant/employees/{employeeId}/status` with
  `{active: boolean}` — the members-module status precedent — gated
  by the existing `employees.manage` key. Idempotent: setting the
  current state is a 200 no-op. Cross-tenant/nonexistent → the same
  404 (F-18).
- Entity mutators `deactivate()`/`activate()`; `active` stays absent
  from create/update DTOs — lifecycle changes only through the
  explicit status endpoint.
- No audit trail (recorded decision): §9's audit scope covers
  principals; employees carry no identity link, and legacy has no
  such trail either. Revisit if/when employee↔identity linking lands.

**Out (tracked):** employee deletion (`hr-legacy#20`); the
employee↔identity link and everything self-service.

## Design

`UpdateEmployeeStatusRequest(@NotNull Boolean active)` record;
`EmployeeService.updateStatus(context, employeeId, active)` →
`Optional<EmployeeView>` (empty → 404), template shapes throughout.

## Testing

`EmployeeModuleFlowTest` additions: deactivate → view inactive →
reactivate round trip (idempotent re-set → 200, state unchanged);
**payroll integration proof** — a deactivated employee with a salary
contract is skipped by batch calculate and included again after
reactivation (locks in `PayrollBatchService`'s previously untested
`isActive` filter); cross-tenant status change → 404;
`employees.read` alone → 403; and the `hr-legacy#15` structural
proof — calling `POST /api/auth/logout` leaves an employee's
`active` flag untouched (the legacy mobile-logout-deactivates-account
bug has no code path here, asserted rather than assumed).

## Consequences

The employees module's admin surface is complete for the
pre-identity-link era; `hr-legacy#15`'s new-system acceptance
criterion gains a real regression test.
