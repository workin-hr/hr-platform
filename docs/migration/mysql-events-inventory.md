# MySQL Events Inventory

## Result: Confirmed — Zero Scheduled Events Exist

`workin-hr/hr-legacy`'s `mysql_workin.schema.sql` (structure-only export,
commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`) was grepped in full for
`CREATE EVENT` (case-insensitive). Zero matches. There is no MySQL Event
Scheduler usage in this system.

This does **not** mean there is no scheduled/periodic behavior at all —
only that none of it lives in the database. Any cron-like behavior (e.g.
leave-balance accrual, notification digests) would be implemented as an
external cron job or application-triggered process outside the scope of
this schema-only export; that surface was not part of this Discovery pass
and would need its own investigation (server crontab, a queue worker,
etc.) if scheduled jobs turn out to matter for migration.

## PostgreSQL Migration Implication

Nothing to port at the database-event level. If scheduled application
behavior is discovered separately (see caveat above), the replacement
approach (pg_cron, an external scheduler, a Java scheduled-task framework)
is a decision independent of this schema.

## Evidence

`grep -niE "CREATE EVENT" mysql_workin.schema.sql` run against the full
1,718-line structure export — zero matches.
