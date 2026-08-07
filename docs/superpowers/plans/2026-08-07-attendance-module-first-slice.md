# Attendance Module First Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Attendance + exception-types schema and HR manual-entry CRUD with the 2-hour-gap and punches-XOR-exception rules per `docs/superpowers/specs/2026-08-07-attendance-module-first-slice-design.md`, by the penalties-module template (same service/result-object/controller/test idioms; only differences specified here).

**Architecture:** Flat package `com.workin.backend.attendance`; two Flyway migrations (V21 tables, V22 RLS per V14's pattern); services return sealed result objects, controllers map them to HTTP statuses; permission gating via existing `@RequiresPermission` + `PermissionKeys.ATTENDANCE_READ`/`ATTENDANCE_CORRECT`.

**Tech Stack:** Spring Boot, JPA, Flyway, Testcontainers Postgres (suite runs via WSL — see the repo memory note), `Instant` for TIMESTAMPTZ per `RefreshToken`.

## Global Constraints

- Everything the prior module plans' constraints say (tenant scoping via `findByIdAndCompanyId` before any state check; `tenantSessionVariable.apply(...)` first line of every `@Transactional` service method; foreign/nonexistent references → the same 404; no Lombok; DTOs as records; `jakarta.validation` at the boundary).
- The XOR is two-layered: DB `CHECK (exception_type_id IS NULL OR check_out IS NULL)`; full shape rules app-level in the service.
- Gap rule fires only 0–119 minutes **after** the latest earlier punch (exception rows and, on update, the row itself excluded); violation → 409.
- No request field may set both shapes at once; an update clearing both punches without an exception → 400 (clear-equals-delete deliberately not ported).
- Exception rows store `check_in` = UTC midnight of `date`, `check_out` null, `method` null.

---

### Task 1: Schema (V21 + V22)

**Files:**
- Create: `backend/src/main/resources/db/migration/common/V21__create_attendance.sql`
- Create: `backend/src/main/resources/db/migration/rls/V22__enable_attendance_row_level_security.sql`

**Interfaces:** Produces tables `exception_types` and `attendance` exactly as below; Task 2/3 entities map them.

- [ ] **Step 1: V21** — translated from hr-legacy's `exception_types` and `attendance` tables (schema inventory: method enum `app`/`excel`/`qr`, optional GPS pair, optional exception FK); `company_id` denormalized for RLS per V13's reasoning:

```sql
CREATE TABLE exception_types (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    name VARCHAR(150) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX exception_types_company_id_idx ON exception_types (company_id);

-- Punches XOR exception day (business-rule-extraction.md): the
-- DB-enforceable half is "an exception row never has a checkout";
-- midnight-forcing and required-fields-per-shape stay app-level.
CREATE TABLE attendance (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    check_in TIMESTAMPTZ NOT NULL,
    check_out TIMESTAMPTZ,
    method VARCHAR(10) CHECK (method IN ('app', 'excel', 'qr')),
    latitude NUMERIC(9, 6),
    longitude NUMERIC(9, 6),
    exception_type_id BIGINT REFERENCES exception_types (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (exception_type_id IS NULL OR check_out IS NULL)
);
CREATE INDEX attendance_employee_id_idx ON attendance (employee_id);
CREATE INDEX attendance_company_id_idx ON attendance (company_id);
CREATE INDEX attendance_employee_check_in_idx ON attendance (employee_id, check_in);
```

- [ ] **Step 2: V22** — copy V14's exact enable+force pattern for both tables (`ENABLE ROW LEVEL SECURITY`, `FORCE`, the `app.current_company_id` USING/WITH CHECK policy per table).
- [ ] **Step 3: Verify** — run one existing integration test class via WSL (Flyway applies on context boot): `./gradlew test --tests "com.workin.backend.penalties.PenaltyModuleFlowTest" --console=plain`. Expected: PASS (proves migrations apply cleanly).
- [ ] **Step 4: Commit** — `feat(backend): attendance + exception_types schema (V21) with RLS (V22)`.

### Task 2: Exception-types module (TDD)

**Files:**
- Test: `backend/src/test/java/com/workin/backend/attendance/ExceptionTypeFlowTest.java`
- Create: `attendance/ExceptionType.java`, `attendance/ExceptionTypeRepository.java`, `attendance/ExceptionTypeService.java`, `attendance/ExceptionTypeController.java`, `attendance/CreateExceptionTypeRequest.java` (`@NotBlank String name`), `attendance/ExceptionTypeView.java` (`Long id, String name`)

**Interfaces:**
- Produces: `ExceptionTypeRepository.findByIdAndCompanyId(Long, Long)` and `findByCompanyIdOrderById(Long)` — Task 3's service uses the former to validate `exceptionTypeId` references.
- `ExceptionTypeController` (`/api/tenant/exception-types`): GET list `@RequiresPermission(ATTENDANCE_READ)` → 200; POST `@RequiresPermission(ATTENDANCE_CORRECT)` → 201. No update/delete (spec defers them).

- [ ] **Step 1: Failing test** — copy `PenaltyModuleFlowTest`'s fixture helpers (`registerCompanyAdmin`, `loginHrMember`, `allowPermission`, `bearer`, `uniquePhone` — new prefix `+2016`). Cases: admin create(201)+list contains it; cross-tenant list exclusion; read-without-correct → create 403; unauthenticated list non-2xx.
- [ ] **Step 2: Red** — compile failure, then implement entity/repo/service/controller per the penalties shapes (service: `list`, `create` returning the view; no result objects needed — no conflict states exist).
- [ ] **Step 3: Green** — class passes via WSL.
- [ ] **Step 4: Commit** — `feat(backend): exception-types minimal module`.

### Task 3: Attendance module (TDD)

**Files:**
- Test: `backend/src/test/java/com/workin/backend/attendance/AttendanceModuleFlowTest.java`
- Create: `attendance/Attendance.java`, `attendance/AttendanceRepository.java`, `attendance/AttendanceService.java`, `attendance/AttendanceController.java`, `attendance/CreateAttendanceRequest.java`, `attendance/UpdateAttendanceRequest.java`, `attendance/AttendanceView.java`

**Interfaces:**

- Requests (both shapes in one record; service resolves which):

```java
public record CreateAttendanceRequest(
        @NotNull Long employeeId,
        Instant checkIn, Instant checkOut,
        @Pattern(regexp = "app|excel|qr") String method,
        BigDecimal latitude, BigDecimal longitude,
        LocalDate date, Long exceptionTypeId) {
}
// UpdateAttendanceRequest: identical minus employeeId.
```

- `AttendanceView`: `Long id, Long employeeId, Instant checkIn, Instant checkOut, String method, BigDecimal latitude, BigDecimal longitude, Long exceptionTypeId`.
- `AttendanceService` methods (all take `AuthorizationContext` first): `list(context, Long employeeId /*nullable*/, LocalDate from /*nullable*/, LocalDate to /*nullable*/)`, `get`, `create`, `update`, `delete` → sealed `MutationResult` = `NotFound` | `InvalidShape(String reason)` | `GapViolation` | `Done(AttendanceView view)`; controller maps 404 / 400 / 409 / 2xx.
- Shape resolution (create and update identically): `exceptionTypeId != null` → exception shape: require `date`, forbid `checkIn`/`checkOut`/`method`/GPS (else `InvalidShape`); store `check_in = date.atStartOfDay(ZoneOffset.UTC).toInstant()`, all punch fields null. Otherwise → punch shape: require `checkIn` and `method` (else `InvalidShape` — this is also what rejects a clear-both-punches update); `checkOut`/GPS optional; `date` must be null.
- Gap guard (punch shape only): repository `findFirstByEmployeeIdAndCompanyIdAndExceptionTypeIdIsNullAndIdNotAndCheckInLessThanEqualOrderByCheckInDesc(employeeId, companyId, excludedId, checkIn)` — self-exclusion must live in the query, not a service-side skip (if the row being updated is itself the latest earlier punch, the next-earlier row still needs checking); create passes `-1L` as `excludedId`. If the hit's `check_in` is within `Duration.ofMinutes(120)` (exclusive) of the new `checkIn` → `GapViolation`. Equal timestamps count as within (legacy `>= 0`).
- `AttendanceRepository` list queries: `findByCompanyIdOrderById`, `findByEmployeeIdAndCompanyIdOrderById`; range filtering applied in the service on `check_in` against `from.atStartOfDay(UTC)` / `to.plusDays(1).atStartOfDay(UTC)` bounds (skip when null).
- `AttendanceController` (`/api/tenant/attendance`): GET list (optional `employeeId`, `from`, `to` params) + GET `/{id}` `@RequiresPermission(ATTENDANCE_READ)`; POST (201) / PUT `/{id}` (200) / DELETE `/{id}` (204) `@RequiresPermission(ATTENDANCE_CORRECT)`.

- [ ] **Step 1: Failing test** — same fixtures (phone prefix `+2017`; helper `createEmployee`; helper creating an exception type via API). Cases:
  - punch round-trip: create(201) → list/get → update checkOut (200) → delete (204) → get 404;
  - exception round-trip: create with `date`+`exceptionTypeId` (201), view shows UTC-midnight `checkIn`, null `checkOut`;
  - XOR 400s: both shapes at once; neither (`employeeId` only); punch without `method`; update clearing both punches without exception;
  - DB CHECK backstop: raw SQL insert with `exception_type_id` and `check_out` both set fails (assert via `JdbcTemplate` + caught exception);
  - gap: second punch +119 min → 409; +120 min → 201; equal timestamp → 409; exception row between the two punches → still 201/409 unchanged (excluded); update moving a punch to within 119 min of another → 409; update keeping its own time → 200 (self excluded);
  - backdated: punch 130 min **before** an existing punch → 201;
  - F-18: foreign/nonexistent `employeeId` create → 404; foreign/nonexistent `exceptionTypeId` create → 404 (company B referencing company A's type); cross-tenant get/update/delete → 404; list exclusion; foreign `employeeId` list filter → empty;
  - read-without-correct → create 403; unauthenticated non-2xx.
- [ ] **Step 2: Red** — compile failure first.
- [ ] **Step 3: Implement** — entity with punch/exception factory-style constructors or a single constructor + `applyPunch`/`applyException` mutators (match `Penalty`'s plain-JPA style); service per interfaces above.
- [ ] **Step 4: Green** — class passes via WSL.
- [ ] **Step 5: Commit** — `feat(backend): attendance module first slice -- HR manual entry with 2-hour-gap and punches-XOR-exception rules (F-18 negatives included)`.

### Task 4: Full verification + docs

- [ ] Full suite `--rerun` via WSL; totals recorded from `build/test-results` XML.
- [ ] Matrix (`docs/migration/consolidated-task-matrix.md`): F-18 row adds attendance (`AttendanceModuleFlowTest`); hr-legacy row #16 (QR gap) and #25 (bulk delete) get a one-line note that the first slice ships neither surface, so the product decisions remain open with nothing to retrofit; #17 unchanged (already parked via the empty MANAGER bundle).
- [ ] `python3 scripts/validate_phase0.py` in WSL exits 0.
- [ ] Commit `docs(migration): record attendance-module F-18 coverage`; PR after human push.
