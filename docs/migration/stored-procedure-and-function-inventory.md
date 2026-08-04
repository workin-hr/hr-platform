# Stored Procedure And Function Inventory

## Result: Confirmed — Zero Stored Procedures Or Functions Exist

`workin-hr/hr-legacy`'s `mysql_workin.schema.sql` (structure-only export,
commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`) was grepped in full for
`CREATE (PROCEDURE|FUNCTION)` (case-insensitive). Zero matches. All
business logic — including the payroll calculation engine, attendance
geofencing, and every other rule documented in
`docs/legacy/business-rule-extraction.md` — lives in PHP
(`apis/helpers/*.php`), not in MySQL stored routines.

The schema does use MySQL `GENERATED ALWAYS AS (...) STORED` computed
columns (`leave_balance.remaining_days`, `salary_contracts.total`) — these
are column-level generated expressions, not stored procedures/functions,
and are already tracked separately in
`docs/migration/database-schema-inventory.md`'s "MySQL/MariaDB-Specific
Features" table.

## PostgreSQL Migration Implication

Nothing to port at the stored-routine level. This is a significant
simplifying fact for the migration: **all business logic is already in
application code**, meaning the Java rewrite's job is porting PHP business
rules (already extensively documented in
`docs/legacy/business-rule-extraction.md`) into Java, not reverse-engineering
opaque SQL routines.

## Evidence

`grep -niE "CREATE (PROCEDURE|FUNCTION)" mysql_workin.schema.sql` run
against the full 1,718-line structure export — zero matches.
