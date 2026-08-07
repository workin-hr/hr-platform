# Requests & Leave Balances First Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The leave/permission request workflow (request-types, requests with the ported legacy approval semantics, leave balances) per `docs/superpowers/specs/2026-08-07-requests-leave-balances-first-slice-design.md`, by the established module template.

**Architecture:** One flat package `com.workin.backend.requests` holding the request-type, request, and leave-balance classes (the deduction side effect couples them); V25 (three tables + the `requests.manage` key) and V26 (RLS). The approve transaction reuses `AttendanceRepository` directly for the skip-check and exception-row inserts, the payroll→advances/penalties same-codebase-reuse precedent.

**Tech Stack:** Spring Boot, JPA, Flyway, Testcontainers via WSL, `Instant`/`LocalDate`/`LocalTime` mappings.

## Global Constraints

- Everything the prior module plans' constraints say (tenant scoping before state checks; `tenantSessionVariable.apply(...)` first line; foreign/nonexistent references → the same 404; records for DTOs; no Lombok).
- HR-created requests **always start PENDING**; decisions happen only via approve/reject.
- Approval semantics are `request_actions_helper.php`'s, exactly: inclusive day count (min 1), year = from-date's year, 422 only when a balance row exists, auto-create at 21.0 total otherwise, per-day exception rows skipping existing-attendance days, midnight/method-null convention, null exception mapping → skip side effect.
- `used_days` has no request field anywhere; `remaining_days` is DB-generated (entity maps it read-only).
- Run `markdownlint-cli2@0.23.2` on `**/*.md` before any docs push.

---

### Task 1: Schema (V25 + V26) and the requests.manage constant

**Files:**

- Create: `backend/src/main/resources/db/migration/common/V25__create_requests_and_leave_balances.sql`
- Create: `backend/src/main/resources/db/migration/rls/V26__enable_requests_row_level_security.sql`
- Modify: `backend/src/main/java/com/workin/backend/authorization/PermissionKeys.java` (add `REQUESTS_MANAGE`)

**Interfaces:** Produces tables `request_types`/`requests`/`leave_balances` exactly as below, catalog key `requests.manage`, constant `PermissionKeys.REQUESTS_MANAGE`.

- [ ] **Step 1: V25**:

```sql
-- requests.manage is a new-platform key (owner-confirmed 2026-08-07):
-- legacy request creation is EMPLOYEE-only self-service, which the
-- rewrite defers codebase-wide; HR create-on-behalf needs its own
-- gate, distinct from requests.approve (legacy can_requests).
INSERT INTO permissions (permission_key, description) VALUES
    ('requests.manage', 'Create/edit/delete requests on behalf of employees and manage request types (new platform; legacy create was employee self-service only)');

INSERT INTO role_permissions (role, permission_id)
SELECT 'COMPANY_ADMIN', p.id FROM permissions p WHERE p.permission_key = 'requests.manage';

-- Translated from hr-legacy/mysql_workin.schema.sql (fetched at the
-- pinned Discovery commit 83c326e). company_id denormalized for RLS
-- (legacy scopes requests via an employees join). Normalizations,
-- recorded in the slice spec: uppercase status values, approver as a
-- membership FK (legacy stored an employee id), no updated_at,
-- plural leave_balances, and a real UNIQUE(employee_id, year) that
-- legacy only assumed app-level (same move as hr-legacy#21).
CREATE TABLE request_types (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    name VARCHAR(100) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    deduct_balance BOOLEAN NOT NULL DEFAULT FALSE,
    counts_as_paid_leave BOOLEAN NOT NULL DEFAULT TRUE,
    add_attendance_exception BOOLEAN NOT NULL DEFAULT FALSE,
    exception_type_id BIGINT REFERENCES exception_types (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX request_types_company_id_idx ON request_types (company_id);

CREATE TABLE requests (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    request_type_id BIGINT NOT NULL REFERENCES request_types (id),
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    from_time TIME,
    to_time TIME,
    notes TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reply TEXT,
    approver_membership_id BIGINT REFERENCES tenant_memberships (id),
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX requests_employee_id_idx ON requests (employee_id);
CREATE INDEX requests_company_id_idx ON requests (company_id);

CREATE TABLE leave_balances (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    year SMALLINT NOT NULL,
    period_from_month SMALLINT NOT NULL DEFAULT 1,
    period_to_month SMALLINT NOT NULL DEFAULT 12,
    monthly_cap_days NUMERIC(5, 2),
    total_days NUMERIC(5, 1) NOT NULL DEFAULT 0.0,
    used_days NUMERIC(5, 1) NOT NULL DEFAULT 0.0,
    remaining_days NUMERIC(5, 1) GENERATED ALWAYS AS (total_days - used_days) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT leave_balances_employee_year_unique UNIQUE (employee_id, year)
);
CREATE INDEX leave_balances_company_id_idx ON leave_balances (company_id);
```

- [ ] **Step 2: V26** — V14's exact enable+force+policy pattern for all three tables.
- [ ] **Step 3: Red** — run `./gradlew test --tests "com.workin.backend.authorization.PermissionCatalogSyncTest" --rerun` via WSL before touching PermissionKeys. Expected: FAIL (one row without a constant).
- [ ] **Step 4: Green** — add `public static final String REQUESTS_MANAGE = "requests.manage";` next to `REQUESTS_APPROVE`; re-run. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(backend): requests/leave-balances schema (V25/V26) and requests.manage key`.

### Task 2: Leave-balances surface (TDD)

**Files:**

- Test: `backend/src/test/java/com/workin/backend/requests/LeaveBalanceFlowTest.java`
- Create: `requests/LeaveBalance.java`, `requests/LeaveBalanceRepository.java`, `requests/LeaveBalanceService.java`, `requests/LeaveBalanceController.java`, `requests/CreateLeaveBalanceRequest.java`, `requests/UpdateLeaveBalanceRequest.java`, `requests/LeaveBalanceView.java`

**Interfaces:**

```java
public record CreateLeaveBalanceRequest(
        @NotNull Long employeeId, @NotNull Short year,
        @NotNull @PositiveOrZero BigDecimal totalDays,
        Short periodFromMonth, Short periodToMonth, BigDecimal monthlyCapDays) {}
public record UpdateLeaveBalanceRequest(
        @NotNull @PositiveOrZero BigDecimal totalDays,
        Short periodFromMonth, Short periodToMonth, BigDecimal monthlyCapDays) {}
public record LeaveBalanceView(
        Long id, Long employeeId, Short year, Short periodFromMonth, Short periodToMonth,
        BigDecimal monthlyCapDays, BigDecimal totalDays, BigDecimal usedDays, BigDecimal remainingDays) {}
```

- Entity `LeaveBalance`: fields per V25; `remaining_days` mapped `@Column(name = "remaining_days", insertable = false, updatable = false)`; constructor `(employeeId, companyId, year)` + `applySettings(totalDays, periodFromMonth, periodToMonth, monthlyCapDays)` mutator (nulls → keep 1/12/null defaults on create via field initializers); **`addUsedDays(BigDecimal days)` mutator** — Task 3's deduction side effect calls it.
- Repository: `findByCompanyIdOrderById`, `findByEmployeeIdAndCompanyIdOrderById`, `findByIdAndCompanyId`, **`findByEmployeeIdAndYear(Long employeeId, Short year)`** (Task 3 uses it inside the tenant transaction).
- Service (template): `list(context, employeeId /*nullable*/, year /*nullable*/)` (year filtered in-service), `get`, `create` → `MutationResult` = `NotFound` | `Duplicate` | `Done(LeaveBalanceView)` (employee lookup via `EmployeeRepository.findByIdAndCompanyId`; `DataIntegrityViolationException` from the unique constraint → `Duplicate`), `update` → `NotFound` | `Done`.
- Controller `/api/tenant/leave-balances`: GET list + GET `/{id}` `@RequiresPermission(LEAVE_BALANCES_READ)`; POST (201/404/409), PUT `/{id}` (200/404) `@RequiresPermission(LEAVE_BALANCES_MANAGE)`. No DELETE (spec defers it).

- [ ] **Step 1: Failing test** — penalties-template fixtures, phone prefix `+2019`. Cases: create(201, view shows remaining == total)→list/get→update totalDays (200, remaining recomputed by the DB)→; duplicate (employee, year) create → 409; negative totalDays → 400; foreign/nonexistent employee create → the same 404; cross-tenant get/update → 404, list exclusion; `usedDays` has no request field (nothing to assert beyond compilation — the records above simply lack it); read-without-manage → 403 on create; unauthenticated → non-2xx.
- [ ] **Step 2: Red** — `compileTestJava` fails on missing symbols.
- [ ] **Step 3: Implement** per Interfaces; **Step 4: Green** — run the class via WSL, verify XML counts; **Step 5: Commit** — `feat(backend): leave-balances surface with real (employee, year) uniqueness`.

### Task 3: Request-types + requests workflow (TDD)

**Files:**

- Test: `backend/src/test/java/com/workin/backend/requests/RequestModuleFlowTest.java`
- Create: `requests/RequestType.java`, `requests/RequestTypeRepository.java`, `requests/RequestTypeService.java`, `requests/RequestTypeController.java`, `requests/CreateRequestTypeRequest.java`, `requests/RequestTypeView.java`, `requests/LeaveRequest.java` (entity — class name avoids the servlet `Request` clash), `requests/LeaveRequestRepository.java`, `requests/RequestStatus.java`, `requests/RequestService.java`, `requests/RequestController.java`, `requests/CreateRequestRequest.java`, `requests/UpdateRequestRequest.java`, `requests/ApproveRequestRequest.java`, `requests/RejectRequestRequest.java`, `requests/RequestView.java`
- Modify: `backend/src/main/java/com/workin/backend/attendance/AttendanceRepository.java` — add `boolean existsByEmployeeIdAndCheckInGreaterThanEqualAndCheckInLessThan(Long employeeId, Instant dayStart, Instant nextDayStart)`

**Interfaces:**

```java
public enum RequestStatus { PENDING, APPROVED, REJECTED }
public record CreateRequestTypeRequest(
        @NotBlank String name, Boolean isActive, Boolean deductBalance,
        Boolean countsAsPaidLeave, Boolean addAttendanceException, Long exceptionTypeId) {}
public record RequestTypeView(
        Long id, String name, boolean isActive, boolean deductBalance,
        boolean countsAsPaidLeave, boolean addAttendanceException, Long exceptionTypeId) {}
public record CreateRequestRequest(
        @NotNull Long employeeId, @NotNull Long requestTypeId,
        @NotNull LocalDate fromDate, @NotNull LocalDate toDate,
        LocalTime fromTime, LocalTime toTime, String notes) {}
public record UpdateRequestRequest(
        @NotNull Long requestTypeId, @NotNull LocalDate fromDate, @NotNull LocalDate toDate,
        LocalTime fromTime, LocalTime toTime, String notes) {}
public record ApproveRequestRequest(String reply) {}
public record RejectRequestRequest(@NotBlank String reply) {}
public record RequestView(
        Long id, Long employeeId, Long requestTypeId, LocalDate fromDate, LocalDate toDate,
        LocalTime fromTime, LocalTime toTime, String notes, RequestStatus status,
        String reply, Long approverMembershipId, Instant decidedAt) {}
```

- `RequestTypeService.create`: toggles null → DDL defaults (false/true/false); `exceptionTypeId` kept only when `addAttendanceException` is true (legacy nulls it otherwise — silently, not an error); when kept, `ExceptionTypeRepository.findByIdAndCompanyId` miss → `NotFound`. `list` → all types for the company (`is_active` exposed, not enforced — the spec's open question).
- `RequestService` sealed `MutationResult` = `NotFound` | `WrongState` | `InsufficientBalance` | `Done(RequestView)`; controller maps 404 / 409 / 422 / 2xx.
  - `create`: employee + type lookups (`findByIdAndCompanyId` each) → `NotFound`. Date ordering is request-shape validation, not a service result: the controller throws `ResponseStatusException(BAD_REQUEST)` when `toDate.isBefore(fromDate)` before calling the service (same altitude as `@Valid`; applies to update too). Status always `PENDING`.
  - `update`/`delete`: row lookup → `NotFound`; `status != PENDING` → `WrongState`; update re-validates the type reference like create.
  - `approve(context, id, reply)`: lookup → `NotFound`; non-pending → `WrongState`; if type.deductBalance: `days = max(1, DAYS.between(from, to) + 1)`, `year = from.getYear()`; existing balance row (`findByEmployeeIdAndYear`) with `remaining < days` → `InsufficientBalance`; then mutate: status/reply/`decidedAt = Instant.now()`/`approverMembershipId = context.membershipId()`; deduction: existing row → `addUsedDays(days)`, missing → `new LeaveBalance(employeeId, companyId, year)` + `applySettings(BigDecimal.valueOf(21.0), null, null, null)` + `addUsedDays(days)` (the 21.0 constant stands in for the deferred MONTHLY_LEAVE_ACCRUAL setting — comment it); exceptions: when type.addAttendanceException and `exceptionTypeId != null`, for each day in [from, to]: `dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant()`; skip if `attendanceRepository.existsByEmployeeIdAndCheckInGreaterThanEqualAndCheckInLessThan(employeeId, dayStart, dayStart.plus(1, DAYS))`; else save `new Attendance(employeeId, companyId)` + `applyException(dayStart, exceptionTypeId)`.
  - `reject(context, id, reply)`: lookup → `NotFound`; non-pending → `WrongState`; mutate status/reply/decidedAt/approver; no side effects.
- `LeaveRequest` entity: fields per V25 (`status` `@Enumerated(EnumType.STRING)`); mutators `updateDetails(requestTypeId, fromDate, toDate, fromTime, toTime, notes)` and `decide(RequestStatus, String reply, Long approverMembershipId, Instant decidedAt)`.
- Controllers: `/api/tenant/request-types` GET (`REQUESTS_READ`) / POST (`REQUESTS_MANAGE`, 201); `/api/tenant/requests` GET list with optional `employeeId`/`status` params + GET `/{id}` (`REQUESTS_READ`); POST 201 / PUT `/{id}` 200 / DELETE `/{id}` 204 (`REQUESTS_MANAGE`); PUT `/{id}/approve` and PUT `/{id}/reject` 200 (`REQUESTS_APPROVE`).

- [ ] **Step 1: Failing test** — fixtures with phone prefix `+2020`; helpers to create employee (SQL), exception type (API), request type (API), balance (API via Task 2's endpoint). Cases (the spec's Testing section, in full): type toggles round trip + exceptionTypeId nulled without the flag + foreign/nonexistent exception type → 404; request round trip, always-PENDING, `toDate < fromDate` → 400; pending-only edit/delete → 409 after decision; approve happy path (status/reply/decidedAt/approver asserted via view); deduction against existing balance (usedDays via leave-balances GET); auto-created balance (SQL-assert total 21.0, used = day count); insufficient → 422; **no-row oversized request approves into negative remaining** (SQL-assert remaining < 0); exception rows per day at UTC midnight, skipping a seeded mid-range attendance day (SQL-assert row count and check_in values); no exception rows when the flag or mapping is absent; approve/reject non-pending → 409; reject blank reply → 400; F-18: cross-tenant get/update/delete/approve/reject → 404 + list exclusion, foreign `employeeId`/`requestTypeId` create → 404; `requests.read` alone → 403 on create and approve; `requests.manage` alone → 403 on approve; unauthenticated → non-2xx.
- [ ] **Step 2: Red** — `compileTestJava` fails; **Step 3: Implement**; **Step 4: Green** — class via WSL, XML counts; **Step 5: Commit** — `feat(backend): requests workflow with ported approval semantics (F-18 negatives included)`.

### Task 4: Full verification + docs

- [ ] Full suite `--rerun` via WSL; totals from XML.
- [ ] Matrix: F-18 row adds requests + leave-balances (`RequestModuleFlowTest`, `LeaveBalanceFlowTest`); hr-legacy #18 row gains a note that the new approve surface is permission-gated (`requests.approve`) with manager scoping still parked exactly like #17 (F-16/F-25).
- [ ] `docs/bootstrap/open-questions.md`: add the two spec-recorded open questions (the exception-type company-default fallback resolver; MONTHLY_LEAVE_ACCRUAL vs the 21.0 constant) under a "Requests/Leave Migration" heading.
- [ ] `python3 scripts/validate_phase0.py` exit 0; `markdownlint-cli2@0.23.2` clean.
- [ ] Commit `docs(migration): record requests/leave-balances F-18 coverage and open questions`; PR after human push.
