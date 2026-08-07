# Organization Structure First Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** branches/departments(+junction)/job_titles/shifts CRUD plus employee organization attribution, per `docs/superpowers/specs/2026-08-07-organization-structure-first-slice-design.md`.

**Architecture:** One flat package `com.workin.backend.organization` with four entity/repository/service/controller quartets (the payroll-group one-package precedent); V29 (five tables) + V30 (RLS). The employees module modification stays in `com.workin.backend.employees`, importing the organization repositories for reference checks.

**Tech Stack:** Spring Boot, JPA, Flyway, Testcontainers via WSL. Existing V4 keys per surface (`BRANCHES_READ/MANAGE`, `DEPARTMENTS_READ/MANAGE`, `JOB_TITLES_READ/MANAGE`, `SHIFTS_READ/MANAGE` in `PermissionKeys`) — no catalog change.

## Global Constraints

- Everything the prior module plans' constraints say (tenant scoping; `tenantSessionVariable.apply(...)` first; records; no Lombok; foreign/nonexistent references → the same 404).
- `qr_code`/`expires_at` have **no request fields anywhere** — view-only columns for the deferred QR slice.
- `isActive` is a nullable Boolean in every request; null → true (both create and update); disabling requires explicit false.
- Delete: a row referenced from outside its aggregate → 409 via the caught FK violation, **thrown, not returned** (the rollback-only lesson); a department clears its own junction rows first.
- Run `markdownlint-cli2@0.23.2` on `**/*.md` before any docs push.

---

### Task 1: Schema (V29 + V30)

**Files:**

- Create: `backend/src/main/resources/db/migration/common/V29__create_organization_structure.sql`
- Create: `backend/src/main/resources/db/migration/rls/V30__enable_organization_row_level_security.sql`

**Interfaces:** Produces the five tables exactly as below plus the three new `employees` columns; Tasks 2/3 map them.

- [ ] **Step 1: V29**:

```sql
-- Translated from hr-legacy/mysql_workin.schema.sql at the pinned
-- Discovery commit. Recorded normalizations (slice spec): shifts
-- gains NOT NULL company_id (legacy allows null, but unowned rows are
-- invisible under RLS); the junction gains a denormalized company_id
-- for RLS; employees' new org columns are nullable (legacy's NOT NULL
-- branch_id cannot hold for already-created employees -- onboarding
-- enforces it when that flow exists).
CREATE TABLE branches (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    name VARCHAR(255) NOT NULL,
    address VARCHAR(500),
    latitude NUMERIC(10, 7),
    longitude NUMERIC(10, 7),
    radius_meters INT NOT NULL DEFAULT 200,
    qr_code VARCHAR(100),
    expires_at TIMESTAMPTZ,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX branches_company_id_idx ON branches (company_id);

CREATE TABLE departments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    name VARCHAR(255) NOT NULL,
    manager_id BIGINT REFERENCES employees (id),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX departments_company_id_idx ON departments (company_id);

CREATE TABLE department_branches (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    department_id BIGINT NOT NULL REFERENCES departments (id),
    branch_id BIGINT NOT NULL REFERENCES branches (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    CONSTRAINT department_branches_pair_unique UNIQUE (department_id, branch_id)
);
CREATE INDEX department_branches_company_id_idx ON department_branches (company_id);

CREATE TABLE job_titles (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    department_id BIGINT REFERENCES departments (id),
    name VARCHAR(255) NOT NULL,
    work_hours NUMERIC(5, 2) NOT NULL DEFAULT 8.00,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX job_titles_company_id_idx ON job_titles (company_id);

CREATE TABLE shifts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    name VARCHAR(100) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    days_off VARCHAR(20),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX shifts_company_id_idx ON shifts (company_id);

ALTER TABLE employees ADD COLUMN branch_id BIGINT REFERENCES branches (id);
ALTER TABLE employees ADD COLUMN department_id BIGINT REFERENCES departments (id);
ALTER TABLE employees ADD COLUMN job_title_id BIGINT REFERENCES job_titles (id);
```

- [ ] **Step 2: V30** — V14's exact enable+force+policy pattern for the five new tables (employees already has RLS from V5).
- [ ] **Step 3: Verify** — run one existing class via WSL (Flyway applies on boot): `./gradlew test --tests "com.workin.backend.employees.EmployeeModuleFlowTest" --rerun`. Expected: PASS.
- [ ] **Step 4: Commit** — `feat(backend): organization-structure schema (V29/V30) with employee org columns`.

### Task 2: Organization module (TDD)

**Files:**

- Test: `backend/src/test/java/com/workin/backend/organization/OrganizationStructureFlowTest.java`
- Create in `backend/src/main/java/com/workin/backend/organization/`: `Branch.java`, `BranchRepository.java`, `BranchService.java`, `BranchController.java`, `UpsertBranchRequest.java`, `BranchView.java`, `Department.java`, `DepartmentBranch.java`, `DepartmentBranchRepository.java`, `DepartmentRepository.java`, `DepartmentService.java`, `DepartmentController.java`, `UpsertDepartmentRequest.java`, `DepartmentView.java`, `JobTitle.java`, `JobTitleRepository.java`, `JobTitleService.java`, `JobTitleController.java`, `UpsertJobTitleRequest.java`, `JobTitleView.java`, `Shift.java`, `ShiftRepository.java`, `ShiftService.java`, `ShiftController.java`, `UpsertShiftRequest.java`, `ShiftView.java`

**Interfaces:**

```java
public record UpsertBranchRequest(
        @NotBlank String name, String address, BigDecimal latitude, BigDecimal longitude,
        @Positive Integer radiusMeters, Boolean isActive) {}          // radius null -> 200
public record BranchView(
        Long id, String name, String address, BigDecimal latitude, BigDecimal longitude,
        int radiusMeters, String qrCode, Instant expiresAt, boolean isActive) {}
public record UpsertDepartmentRequest(
        @NotBlank String name, Long managerId, List<Long> branchIds, Boolean isActive) {}  // branchIds null == empty
public record DepartmentView(Long id, String name, Long managerId, List<Long> branchIds, boolean isActive) {}
public record UpsertJobTitleRequest(
        @NotBlank String name, Long departmentId, @Positive BigDecimal workHours, Boolean isActive) {}  // workHours null -> 8.00
public record JobTitleView(Long id, String name, Long departmentId, BigDecimal workHours, boolean isActive) {}
public record UpsertShiftRequest(
        @NotBlank String name, @NotNull LocalTime startTime, @NotNull LocalTime endTime,
        @Size(max = 20) String daysOff, Boolean isActive) {}
public record ShiftView(Long id, String name, LocalTime startTime, LocalTime endTime, String daysOff, boolean isActive) {}
```

- Every repository: `findByCompanyIdOrderById(Long)`, `findByIdAndCompanyId(Long, Long)`; `DepartmentBranchRepository` adds `List<DepartmentBranch> findByDepartmentId(Long)` and `void deleteByDepartmentId(Long)`.
- Every service (template): `list`, `get`, `create` (Optional — empty on a failed reference check → 404), `update` (same), `delete` (`boolean`-style NotFound → 404; FK violation from external references caught around a `deleteById` + `flush` → `throw new ResponseStatusException(CONFLICT, "still referenced")`). Reference checks: department `managerId` via `EmployeeRepository.findByIdAndCompanyId`; department `branchIds` each via `BranchRepository.findByIdAndCompanyId` (junction replace: `deleteByDepartmentId` then save a row per id with `company_id`); job-title `departmentId` via `DepartmentRepository.findByIdAndCompanyId`. `DepartmentService.delete` clears its junction rows first, then deletes (external references from `job_titles`/`employees` still 409 via the FK catch).
- Controllers: `/api/tenant/branches`, `/api/tenant/departments`, `/api/tenant/job-titles`, `/api/tenant/shifts` — GET ×2 with the surface's `_READ` key; POST (201)/PUT (200)/DELETE (204) with `_MANAGE`; the standard `contextFrom` helper.
- Entities: plain-JPA per the `Penalty` template; one `apply(Upsert...Request)` mutator each; `Branch` has no setter for `qrCode`/`expiresAt` (view-only columns).

- [ ] **Step 1: Failing test** — `OrganizationStructureFlowTest` (phone prefix `+2022`, shared penalties-template fixtures, helper per surface). Cases: **branches** — create with defaults (radius 200, active true, null qr/expiry in view) → update name+isActive false → delete unreferenced 204; JSON request with a `qrCode` field is simply ignored (no binding target); **departments** — create with `managerId` + two `branchIds` → view returns both; update replacing with one branch → view shows one; update clearing (`branchIds` empty) → empty; foreign/nonexistent manager → 404; foreign branch id in the set → 404; delete with junction rows → 204 (aggregate-owned); **job-titles** — default `workHours` 8.00; zero/negative → 400; foreign department → 404; **shifts** — missing `startTime` → 400; `daysOff` round trip; **cross-surface delete conflict** — branch referenced by a department's junction → DELETE branch → 409; after removing it from the department, DELETE → 204; **F-18** — per surface: cross-tenant get/update/delete → 404, list exclusion; read-without-manage → 403 (one surface suffices for the pattern, branches); unauthenticated → non-2xx (all four paths).
- [ ] **Step 2: Red** — `compileTestJava` via WSL fails on missing symbols.
- [ ] **Step 3: Implement** per Interfaces; **Step 4: Green** — class via WSL, XML counts; **Step 5: Commit** — `feat(backend): organization-structure CRUD -- branches, departments(+junction), job titles, shifts (F-18 negatives included)`.

### Task 3: Employee attribution (TDD)

**Files:**

- Modify: `backend/src/main/java/com/workin/backend/employees/Employee.java` (three nullable Long columns + `place(Long branchId, Long departmentId, Long jobTitleId)` mutator), `CreateEmployeeRequest.java`/`UpdateEmployeeRequest.java` (add nullable `Long branchId, Long departmentId, Long jobTitleId`), `EmployeeView.java` (expose all three), `EmployeeService.java` (inject `BranchRepository`/`DepartmentRepository`/`JobTitleRepository`; validate each non-null id via `findByIdAndCompanyId`, miss → `throw new ResponseStatusException(NOT_FOUND)` — the service already throws for the phone-conflict case; call `place(...)` in create and update)
- Test: `backend/src/test/java/com/workin/backend/employees/EmployeeModuleFlowTest.java` (add cases)

**Interfaces:** Consumes Task 2's three repositories; no new types.

- [ ] **Step 1: Failing tests** — create an employee with valid branch/department/job-title (created via the Task 2 APIs) → view round-trips all three; update clearing them to null → view shows nulls; foreign and nonexistent ids for each of the three on update → the same 404 (six assertions).
- [ ] **Step 2: Red** — the new cases fail (missing DTO fields → compile failure first).
- [ ] **Step 3: Implement**; **Step 4: Green** — `EmployeeModuleFlowTest` fully green; **Step 5: Commit** — `feat(backend): employee organization attribution -- nullable branch/department/job-title with 404 reference checks`.

### Task 4: Full verification + docs

- [ ] Full suite `--rerun` via WSL; totals from XML.
- [ ] Matrix: F-18 row adds the organization group (`OrganizationStructureFlowTest` + the employees attribution 404s).
- [ ] `python3 scripts/validate_phase0.py` exit 0; `markdownlint-cli2@0.23.2` clean.
- [ ] Commit `docs(migration): record organization-structure F-18 coverage`; PR after human push.
