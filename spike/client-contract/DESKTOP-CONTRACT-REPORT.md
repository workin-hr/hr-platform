# Desktop client contract conformance

**This is a contract layer, not a runtime one.** It proves what the desktop
client's own parsers would do with each stack's bytes. It verifies **nothing**
about rendering, navigation, the file picker, downloads to disk, OS
integration, or any other runtime UI behaviour, because this layer executes
nothing -- it replays recorded bytes through the parsers. Whether the real
application was ever run is a separate measurement: see "Runtime status for
this client" below.

## Coverage, with the denominators kept apart

| | count |
|---|---|
| API endpoint constants declared by the client | 187 |
| Referenced from client source (reachable) | 182 |
| Distinct paths the data source actually calls | 179 |
| Contracts statically derived from client source | 180 |
| Client parsers extracted | 141 |
| **Contracts replayed against PHP and Java** | **89** |
| **Contracts replayed by THIS layer** | **89** |

This layer replays contracts; it executes nothing. The runtime verdict for
this client is a separate measurement with its own evidence in
`spike/client-runtime` — see the runtime status line below.

The 187 declared constants are **not** the denominator.
5 are declared and never referenced by any
screen, so they are excluded from what a client run could exercise:

- `getOfficialHolidayEndpoint`
- `getWorkforcePlanningSummaryEndpoint`
- `loginCompanyEndpoint`
- `saveWorkforcePlanningEndpoint`
- `setEmployeeAttendanceMethodEndpoint`

> **The client-side counts above were refreshed on 2026-09-05 against the
> current desktop client; the replay counts below were not.** The client grew
> four endpoints (`guide_videos/list`, `employees/update_bulk`,
> `employees/analyze_excel_update`, `complaints/delete`), four parsers, and
> new fields on `ConfigData`, `OverallAttendanceReportModel` and
> `PayslipModel`. Each was checked against the Java implementation by hand and
> is served; none has been *replayed* through this layer, because a replay
> needs both stacks up and PHP is not deployed locally. So the 89 is the
> number of contracts this layer has actually replayed, not the number
> verified -- and the gap between 89 and 180 is the honest measure of what
> replay still owes.

## Verdicts

| verdict | count |
|---|---|
| compatible | 89 |
| not verified | 1 |

## endpoint -> parser -> PHP -> Java -> client verdict

| endpoint | method | client parser | PHP | Java | verdict |
|---|---|---|---|---|---|
| `administrative_decisions/list` | GET | `AdministrativeDecisionsResponse` | 200 parses | 200 parses | compatible |
| `advances/approve` | PUT | `AdvanceResponse` | 200 parses | 200 parses | compatible |
| `advances/create` | POST | `AdvanceResponse` | 201 parses | 201 parses | compatible |
| `advances/list` | GET | `AdvancesResponse` | 200 parses | 200 parses | compatible |
| `advances/one` | GET | `AdvanceResponse` | 200 parses | 200 parses | compatible |
| `advances/reject` | PUT | `AdvanceResponse` | 200 parses | 200 parses | compatible |
| `advances/update` | PUT | `AdvanceResponse` | 200 parses | 200 parses | compatible |
| `app_content/one` | GET | `AppContentResponse` | 200 parses | 200 parses | compatible |
| `assets/list` | GET | `AssetsResponse` | 200 parses | 200 parses | compatible |
| `assets/one` | GET | `AssetResponse` | 200 parses | 200 parses | compatible |
| `attendance/analyze_excel` | POST (multipart) | `AttendanceExcelAnalyzeResponse` | 200 parses | 200 parses | compatible |
| `attendance/create` | POST | `AttendanceResponse` | 201 parses | 201 parses | compatible |
| `attendance/delete` | DELETE | `BaseResponse` | 200 parses | 200 parses | compatible |
| `attendance/employee_monthly_attendance` | GET | `AttendanceRecordsResponse` | 200 parses | 200 parses | compatible |
| `attendance/list` | GET | `AttendanceRecordsResponse` | 200 parses | 200 parses | compatible |
| `attendance/one` | GET | `AttendanceResponse` | 200 parses | 200 parses | compatible |
| `attendance/overall_report` | GET | `OverallAttendanceReportResponse` | 200 parses | 200 parses | compatible |
| `attendance/stats` | GET | `AttendanceStatsResponse` | 200 parses | 200 parses | compatible |
| `attendance/update` | PUT | `AttendanceResponse` | 200 parses | 200 parses | compatible |
| `attendance_exception_types/list` | GET | `ExceptionTypesResponse` | 200 parses | 200 parses | compatible |
| `auth/get_company_registration_options` | GET | `CompanyOptionsResponse` | 200 parses | 200 parses | compatible |
| `banners/list` | GET | `BannersResponse` | 200 parses | 200 parses | compatible |
| `branches/list` | GET | `BranchesResponse` | 200 parses | 200 parses | compatible |
| `branches/one` | GET | `BranchResponse` | 200 parses | 200 parses | compatible |
| `company/upload_commercial_reg` | POST (multipart) | `CompanyProfileResponse` | 400 ServerException | 400 ServerException | not verified |
| `company/upload_logo` | POST (multipart) | `CompanyProfileResponse` | 200 parses | 200 parses | compatible |
| `company_join_requests/list` | GET | `CompanyJoinRequestsResponse` | 200 parses | 200 parses | compatible |
| `company_official_holidays/list` | GET | `OfficialHolidaysResponse` | 200 parses | 200 parses | compatible |
| `company_settings/list` | GET | `CompanySettingsResponse` | 200 parses | 200 parses | compatible |
| `company_settings/one` | GET | `CompanySettingResponse` | 200 parses | 200 parses | compatible |
| `company_settings/options` | GET | `CompanySettingOptionsResponse` | 200 parses | 200 parses | compatible |
| `complaints/list` | GET | `ComplaintsResponse` | 200 parses | 200 parses | compatible |
| `configs/get` | GET | `ConfigsResponse` | 200 parses | 200 parses | compatible |
| `dashboard/stats` | GET | `DashboardStatsResponse` | 200 parses | 200 parses | compatible |
| `departments/list` | GET | `DepartmentsResponse` | 200 parses | 200 parses | compatible |
| `departments/one` | GET | `DepartmentResponse` | 200 parses | 200 parses | compatible |
| `employee_docs/list` | GET | `EmployeeDocumentsResponse` | 200 parses | 200 parses | compatible |
| `employee_docs/upload` | POST (multipart) | `EmployeeDocumentResponse` | 201 parses | 201 parses | compatible |
| `employees/analyze_excel` | POST (multipart) | `EmployeeExcelAnalyzeResponse` | 200 parses | 200 parses | compatible |
| `employees/create` | POST | `EmployeeResponse` | 201 parses | 201 parses | compatible |
| `employees/deactivate` | DELETE | `EmployeeResponse` | 200 parses | 200 parses | compatible |
| `employees/delete_preview` | GET | `EmployeeDeletePreviewResponse` | 200 parses | 200 parses | compatible |
| `employees/list` | GET | `EmployeesResponse` | 200 parses | 200 parses | compatible |
| `employees/one` | GET | `EmployeeResponse` | 200 parses | 200 parses | compatible |
| `employees/reactivate` | PUT | `EmployeeResponse` | 200 parses | 200 parses | compatible |
| `employees/stats` | GET | `EmployeesStatsResponse` | 200 parses | 200 parses | compatible |
| `employees/update` | PUT | `EmployeeResponse` | 200 parses | 200 parses | compatible |
| `employees/upload_photo` | POST (multipart) | `EmployeeResponse` | 200 parses | 200 parses | compatible |
| `faqs/list` | GET | `FaqsResponse` | 200 parses | 200 parses | compatible |
| `hr_employees/list` | GET | `EmployeesResponse` | 200 parses | 200 parses | compatible |
| `job_titles/list` | GET | `JobTitlesResponse` | 200 parses | 200 parses | compatible |
| `job_titles/one` | GET | `JobTitleResponse` | 200 parses | 200 parses | compatible |
| `leave_balances/analyze_excel` | POST (multipart) | `LeaveBalanceExcelAnalyzeResponse` | 200 parses | 200 parses | compatible |
| `leave_balances/list` | GET | `LeaveBalancesResponse` | 200 parses | 200 parses | compatible |
| `leave_balances/one` | GET | `LeaveBalanceResponse` | 200 parses | 200 parses | compatible |
| `leave_balances/stats` | GET | `LeaveBalancesStatsResponse` | 200 parses | 200 parses | compatible |
| `notifications/list` | GET | `NotificationsResponse` | 200 parses | 200 parses | compatible |
| `notifications/one` | GET | `NotificationResponse` | 200 parses | 200 parses | compatible |
| `notifications/unread_count` | GET | `GetUnreadNotificationsCountResponse` | 200 parses | 200 parses | compatible |
| `payroll_batches/calculate` | POST | `PayrollBatchResponse` | 200 parses | 200 parses | compatible |
| `payroll_batches/create` | POST | `PayrollBatchResponse` | 201 parses | 201 parses | compatible |
| `payroll_batches/finalize` | PUT | `PayrollBatchResponse` | 200 parses | 200 parses | compatible |
| `payroll_batches/fiscal_period` | GET | `PayrollFiscalPeriodResponse` | 200 parses | 200 parses | compatible |
| `payroll_batches/list` | GET | `PayrollBatchesResponse` | 200 parses | 200 parses | compatible |
| `payroll_batches/one` | GET | `PayrollBatchResponse` | 200 parses | 200 parses | compatible |
| `payroll_batches/stats` | GET | `PayrollBatchStatsResponse` | 200 parses | 200 parses | compatible |
| `payslips/list` | GET | `PayslipsResponse` | 200 parses | 200 parses | compatible |
| `payslips/one` | GET | `PayslipResponse` | 200 parses | 200 parses | compatible |
| `penalties/list` | GET | `PenaltiesResponse` | 200 parses | 200 parses | compatible |
| `penalties/one` | GET | `PenaltyResponse` | 200 parses | 200 parses | compatible |
| `penalties/stats` | GET | `PenaltiesStatsResponse` | 200 parses | 200 parses | compatible |
| `penalties/update` | PUT | `PenaltyResponse` | 200 parses | 200 parses | compatible |
| `phone_countries/list` | GET | `PhoneCountriesResponse` | 200 parses | 200 parses | compatible |
| `profile/company` | GET | `CompanyProfileResponse` | 200 parses | 200 parses | compatible |
| `profile/delete_account_preview` | GET | `CompanyDeletePreviewResponse` | 200 parses | 200 parses | compatible |
| `profile/employee` | GET | `EmployeeResponse` | 200 parses | 200 parses | compatible |
| `request_types/list` | GET | `RequestTypesResponse` | 200 parses | 200 parses | compatible |
| `request_types/one` | GET | `RequestTypeResponse` | 200 parses | 200 parses | compatible |
| `requests/approve` | POST | `BaseResponse` | 200 parses | 200 parses | compatible |
| `requests/list` | GET | `RequestsResponse` | 200 parses | 200 parses | compatible |
| `requests/one` | GET | `RequestResponse` | 200 parses | 200 parses | compatible |
| `requests/reject` | POST | `BaseResponse` | 200 parses | 200 parses | compatible |
| `salary_contracts/list` | GET | `SalaryContractsResponse` | 200 parses | 200 parses | compatible |
| `salary_contracts/one` | GET | `SalaryContractResponse` | 200 parses | 200 parses | compatible |
| `schedules/employee_monthly_schedule` | GET | `SchedulesResponse` | 200 parses | 200 parses | compatible |
| `setting_definitions/list` | GET | `SettingDefinitionsResponse` | 200 parses | 200 parses | compatible |
| `shifts/list` | GET | `ShiftsResponse` | 200 parses | 200 parses | compatible |
| `shifts/one` | GET | `ShiftResponse` | 200 parses | 200 parses | compatible |
| `workforce_planning/list` | GET | `WorkforcePlanningsResponse` | 200 parses | 200 parses | compatible |
| `workforce_planning/one` | GET | `WorkforcePlanningResponse` | 200 parses | 200 parses | compatible |

## Findings

### 1. No Java parity defect was found by this layer

Across every replayed contract the client verdict is the same on both stacks:
same status, same parse outcome, and the same set of fields that end up blank
or defaulted. Nothing here asks for a Java change.

### 2. `company/upload_commercial_reg` -- a client defect, not a parity defect

The client sends the multipart part named **`logo`**
(`EditCompanyCommercialRegParameters.toBodyMap` -> `ApiConstants.logoKey`, and
`HttpHelper.multipartRequest` uses each body-map key as the field name). The
endpoint reads `UploadMultipart::FILE`, i.e. **`file`**.

Measured with the request the client actually sends:

```text
part `logo`   php 400 "No file uploaded"   java 400 "No file uploaded"
part `file`   php 200 uploaded             java 200 uploaded
```

**Both stacks behave identically**, so the migration neither causes nor fixes
it. The client is left unchanged: PHP and Java do not differ here, so nothing
about it is a migration decision.

### 3. The three `getModel(...)!` sites are safe

`dashboard/stats`, `employees/stats` and `configs/get` throw in the client if
`data` is not a JSON object. Checked explicitly because **D-156** changed how
Java renders an empty structure: `data` is an object on both stacks,
dashboard's eight `(object)[]` keys stay objects on both, and
`workforce_planning_stats` stays a list on both.

## Runtime status for this client

**Verified separately.** The real desktop application was built from
unmodified source and executed against the local Java backend through its
own hardcoded URL: login, navigation, create/update/delete, a multipart
upload through the real file chooser, and logout. Evidence and setup are in
`spike/client-runtime/DESKTOP-RUNTIME-REPORT.md`.

The clients are pinned read-only submodules and were **not** modified for
either layer.

## What this does not cover

- rendering, widget state, navigation between screens
- the file picker, downloads written to disk, OS integration, auto-update
- the 4 `ResponseType.bytes` endpoints (downloads): the client
  treats them as raw bytes, so there is no parser to check here
- endpoints whose request shape needs state this harness does not seed
- anything about the mobile client, which is a separate pass
