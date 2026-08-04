# Trigger Inventory

## Result: Confirmed — Zero Triggers Exist

`workin-hr/hr-legacy`'s `mysql_workin.schema.sql` (structure-only export,
commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`) was grepped in full for
`CREATE TRIGGER` (case-insensitive). Zero matches. No `BEFORE`/`AFTER`
`INSERT`/`UPDATE`/`DELETE` triggers exist anywhere in the schema.

This directly corroborates a separate finding from the API-layer Discovery
pass: `apis/api/employees/delete.php`'s cascade-delete of an employee's
payroll/attendance/financial history
(`docs/legacy/business-rule-extraction.md`, "Employee deletion can
cascade-delete payroll/financial history") is implemented as explicit,
sequential `DELETE` statements in PHP application code
(`employee_cascade_delete_related()`), not a database trigger — consistent
with there being no trigger-based cascade logic anywhere in this system.

## PostgreSQL Migration Implication

Nothing to port at the trigger level. Every cascade/side-effect behavior
in this system (payroll finalize/reopen side effects, employee-delete
cascade, notification fan-out) is application-orchestrated, which means
the Java rewrite needs to consciously decide, for each one, whether to
keep it in application code or move it to the database layer (Postgres
triggers, `ON DELETE CASCADE`, etc.) — none of that behavior transfers
automatically from "how MySQL enforced it," because MySQL wasn't enforcing
any of it.

## Evidence

`grep -niE "CREATE TRIGGER" mysql_workin.schema.sql` run against the full
1,718-line structure export — zero matches.
