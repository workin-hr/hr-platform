# Advances Module First Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Advances list/get/create/approve/reject per `docs/superpowers/specs/2026-08-07-advances-module-first-slice-design.md`, by the employees-module template (`2026-08-07-employees-module-first-slice.md` — same service pattern, fixtures, and test idioms; only the differences are specified here).

## Global Constraints

- Everything the employees plan's constraints say (session variable per transaction, explicit company_id filters, indistinguishable 404s, tabs in Java).
- Status transitions only from `PENDING`; violations are 409 with no detail.
- The employee reference in create is resolved tenant-scoped; foreign/nonexistent employee → the same 404.
- `remaining` is set to `amount` at creation and never mutated by this surface (payroll's job).

---

### Task 1: Module (TDD)

**Files:** Test `backend/src/test/java/com/workin/backend/advances/AdvanceModuleFlowTest.java`; Create `advances/AdvanceStatus.java`, `advances/Advance.java`, `advances/AdvanceRepository.java`, `advances/AdvanceService.java`, `advances/AdvanceController.java`, `advances/CreateAdvanceRequest.java`, `advances/RejectAdvanceRequest.java`, `advances/AdvanceView.java`.

**Interfaces:**

- `AdvanceService` (all `@Transactional`, context first arg, session variable first):
  - `list(AuthorizationContext)` → `List<AdvanceView>`
  - `get(AuthorizationContext, Long)` → `Optional<AdvanceView>`
  - `create(AuthorizationContext, CreateAdvanceRequest)` → `Optional<AdvanceView>` — empty when the tenant-scoped employee lookup finds nothing (controller → 404); otherwise saves `new Advance(employeeId, companyId, amount, reason, LocalDate.now())`
  - `approve(AuthorizationContext, Long)` / `reject(AuthorizationContext, Long, String)` → result enum-ish: `Optional<AdvanceView>` is not enough (404 vs 409). Use a small sealed-interface result: `TransitionResult` = `NotFound` | `WrongState` | `Done(AdvanceView)`; controller maps to 404 / 409 / 200.
- `AdvanceController` (`/api/tenant/advances`): GET list + GET `/{id}` `@RequiresPermission(ADVANCES_READ)`; POST (201/404) `@RequiresPermission(ADVANCES_MANAGE)`; POST `/{advanceId}/approve` and `/{advanceId}/reject` `@RequiresPermission(ADVANCES_APPROVE)`.
- `Advance` entity: fields per spec; `approve()`/`reject(String reason)` guard `status == PENDING` (service pre-checks and returns `WrongState`, so the entity guard is a belt-and-braces `IllegalStateException`).

**Test cases** (fixtures identical in style to `EmployeeModuleFlowTest`; grant overrides per key):

1. Admin: create (201, `PENDING`, `remaining == amount`) → list contains → get 200 → approve 200 (`APPROVED`) — and `remaining` unchanged.
2. Reject path with reason recorded; rejected advance's `rejectionReason` in view.
3. Approve on approved → 409; reject on approved → 409.
4. Negative/zero amount → 400.
5. Create with other-company employee id → 404; create with nonexistent employee id → 404 (same shape).
6. Cross-tenant: B's get/approve/reject on A's advance → 404; B's list excludes A's rows.
7. HR + `advances.manage` only: create 200/201, approve 403. HR + `advances.read` only: list 200, create 403.
8. Unauthenticated → non-2xx.

Steps: red (compile) → implement → green → commit.

### Task 2: Full verification + docs

- Full suite `--rerun` green; totals recorded.
- Matrix: `hr-legacy#5` row — new-module tenant isolation + op-shape tests exist (approve/reject/create; pay/delete deliberately absent from the surface so far); F-18 row — advances added to covered modules.
- Lint; commit; PR after human push.
