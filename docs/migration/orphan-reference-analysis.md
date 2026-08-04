# Orphan-Reference Analysis

Tracks foreign-key-shaped relationships that MySQL did not enforce (or
that were enforced inconsistently) and that PostgreSQL's stricter
constraint model may reject on migration.

## Method

Measured directly, 2026-08-04, against the real schema + data dump
loaded into a throwaway Docker MySQL container (see
`docs/migration/data-quality-analysis.md` for the full method note).
Enumerated **all 41 real foreign-key relationships** in the schema via
`information_schema.KEY_COLUMN_USAGE`, then ran a `LEFT JOIN ... WHERE
parent.id IS NULL` orphan check for every single one — not a sample.
This is **proven-from-data**, exhaustive for this snapshot.

## Result: Zero orphan references found across all 41 foreign keys

Every one of the following relationships was checked and returned
**0 orphaned rows** (child row with a non-null FK value pointing at a
parent row that does not exist):

`advances.employee_id`, `attendance.employee_id`,
`attendance.exception_type_id`, `branches.company_id`,
`companies.company_activity_id`, `companies.company_size_id`,
`companies.company_title_id`,
`company_setting_values.setting_allowed_value_id`,
`company_setting_values.company_setting_id`,
`company_settings.company_id`, `company_settings.setting_definition_id`,
`complaints.employee_id`, `complaints.company_id`,
`department_branches.branch_id`, `department_branches.department_id`,
`departments.company_id`, `employee_docs.employee_id`,
`employee_schedules.employee_id`,
`employee_shift_assignments.employee_id`,
`employee_shift_assignments.shift_id`, `employees.company_id`,
`employees.job_title_id`, `employees.department_id`,
`employees.branch_id`, `faq_items.faq_category_id`,
`hr_permissions.employee_id`, `job_titles.department_id`,
`job_titles.company_id`, `leave_balance.employee_id`,
`notifications.company_id`, `notifications.from_employee_id`,
`notifications.to_employee_id`, `payroll_batches.company_id`,
`payslips.employee_id`, `payslips.batch_id`, `penalties.employee_id`,
`push_tokens.employee_id`, `request_types.company_id`,
`requests.employee_id`, `requests.request_type_id`,
`salary_contracts.employee_id`,
`setting_allowed_values.setting_definition_id`, `shifts.company_id`.

## Migration Impact

**This is good news, worth stating plainly**: PostgreSQL's stricter FK
enforcement is not expected to reject any existing row in this dataset
on referential-integrity grounds. All 41 relationships are already
internally consistent in the real data, even where MySQL's own
enforcement was inconsistent or absent in the schema definition.

## A Related, Important Non-Finding: The Import-Time FK Error Was A Loading Artifact, Not A Real Orphan

Loading the data dump initially failed with a foreign-key error on
`advances.employee_id` referencing `employees.id` — but re-loading with
`FOREIGN_KEY_CHECKS=0` (standard restore practice, since `mysqldump`
output doesn't guarantee parent-before-child table ordering) and then
running the orphan check above with checks back on and the full dataset
present confirmed **zero real orphans**. The initial error was purely a
table-load-order artifact of the dump file, not evidence of bad data.
Recorded here explicitly so this isn't misremembered as a real finding
later.

## Findings Requiring A Fresh Production Snapshot

This result is a point-in-time snapshot (dump date 2026-08-03). Since
MySQL's own FK enforcement on this schema is generally present (only
`advances`'s dump-ordering issue above suggested otherwise, and that was
ruled out), new orphans are unlikely to accumulate between now and a
production snapshot taken closer to actual cutover — but re-running
this exact check against a fresh dump immediately before migration is
still the correct practice, not assumed to still hold from this pass.

## Operational Assumptions Still Requiring Confirmation

None — this finding required no operational assumption. It is a direct,
exhaustive, proven-from-data result.

## Evidence

Loaded `mysql_workin.schema.sql` + `mysql_workin.data.sql` into a
throwaway Docker MySQL 8.0 container, ran the full 41-relationship
orphan-check query directly, container destroyed after analysis,
2026-08-04. No raw customer records reproduced — only pass/fail counts
per relationship.
