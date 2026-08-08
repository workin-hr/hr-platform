# Employee Schedule Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Employee shift-assignment history, per-day schedule rows (manual + generated), and the three schedules endpoints, per `docs/superpowers/specs/2026-08-08-employee-schedule-foundation-design.md` — the foundation the attendance-calendar engine, weekly-rest credit, and payroll reconciliation all consume.

**Architecture:** New flat package `com.workin.backend.schedule` (one-package-per-bounded-concept, the `organization`/`payroll` precedent) holding two entity/repository pairs, one `ScheduleService`, one `ScheduleController`, and a pure `DaysOffParser`. V33 (two tables + two permission keys) + V34 (RLS). The employee create/update integration stays in `com.workin.backend.employees`, importing the schedule repository the same way it already imports organization repositories.

**Tech Stack:** Java 25, Spring Boot 4.1.0, JPA, Flyway, Gradle. Tests: real-Postgres Testcontainers (`postgres:17-alpine`) via `AbstractIntegrationTest` — Docker must be running. Run a single test class from `backend/`: `./gradlew test --tests "com.workin.backend.schedule.ScheduleModuleFlowTest"`.

## Global Constraints

- **Owner decisions (recorded 2026-08-08, resolving the spec's open questions):** shift is **optional** at employee create/update (matching nullable `branchId`/`departmentId`/`jobTitleId`); new **`schedules.read` / `schedules.manage`** permission keys (not `SHIFTS_*` reuse). The work-hours fallback decision (two-level: `job_title.work_hours` → 8) belongs to the calendar-engine slice and is not used here.
- Legacy source of truth: `hr-legacy/apis/helpers/schedule_helper.php` @ commit `d113204`, ported 1:1. Deviations must be stated in a code comment at the deviation site.
- Every `@Transactional` service method starts with `tenantSessionVariable.apply(context.companyId())`; queries carry an explicit `company_id` filter on top of RLS (enforcement layers 4 and 5). Cross-tenant or nonexistent ids → uniform 404 (the caller cannot distinguish "not yours" from "does not exist").
- Employee-targeted reads/writes also pass `ResourceScopeService.isEmployeeInScope` (the platform-wide boundary-3 mechanism already applied in `AttendanceService`); out-of-scope → the same 404.
- Every controller handler carries `@RequiresPermission` (`AuthorizationPolicyArchTest` enforces this). Permission strings exist **only** in `PermissionKeys` + the V4-catalog migrations (`PermissionCatalogSyncTest` enforces the bidirectional match).
- Records for requests/views, no Lombok, tab indentation, Javadoc that states constraints not narration.
- No i18n infrastructure exists — the weekly-rest label is the constant English string `"Weekly rest"`, day names are English (`Sunday`…`Saturday`); localization is a recorded normalization from legacy's `t(LangKey::SCHEDULE_WEEKLY_REST)` / Arabic labels.
- Legacy day-of-week numbering (0=Sunday…6=Saturday, PHP `format('w')`) is the **wire format** for `dayOfWeek` fields; internally use `java.time.DayOfWeek` and convert via `DaysOffParser.toLegacyIndex` (`dow.getValue() % 7`).
- Commit style: `type(scope): message` (e.g. `feat(schedule): …`).
- Run `markdownlint-cli2@0.23.2` on `**/*.md` before any docs push.

## File Structure

| File | Responsibility |
|---|---|
| `db/migration/common/V33__create_employee_schedule_foundation.sql` | Two tables, indexes, `schedules.*` catalog keys + COMPANY_ADMIN grant |
| `db/migration/rls/V34__enable_employee_schedule_row_level_security.sql` | RLS enable+force for both tables |
| `authorization/PermissionKeys.java` (modify) | `SCHEDULES_READ` / `SCHEDULES_MANAGE` constants |
| `schedule/DaysOffParser.java` | Pure token→`DayOfWeek` parsing (shift `days_off` text; company `weekly_off_days` values incl. numeric) |
| `schedule/EmployeeShiftAssignment.java` + `EmployeeShiftAssignmentRepository.java` | Append-only date-effective history |
| `schedule/EmployeeSchedule.java` + `EmployeeScheduleRepository.java` | Per-employee per-date rows, upsert target |
| `schedule/ScheduleService.java` | Ported computation + the three use cases |
| `schedule/ScheduleController.java` | Thin delegation, 3 endpoints |
| `schedule/AssignScheduleRequest.java`, `GenerateScheduleRequest.java` | Request records |
| `schedule/MonthlyOverviewView.java`, `ShiftSummaryView.java`, `WeeklyRestDayView.java`, `HolidayView.java`, `ScheduleDayView.java`, `GenerateResultView.java` | Response records |
| `companysettings/EffectiveCompanySettings.java` + `CompanySettingsService.java` (modify) | `weeklyOffDays` resolved accessor (fallback: empty list) |
| `employees/CreateEmployeeRequest.java`, `UpdateEmployeeRequest.java`, `EmployeeService.java` (modify) | Optional `shiftId` integration |
| `test/…/schedule/DaysOffParserTest.java` | Pure unit tests, no Spring |
| `test/…/schedule/ScheduleModuleFlowTest.java` | Integration flow + negatives |

---

### Task 1: Schema + permission keys (V33, V34, PermissionKeys)

**Files:**

- Create: `backend/src/main/resources/db/migration/common/V33__create_employee_schedule_foundation.sql`
- Create: `backend/src/main/resources/db/migration/rls/V34__enable_employee_schedule_row_level_security.sql`
- Modify: `backend/src/main/java/com/workin/backend/authorization/PermissionKeys.java` (add two constants after `SHIFTS_MANAGE`)

**Interfaces:**

- Consumes: nothing new.
- Produces: tables `employee_shift_assignments`, `employee_schedules` (columns exactly as below — Tasks 3–6 map them); catalog keys `schedules.read`/`schedules.manage`; constants `PermissionKeys.SCHEDULES_READ`, `PermissionKeys.SCHEDULES_MANAGE`.

- [ ] **Step 1: Write V33**

```sql
-- Employee-schedule foundation
-- (docs/superpowers/specs/2026-08-08-employee-schedule-foundation-design.md).
-- Translated from hr-legacy/mysql_workin.schema.sql:455-482 @ d113204.
-- Recorded normalizations: both tables gain a denormalized company_id
-- for RLS (legacy has no tenant column here -- the same treatment V29
-- gave department_branches); employee_schedules gets an explicit
-- UNIQUE (employee_id, schedule_date) -- legacy relies on an implicit
-- unique key for ON DUPLICATE KEY UPDATE, Postgres needs it declared.
CREATE TABLE employee_shift_assignments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    shift_id BIGINT NOT NULL REFERENCES shifts (id),
    effective_from DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX employee_shift_assignments_lookup_idx
    ON employee_shift_assignments (employee_id, effective_from DESC);
CREATE INDEX employee_shift_assignments_company_id_idx
    ON employee_shift_assignments (company_id);

CREATE TABLE employee_schedules (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    schedule_date DATE NOT NULL,
    name VARCHAR(255),
    start_time TIME,
    end_time TIME,
    exception_note VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT employee_schedules_employee_date_unique UNIQUE (employee_id, schedule_date)
);
CREATE INDEX employee_schedules_company_id_idx ON employee_schedules (company_id);

-- Owner decision 2026-08-08: dedicated schedules.* keys (the
-- key-per-module pattern), a recorded departure from legacy's
-- role-only gate on apis/api/schedules/*.
INSERT INTO permissions (permission_key, description) VALUES
    ('schedules.read', 'View employee schedules and monthly overview (legacy: role-only gate)'),
    ('schedules.manage', 'Assign and generate employee schedule days (legacy: role-only gate)');

INSERT INTO role_permissions (role, permission_id)
SELECT 'COMPANY_ADMIN', p.id FROM permissions p
WHERE p.permission_key IN ('schedules.read', 'schedules.manage');
```

- [ ] **Step 2: Write V34**

```sql
-- Same fail-closed pattern as rls/V5 through rls/V32: FORCE ROW LEVEL
-- SECURITY, NULLIF(...) so an unset app.current_company_id resolves to
-- NULL -- zero rows visible by default, not fail-open.
ALTER TABLE employee_shift_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_shift_assignments FORCE ROW LEVEL SECURITY;

CREATE POLICY employee_shift_assignments_isolation ON employee_shift_assignments
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE employee_schedules ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_schedules FORCE ROW LEVEL SECURITY;

CREATE POLICY employee_schedules_isolation ON employee_schedules
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);
```

- [ ] **Step 3: Run the sync test to verify it fails**

Run: `./gradlew test --tests "com.workin.backend.authorization.PermissionCatalogSyncTest"`
Expected: FAIL — the catalog now contains `schedules.read`/`schedules.manage` with no matching `PermissionKeys` constants (this also proves both migrations apply cleanly, since the test boots the app against a fresh container).

- [ ] **Step 4: Add the constants**

In `PermissionKeys.java`, directly after `SHIFTS_MANAGE`:

```java
    public static final String SCHEDULES_READ = "schedules.read";
    public static final String SCHEDULES_MANAGE = "schedules.manage";
```

- [ ] **Step 5: Run the sync test to verify it passes**

Run: `./gradlew test --tests "com.workin.backend.authorization.PermissionCatalogSyncTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration backend/src/main/java/com/workin/backend/authorization/PermissionKeys.java
git commit -m "feat(schedule): employee schedule schema, RLS, and schedules.* permission keys"
```

---

### Task 2: DaysOffParser (pure logic)

**Files:**

- Create: `backend/src/main/java/com/workin/backend/schedule/DaysOffParser.java`
- Test: `backend/src/test/java/com/workin/backend/schedule/DaysOffParserTest.java`

**Interfaces:**

- Consumes: nothing.
- Produces (Tasks 3–5 call these):
  - `static Set<DayOfWeek> parseDaysOff(String daysOff)` — shift `days_off` free text (name tokens only, legacy `schedule_parse_days_off_to_dows`).
  - `static Set<DayOfWeek> parseCompanyRestDays(List<String> values)` — company `weekly_off_days` values (name tokens **plus** numeric `0`–`6`, legacy `schedule_company_weekly_rest_dows`).
  - `static int toLegacyIndex(DayOfWeek dow)` — 0=Sunday…6=Saturday.
  - `static String englishLabel(DayOfWeek dow)` — `Sunday`…`Saturday`.

- [ ] **Step 1: Write the failing test**

```java
package com.workin.backend.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Token map ported verbatim from schedule_helper.php @ d113204,
 * including both hamza spellings for Sunday/Monday/Wednesday.
 */
class DaysOffParserTest {

    @Test
    void everyLegacyTokenResolvesToItsDay() {
        assertThat(DaysOffParser.parseDaysOff("Fri")).containsExactly(DayOfWeek.FRIDAY);
        assertThat(DaysOffParser.parseDaysOff("friday")).containsExactly(DayOfWeek.FRIDAY);
        assertThat(DaysOffParser.parseDaysOff("الجمعة")).containsExactly(DayOfWeek.FRIDAY);
        assertThat(DaysOffParser.parseDaysOff("الأحد")).containsExactly(DayOfWeek.SUNDAY);
        assertThat(DaysOffParser.parseDaysOff("الاحد")).containsExactly(DayOfWeek.SUNDAY);
        assertThat(DaysOffParser.parseDaysOff("الإثنين")).containsExactly(DayOfWeek.MONDAY);
        assertThat(DaysOffParser.parseDaysOff("الاثنين")).containsExactly(DayOfWeek.MONDAY);
        assertThat(DaysOffParser.parseDaysOff("الثلاثاء")).containsExactly(DayOfWeek.TUESDAY);
        assertThat(DaysOffParser.parseDaysOff("الأربعاء")).containsExactly(DayOfWeek.WEDNESDAY);
        assertThat(DaysOffParser.parseDaysOff("الاربعاء")).containsExactly(DayOfWeek.WEDNESDAY);
        assertThat(DaysOffParser.parseDaysOff("الخميس")).containsExactly(DayOfWeek.THURSDAY);
        assertThat(DaysOffParser.parseDaysOff("السبت")).containsExactly(DayOfWeek.SATURDAY);
        assertThat(DaysOffParser.parseDaysOff("Sun,Mon,Tue,Wed,Thu"))
                .containsExactlyInAnyOrder(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);
    }

    @Test
    void splitsOnLatinAndArabicCommaAndSemicolon() {
        assertThat(DaysOffParser.parseDaysOff("Fri،Sat;Sun"))
                .containsExactlyInAnyOrder(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    @Test
    void blankUnknownAndEmptyTokensAreIgnored() {
        assertThat(DaysOffParser.parseDaysOff(null)).isEmpty();
        assertThat(DaysOffParser.parseDaysOff("  ")).isEmpty();
        assertThat(DaysOffParser.parseDaysOff("Fri,,notaday, ")).containsExactly(DayOfWeek.FRIDAY);
    }

    @Test
    void shiftDaysOffDoesNotAcceptNumericTokens() {
        // Legacy schedule_parse_days_off_to_dows has no is_numeric branch.
        assertThat(DaysOffParser.parseDaysOff("5")).isEmpty();
    }

    @Test
    void companyValuesAcceptNamesAndLegacyNumericIndexes() {
        assertThat(DaysOffParser.parseCompanyRestDays(List.of("friday", "6")))
                .containsExactlyInAnyOrder(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
        assertThat(DaysOffParser.parseCompanyRestDays(List.of("0"))).containsExactly(DayOfWeek.SUNDAY);
        // Out-of-range numerics never match any real day in legacy
        // (format('w') is 0-6); dropping them is the equivalent behavior.
        assertThat(DaysOffParser.parseCompanyRestDays(List.of("9"))).isEmpty();
        assertThat(DaysOffParser.parseCompanyRestDays(List.of())).isEmpty();
    }

    @Test
    void legacyIndexAndLabelConversions() {
        assertThat(DaysOffParser.toLegacyIndex(DayOfWeek.SUNDAY)).isZero();
        assertThat(DaysOffParser.toLegacyIndex(DayOfWeek.SATURDAY)).isEqualTo(6);
        assertThat(DaysOffParser.englishLabel(DayOfWeek.SUNDAY)).isEqualTo("Sunday");
    }

}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "com.workin.backend.schedule.DaysOffParserTest"`
Expected: COMPILE FAILURE — `DaysOffParser` does not exist.

- [ ] **Step 3: Implement**

```java
package com.workin.backend.schedule;

import java.time.DayOfWeek;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Day-name token parsing ported verbatim from
 * hr-legacy/apis/helpers/schedule_helper.php @ d113204
 * (schedule_parse_days_off_to_dows / schedule_company_weekly_rest_dows),
 * including the intentional double spellings (with and without hamza)
 * for Sunday, Monday, and Wednesday. Shift days_off accepts name
 * tokens only; company weekly_off_days values additionally accept
 * legacy numeric indexes 0=Sunday..6=Saturday.
 */
public final class DaysOffParser {

    private static final Pattern SPLITTER = Pattern.compile("[,،;]+");

    private static final Map<String, DayOfWeek> TOKENS = Map.ofEntries(
            Map.entry("sunday", DayOfWeek.SUNDAY), Map.entry("monday", DayOfWeek.MONDAY),
            Map.entry("tuesday", DayOfWeek.TUESDAY), Map.entry("wednesday", DayOfWeek.WEDNESDAY),
            Map.entry("thursday", DayOfWeek.THURSDAY), Map.entry("friday", DayOfWeek.FRIDAY),
            Map.entry("saturday", DayOfWeek.SATURDAY),
            Map.entry("sun", DayOfWeek.SUNDAY), Map.entry("mon", DayOfWeek.MONDAY),
            Map.entry("tue", DayOfWeek.TUESDAY), Map.entry("wed", DayOfWeek.WEDNESDAY),
            Map.entry("thu", DayOfWeek.THURSDAY), Map.entry("fri", DayOfWeek.FRIDAY),
            Map.entry("sat", DayOfWeek.SATURDAY),
            Map.entry("الأحد", DayOfWeek.SUNDAY), Map.entry("الاحد", DayOfWeek.SUNDAY),
            Map.entry("الإثنين", DayOfWeek.MONDAY), Map.entry("الاثنين", DayOfWeek.MONDAY),
            Map.entry("الثلاثاء", DayOfWeek.TUESDAY),
            Map.entry("الأربعاء", DayOfWeek.WEDNESDAY), Map.entry("الاربعاء", DayOfWeek.WEDNESDAY),
            Map.entry("الخميس", DayOfWeek.THURSDAY),
            Map.entry("الجمعة", DayOfWeek.FRIDAY),
            Map.entry("السبت", DayOfWeek.SATURDAY));

    public static Set<DayOfWeek> parseDaysOff(String daysOff) {
        Set<DayOfWeek> out = new LinkedHashSet<>();
        if (daysOff == null || daysOff.trim().isEmpty()) {
            return out;
        }
        for (String part : SPLITTER.split(daysOff)) {
            DayOfWeek day = TOKENS.get(part.trim().toLowerCase(Locale.ROOT));
            if (day != null) {
                out.add(day);
            }
        }
        return out;
    }

    public static Set<DayOfWeek> parseCompanyRestDays(List<String> values) {
        Set<DayOfWeek> out = new LinkedHashSet<>();
        for (String raw : values) {
            String token = raw == null ? "" : raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.chars().allMatch(Character::isDigit)) {
                int index = Integer.parseInt(token);
                if (index >= 0 && index <= 6) {
                    out.add(fromLegacyIndex(index));
                }
                continue;
            }
            DayOfWeek day = TOKENS.get(token.toLowerCase(Locale.ROOT));
            if (day != null) {
                out.add(day);
            }
        }
        return out;
    }

    /** 0=Sunday..6=Saturday, PHP date('w') -- the legacy wire format. */
    public static int toLegacyIndex(DayOfWeek dow) {
        return dow.getValue() % 7;
    }

    public static DayOfWeek fromLegacyIndex(int legacyIndex) {
        return legacyIndex == 0 ? DayOfWeek.SUNDAY : DayOfWeek.of(legacyIndex);
    }

    public static String englishLabel(DayOfWeek dow) {
        return dow.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
    }

    private DaysOffParser() {
    }

}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests "com.workin.backend.schedule.DaysOffParserTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/workin/backend/schedule/DaysOffParser.java backend/src/test/java/com/workin/backend/schedule/DaysOffParserTest.java
git commit -m "feat(schedule): port legacy days-off token parser"
```

---

### Task 3: Entities, repositories, weeklyOffDays accessor, monthly-overview endpoint

**Files:**

- Create: `backend/src/main/java/com/workin/backend/schedule/EmployeeShiftAssignment.java`
- Create: `backend/src/main/java/com/workin/backend/schedule/EmployeeShiftAssignmentRepository.java`
- Create: `backend/src/main/java/com/workin/backend/schedule/EmployeeSchedule.java`
- Create: `backend/src/main/java/com/workin/backend/schedule/EmployeeScheduleRepository.java`
- Create: `backend/src/main/java/com/workin/backend/schedule/ScheduleService.java`
- Create: `backend/src/main/java/com/workin/backend/schedule/ScheduleController.java`
- Create: `backend/src/main/java/com/workin/backend/schedule/MonthlyOverviewView.java`, `ShiftSummaryView.java`, `WeeklyRestDayView.java`, `HolidayView.java`, `ScheduleDayView.java`
- Modify: `backend/src/main/java/com/workin/backend/companysettings/EffectiveCompanySettings.java` (add 4th component)
- Modify: `backend/src/main/java/com/workin/backend/companysettings/CompanySettingsService.java` (`effective()` builds it)
- Test: `backend/src/test/java/com/workin/backend/schedule/ScheduleModuleFlowTest.java`

**Interfaces:**

- Consumes: Task 1's tables/keys, Task 2's `DaysOffParser`; existing `ShiftRepository.findByIdAndCompanyId(Long, Long)`, `EmployeeRepository.findByIdAndCompanyId(Long, Long)`, `ResourceScopeService.isEmployeeInScope(AuthorizationContext, Long)`, `TenantSessionVariable.apply(Long)`, `CompanySettingsService.effective(Long)`.
- Produces (Tasks 4–6 and the future calendar engine rely on these exact signatures):
  - `ScheduleService.assignmentOnDate(Long companyId, Long employeeId, LocalDate onDate)` → `Optional<EmployeeShiftAssignment>`
  - `ScheduleService.shiftForEmployeeOnDate(Long companyId, Long employeeId, LocalDate onDate)` → `Optional<Shift>`
  - `ScheduleService.isWeeklyRestDay(Long companyId, Shift shift, LocalDate date)` → `boolean`
  - `ScheduleService.monthlyOverview(AuthorizationContext context, Long employeeId, int year, int month)` → `Optional<MonthlyOverviewView>`
  - `EmployeeShiftAssignmentRepository` (Task 6 injects it into `EmployeeService`)
  - `EffectiveCompanySettings(int monthStartDay, Integer monthEndDay, BigDecimal monthlyLeaveAccrual, List<String> weeklyOffDays)` — note: 4-component record; the only constructor call site is `CompanySettingsService.effective()`; `PayrollBatchService` reads components by name and is unaffected.
  - `GET /api/tenant/schedules/{employeeId}/monthly?year=&month=` → 200 `MonthlyOverviewView` | 404 | 403.

- [ ] **Step 1: Write the failing flow tests**

Create `ScheduleModuleFlowTest` with the fixture helpers and the first three tests. Fixture style is copied from `OrganizationStructureFlowTest` (same `flywayDataSource` jdbc seeding, same register/bearer helpers). March 2026 is used everywhere because it is fully in the past (deterministic `summaryDate` = last day of month) and 2026-03-01 is a Sunday, so Fridays are 6/13/20/27 and Saturdays 7/14/21/28.

```java
package com.workin.backend.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * Schedule foundation flow: assignment-history resolution, weekly-rest
 * union (shift days_off + company weekly_off_days), manual-row
 * precedence, destructive generate, and the F-18 negatives. Fixed
 * historical months keep summaryDate deterministic (LocalDate.now()
 * only matters when the requested month is the current one).
 */
class ScheduleModuleFlowTest extends AbstractIntegrationTest {

    private static final AtomicLong PHONE = new AtomicLong(7_000_000_000L);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    @Qualifier("flywayDataSource")
    private DataSource flywayDataSource;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(flywayDataSource);
    }

    private static String uniquePhone() {
        return "+2" + PHONE.incrementAndGet();
    }

    private AuthResponse registerCompanyAdmin() {
        return restTemplate.postForEntity(
                "/api/auth/register",
                new RegisterCompanyRequest("Schedule Co", uniquePhone(), "correct horse battery staple"),
                AuthResponse.class).getBody();
    }

    private Long createEmployee(Long companyId) {
        return jdbc().queryForObject(
                "INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'Sched', 'Emp') RETURNING id",
                Long.class, companyId);
    }

    private Long createShift(Long companyId, String name, String start, String end, String daysOff) {
        return jdbc().queryForObject(
                "INSERT INTO shifts (company_id, name, start_time, end_time, days_off) "
                        + "VALUES (?, ?, ?::time, ?::time, ?) RETURNING id",
                Long.class, companyId, name, start, end, daysOff);
    }

    private void insertAssignment(Long companyId, Long employeeId, Long shiftId, String effectiveFrom) {
        jdbc().update(
                "INSERT INTO employee_shift_assignments (company_id, employee_id, shift_id, effective_from) "
                        + "VALUES (?, ?, ?, ?::date)",
                companyId, employeeId, shiftId, effectiveFrom);
    }

    private void setCompanyWeeklyOffDays(Long companyId, String value) {
        jdbc().update(
                "INSERT INTO company_settings (company_id, weekly_off_days) VALUES (?, ?)",
                companyId, value);
    }

    private HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<MonthlyOverviewView> monthly(String token, Long employeeId, int year, int month) {
        return restTemplate.exchange(
                "/api/tenant/schedules/" + employeeId + "/monthly?year=" + year + "&month=" + month,
                HttpMethod.GET, new HttpEntity<>(bearer(token)), MonthlyOverviewView.class);
    }

    @Test
    void monthlyOverviewResolvesAssignmentHistory() {
        AuthResponse admin = registerCompanyAdmin();
        Long employeeId = createEmployee(admin.companyId());
        Long shiftA = createShift(admin.companyId(), "Shift A", "09:00", "17:00", null);
        Long shiftB = createShift(admin.companyId(), "Shift B", "10:00", "18:00", null);
        Long shiftC = createShift(admin.companyId(), "Shift C", "11:00", "19:00", null);
        insertAssignment(admin.companyId(), employeeId, shiftA, "2026-03-01");
        insertAssignment(admin.companyId(), employeeId, shiftB, "2026-03-10");
        insertAssignment(admin.companyId(), employeeId, shiftC, "2026-05-01");

        ResponseEntity<MonthlyOverviewView> response = monthly(admin.accessToken(), employeeId, 2026, 3);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MonthlyOverviewView body = response.getBody();
        // summaryDate = 2026-03-31 (past month): B is current, next is C.
        assertThat(body.shift().shiftId()).isEqualTo(shiftB);
        assertThat(body.shift().effectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(body.shift().effectiveTo()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(body.days()).hasSize(31);
        // The exact "latest effective_from <= date" rule: A on days 1-9, B from day 10.
        assertThat(body.days().get(0).name()).isEqualTo("Shift A");
        assertThat(body.days().get(8).name()).isEqualTo("Shift A");
        assertThat(body.days().get(9).name()).isEqualTo("Shift B");
        assertThat(body.days().get(30).name()).isEqualTo("Shift B");
        assertThat(body.days().get(9).startTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void monthlyOverviewWithoutAssignmentReturnsScaffolding() {
        AuthResponse admin = registerCompanyAdmin();
        Long employeeId = createEmployee(admin.companyId());

        ResponseEntity<MonthlyOverviewView> response = monthly(admin.accessToken(), employeeId, 2026, 3);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MonthlyOverviewView body = response.getBody();
        assertThat(body.shift()).isNull();
        assertThat(body.weeklyRestDays()).isEmpty();
        assertThat(body.officialHolidays()).isEmpty();
        assertThat(body.days()).isEmpty();
    }

    @Test
    void weeklyRestDaysAreShiftUnionCompany() {
        AuthResponse admin = registerCompanyAdmin();
        Long employeeId = createEmployee(admin.companyId());
        // Arabic token on the shift exercises the legacy token map end to end.
        Long shift = createShift(admin.companyId(), "Day", "09:00", "17:00", "الجمعة");
        setCompanyWeeklyOffDays(admin.companyId(), "Sat");
        insertAssignment(admin.companyId(), employeeId, shift, "2026-03-01");

        MonthlyOverviewView body = monthly(admin.accessToken(), employeeId, 2026, 3).getBody();

        assertThat(body.weeklyRestDays())
                .containsExactly(new WeeklyRestDayView(5, "Friday"), new WeeklyRestDayView(6, "Saturday"));
        // 2026-03-06 is a Friday: rest label suppresses the shift columns.
        ScheduleDayView friday = body.days().get(5);
        assertThat(friday.scheduleDate()).isEqualTo(LocalDate.of(2026, 3, 6));
        assertThat(friday.exception()).isEqualTo("Weekly rest");
        assertThat(friday.name()).isNull();
        assertThat(friday.startTime()).isNull();
        // 2026-03-07 is a Saturday: company setting alone also marks rest.
        assertThat(body.days().get(6).exception()).isEqualTo("Weekly rest");
        // An ordinary weekday keeps the shift snapshot.
        assertThat(body.days().get(1).name()).isEqualTo("Day");
        assertThat(body.days().get(1).exception()).isNull();
    }

}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.workin.backend.schedule.ScheduleModuleFlowTest"`
Expected: COMPILE FAILURE — views/service/controller don't exist yet.

- [ ] **Step 3: Implement the entities**

`EmployeeShiftAssignment.java`:

```java
package com.workin.backend.schedule;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Date-effective shift assignment (V33). Append-only by design: legacy
 * never updates or deletes an assignment row, only inserts new ones --
 * so no mutators and no repository delete/update methods exist.
 */
@Entity
@Table(name = "employee_shift_assignments")
public class EmployeeShiftAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    protected EmployeeShiftAssignment() {
    }

    public EmployeeShiftAssignment(Long companyId, Long employeeId, Long shiftId, LocalDate effectiveFrom) {
        this.companyId = companyId;
        this.employeeId = employeeId;
        this.shiftId = shiftId;
        this.effectiveFrom = effectiveFrom;
    }

    public Long getId() {
        return id;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getShiftId() {
        return shiftId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

}
```

`EmployeeSchedule.java`:

```java
package com.workin.backend.schedule;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-employee per-date schedule row (V33). One row per
 * (employee, date) -- the DB UNIQUE constraint is the concurrency
 * backstop behind the service's read-then-write upsert.
 */
@Entity
@Table(name = "employee_schedules")
public class EmployeeSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "schedule_date", nullable = false)
    private LocalDate scheduleDate;

    @Column
    private String name;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "exception_note")
    private String exceptionNote;

    protected EmployeeSchedule() {
    }

    public EmployeeSchedule(Long companyId, Long employeeId, LocalDate scheduleDate) {
        this.companyId = companyId;
        this.employeeId = employeeId;
        this.scheduleDate = scheduleDate;
    }

    /** Legacy ON DUPLICATE KEY UPDATE overwrites all four columns. */
    public void snapshot(String name, LocalTime startTime, LocalTime endTime, String exceptionNote) {
        this.name = name;
        this.startTime = startTime;
        this.endTime = endTime;
        this.exceptionNote = exceptionNote;
    }

    public Long getId() {
        return id;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public LocalDate getScheduleDate() {
        return scheduleDate;
    }

    public String getName() {
        return name;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getExceptionNote() {
        return exceptionNote;
    }

}
```

- [ ] **Step 4: Implement the repositories**

`EmployeeShiftAssignmentRepository.java`:

```java
package com.workin.backend.schedule;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only: intentionally no update/delete queries. */
public interface EmployeeShiftAssignmentRepository extends JpaRepository<EmployeeShiftAssignment, Long> {

    /** schedule_shift_for_employee_on_date's exact rule (effective_from DESC, id DESC tiebreak). */
    Optional<EmployeeShiftAssignment> findFirstByEmployeeIdAndCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDescIdDesc(
            Long employeeId, Long companyId, LocalDate onDate);

    /** The next assignment after a given effective_from -- schedule_shift_summary's effective_to lookup. */
    Optional<EmployeeShiftAssignment> findFirstByEmployeeIdAndCompanyIdAndEffectiveFromGreaterThanOrderByEffectiveFromAscIdAsc(
            Long employeeId, Long companyId, LocalDate afterDate);

}
```

`EmployeeScheduleRepository.java`:

```java
package com.workin.backend.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeScheduleRepository extends JpaRepository<EmployeeSchedule, Long> {

    List<EmployeeSchedule> findByEmployeeIdAndCompanyIdAndScheduleDateBetweenOrderByScheduleDateAsc(
            Long employeeId, Long companyId, LocalDate from, LocalDate to);

    Optional<EmployeeSchedule> findByEmployeeIdAndCompanyIdAndScheduleDate(
            Long employeeId, Long companyId, LocalDate scheduleDate);

    /**
     * Bulk JPQL delete, executed immediately -- a derived deleteBy...
     * would queue entity removals that Hibernate flushes AFTER the
     * regeneration's inserts, violating the (employee_id, schedule_date)
     * unique constraint. Generate (Task 5) depends on this ordering.
     */
    @Modifying
    @Query("DELETE FROM EmployeeSchedule s WHERE s.employeeId = :employeeId "
            + "AND s.companyId = :companyId AND s.scheduleDate BETWEEN :from AND :to")
    void deleteRange(@Param("employeeId") Long employeeId, @Param("companyId") Long companyId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

}
```

- [ ] **Step 5: Extend EffectiveCompanySettings**

Replace the record in `EffectiveCompanySettings.java`:

```java
package com.workin.backend.companysettings;

import java.math.BigDecimal;
import java.util.List;

/**
 * Scalar reads with the legacy fallbacks already applied --
 * {@code monthEndDay} stays nullable because its fallback (the
 * month's last day) depends on which month the caller is computing.
 * {@code weeklyOffDays} is the split, trimmed token list from the
 * weekly_off_days column; unset resolves to an empty list (legacy's
 * schedule_company_weekly_rest_dows returns [] on empty input).
 */
public record EffectiveCompanySettings(
        int monthStartDay, Integer monthEndDay, BigDecimal monthlyLeaveAccrual, List<String> weeklyOffDays) {
}
```

In `CompanySettingsService.effective()` (the record's only constructor call site), replace the return with:

```java
        return new EffectiveCompanySettings(
                settings.map(CompanySettings::getMonthStartDay).map(Short::intValue).orElse(FALLBACK_MONTH_START_DAY),
                settings.map(CompanySettings::getMonthEndDay).map(Short::intValue).orElse(null),
                settings.map(CompanySettings::getMonthlyLeaveAccrual).orElse(FALLBACK_MONTHLY_LEAVE_ACCRUAL),
                settings.map(CompanySettings::getWeeklyOffDays)
                        .map(raw -> Arrays.stream(raw.split("[,،;]+"))
                                .map(String::trim).filter(s -> !s.isEmpty()).toList())
                        .orElse(List.of()));
```

(add `import java.util.Arrays;` and `import java.util.List;`).

- [ ] **Step 6: Implement the view records**

Five small files in `com.workin.backend.schedule`:

```java
public record ShiftSummaryView(
        Long shiftId, String name, java.time.LocalTime startTime, java.time.LocalTime endTime,
        java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveTo) {
}
```

```java
/** dayOfWeek keeps legacy's 0=Sunday..6=Saturday wire numbering. */
public record WeeklyRestDayView(int dayOfWeek, String name) {
}
```

```java
public record HolidayView(java.time.LocalDate date, String name) {
}
```

```java
/**
 * One calendar day. id is the employee_schedules row id for manual
 * rows, null for computed-from-shift rows (recorded normalization:
 * legacy returns 0 there).
 */
public record ScheduleDayView(
        Long id, java.time.LocalDate scheduleDate, String name,
        java.time.LocalTime startTime, java.time.LocalTime endTime, String exception) {
}
```

```java
/**
 * schedule_month_overview's shape. officialHolidays is a declared stub
 * (always empty) until the holidays module lands -- spec Out item.
 */
public record MonthlyOverviewView(
        ShiftSummaryView shift, java.util.List<WeeklyRestDayView> weeklyRestDays,
        java.util.List<HolidayView> officialHolidays, java.util.List<ScheduleDayView> days) {
}
```

(Write each with proper package/import statements rather than inline qualified names if preferred — match `ShiftView`'s existing style.)

- [ ] **Step 7: Implement ScheduleService (resolution core + monthly overview)**

```java
package com.workin.backend.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.authorization.ResourceScopeService;
import com.workin.backend.companysettings.CompanySettingsService;
import com.workin.backend.employees.EmployeeRepository;
import com.workin.backend.organization.Shift;
import com.workin.backend.organization.ShiftRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * Ported 1:1 from hr-legacy/apis/helpers/schedule_helper.php @ d113204.
 * Two distinct concepts, kept split exactly as legacy keeps them:
 * employee_shift_assignments = "what shift do they have going forward"
 * (append-only history); employee_schedules = "what does this specific
 * day look like" (manual rows win over computed-from-shift rows).
 * Official-holiday lookups are a declared stub (empty) until the
 * holidays module lands.
 */
@Service
public class ScheduleService {

    static final String WEEKLY_REST_LABEL = "Weekly rest";

    private final EmployeeShiftAssignmentRepository assignmentRepository;
    private final EmployeeScheduleRepository scheduleRepository;
    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    private final CompanySettingsService companySettingsService;
    private final ResourceScopeService resourceScopeService;
    private final TenantSessionVariable tenantSessionVariable;

    public ScheduleService(
            EmployeeShiftAssignmentRepository assignmentRepository,
            EmployeeScheduleRepository scheduleRepository,
            EmployeeRepository employeeRepository,
            ShiftRepository shiftRepository,
            CompanySettingsService companySettingsService,
            ResourceScopeService resourceScopeService,
            TenantSessionVariable tenantSessionVariable) {
        this.assignmentRepository = assignmentRepository;
        this.scheduleRepository = scheduleRepository;
        this.employeeRepository = employeeRepository;
        this.shiftRepository = shiftRepository;
        this.companySettingsService = companySettingsService;
        this.resourceScopeService = resourceScopeService;
        this.tenantSessionVariable = tenantSessionVariable;
    }

    /** Latest assignment with effective_from <= onDate (schedule_shift_for_employee_on_date). */
    public Optional<EmployeeShiftAssignment> assignmentOnDate(Long companyId, Long employeeId, LocalDate onDate) {
        return assignmentRepository
                .findFirstByEmployeeIdAndCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDescIdDesc(
                        employeeId, companyId, onDate);
    }

    /** The seam the attendance-calendar engine consumes; caller must hold a tenant transaction. */
    public Optional<Shift> shiftForEmployeeOnDate(Long companyId, Long employeeId, LocalDate onDate) {
        return assignmentOnDate(companyId, employeeId, onDate)
                .flatMap(a -> shiftRepository.findByIdAndCompanyId(a.getShiftId(), companyId));
    }

    /** schedule_is_weekly_rest_day: shift days_off OR company weekly_off_days marks the dow. */
    public boolean isWeeklyRestDay(Long companyId, Shift shift, LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return DaysOffParser.parseDaysOff(shift == null ? null : shift.getDaysOff()).contains(dow)
                || companyRestDays(companyId).contains(dow);
    }

    @Transactional(readOnly = true)
    public Optional<MonthlyOverviewView> monthlyOverview(
            AuthorizationContext context, Long employeeId, int year, int month) {
        tenantSessionVariable.apply(context.companyId());
        if (!employeeInScope(context, employeeId)) {
            return Optional.empty();
        }
        int clamped = Math.max(1, Math.min(12, month));
        LocalDate from = LocalDate.of(year, clamped, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
        LocalDate today = LocalDate.now();
        LocalDate summaryDate = (today.getYear() == year && today.getMonthValue() == clamped) ? today : to;

        Optional<EmployeeShiftAssignment> current = assignmentOnDate(context.companyId(), employeeId, summaryDate);
        ShiftSummaryView summary = current.map(a -> shiftSummary(context.companyId(), a)).orElse(null);
        List<WeeklyRestDayView> weeklyRest = current
                .flatMap(a -> shiftRepository.findByIdAndCompanyId(a.getShiftId(), context.companyId()))
                .map(shift -> weeklyRestDays(context.companyId(), shift))
                .orElse(List.of());

        return Optional.of(new MonthlyOverviewView(
                summary, weeklyRest, List.of(), computeDays(context.companyId(), employeeId, from, to)));
    }

    /** schedule_shift_summary: effective_to = day before the next assignment, or open-ended. */
    private ShiftSummaryView shiftSummary(Long companyId, EmployeeShiftAssignment assignment) {
        Shift shift = shiftRepository.findByIdAndCompanyId(assignment.getShiftId(), companyId).orElse(null);
        LocalDate effectiveTo = assignmentRepository
                .findFirstByEmployeeIdAndCompanyIdAndEffectiveFromGreaterThanOrderByEffectiveFromAscIdAsc(
                        assignment.getEmployeeId(), companyId, assignment.getEffectiveFrom())
                .map(next -> next.getEffectiveFrom().minusDays(1))
                .orElse(null);
        return new ShiftSummaryView(
                assignment.getShiftId(),
                shift != null ? shift.getName() : "",
                shift != null ? shift.getStartTime() : null,
                shift != null ? shift.getEndTime() : null,
                assignment.getEffectiveFrom(),
                effectiveTo);
    }

    /** schedule_collect_weekly_rest_days: shift ∪ company, ascending legacy dow order. */
    private List<WeeklyRestDayView> weeklyRestDays(Long companyId, Shift shift) {
        Set<DayOfWeek> union = DaysOffParser.parseDaysOff(shift.getDaysOff());
        union.addAll(companyRestDays(companyId));
        return union.stream()
                .sorted(Comparator.comparingInt(DaysOffParser::toLegacyIndex))
                .map(d -> new WeeklyRestDayView(DaysOffParser.toLegacyIndex(d), DaysOffParser.englishLabel(d)))
                .toList();
    }

    private Set<DayOfWeek> companyRestDays(Long companyId) {
        return DaysOffParser.parseCompanyRestDays(
                companySettingsService.effective(companyId).weeklyOffDays());
    }

    /** schedule_compute_days_for_range: read-only, manual rows win, days with no shift are skipped. */
    private List<ScheduleDayView> computeDays(Long companyId, Long employeeId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return List.of();
        }
        Map<LocalDate, EmployeeSchedule> manual = scheduleRepository
                .findByEmployeeIdAndCompanyIdAndScheduleDateBetweenOrderByScheduleDateAsc(
                        employeeId, companyId, from, to)
                .stream()
                .collect(Collectors.toMap(EmployeeSchedule::getScheduleDate, Function.identity()));
        Set<DayOfWeek> companyRest = companyRestDays(companyId);
        List<ScheduleDayView> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            EmployeeSchedule row = manual.get(d);
            if (row != null) {
                out.add(new ScheduleDayView(row.getId(), d, row.getName(),
                        row.getStartTime(), row.getEndTime(), row.getExceptionNote()));
                continue;
            }
            Optional<Shift> dayShift = shiftForEmployeeOnDate(companyId, employeeId, d);
            if (dayShift.isEmpty()) {
                continue;
            }
            Shift shift = dayShift.get();
            boolean rest = DaysOffParser.parseDaysOff(shift.getDaysOff()).contains(d.getDayOfWeek())
                    || companyRest.contains(d.getDayOfWeek());
            if (rest) {
                // schedule_row_from_shift: an exception label suppresses the shift columns.
                out.add(new ScheduleDayView(null, d, null, null, null, WEEKLY_REST_LABEL));
            } else {
                out.add(new ScheduleDayView(null, d, blankToNull(shift.getName()),
                        shift.getStartTime(), shift.getEndTime(), null));
            }
        }
        return out;
    }

    private boolean employeeInScope(AuthorizationContext context, Long employeeId) {
        return employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
                .filter(e -> resourceScopeService.isEmployeeInScope(context, e.getId()))
                .isPresent();
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

}
```

- [ ] **Step 8: Implement ScheduleController (monthly endpoint only in this task)**

```java
package com.workin.backend.schedule;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;
import com.workin.backend.tenancy.AuthorizationContext;

/** Thin delegation, module template. */
@RestController
@RequestMapping("/api/tenant/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @RequiresPermission(PermissionKeys.SCHEDULES_READ)
    @GetMapping("/{employeeId}/monthly")
    public MonthlyOverviewView monthly(
            HttpServletRequest request, @PathVariable Long employeeId,
            @RequestParam int year, @RequestParam int month) {
        return scheduleService.monthlyOverview(contextFrom(request), employeeId, year, month)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static AuthorizationContext contextFrom(HttpServletRequest request) {
        Object context = request.getAttribute(AuthorizationContext.class.getName());
        if (context == null) {
            // The interceptor always stashes the context for
            // @RequiresPermission handlers -- absence is a wiring bug,
            // not a caller error, and must fail loudly.
            throw new IllegalStateException("AuthorizationContext missing -- authorization interceptor not applied");
        }
        return (AuthorizationContext) context;
    }

}
```

- [ ] **Step 9: Run the flow tests to verify they pass**

Run: `./gradlew test --tests "com.workin.backend.schedule.ScheduleModuleFlowTest"`
Expected: PASS (3 tests)

- [ ] **Step 10: Run the full suite (record-shape change touched companysettings + payroll)**

Run: `./gradlew test`
Expected: PASS — `PayrollBatchService` reads `EffectiveCompanySettings` components by accessor, so the added component must not break anything; a failure here means a missed constructor call site.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/workin/backend/schedule backend/src/main/java/com/workin/backend/companysettings backend/src/test/java/com/workin/backend/schedule/ScheduleModuleFlowTest.java
git commit -m "feat(schedule): assignment history, schedule rows, monthly overview endpoint"
```

---

### Task 4: Assign endpoint (materialization write)

**Files:**

- Create: `backend/src/main/java/com/workin/backend/schedule/AssignScheduleRequest.java`
- Modify: `backend/src/main/java/com/workin/backend/schedule/ScheduleService.java` (add `assign` + `upsertDay`)
- Modify: `backend/src/main/java/com/workin/backend/schedule/ScheduleController.java` (add handler)
- Test: `backend/src/test/java/com/workin/backend/schedule/ScheduleModuleFlowTest.java` (add tests)

**Interfaces:**

- Consumes: Task 3's service/repositories/views.
- Produces: `POST /api/tenant/schedules/{employeeId}/assign` body `{"shiftId": 1, "dates": ["2026-03-06"]}` → 204 | 404 | 403; `ScheduleService.assign(AuthorizationContext, Long employeeId, AssignScheduleRequest)` → `boolean`; private `upsertDay(Long companyId, Long employeeId, LocalDate date, String name, LocalTime startTime, LocalTime endTime, String exceptionNote)` (Task 5 reuses it).

- [ ] **Step 1: Write the failing tests** (add to `ScheduleModuleFlowTest`)

```java
    private ResponseEntity<Void> assign(String token, Long employeeId, Long shiftId, List<String> dates) {
        String body = "{\"shiftId\": " + shiftId + ", \"dates\": [\""
                + String.join("\", \"", dates) + "\"]}";
        return restTemplate.exchange(
                "/api/tenant/schedules/" + employeeId + "/assign",
                HttpMethod.POST, new HttpEntity<>(body, bearer(token)), Void.class);
    }

    @Test
    void assignWritesManualRowsThatWinOverComputedOnes() {
        AuthResponse admin = registerCompanyAdmin();
        Long employeeId = createEmployee(admin.companyId());
        Long dayShift = createShift(admin.companyId(), "Day", "09:00", "17:00", "Fri");
        Long nightShift = createShift(admin.companyId(), "Night", "22:00", "06:00", null);
        insertAssignment(admin.companyId(), employeeId, dayShift, "2026-03-01");

        // 2026-03-06 is a Friday -- computed classification would be rest.
        ResponseEntity<Void> response = assign(admin.accessToken(), employeeId, nightShift,
                List.of("2026-03-06", "2026-03-16"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        MonthlyOverviewView body = monthly(admin.accessToken(), employeeId, 2026, 3).getBody();
        ScheduleDayView friday = body.days().get(5);
        // Manual row wins over the computed rest day, exactly as
        // schedule_compute_days_for_range checks manual first.
        assertThat(friday.id()).isNotNull();
        assertThat(friday.name()).isEqualTo("Night");
        assertThat(friday.startTime()).isEqualTo(LocalTime.of(22, 0));
        assertThat(friday.exception()).isNull();
        assertThat(body.days().get(15).name()).isEqualTo("Night");

        // Re-assigning the same date is an upsert, not a duplicate row.
        assign(admin.accessToken(), employeeId, dayShift, List.of("2026-03-06"));
        Integer rows = jdbc().queryForObject(
                "SELECT COUNT(*) FROM employee_schedules WHERE employee_id = ? AND schedule_date = '2026-03-06'::date",
                Integer.class, employeeId);
        assertThat(rows).isEqualTo(1);
        assertThat(monthly(admin.accessToken(), employeeId, 2026, 3).getBody().days().get(5).name())
                .isEqualTo("Day");
    }

    @Test
    void assignRejectsUnknownEmployeeOrShift() {
        AuthResponse admin = registerCompanyAdmin();
        Long employeeId = createEmployee(admin.companyId());
        Long shiftId = createShift(admin.companyId(), "Day", "09:00", "17:00", null);

        assertThat(assign(admin.accessToken(), 999999L, shiftId, List.of("2026-03-02")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(assign(admin.accessToken(), employeeId, 999999L, List.of("2026-03-02")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.workin.backend.schedule.ScheduleModuleFlowTest"`
Expected: FAIL — 404 from the missing `/assign` route (no handler yet).

- [ ] **Step 3: Implement**

`AssignScheduleRequest.java`:

```java
package com.workin.backend.schedule;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** assign_employee_schedule.php's body: one snapshot row per date. */
public record AssignScheduleRequest(@NotNull Long shiftId, @NotEmpty List<LocalDate> dates) {
}
```

In `ScheduleService`, add (plus imports `LocalTime`, `DataIntegrityViolationException`, `HttpStatus`, `ResponseStatusException`):

```java
    /**
     * assign_employee_schedule.php: a materialization write -- snapshots
     * the chosen shift onto specific dates, clearing any exception note.
     * Distinct from changing the employee's ongoing assignment (that is
     * employee create/update's append to employee_shift_assignments).
     * Legacy's notification_to_employee call is out (no notification
     * infrastructure -- spec Out item). Uniform 404 replaces legacy's
     * distinct EMPLOYEE_NOT_FOUND/SHIFT_NOT_FOUND messages (house rule).
     */
    @Transactional
    public boolean assign(AuthorizationContext context, Long employeeId, AssignScheduleRequest request) {
        tenantSessionVariable.apply(context.companyId());
        if (!employeeInScope(context, employeeId)) {
            return false;
        }
        Optional<Shift> shift = shiftRepository.findByIdAndCompanyId(request.shiftId(), context.companyId());
        if (shift.isEmpty()) {
            return false;
        }
        for (LocalDate date : request.dates()) {
            upsertDay(context.companyId(), employeeId, date,
                    blankToNull(shift.get().getName()), shift.get().getStartTime(),
                    shift.get().getEndTime(), null);
        }
        return true;
    }

    /**
     * Read-then-write upsert (JPA has no native ON CONFLICT); the V33
     * UNIQUE constraint is the backstop under concurrent writes -- a
     * lost race surfaces as 409, the CompanySettingsService precedent.
     */
    private void upsertDay(Long companyId, Long employeeId, LocalDate date,
            String name, LocalTime startTime, LocalTime endTime, String exceptionNote) {
        EmployeeSchedule row = scheduleRepository
                .findByEmployeeIdAndCompanyIdAndScheduleDate(employeeId, companyId, date)
                .orElseGet(() -> new EmployeeSchedule(companyId, employeeId, date));
        row.snapshot(name, startTime, endTime, exceptionNote);
        try {
            scheduleRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "schedule day was written concurrently", ex);
        }
    }
```

In `ScheduleController`, add (plus imports `Valid`, `RequestBody`, `PostMapping`, `ResponseEntity`):

```java
    @RequiresPermission(PermissionKeys.SCHEDULES_MANAGE)
    @PostMapping("/{employeeId}/assign")
    public ResponseEntity<Void> assign(
            HttpServletRequest request, @PathVariable Long employeeId,
            @Valid @RequestBody AssignScheduleRequest body) {
        if (!scheduleService.assign(contextFrom(request), employeeId, body)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests "com.workin.backend.schedule.ScheduleModuleFlowTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/workin/backend/schedule backend/src/test/java/com/workin/backend/schedule/ScheduleModuleFlowTest.java
git commit -m "feat(schedule): assign endpoint with per-date shift snapshot upsert"
```

---

### Task 5: Generate endpoint (destructive regeneration)

**Files:**

- Create: `backend/src/main/java/com/workin/backend/schedule/GenerateScheduleRequest.java`
- Create: `backend/src/main/java/com/workin/backend/schedule/GenerateResultView.java`
- Modify: `backend/src/main/java/com/workin/backend/schedule/ScheduleService.java` (add `generate`)
- Modify: `backend/src/main/java/com/workin/backend/schedule/ScheduleController.java` (add handler)
- Test: `backend/src/test/java/com/workin/backend/schedule/ScheduleModuleFlowTest.java` (add tests)

**Interfaces:**

- Consumes: Task 4's `upsertDay`, Task 3's resolution core, `EmployeeScheduleRepository.deleteRange`.
- Produces: `POST /api/tenant/schedules/{employeeId}/generate` body `{"from": "2026-03-01", "to": "2026-03-31"}` → 200 `GenerateResultView(int count, Long shiftId, String shiftName)` | 400 (no assignment, or `to` before `from`) | 404 | 403; `ScheduleService.generate(AuthorizationContext, Long employeeId, GenerateScheduleRequest)` → `Optional<GenerateResultView>` (empty = employee 404).

- [ ] **Step 1: Write the failing tests** (add to `ScheduleModuleFlowTest`)

```java
    private ResponseEntity<GenerateResultView> generate(String token, Long employeeId, String from, String to) {
        String body = "{\"from\": \"" + from + "\", \"to\": \"" + to + "\"}";
        return restTemplate.exchange(
                "/api/tenant/schedules/" + employeeId + "/generate",
                HttpMethod.POST, new HttpEntity<>(body, bearer(token)), GenerateResultView.class);
    }

    @Test
    void generateReplacesExistingRowsAndLabelsRestDays() {
        AuthResponse admin = registerCompanyAdmin();
        Long employeeId = createEmployee(admin.companyId());
        Long dayShift = createShift(admin.companyId(), "Day", "09:00", "17:00", "Fri");
        Long nightShift = createShift(admin.companyId(), "Night", "22:00", "06:00", null);
        setCompanyWeeklyOffDays(admin.companyId(), "Sat");
        insertAssignment(admin.companyId(), employeeId, dayShift, "2026-03-01");
        // A pre-existing manual row inside the range -- regenerate must replace it.
        assign(admin.accessToken(), employeeId, nightShift, List.of("2026-03-02"));
        // And one outside the range -- must survive untouched.
        assign(admin.accessToken(), employeeId, nightShift, List.of("2026-04-01"));

        ResponseEntity<GenerateResultView> response =
                generate(admin.accessToken(), employeeId, "2026-03-01", "2026-03-31");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().count()).isEqualTo(31);
        assertThat(response.getBody().shiftId()).isEqualTo(dayShift);
        assertThat(response.getBody().shiftName()).isEqualTo("Day");

        // Regenerate is destructive by design (legacy's only exposed mode):
        // the manual Night row on Mar 2 became a Day row.
        String mar2Name = jdbc().queryForObject(
                "SELECT name FROM employee_schedules WHERE employee_id = ? AND schedule_date = '2026-03-02'::date",
                String.class, employeeId);
        assertThat(mar2Name).isEqualTo("Day");
        // Rest days persisted with the exception label and no shift columns.
        String mar6Note = jdbc().queryForObject(
                "SELECT exception_note FROM employee_schedules WHERE employee_id = ? AND schedule_date = '2026-03-06'::date",
                String.class, employeeId);
        assertThat(mar6Note).isEqualTo("Weekly rest");
        String mar7Note = jdbc().queryForObject(
                "SELECT exception_note FROM employee_schedules WHERE employee_id = ? AND schedule_date = '2026-03-07'::date",
                String.class, employeeId);
        assertThat(mar7Note).isEqualTo("Weekly rest");
        // The out-of-range manual row survived.
        String apr1Name = jdbc().queryForObject(
                "SELECT name FROM employee_schedules WHERE employee_id = ? AND schedule_date = '2026-04-01'::date",
                String.class, employeeId);
        assertThat(apr1Name).isEqualTo("Night");
    }

    @Test
    void generateWithoutAssignmentOrWithInvertedRangeIsBadRequest() {
        AuthResponse admin = registerCompanyAdmin();
        Long employeeId = createEmployee(admin.companyId());

        assertThat(generate(admin.accessToken(), employeeId, "2026-03-01", "2026-03-31").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        Long shiftId = createShift(admin.companyId(), "Day", "09:00", "17:00", null);
        insertAssignment(admin.companyId(), employeeId, shiftId, "2026-03-01");
        assertThat(generate(admin.accessToken(), employeeId, "2026-03-31", "2026-03-01").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.workin.backend.schedule.ScheduleModuleFlowTest"`
Expected: FAIL — missing `/generate` route and `GenerateResultView`.

- [ ] **Step 3: Implement**

`GenerateScheduleRequest.java`:

```java
package com.workin.backend.schedule;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/**
 * generate_employee_schedule.php's body. Legacy's optional
 * replace flag is dropped: replace-existing is the only mode its
 * endpoint effectively exposes (spec In section).
 */
public record GenerateScheduleRequest(@NotNull LocalDate from, @NotNull LocalDate to) {
}
```

`GenerateResultView.java`:

```java
package com.workin.backend.schedule;

/** schedule_generate_for_employee's result triple. */
public record GenerateResultView(int count, Long shiftId, String shiftName) {
}
```

In `ScheduleService`, add:

```java
    /**
     * schedule_generate_for_employee with $replace_existing = true (the
     * only mode legacy's endpoint exposes): deletes the range, then
     * re-materializes one row per day from that day's assignment,
     * skipping days with no assignment yet. The eligibility gate
     * resolves the assignment on `to` (legacy's exact probe). 400s are
     * split by cause (invalid range vs. no assignment) where legacy
     * reuses SHIFT_NOT_ASSIGNED for both -- recorded normalization,
     * same status code.
     */
    @Transactional
    public Optional<GenerateResultView> generate(
            AuthorizationContext context, Long employeeId, GenerateScheduleRequest request) {
        tenantSessionVariable.apply(context.companyId());
        if (!employeeInScope(context, employeeId)) {
            return Optional.empty();
        }
        if (request.to().isBefore(request.from())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to precedes from");
        }
        EmployeeShiftAssignment assignment = assignmentOnDate(context.companyId(), employeeId, request.to())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "no shift assignment effective in range"));
        scheduleRepository.deleteRange(employeeId, context.companyId(), request.from(), request.to());
        Set<DayOfWeek> companyRest = companyRestDays(context.companyId());
        int count = 0;
        for (LocalDate d = request.from(); !d.isAfter(request.to()); d = d.plusDays(1)) {
            Optional<Shift> dayShift = shiftForEmployeeOnDate(context.companyId(), employeeId, d);
            if (dayShift.isEmpty()) {
                continue;
            }
            Shift shift = dayShift.get();
            boolean rest = DaysOffParser.parseDaysOff(shift.getDaysOff()).contains(d.getDayOfWeek())
                    || companyRest.contains(d.getDayOfWeek());
            if (rest) {
                upsertDay(context.companyId(), employeeId, d, null, null, null, WEEKLY_REST_LABEL);
            } else {
                upsertDay(context.companyId(), employeeId, d, blankToNull(shift.getName()),
                        shift.getStartTime(), shift.getEndTime(), null);
            }
            count++;
        }
        Shift resolved = shiftRepository.findByIdAndCompanyId(assignment.getShiftId(), context.companyId())
                .orElse(null);
        return Optional.of(new GenerateResultView(
                count, assignment.getShiftId(), resolved != null ? resolved.getName() : null));
    }
```

In `ScheduleController`, add:

```java
    @RequiresPermission(PermissionKeys.SCHEDULES_MANAGE)
    @PostMapping("/{employeeId}/generate")
    public GenerateResultView generate(
            HttpServletRequest request, @PathVariable Long employeeId,
            @Valid @RequestBody GenerateScheduleRequest body) {
        return scheduleService.generate(contextFrom(request), employeeId, body)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests "com.workin.backend.schedule.ScheduleModuleFlowTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/workin/backend/schedule backend/src/test/java/com/workin/backend/schedule/ScheduleModuleFlowTest.java
git commit -m "feat(schedule): generate endpoint with destructive range regeneration"
```

---

### Task 6: Optional shift at employee create/update

**Files:**

- Modify: `backend/src/main/java/com/workin/backend/employees/CreateEmployeeRequest.java`
- Modify: `backend/src/main/java/com/workin/backend/employees/UpdateEmployeeRequest.java`
- Modify: `backend/src/main/java/com/workin/backend/employees/EmployeeService.java`
- Test: `backend/src/test/java/com/workin/backend/schedule/ScheduleModuleFlowTest.java` (add tests — assignment semantics live with the schedule module's test)

**Interfaces:**

- Consumes: Task 3's `EmployeeShiftAssignmentRepository`; existing `ShiftRepository`.
- Produces: `CreateEmployeeRequest(String firstName, String lastName, String phone, Long branchId, Long departmentId, Long jobTitleId, Long shiftId, LocalDate shiftEffectiveFrom)`; `UpdateEmployeeRequest(String firstName, String lastName, Long branchId, Long departmentId, Long jobTitleId, Long shiftId)`. **Existing API callers are unaffected** — the new record components deserialize as null when absent.

- [ ] **Step 1: Write the failing tests** (add to `ScheduleModuleFlowTest`)

```java
    @Test
    void employeeCreateAndUpdateAppendAssignmentHistory() {
        AuthResponse admin = registerCompanyAdmin();
        Long shiftA = createShift(admin.companyId(), "Shift A", "09:00", "17:00", null);
        Long shiftB = createShift(admin.companyId(), "Shift B", "10:00", "18:00", null);

        // Create with a shift + explicit effective date writes one history row.
        String createBody = "{\"firstName\": \"Shifted\", \"lastName\": \"Emp\", \"phone\": \""
                + uniquePhone() + "\", \"shiftId\": " + shiftA
                + ", \"shiftEffectiveFrom\": \"2026-03-01\"}";
        ResponseEntity<String> created = restTemplate.exchange(
                "/api/tenant/employees", HttpMethod.POST,
                new HttpEntity<>(createBody, bearer(admin.accessToken())), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long employeeId = jdbc().queryForObject(
                "SELECT id FROM employees WHERE first_name = 'Shifted' AND company_id = ?",
                Long.class, admin.companyId());
        assertThat(assignmentRows(employeeId)).isEqualTo(1);

        // Update to a different shift appends a second row (history, never in-place).
        String updateToB = "{\"firstName\": \"Shifted\", \"lastName\": \"Emp\", \"shiftId\": " + shiftB + "}";
        restTemplate.exchange("/api/tenant/employees/" + employeeId, HttpMethod.PUT,
                new HttpEntity<>(updateToB, bearer(admin.accessToken())), String.class);
        assertThat(assignmentRows(employeeId)).isEqualTo(2);

        // Re-sending the same shift on a full-replace PUT is a no-op --
        // deviation from legacy's unconditional append, recorded in
        // EmployeeService (legacy's PHP update is patch-shaped; a PUT
        // client echoing shiftId would otherwise grow history per save).
        restTemplate.exchange("/api/tenant/employees/" + employeeId, HttpMethod.PUT,
                new HttpEntity<>(updateToB, bearer(admin.accessToken())), String.class);
        assertThat(assignmentRows(employeeId)).isEqualTo(2);

        // Null shiftId means "no schedule statement", not "unassign".
        String updateNoShift = "{\"firstName\": \"Shifted\", \"lastName\": \"Emp\"}";
        restTemplate.exchange("/api/tenant/employees/" + employeeId, HttpMethod.PUT,
                new HttpEntity<>(updateNoShift, bearer(admin.accessToken())), String.class);
        assertThat(assignmentRows(employeeId)).isEqualTo(2);
    }

    @Test
    void employeeCreateWithForeignShiftIsNotFound() {
        AuthResponse admin = registerCompanyAdmin();
        AuthResponse other = registerCompanyAdmin();
        Long foreignShift = createShift(other.companyId(), "Foreign", "09:00", "17:00", null);

        String body = "{\"firstName\": \"X\", \"lastName\": \"Y\", \"phone\": \""
                + uniquePhone() + "\", \"shiftId\": " + foreignShift + "}";
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/tenant/employees", HttpMethod.POST,
                new HttpEntity<>(body, bearer(admin.accessToken())), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Integer assignmentRows(Long employeeId) {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = ?",
                Integer.class, employeeId);
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.workin.backend.schedule.ScheduleModuleFlowTest"`
Expected: FAIL — unknown `shiftId` body fields are ignored, so no assignment rows are written (counts are 0).

- [ ] **Step 3: Implement**

`CreateEmployeeRequest.java` — replace the record:

```java
package com.workin.backend.employees;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;

/**
 * Org attribution ids nullable; each non-null id is tenant-validated
 * -> the same 404. shiftId optional (owner decision 2026-08-08,
 * diverging from legacy's required shift_id at creation to match this
 * platform's nullable org references); when present, one
 * employee_shift_assignments row is written, effective
 * shiftEffectiveFrom or today (legacy: shift_effective_from ??
 * hire_date ?? today -- no hire date exists here).
 */
public record CreateEmployeeRequest(
        @NotBlank String firstName,
        String lastName,
        String phone,
        Long branchId,
        Long departmentId,
        Long jobTitleId,
        Long shiftId,
        LocalDate shiftEffectiveFrom) {
}
```

`UpdateEmployeeRequest.java` — replace the record:

```java
package com.workin.backend.employees;

import jakarta.validation.constraints.NotBlank;

/**
 * Deliberately carries no credential, role, or lifecycle fields (see
 * Employee's Javadoc). Org attribution ids nullable -- null clears;
 * each non-null id is tenant-validated -> the same 404. shiftId is the
 * exception to null-clears: assignment history is append-only (legacy
 * has no unassign), so null means "no schedule statement" and a
 * non-null value appends a new history row only when it differs from
 * the current assignment.
 */
public record UpdateEmployeeRequest(
        @NotBlank String firstName,
        String lastName,
        Long branchId,
        Long departmentId,
        Long jobTitleId,
        Long shiftId) {
}
```

`EmployeeService.java` — inject two more dependencies and wire both paths:

```java
// new imports
import java.time.LocalDate;
import com.workin.backend.organization.ShiftRepository;
import com.workin.backend.schedule.EmployeeShiftAssignment;
import com.workin.backend.schedule.EmployeeShiftAssignmentRepository;
```

Add `private final ShiftRepository shiftRepository;` and `private final EmployeeShiftAssignmentRepository shiftAssignmentRepository;`, both assigned from new constructor parameters (append them to the parameter list; Spring wires by type).

In `create(...)`, extend the reference check and append the assignment after `save`:

```java
    @Transactional
    public EmployeeView create(AuthorizationContext context, CreateEmployeeRequest request) {
        tenantSessionVariable.apply(context.companyId());
        requireOrgReferences(context, request.branchId(), request.departmentId(), request.jobTitleId());
        requireShiftReference(context, request.shiftId());
        try {
            Employee employee = new Employee(
                    context.companyId(), request.firstName(), request.lastName(), request.phone());
            employee.place(request.branchId(), request.departmentId(), request.jobTitleId());
            Employee saved = employeeRepository.save(employee);
            if (request.shiftId() != null) {
                // employee_create_helper.php:83 -- effective_from is the
                // caller's date or today (no hire-date concept here).
                shiftAssignmentRepository.save(new EmployeeShiftAssignment(
                        context.companyId(), saved.getId(), request.shiftId(),
                        request.shiftEffectiveFrom() != null ? request.shiftEffectiveFrom() : LocalDate.now()));
            }
            return EmployeeView.of(saved);
        } catch (DataIntegrityViolationException ex) {
            // employees.phone is globally UNIQUE (V8); same clean-409
            // pattern as RegistrationService's registration race.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone number already in use", ex);
        }
    }
```

In `updateNames(...)`, add the shift validation before the lookup and the append inside the `map`:

```java
    @Transactional
    public Optional<EmployeeView> updateNames(
            AuthorizationContext context, Long employeeId, UpdateEmployeeRequest request) {
        tenantSessionVariable.apply(context.companyId());
        requireOrgReferences(context, request.branchId(), request.departmentId(), request.jobTitleId());
        requireShiftReference(context, request.shiftId());
        return employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
                .filter(employee -> resourceScopeService.isEmployeeInScope(context, employee.getId()))
                .map(employee -> {
                    employee.rename(request.firstName(), request.lastName());
                    employee.place(request.branchId(), request.departmentId(), request.jobTitleId());
                    appendShiftIfChanged(context, employee.getId(), request.shiftId());
                    return EmployeeView.of(employee);
                });
    }

    /**
     * Deviation from legacy (recorded): api/employees/update.php appends
     * unconditionally whenever shift_id is present, but it is
     * patch-shaped -- this platform's full-replace PUT would grow one
     * history row per save from clients echoing the current shift, so
     * the append is gated on an actual change.
     */
    private void appendShiftIfChanged(AuthorizationContext context, Long employeeId, Long shiftId) {
        if (shiftId == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        boolean unchanged = shiftAssignmentRepository
                .findFirstByEmployeeIdAndCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDescIdDesc(
                        employeeId, context.companyId(), today)
                .map(current -> current.getShiftId().equals(shiftId))
                .orElse(false);
        if (!unchanged) {
            shiftAssignmentRepository.save(new EmployeeShiftAssignment(
                    context.companyId(), employeeId, shiftId, today));
        }
    }

    /** Same uniform-404 rule as requireOrgReferences, for the shift reference. */
    private void requireShiftReference(AuthorizationContext context, Long shiftId) {
        if (shiftId != null
                && shiftRepository.findByIdAndCompanyId(shiftId, context.companyId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
```

- [ ] **Step 4: Run schedule + employees tests to verify pass**

Run: `./gradlew test --tests "com.workin.backend.schedule.ScheduleModuleFlowTest" --tests "com.workin.backend.employees.EmployeeModuleFlowTest"`
Expected: PASS — the employees flow test still passes because the new record components are optional and existing JSON bodies deserialize them as null.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/workin/backend/employees backend/src/test/java/com/workin/backend/schedule/ScheduleModuleFlowTest.java
git commit -m "feat(employees): optional shift assignment at create/update (append-only history)"
```

---

### Task 7: Access and tenancy negatives

**Files:**

- Test: `backend/src/test/java/com/workin/backend/schedule/ScheduleModuleFlowTest.java` (add tests; production code should already satisfy them — any failure is a real bug to fix in place)

**Interfaces:**

- Consumes: everything prior; the `loginHrMember`/`grantPermission` fixture pattern copied from `OrganizationStructureFlowTest` (identity + membership + `membership_roles` row via jdbc, login via `/api/auth/login`, ALLOW override via `membership_permission_overrides`).

- [ ] **Step 1: Add the fixture helpers** (copy the shape from `OrganizationStructureFlowTest`)

```java
    private record HrFixture(String accessToken, Long membershipId, Long companyId) {
    }

    private HrFixture loginHrMember(Long companyId) {
        JdbcTemplate jdbc = jdbc();
        String phone = uniquePhone();
        String password = "correct horse battery staple";
        Long identityId = jdbc.queryForObject(
                "INSERT INTO identities (phone, password_hash) VALUES (?, ?) RETURNING id",
                Long.class, phone, passwordEncoder.encode(password));
        Long membershipId = jdbc.queryForObject(
                "INSERT INTO tenant_memberships (identity_id, company_id, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
                Long.class, identityId, companyId);
        jdbc.update(
                "INSERT INTO membership_roles (membership_id, company_id, role) VALUES (?, ?, 'HR')",
                membershipId, companyId);
        AuthResponse login = restTemplate.postForEntity(
                "/api/auth/login",
                new com.workin.backend.identity.LoginRequest(phone, password),
                AuthResponse.class).getBody();
        return new HrFixture(login.accessToken(), membershipId, companyId);
    }

    private void grantPermission(HrFixture hr, String permissionKey) {
        jdbc().update(
                "INSERT INTO membership_permission_overrides (membership_id, company_id, permission_id, effect) "
                        + "SELECT ?, ?, p.id, 'ALLOW' FROM permissions p WHERE p.permission_key = ?",
                hr.membershipId(), hr.companyId(), permissionKey);
    }
```

Note: before writing this step, compare against the current `OrganizationStructureFlowTest.loginHrMember` and copy its exact column list — if that helper has drifted from what is quoted here, the existing code wins.

- [ ] **Step 2: Write the failing-or-passing negative tests**

```java
    @Test
    void monthlyOverviewRequiresSchedulesRead() {
        AuthResponse admin = registerCompanyAdmin();
        Long employeeId = createEmployee(admin.companyId());
        HrFixture hr = loginHrMember(admin.companyId());

        assertThat(monthly(hr.accessToken(), employeeId, 2026, 3).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        grantPermission(hr, com.workin.backend.authorization.PermissionKeys.SCHEDULES_READ);
        assertThat(monthly(hr.accessToken(), employeeId, 2026, 3).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void writesRequireSchedulesManage() {
        AuthResponse admin = registerCompanyAdmin();
        Long employeeId = createEmployee(admin.companyId());
        Long shiftId = createShift(admin.companyId(), "Day", "09:00", "17:00", null);
        HrFixture hr = loginHrMember(admin.companyId());
        grantPermission(hr, com.workin.backend.authorization.PermissionKeys.SCHEDULES_READ);

        assertThat(assign(hr.accessToken(), employeeId, shiftId, List.of("2026-03-02")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(generate(hr.accessToken(), employeeId, "2026-03-01", "2026-03-31").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void crossTenantAccessIsUniformNotFound() {
        AuthResponse admin = registerCompanyAdmin();
        AuthResponse other = registerCompanyAdmin();
        Long foreignEmployee = createEmployee(other.companyId());
        Long foreignShift = createShift(other.companyId(), "Foreign", "09:00", "17:00", null);
        Long ownEmployee = createEmployee(admin.companyId());

        assertThat(monthly(admin.accessToken(), foreignEmployee, 2026, 3).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(assign(admin.accessToken(), foreignEmployee, foreignShift, List.of("2026-03-02"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(assign(admin.accessToken(), ownEmployee, foreignShift, List.of("2026-03-02"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(generate(admin.accessToken(), foreignEmployee, "2026-03-01", "2026-03-31")
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unauthenticatedCallsAreRejected() {
        assertThat(monthly(null, 1L, 2026, 3).getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(assign(null, 1L, 1L, List.of("2026-03-02")).getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(generate(null, 1L, "2026-03-01", "2026-03-31").getStatusCode().is2xxSuccessful()).isFalse();
    }
```

- [ ] **Step 3: Run the full flow test**

Run: `./gradlew test --tests "com.workin.backend.schedule.ScheduleModuleFlowTest"`
Expected: PASS. If any negative fails, the production gap is real — fix it in the service/controller (per the Global Constraints), never by weakening the test.

- [ ] **Step 4: Run the entire suite**

Run: `./gradlew test`
Expected: PASS — includes `PermissionCatalogSyncTest` (keys in sync) and `AuthorizationPolicyArchTest` (every new handler annotated).

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/com/workin/backend/schedule/ScheduleModuleFlowTest.java
git commit -m "test(schedule): access, tenancy, and authentication negatives"
```

---

## Deferred (tracked in the spec, not lost)

- Official holidays in the monthly overview — stubbed empty until the holidays module (weekly-rest/holiday-credit spec) lands; the `HolidayView` list and `exceptionForDay`'s holiday-name-wins rule are the extension points.
- `workforce_planning` table — no consumer.
- Manager-scoping *semantics* for schedule writes (F-16) — the mechanical `isEmployeeInScope` boundary is applied here; policy refinement is the open item.
- Notification-on-assign — no notification infrastructure exists.
