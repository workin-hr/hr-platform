# deploy

The Workin backend as a container, for all three environments. One image; only
the profile and the injected configuration differ, so production is never the
first environment to run what it runs.

## For mobile and desktop developers

You need Docker and a clone of this repository. Nothing else — no VPS, no
credentials, no database to install.

```sh
cd deploy
docker compose -f compose.local.yaml up --build
```

First run takes a few minutes: it builds the backend and restores a 9.6 MB
seed. After that it is seconds.

When it is up:

| What | Where |
|---|---|
| API | `http://localhost:8080/apis/...` |
| Health | `http://localhost:8080/actuator/health` |
| MariaDB | `127.0.0.1:13306`, user `workin`, password `workin-local` |

Every account in the seed has the password **`devpassword`**. Pick any company
or employee phone number out of the database and sign in as them.

```sh
# a company you can log in as
docker exec -it workin-local-db-1 \
  mariadb -uworkin -pworkin-local workin \
  -e "SELECT phone, company_name FROM companies LIMIT 5"
```

### The data is real in shape and fake in content

386 companies, 3,783 employees, 44,756 attendance rows — production's actual
volumes, so pagination, list performance and payroll arithmetic behave the way
they will in production. Every name, phone number, address, coordinate,
document URL, credential and amount has been replaced. See
[`seed/README.md`](seed/README.md).

**Phone numbers are format-valid but undialable**: `010000NNNNN`. They pass the
application's own validation, so login flows work, and they reach nobody.

### Reseeding

The database seeds on **first start only** — MariaDB runs its init directory
when the data directory is empty, and that directory is a named volume. To take
a newer seed:

```sh
docker compose -f compose.local.yaml down -v   # -v drops the volume
docker compose -f compose.local.yaml up
```

### Things that will look broken and are not

**The admin UI at `/admin` will not hold a session over plain HTTP.** The
session cookie is `Secure` unconditionally and that is not relaxed for
convenience. `/apis/**` is bearer-token authenticated and needs no cookie,
which is what this stack is for; to work on the admin UI, put TLS in front.

**Every OTP route answers 503 `otp_delivery_failed`.** WhatsApp is deliberately
unconfigured. That is legacy's own behaviour without credentials.

## The other two environments

| | Profile | Data | Secrets |
|---|---|---|---|
| `compose.local.yaml` | `local` | sanitised seed | committed defaults |
| `compose.integration.yaml` | `integration` | sanitised seed | from `.env.integration` |
| `compose.prod.yaml` | `prod` | restored by hand | from `.env.prod`, all required |

Integration holds no real data but still takes real secrets, because more than
one person can reach it — a shared box with a committed signing secret is a box
anyone can mint tokens for. Its `JWT_SECRET` must **differ** from production's,
so a token minted there is not accepted here.

```sh
cp env.integration.example .env.integration    # then fill it in
docker compose -f compose.integration.yaml --env-file .env.integration up -d
```

### Production

```sh
cp env.prod.example .env.prod                  # then fill it in
docker compose -f compose.prod.yaml --env-file .env.prod up -d
```

`compose.prod.yaml` mounts **no seed directory**. Production data arrives by
restoring a real dump as a deliberate, supervised step — never from a file the
compose file would run on any first start, unreviewed.

Both ports bind to loopback. TLS terminates at a reverse proxy on the host, and
the `prod` profile trusts forwarded headers — which is only safe because that
proxy is the sole route to the container. **R-049** (the per-IP OTP cap is keyed
on a spoofable header) is still open; the loopback binding is what keeps it
unreachable from the internet meanwhile.

Before the first production start, work through the pre-deployment list in
`docs/bootstrap/risk-register.md` — **R-023** (schema provisioning), **R-024**
(the signing secret must match PHP's, or every user is logged out twice) and
**R-025** (the rollback target has never been shown to be restorable).

## Why this directory is unlocked

`Dockerfile` and `docker-compose.yml` are forbidden filenames outside `spike/`
and `backend/`, so that shipping one is a recorded decision rather than a file
someone added. **D-193** opened this boundary deliberately. Naming a file
`compose.yaml` to slip past the check would have defeated the rule while
appearing to honour it.
