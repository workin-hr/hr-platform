# MySQL Views Inventory

## Result: Confirmed — Zero Views Exist

`workin-hr/hr-legacy`'s `mysql_workin.schema.sql` (structure-only export,
commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`) was grepped in full for
`CREATE VIEW` (case-insensitive). Zero matches. There are no MySQL views
in this schema — every read-side composition (joins, computed totals,
filtered lists) happens in PHP application code (`apis/helpers/*.php`,
`dashboard/includes/*.php`), not in the database.

## PostgreSQL Migration Implication

Nothing to port at the view level. This simplifies the schema migration —
there is no update-semantics or permissions behavior tied to a view layer
to preserve. Any Postgres views the new system chooses to add would be a
net-new architectural decision, not a migration of existing behavior.

## Evidence

`grep -niE "CREATE VIEW" mysql_workin.schema.sql` run against the full
1,718-line structure export — zero matches, cross-checked against the
same file already read in full for `docs/migration/database-schema-inventory.md`.
