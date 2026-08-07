# Company Settings First Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Typed per-company settings with a GET/PUT upsert surface, plus the two consumer rewirings (payroll fiscal periods, leave-accrual fallback), per `docs/superpowers/specs/2026-08-07-company-settings-first-slice-design.md`.

**Architecture:** Flat package `com.workin.backend.companysettings`; V27 (table, real `UNIQUE (company_id)`) + V28 (RLS). `CompanySettingsService.effective(companyId)` is the one read seam both consumers call inside their own tenant transactions; period math stays in `PayrollBatchService`.

**Tech Stack:** Spring Boot, JPA, Flyway, Testcontainers via WSL. Existing keys `PermissionKeys.COMPANY_SETTINGS_READ`/`COMPANY_SETTINGS_MANAGE` (V4) — no catalog change.

## Global Constraints

- Everything the prior module plans' constraints say (tenant scoping; `tenantSessionVariable.apply(...)` first; records; no Lombok).
- Null column = "unset — apply the legacy fallback" (start 1; end = month's last day; accrual 21.0). A company with no row behaves byte-for-byte like today — existing payroll/leave tests must pass unchanged.
- Legacy's silent clamping becomes 400 validation: `monthStartDay`/`monthEndDay` null or 1–31, `overtimeRate`/`monthlyLeaveAccrual` null or ≥ 0, `weeklyOffDays` ≤ 60 chars.
- Run `markdownlint-cli2@0.23.2` on `**/*.md` before any docs push.

---

### Task 1: Schema (V27/V28) + settings module (TDD)

**Files:**

- Create: `backend/src/main/resources/db/migration/common/V27__create_company_settings.sql`
- Create: `backend/src/main/resources/db/migration/rls/V28__enable_company_settings_row_level_security.sql`
- Test: `backend/src/test/java/com/workin/backend/companysettings/CompanySettingsFlowTest.java`
- Create: `companysettings/CompanySettings.java`, `companysettings/CompanySettingsRepository.java`, `companysettings/CompanySettingsService.java`, `companysettings/CompanySettingsController.java`, `companysettings/UpdateCompanySettingsRequest.java`, `companysettings/CompanySettingsView.java`, `companysettings/EffectiveCompanySettings.java`

**Interfaces:**

- [ ] **Step 1: V27** — legacy's 4-table EAV collapsed to typed columns (owner-confirmed; conversion at cutover recorded in the spec):

```sql
-- Typed replacement for legacy's setting_definitions/
-- setting_allowed_values/company_settings/company_setting_values EAV
-- (owner-confirmed simplification; the EAV-to-typed data conversion is
-- cutover work). Exactly the five code-consumed CompanySettingEnum
-- keys. NULL means "unset -- apply the legacy fallback" (start 1,
-- end = month's last day, accrual 21.0), so a company with no row
-- behaves exactly as before this table existed.
CREATE TABLE company_settings (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    month_start_day SMALLINT,
    month_end_day SMALLINT,
    weekly_off_days VARCHAR(60),
    overtime_rate NUMERIC(5, 2),
    monthly_leave_accrual NUMERIC(5, 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT company_settings_company_unique UNIQUE (company_id)
);
```

- [ ] **Step 2: V28** — V14's exact enable+force+policy pattern for `company_settings`.
- [ ] **Step 3: Failing test** — penalties-template fixtures (phone prefix `+2021`). DTOs under test:

```java
public record UpdateCompanySettingsRequest(
        @Min(1) @Max(31) Short monthStartDay,
        @Min(1) @Max(31) Short monthEndDay,
        @Size(max = 60) String weeklyOffDays,
        @PositiveOrZero BigDecimal overtimeRate,
        @PositiveOrZero BigDecimal monthlyLeaveAccrual) {}
public record CompanySettingsView(
        Short monthStartDay, Short monthEndDay, String weeklyOffDays,
        BigDecimal overtimeRate, BigDecimal monthlyLeaveAccrual) {}
```

  Cases: GET with no row → 200, all fields null; PUT (start 26, end 25, accrual 15.5) → 200, GET round-trips; second PUT (start null) → 200 and SQL-assert exactly one row for the company with `month_start_day` now NULL; `monthStartDay` 0 and 32 → 400; negative `monthlyLeaveAccrual` → 400; company B's PUT then A's GET shows A's values only (isolation both directions); `company.settings.read` alone → 403 on PUT; unauthenticated → non-2xx. (All jakarta annotations are null-tolerant, so nulls pass validation by design.)
- [ ] **Step 4: Red** — `./gradlew compileTestJava` via WSL fails on missing symbols.
- [ ] **Step 5: Implement**:
  - Entity: the 7 mapped columns, `protected` no-arg + `CompanySettings(Long companyId)` constructor, one `apply(UpdateCompanySettingsRequest r)` mutator setting all five fields verbatim (nulls included — null is a real value).
  - Repository: `Optional<CompanySettings> findByCompanyId(Long companyId)`.
  - Service (`@Transactional`, `tenantSessionVariable.apply` first):
    - `view(context)` → `CompanySettingsView` (from the row or all-null).
    - `upsert(context, request)` → find-or-new + `apply` + `saveAndFlush`; catch `DataIntegrityViolationException` → throw `ResponseStatusException(CONFLICT)` (the leave-balances precedent — a lost concurrent-insert race; not reachable in tests, still guarded).
    - `effective(Long companyId)` (no context param — called by other modules inside their own established tenant transactions, the `SalaryContractService.findEffectiveContract` precedent) → `EffectiveCompanySettings(int monthStartDay /*fallback 1*/, Integer monthEndDay /*null = month's last day*/, BigDecimal monthlyLeaveAccrual /*fallback 21.0*/)`.
  - Controller `/api/tenant/company-settings`: GET (`COMPANY_SETTINGS_READ`) → 200; PUT (`COMPANY_SETTINGS_MANAGE`, `@Valid`) → 200 view. No path variables anywhere.
- [ ] **Step 6: Green** — run the class via WSL; verify XML counts.
- [ ] **Step 7: Commit** — `feat(backend): typed company-settings module (V27/V28) -- EAV collapsed, null means legacy fallback`.

### Task 2: Consumer rewiring (TDD)

**Files:**

- Modify: `backend/src/main/java/com/workin/backend/payroll/PayrollBatchService.java` (the `create` method's period computation + constructor injection of `CompanySettingsService`)
- Modify: `backend/src/main/java/com/workin/backend/requests/RequestService.java` (`applyLeaveDeduction`'s fallback + constructor injection)
- Test: `backend/src/test/java/com/workin/backend/payroll/PayrollBatchLifecycleTest.java` (add 2 cases), `backend/src/test/java/com/workin/backend/requests/RequestModuleFlowTest.java` (add 1 case)

**Interfaces:**

- Consumes: `CompanySettingsService.effective(Long companyId)` → `EffectiveCompanySettings(int monthStartDay, Integer monthEndDay, BigDecimal monthlyLeaveAccrual)` from Task 1.
- Payroll period algorithm (legacy `payroll_fiscal_period_bounds`, ported exactly — replaces `ym.atDay(1)`/`ym.atEndOfMonth()` in `create`):

```java
EffectiveCompanySettings settings = companySettingsService.effective(context.companyId());
YearMonth ym = YearMonth.of(year, month);
int lastDom = ym.lengthOfMonth();
int startDay = settings.monthStartDay();
int endDay = settings.monthEndDay() != null ? settings.monthEndDay() : lastDom;
LocalDate from;
LocalDate to = ym.atDay(Math.min(endDay, lastDom));
if (startDay <= endDay) {
    from = ym.atDay(Math.min(startDay, lastDom));
} else {
    YearMonth prev = ym.minusMonths(1);
    from = prev.atDay(Math.min(startDay, prev.lengthOfMonth()));
}
```

- Leave rewiring: in `RequestService.applyLeaveDeduction`, replace `FALLBACK_TOTAL_DAYS` with `companySettingsService.effective(companyId).monthlyLeaveAccrual()`; delete the constant (the 21.0 fallback now lives in one place, `effective`).

- [ ] **Step 1: Failing tests** — add to `PayrollBatchLifecycleTest`: (a) `fiscalPeriodSettingsShiftTheBatchWindow` — PUT settings start=26/end=25 via the API, create a March 2026 batch, assert `periodFrom() == 2026-02-26` and `periodTo() == 2026-03-25`; (b) `fiscalStartDayCapsToTheMonthsLastDay` — PUT start=31/end=null, create a February 2026 batch, assert `periodFrom() == 2026-02-28` and `periodTo() == 2026-02-28`. Add to `RequestModuleFlowTest`: `configuredAccrualDrivesAutoCreatedBalances` — PUT `monthlyLeaveAccrual` 15.5 via the API, approve a 3-day deduct-balance request with no balance row, SQL-assert total 15.5/used 3.0. (The admin fixture holds the full `COMPANY_ADMIN` catalog, so the settings PUT needs no extra permission setup.)
- [ ] **Step 2: Red** — run the two classes via WSL; the three new cases FAIL (calendar-month periods / 21.0 total), everything old passes.
- [ ] **Step 3: Implement** per Interfaces; **Step 4: Green** — both classes fully green, old cases untouched; **Step 5: Commit** — `feat(backend): wire payroll fiscal periods and leave accrual to company settings`.

### Task 3: Full verification + docs

- [ ] Full suite `--rerun` via WSL; totals from XML.
- [ ] `docs/bootstrap/open-questions.md`: the payroll fiscal-period bullet and the requests/leave `MONTHLY_LEAVE_ACCRUAL` bullet each gain a **Resolved 2026-08-07** note pointing at the module (do not delete the questions — the file's convention keeps history inline).
- [ ] Matrix: no F-18 row change (the surface has no cross-tenant ids — noted in the commit message instead); `hr-legacy#14`'s row is untouched (housing allowance is salary-contract domain, not settings).
- [ ] `python3 scripts/validate_phase0.py` exit 0; `markdownlint-cli2@0.23.2` clean.
- [ ] Commit `docs(bootstrap): resolve the two company-settings open questions`; PR after human push.
