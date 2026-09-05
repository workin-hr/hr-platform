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
| `R-046-cross-tenant-write.patch` | **R-046** — HR dashboard pages wrote by row id with no tenant check | `R-046-verification.php`, 43 assertions against a copy of production |

## Applying R-046

```sh
cd ../hr-legacy
git apply --check contracts/legacy-fixes/R-046-cross-tenant-write.patch   # dry run
git apply          contracts/legacy-fixes/R-046-cross-tenant-write.patch
```

It touches nine files: one helper pair in
`dashboard/includes/hr_helper.php`, one guard call at the top of seven pages'
POST blocks, and a rebinding of `complaints`' three existing guards so they
cover HR sessions as well as company owners.

`workforce_planning` was added on 2026-09-05, after the first version of this
patch shipped. It was missed because the original sweep matched the page's
`org_branch_belongs_to_company()` / `org_department_belongs_to_company()`
calls and read them as row guards; they are foreign-key checks inside
`$validateWpPayload()` and say nothing about who owns the row being written.
Its `delete_wp` action had no tenant check of any kind — not even the
`$cid > 0` one its sibling `edit_wp` carries — so a company-scoped session
could delete another company's row by posting its id. R-046 therefore covers
**seven** of the eight HR pages it names, not six.

`employees` was added on 2026-09-05 as well, and carries the heaviest
consequences in this patch — it is tracked separately as **R-053** because
R-046's page table never named it. All four of its write paths take the row id
from the POST body with no tenant check: `save_edit` can write
`password_hash`, which is the credential `login_employee.php` verifies, and
`delete` is a hard delete from which fourteen tables cascade. It ships here
rather than in a patch of its own because it is the same defect, the same
guard and the same file set, and a live account-takeover path should not wait
for a second review cycle.

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
