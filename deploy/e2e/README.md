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

The dashboard's session cookie is `secure` unconditionally (ADR-0015
prerequisite 6). Rather than relax that flag to make a browser test convenient —
the exact thing the flag exists to prevent — the integration run puts a real
nginx in front with a throwaway certificate and tells Playwright to accept that
one certificate. The connection is TLS; only the certificate is disposable.

The key and certificate are written **outside the repository**, under
`$TMPDIR`. `validate_phase0`'s secret scan reads the working tree rather than
the index, which is the right behaviour — its job is to catch a private key
*before* somebody commits it — so the answer is to keep the key out of the tree
rather than teach the scan to look away.

## Configuration

`integration` and `prod` run from `.env.<profile>-e2e`, which `run.sh` generates
and git ignores. It deliberately does **not** read or write `.env.integration`
or `.env.prod`: those are an operator's real files, and a test run must not be
able to start a real deployment or leave throwaway secrets where a real one
would pick them up.

| Variable | Default | |
|---|---|---|
| `ADMIN_ACTIONS_ENABLED` | `false` | `true` runs the step-up and audit case |
| `E2E_SEED_PROD` | unset | restores the sanitised seed into the prod stack by hand |
| `E2E_REGENERATE_TLS` | unset | new certificate |
| `E2E_TLS_DIR` | `$TMPDIR/workin-e2e-tls-<uid>` | where the run's key and certificate live |
| `E2E_REGENERATE_ENV` | unset | new `.env.<profile>-e2e` |

Any `docker compose` command that includes `e2e/compose.proxy.yaml` needs
`E2E_TLS_DIR` exported — compose interpolates across the merged files, so even
`build app` fails without it. `run.sh` sets it; driving compose by hand does
not.

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

Signs in through the real MFA flow once, then captures each page named. The
alternative — rebuild, click through login and a TOTP code by hand, look — is a
minute per iteration, and appearance work is many iterations. `SHOT_PROBE` runs
an expression in the page after each capture and prints what it returns, which
is how you learn that a table is 813px wide inside a 1130px card instead of
guessing from the picture.

## Reports

`report/index.html` after a run (`npx playwright show-report report`), with
`report/screenshots/` holding one full-page capture per dashboard page.

## Not wired into CI

CI has no Docker daemon with the image built, and a full run is minutes rather
than seconds. This is a pre-cutover and pre-release check that a human starts,
in the same category as `scripts/verify-platform-admin-flow.sh` — which covers
the same admin flow at the HTTP level and remains the quicker way to re-check it
by hand.
