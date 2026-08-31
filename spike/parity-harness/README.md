# PHP↔Java Parity Harness

Serves `hr-legacy` (PHP) and `hr-platform` (Java) from **one MariaDB**, so any
response difference can only come from the application.

Full write-up, findings and methodology:
`docs/migration/2026-08-31-php-java-parity-harness.md`

Versioned here under `spike/` so the parity results that document cites are
reproducible. `spike/` is the one top-level directory excluded from the Phase 0
file lock (`scripts/validate_phase0.py`, `SPIKE_DIR_NAME`), which is what makes
a `docker-compose.yml` legal at this path.

## Prerequisites

- `hr-legacy` checked out **beside** `hr-platform` in the same parent directory.
  Override with `WORKSPACE=/path/to/parent`, or `LEGACY=` / `PLATFORM=`
  individually.
- The Java jar built once: `(cd "$PLATFORM/backend" && ./gradlew bootJar)`.
- Docker with the compose plugin.

## First-time setup

The two PHP config files are git-ignored because they hold a JWT secret and a
dashboard admin password. Create them from the templates beside them:

```sh
cp php/constants.php.example php/constants.php
cp php/dashboard-constants.php.example php/dashboard-constants.php
# then replace every CHANGE_ME_LOCALLY. JWT_SECRET must match what run-java.sh
# passes to the Java process -- generate one locally, e.g.
#   openssl rand -base64 48 > .jwt-secret
```

Never copy a production value into either file.

## Run

```sh
docker compose up -d db php     # MariaDB :13306, PHP :18080
./seed.sh                       # schema + the legacy snapshot + a test employee
./run-java.sh &                 # Java :18081, same DB, same JWT secret
./login.sh php                  # mint a token (see the ordering note in the script)
./sweep.sh                      # reachability: status codes, unauthenticated
./sweep-auth.sh                 # parity: JSON bodies, authenticated
```

## What is deliberately not wired

- **No production credentials.** The harness generates its own JWT secret and
  dashboard admin password. `hr-legacy/apis/config/constants.php` holds the real
  values and is never read or modified — the container gets a harness-local file
  mounted over that path.
- **No outbound integrations.** WhatsApp, SMS and FCM keep placeholder tokens,
  so no OTP or push can reach a real person. Two legacy OTP routes send messages;
  this is why.
- **`.jwt-secret`, `.php-token`, `.java-token`, the sweep output and the two
  real `php/*.php` configs** are git-ignored. The sweep output is excluded
  because it contains real response bodies from the snapshot, not just verdicts.

## Two traps that cost time

**Every login bumps `token_version`.** A token minted before another login is
correctly rejected. Log into the stack you intend to test *last*, or
single-active-session enforcement reads as a compatibility failure.

**Never compare `data[0]` across stacks.** Where ordering differs, positional
comparison invents catastrophic-looking value differences that are pure
misalignment — it made `basic_salary` look like `4615.44` versus `0.00`. Diff
keyed by id, and separate "same number, different JSON type" from "different
value".
