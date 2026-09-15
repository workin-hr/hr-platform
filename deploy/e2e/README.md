# End-to-end tests

Drives the **deployed stack** — a container, a real database, a browser — rather
than the application in a test harness. What it is for is the class of defect a
unit or slice test cannot see: a profile that switches the wrong thing on, a
cookie the browser refuses, a page that renders a translation key, a refusal
that still writes a row.

```sh
cd deploy/e2e
./run.sh local          # the client API and the deployment shape
./run.sh integration    # ...and the admin dashboard, in a browser, over TLS
./run.sh prod           # the production shape, on a throwaway stack
```

`run.sh` builds the image, starts the stack, waits for it to report healthy,
runs the suite, and leaves the stack up so you can look at it. Add
`--headed`, `--debug`, or any other Playwright flag after the profile.

## What runs where, and why not everywhere

| Suite | local | integration | prod | |
|---|---|---|---|---|
| `client-api` | ✅ | ✅ | — | prod mounts no seed; there is nothing to read |
| `deployment-shape` | ✅ | ✅ | ✅ | the profile's own promises |
| `admin-dashboard` | — | ✅ | — | needs TLS in front, which only integration has |
| `row-dialog`, `setting-templates`, `emp-picker`, `job-title-form`, `department-form`, `workforce-form`, `employee-form` | ✅ | ✅ | — | the dashboard's own scripts on a static page; they need no stack, so `npx playwright test --project=browser` runs them on their own |

The dashboard's session cookie is `secure` unconditionally (ADR-0015
prerequisite 6). Rather than relax that flag to make a browser test convenient —
the exact thing the flag exists to prevent — the integration run puts a real
nginx in front with a throwaway certificate and tells Playwright to accept that
one certificate. The connection is TLS; only the certificate is disposable.

The key and certificate are written **outside the repository**, in the user's
state directory (`$XDG_STATE_HOME` when it is an absolute path, otherwise
`~/.local/state`), and not under `/tmp`: the stack restarts after a reboot, and
a certificate kept where the reboot clears it would not be there for the proxy.
`validate_phase0`'s secret scan reads the working tree rather than the index,
which is the right behaviour — its job is to catch a private key *before*
somebody commits it — so the answer is to keep the key out of the tree rather
than teach the scan to look away. `run.sh` therefore refuses an `E2E_TLS_DIR`
that is relative or resolves inside the repository, and
`scripts/test-e2e-run-tls.sh` checks where the key goes on every pull request.

## Configuration

`integration` and `prod` run from `.env.<profile>-e2e`, which `run.sh` generates
and git ignores. It deliberately does **not** read or write `.env.integration`
or `.env.prod`: those are an operator's real files, and a test run must not be
able to start a real deployment or leave throwaway secrets where a real one
would pick them up.

| Variable | Default | |
|---|---|---|
| `ADMIN_ACTIONS_ENABLED` | `false` | `true` runs the administrative-action case |
| `E2E_SEED_PROD` | unset | restores the sanitised seed into the prod stack by hand |
| `E2E_REGENERATE_TLS` | unset | new certificate |
| `E2E_TLS_DIR` | `$XDG_STATE_HOME/workin-e2e/tls` if `XDG_STATE_HOME` is absolute, otherwise `~/.local/state/workin-e2e/tls` | where the run's key and certificate live: an absolute path outside the repository, not somewhere a reboot clears |
| `E2E_REGENERATE_ENV` | unset | new `.env.<profile>-e2e` |

Any `docker compose` command that includes `e2e/compose.proxy.yaml` needs
`E2E_TLS_DIR` exported — compose interpolates across the merged files, so even
`build app` fails without it. `run.sh` sets it; driving compose by hand does
not.

If the proxy restarts endlessly with `cannot load certificate`, its certificate
directory is gone, and Docker has put empty directories owned by root in its
place. What recovers it depends on the compose project the proxy belongs to,
which `docker inspect` shows as the label `com.docker.compose.project`:

- **`workin-e2e-integration`** is `run.sh`'s own project. Run `run.sh
  integration` again. If it reports that the directory is not writable, stop
  the proxy, remove the empty root-owned directories Docker created with
  `sudo rmdir`, deepest first, and run it once more.
- **`workin-integration`** is a stack started with these compose files
  directly, or by a `run.sh` from before it passed `-p` (2026-09-08). `run.sh`
  does not manage that project, and its own stack would ask for ports that
  stack already holds. Replace only that stack's proxy, which leaves its
  application and database running:
  1. Work from the `deploy/` directory that started it (the label
     `com.docker.compose.project.working_dir`).
  2. Set `ENV_FILE` to the environment file it used (the label
     `com.docker.compose.project.environment_file`).
  3. Note the directory the proxy mounts now, which `docker inspect` shows as
     its mount at `/etc/nginx/tls`. A stack from before this change mounted
     `${TMPDIR:-/tmp}/workin-e2e-tls-<uid>` as its own operator had them set,
     so it need not be the one your shell would name.
  4. Give the proxy a certificate where a reboot does not clear it, the way
     `run.sh` makes one, and recreate the proxy on it.
  5. Remove the directory noted in step 3.

  ```sh
  # A subshell that stops at the first failure: the proxy is recreated only once
  # a certificate exists, and the old directory is removed only after that.
  (
    set -eu
    old_tls_dir="$(docker inspect workin-integration-proxy-1 \
      --format '{{range .Mounts}}{{if eq .Destination "/etc/nginx/tls"}}{{.Source}}{{end}}{{end}}')"
    [ -n "$old_tls_dir" ] || { echo "no mount at /etc/nginx/tls was found" >&2; exit 1; }
    export E2E_TLS_DIR="$HOME/.local/state/workin-e2e/tls"
    mkdir -p "$E2E_TLS_DIR"
    chmod 700 "$E2E_TLS_DIR"
    openssl req -x509 -newkey rsa:2048 -nodes -days 30 \
      -keyout "$E2E_TLS_DIR/server.key" -out "$E2E_TLS_DIR/server.crt" \
      -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
    chmod 644 "$E2E_TLS_DIR/server.crt" "$E2E_TLS_DIR/server.key"
    [ -s "$E2E_TLS_DIR/server.key" ]
    [ -s "$E2E_TLS_DIR/server.crt" ]
    docker compose -p workin-integration -f compose.integration.yaml \
      -f e2e/compose.proxy.yaml --env-file "$ENV_FILE" up -d --no-deps proxy
    sudo rmdir "$old_tls_dir"
  )
  ```

`E2E_SEED_PROD` restores the seed with `mariadb <` rather than by mounting it,
because `compose.prod.yaml` mounts no seed **on purpose**: production data
arrives by a supervised restore that a human reviews, never by a file that runs
on any first start. Adding a mount to make testing easier would put that path
into production too.

## What it asserts, and what it deliberately does not

Every case that expects a **refusal** also fingerprints the target row before
and after. A 404 proves the caller was told no; it does not prove nothing was
written, and those are different claims.

The page list for the dashboard is read from
`contracts/legacy-dashboard-pages.txt` — the same committed manifest the port is
measured against — minus the three pages D-192 put out of scope. A page ported
later is covered the day it lands, without this suite being edited.

It does **not** re-check PHP↔Java response parity. `spike/parity-harness/`
already does that for every endpoint in the Flutter clients' constants, against
both stacks on one database, which is a stronger comparison than anything this
suite could make on its own.

## Looking at a page while you change it

```sh
node shoot.mjs employees branches payroll     # -> shots/<page>.png
```

Signs in once, then captures each page named. The alternative — rebuild, click
through login by hand, look — is a minute per iteration, and appearance work is many iterations. `SHOT_PROBE` runs
an expression in the page after each capture and prints what it returns, which
is how you learn that a table is 813px wide inside a 1130px card instead of
guessing from the picture.

## Measuring against production

`prod-shots.mjs` captures one full-page screenshot per dashboard page from the
live PHP surface, and `headers-prod.mjs` captures each page's rendered `<th>`
list. `headers-local.mjs` captures the same headers from this application, so
the two can be diffed -- which is how **D-199** and **D-200** measured column
parity. Diffing rendered headers rather than counting patterns in the source
matters: the source heuristic gave wrong answers on four pages.

Both production tools install a route guard that allows **GET only**, plus the
single login POST, and abort anything else with a line on stdout. Neither holds
a credential: the password comes from a file named by `PROD_SECRET_FILE`, which
is the operator's to provide.

They are a read-only evidence check, and `AGENTS.md` requires the repository
owner's explicit authorization for each one -- so they are run by hand, on
request, and never from CI or a script. Their output is production data: real
names, phone numbers and salaries. It stays outside the repository.

```sh
PROD_SECRET_FILE=~/secret PROD_MANIFEST=pages.txt PROD_HEADERS_OUT=prod.json \
  node headers-prod.mjs
LOCAL_MANIFEST=pages.txt LOCAL_HEADERS_OUT=local.json node headers-local.mjs
```

## Reports

`report/index.html` after a run (`npx playwright show-report report`), with
`report/screenshots/` holding one full-page capture per dashboard page.

## Not wired into CI

CI has no Docker daemon with the image built, and a full run is minutes rather
than seconds. This is a pre-cutover and pre-release check that a human starts.
