# Trial Stack

The Java backend and its own MariaDB, on one machine, with `docker compose`.

## What this is, and what it is not

**A trial environment.** The owner's PHP on Hostinger remains the live
system serving customers. This holds a **point-in-time copy** of that
database, and it drifts from the real one the moment anyone uses the real
one. Nothing here is a migration, and nothing here is authoritative.

That is deliberate and it is the safe arrangement: a mistake in this stack
cannot reach a customer. It also means that when a real cutover is
scheduled, it starts with a **fresh dump** — this one will be stale.

Not production, so it does not carry a production deployment's controls.
When it becomes production it moves to `infrastructure/`, which exists for
exactly that and is locked until an architecture decision opens it.

## First run

```sh
cp .env.example .env      # then fill it in
# put a production dump at seed/01-production.sql
docker compose up -d
```

The database seeds from `seed/` **only when its data directory is empty**.
MariaDB's entrypoint runs `/docker-entrypoint-initdb.d` on first
initialisation and never again, and the data directory is a named volume, so:

| You run | The seed re-runs? |
|---|---|
| `docker compose restart` | No |
| `docker compose down` then `up` | No — the volume outlives the containers |
| `docker compose down -v` then `up` | **Yes** — `-v` destroys the volume |

Verified rather than assumed: a marker row written after the first start
survived a full `down`/`up`, and the second start logged no `initdb` step.

Reseeding therefore takes a deliberate `-v`. It cannot happen by accident.

Seed order is alphabetical, which is why the files are numbered:

| File | Contents |
|---|---|
| `01-production.sql` | The dump: 44 tables, real data. **Git-ignored** |
| `02-phase1-extensions.sql` | The 10 tables Phase 1 adds (R-023) |

## The dump

Take it read-only, with a consistent snapshot:

```sh
mariadb-dump -h HOST -P PORT -u USER -p \
  --single-transaction --skip-lock-tables --no-tablespaces \
  --default-character-set=utf8mb4 --hex-blob \
  DBNAME > seed/01-production.sql
```

`--single-transaction` takes the snapshot without locking, so the live site
keeps serving while it runs. About 10 MB for the current database.

**It holds real customer data** — names, phone numbers and salaries for
3,783 employees. `seed/.gitignore` keeps it out of the repository; keep it
off shared drives and out of tickets too.

## Two things set deliberately

**MariaDB is published on `127.0.0.1` only.** The application reaches it
over the compose network and needs no published port at all; the loopback
binding exists so you can attach a client from the box itself. The
Hostinger database is currently reachable from the whole internet, and this
stack does not repeat that.

**The WhatsApp credentials are left unset.** Without them every OTP route
answers `otp_delivery_failed`, which is legacy's own behaviour when
unconfigured. Set them on a box holding a copy of real customer data and a
password-reset test sends a real message to a real person.

## The signing secret

`JWT_SECRET` must equal the PHP deployment's `AppConfig::JWT_SECRET`, or
tokens already on users' devices stop working. The application logs a
fingerprint of it at every startup; compare that with PHP's rather than
comparing the secrets themselves —
`docs/operations/verifying-the-signing-secret.md`.

On a trial box with a throwaway secret, expect existing tokens to be
rejected. That is correct, and it is the thing to get right before a real
cutover.

## What runs

| Service | |
|---|---|
| `db` | MariaDB 11.8, matching production's 11.8.8. Started **non-strict** (`--sql-mode=`), because production is: a strict server rejects rows the live system holds, including its 24 zero-date rows |
| `app` | The backend under `phase1-mysql`, serving `/apis/**` and the admin surface at `/admin` |

Read the first seconds of `docker compose logs app`. Three lines say whether
the deployment is sound:

```text
Phase 1 schema check: all 10 owned tables are present.
JWT signing secret fingerprint: <16 hex>
WhatsApp OTP delivery is configured        (or the ERROR saying it is not)
```

## The smoke test this stack has actually passed

Run on 2026-09-05 against the full production copy (386 companies, 3,783
employees), on the image built from the commit that added it. Recorded
because "the stack starts" and "the stack serves what the clients ask for"
are different claims, and only the second is worth anything at a cutover.

| Checked | Result |
|---|---|
| Startup | `Started BackendApplication in 6.8s`, all 10 Phase 1 tables present |
| Every route the clients call, incl. the four newest | 401, not 404 — mapped in the shipped jar |
| `configs/get` key order | `server_time`, `server_unix`, `server_timezone` — PHP's order |
| `show_export_*` flags | Present in the production data, so the desktop export buttons resolve |
| `template_excel` | `employees_template_…xlsx`; with `?purpose=update`, `employees_update_template_…xlsx`, 28 columns, exactly one example, "فاضي = بدون تعديل" captions |
| `analyze_excel` on a 4-column sheet | `unknown` without `sheet_layout`, `punch_log` with it — 4 punches, 2 days, the overnight row rolled to 06:30 the next morning |
| `import_excel` with `sheet_layout` | 2 rows written, `method = 'excel'`, the overnight row spanning the midnight |
| `profile/logout`, admin, no platform | Account **stays active** — the role gate |
| `profile/logout`, employee, `platform=desktop` | Account **stays active** |
| `profile/logout`, employee, `platform=android` | Deactivated, `token_version` bumped, the token issued a moment earlier now 401 |
| Message catalog in the jar | All six corrected entries present at `BOOT-INF/classes/legacy/lang/en.properties` |

The fixture was a throwaway company and two employees at ids far above the
live maxima, removed afterwards; the company and employee counts were
verified identical before and after. Do the same if you repeat this —
**this database is a copy of real customer data**, and a test row left in it
is a row someone later mistakes for real.
