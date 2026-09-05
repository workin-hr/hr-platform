# Fixes for `hr-legacy`

Patches this repository produced for the **PHP** system, not for itself.

`hr-legacy` is a separate repository and is frozen for feature work, but it
serves customers until cutover — so a security defect found while porting is
still worth fixing there. These are supplied as patches rather than committed
upstream because the tree they were written against held unrelated in-flight
changes, and because deploying the PHP is the owner's call, not this
repository's.

| Patch | Risk | Verified |
|---|---|---|
| `R-046-cross-tenant-write.patch` | **R-046** — HR dashboard pages wrote by row id with no tenant check | `R-046-verification.php`, 33 assertions against a copy of production |

## Applying R-046

```sh
cd ../hr-legacy
git apply --check contracts/legacy-fixes/R-046-cross-tenant-write.patch   # dry run
git apply          contracts/legacy-fixes/R-046-cross-tenant-write.patch
```

It touches seven files and adds 113 lines: one helper pair in
`dashboard/includes/hr_helper.php`, one guard call at the top of five pages'
POST blocks, and a rebinding of `complaints`' three existing guards so they
cover HR sessions as well as company owners.

## Verifying it

The harness needs a database with the schema and some rows — the trial stack's
is the one it was written against:

```sh
docker run --rm --network host \
  -v "$PWD:/src:ro" -v "$PWD/contracts/legacy-fixes:/t:ro" \
  -e DBPORT=13307 -e DBNAME=workin -e DBUSER=... -e DBPASS=... \
  php:8.3-cli sh -c "docker-php-ext-install pdo_mysql >/dev/null && php /t/R-046-verification.php"
```

It stubs the session globals and drives `hr_verify_post_row()` through its whole
decision table on every affected table: the owner editing their own row, another
company editing it, the administrator unfiltered, filtered elsewhere and filtered
to the owner — plus id 0 (an add, which must not be blocked), a missing row and
an unknown table (both of which must be refused).

**It reads only.** It selects existing ids and never writes, so it is safe
against a copy of production; it is not safe to point at production itself, and
nothing here needs to be.
