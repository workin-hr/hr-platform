# Penalties Module First Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Penalties CRUD with the `applied_to_payroll` lock per `docs/superpowers/specs/2026-08-07-penalties-module-first-slice-design.md`, by the advances-module template (`2026-08-07-advances-module-first-slice.md` — same service/result-object/controller/test idioms; only differences specified).

## Global Constraints

- Everything the prior module plans' constraints say.
- `applied_to_payroll` has no request field anywhere; locked rows answer 409 to update and delete.
- `penaltyDays` accepts zero (`@PositiveOrZero`), rejects negative.

---

### Task 1: Module (TDD)

**Files:** Test `backend/src/test/java/com/workin/backend/penalties/PenaltyModuleFlowTest.java`; Create `penalties/Penalty.java`, `penalties/PenaltyRepository.java`, `penalties/PenaltyService.java`, `penalties/PenaltyController.java`, `penalties/CreatePenaltyRequest.java` (`@NotNull Long employeeId`, `@NotBlank String penaltyType`, `@NotNull @PositiveOrZero BigDecimal penaltyDays`, `String reason`, `LocalDate penaltyDate` nullable → server date), `penalties/UpdatePenaltyRequest.java` (same minus employeeId), `penalties/PenaltyView.java` (`id, employeeId, penaltyType, penaltyDays, reason, penaltyDate, appliedToPayroll`).

**Interfaces:**

- `PenaltyService` (template): `list`, `get`, `create` (→ `Optional`, empty = employee not found), `update(context, id, UpdatePenaltyRequest)` and `delete(context, id)` both → `MutationResult` = `NotFound` | `Locked` | `Done(PenaltyView view)` (`Done(null)` acceptable for delete — controller returns 204).
- `PenaltyController` (`/api/tenant/penalties`): GET ×2 `@RequiresPermission(PENALTIES_READ)`; POST (201/404), PUT (200/404/409), DELETE (204/404/409) `@RequiresPermission(PENALTIES_MANAGE)`.
- `Penalty` entity: fields per spec; `update(type, days, reason, date)` mutator; `isAppliedToPayroll()`; no setter for the flag.

**Test cases:** round-trip create(201)/list/get/update(200)/delete(204, then get 404); zero days 201, negative days 400; lock: `UPDATE penalties SET applied_to_payroll = TRUE` via SQL → PUT 409, DELETE 409; foreign + nonexistent employee create → 404; cross-tenant get/update/delete → 404, list exclusion; HR + `penalties.read` only → list 200, create 403; unauthenticated non-2xx; raw list JSON contains `"appliedToPayroll"` and create request with extra JSON field is simply ignored by Jackson (no assertion needed — no field exists to bind).

Steps: red (compile) → implement → green → commit.

### Task 2: Full verification + docs

- Full suite `--rerun`; totals recorded.
- Matrix: F-18 row adds penalties; no `hr-legacy` row changes (the penalties findings #23 report-export and #25 are out of this scope).
- Lint; commit; PR after human push.
