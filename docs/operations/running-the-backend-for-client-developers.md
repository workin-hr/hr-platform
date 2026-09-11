# Running The Backend, For Client Developers

For a Flutter or desktop developer who needs the API running to build against.
You do not need a JDK, a database, credentials, a VPS, or any knowledge of the
backend.

## Once

Install Docker Desktop (or Docker Engine + the compose plugin).

**On x86-64, or on a machine that can emulate it.** The published image is
`linux/amd64` only — CI builds it on a GitHub-hosted x86 runner. Apple Silicon
is fine: Docker Desktop emulates amd64 transparently. A native ARM64 **Linux**
host is not, unless `binfmt`/QEMU is installed — without it the pull succeeds
and the container fails to start, which looks like a broken image rather than
an architecture mismatch. Check with `docker version --format '{{.Server.Arch}}'`.

**You need to sign in to the image registry first.** The published image is
**private**, so an unauthenticated `pull` fails with `unauthorized` — see
"Why a login is needed" below.

**Create your own token; do not use anyone else's.** Ask the repository owner
to grant your GitHub account read access to the package, then generate a
personal access token *on your own account* with the **`read:packages`** scope:

```bash
export CR_PAT=<the token you generated>
echo "$CR_PAT" | docker login ghcr.io -u <your-github-username> --password-stdin
```

A shared token would authenticate as its owner while the `-u` above names you,
and it would give every recipient whatever else that account can read. It also
cannot be revoked for one person: revoking it logs everybody out. One token per
developer keeps access grantable and revocable individually.

That is once per machine; Docker stores it. Then:

```bash
git clone https://github.com/workin-hr/hr-platform.git
cd hr-platform/deploy
docker compose -f compose.dev.yaml up -d
```

First start takes a few minutes — it restores a 9.6 MB database. Wait for it
with:

```bash
docker compose -f compose.dev.yaml up -d --wait
```

`--wait` returns when the containers report **healthy**, so it is the
completion signal. `logs -f app` is useful for watching progress, but Docker
keeps `HEALTHCHECK` state in the engine and never prints "healthy" to the
application's output — following the logs gives you nothing to wait *for*. To
check after the fact:

```bash
docker compose -f compose.dev.yaml ps          # STATUS column shows (healthy)
curl -fsS http://localhost:8080/actuator/health
```

The API is then on **`http://localhost:8080/apis/api/`** — see
"Which `baseUrl` to use" below, because the prefix is part of it.

> The clone is for the compose file and the seed, not for the backend source.
> The backend itself is **pulled as a published image**, so no Gradle build
> runs on your machine and you never need the JDK.

### Why a login is needed

A new package on `ghcr.io` is **private by default and does not inherit the
repository's visibility**, even when the repository is public. GitHub's own
documentation is contradictory here — the packages-with-Actions page says a
workflow-created package "inherits the visibility ... of the repository", while
the visibility page says the default is private and inheritance covers
"access permissions (but not the visibility)".

This was settled by measurement rather than by reading, using a throwaway
package published over the same `GITHUB_TOKEN` path from this public
repository:

```text
$ docker logout ghcr.io
$ docker pull ghcr.io/workin-hr/hr-platform/visibility-probe:probe
Error response from daemon: Head "https://ghcr.io/v2/.../manifests/probe": unauthorized
```

The visibility page is the one that describes actual behaviour. **The package
stays private deliberately** — this is a commercial product, and GitHub
documents making a package public as irreversible ("Once you make a package
public, you cannot make it private again"). Authentication is therefore part
of the client flow by design, not an oversight.

If a pull ever fails with `denied` rather than `unauthorized`, the token is
being sent but lacks `read:packages`.

## Every time the backend changes

```bash
cd hr-platform/deploy
git pull
docker compose -f compose.dev.yaml pull
docker compose -f compose.dev.yaml up -d
```

`pull` is the whole update. CI publishes a new image on every push to `main`
(`.github/workflows/publish-image.yml`), so the backend you get is whatever
merged, usually within a minute or two of it merging.

## Which `baseUrl` to use

This is where the hour usually goes. `localhost` means different things in
different places:

| Where the client runs | `baseUrl` |
|---|---|
| Desktop app, or iOS simulator | `http://localhost:8080/apis/api/` |
| **Android emulator** | `http://10.0.2.2:8080/apis/api/` |
| **Physical phone**, same Wi-Fi | `http://<your machine's LAN IP>:8080/apis/api/` |

The trailing `/apis/api/` is part of the base URL, not something the client
appends per route. Every client route is served beneath it — `login_company` is
`/apis/api/auth/login_company` — so a base URL without it returns 404 for
everything, which reads like a backend that is down rather than a wrong prefix.
This matches the committed client configuration and `flutter-local-integration.md`.

`localhost` inside an Android emulator is the emulator, not your machine —
`10.0.2.2` is the alias for the host. For a physical device, find your IP with
`hostname -I` (Linux) or `ipconfig getifaddr en0` (macOS); the API is published
on all interfaces so the phone can reach it, and both devices must be on the
same network.

### Mobile clients must be allowed to speak plain HTTP first

These URLs are `http://`, and **neither Android nor iOS permits cleartext by
default** — so on a mobile target the first request fails even when the address
is right. Before using the table above, make the debug-only changes in
[flutter-local-integration.md](flutter-local-integration.md#cleartext-http-is-blocked-and-you-have-to-turn-it-on):
`android:usesCleartextTraffic="true"` in the debug manifest, and the
`NSAppTransportSecurity` exception in the iOS `Info.plist`.

Both are for local development against this backend only. Neither belongs in a
release build, and production is HTTPS, so neither is needed there.

## What is in the database

A **sanitised copy of production**: real in shape, fake in content. 386
companies, 3,783 employees, 44,756 attendance rows — production's actual
volumes, so pagination and list performance behave the way they will in
production. Every name, phone, address, coordinate, document and amount is
replaced. See `deploy/seed/README.md`.

That matters for client work: a list that is fast against five fabricated rows
tells you nothing. This tells you something.

## Signing in

For the mobile and desktop flows, use accounts from the seed — they are
sanitised, so any phone number in it is fake and safe to use.

**The web dashboard signs in on `localhost`, and not from another machine.**
`server.servlet.session.cookie.secure=true` is unconditional, so the session
cookie is only sent back over a connection the client considers secure.
Browsers -- and `curl` -- treat `http://localhost` and `http://127.0.0.1` as
secure contexts, so on the machine running the stack the dashboard works
normally.

Open it by **LAN IP** from a phone or another laptop and it will not stay
signed in: that origin is not a secure context, the cookie is dropped, and you
land back on the sign-in page with nothing to explain why. That is the stack,
not a bug you have found. Put a TLS proxy in front of it if you need the
dashboard off-machine. The client API is unaffected either way -- it
authenticates with a bearer token and never uses that cookie.

## When something is wrong

**Pin a known-good backend.** Every build is also tagged by commit, so you can
go back without waiting for anyone:

```bash
BACKEND_TAG=sha-<commit> docker compose -f compose.dev.yaml up -d
```

Then say which SHA worked and which did not — that turns "the API broke" into
something immediately actionable.

For an exact rebuild, pin the **digest** instead. `sha-<commit>` is a tag, and
re-running the publish workflow at the same commit pushes that tag again; the
Dockerfile uses floating base-image tags and `apt-get`, so the second build can
differ from the first. The digest cannot. Each publish run prints it in its
summary, and you can read it from a tag you still have:

```bash
# prints ghcr.io/workin-hr/hr-platform/backend@sha256:<digest>
docker image inspect --format '{{index .RepoDigests 0}}' \
  ghcr.io/workin-hr/hr-platform/backend:sha-<commit>
```

Then pass the `sha256:...` part — `name:tag@digest` is a valid reference and
the digest is what actually gets pulled:

```bash
BACKEND_TAG='sha-<commit>@sha256:<digest>' docker compose -f compose.dev.yaml up -d
```

**Start clean.** This destroys the database and re-seeds on next start:

```bash
docker compose -f compose.dev.yaml down -v
docker compose -f compose.dev.yaml up -d
```

**Look at the API directly**, to establish whether a problem is client-side:

```bash
curl -s http://localhost:8080/actuator/health
```

**Attach a SQL client** on `127.0.0.1:13306`, user `workin`, password
`workin-local`, database `workin`.

## Which compose file

| File | For | Backend |
|---|---|---|
| `compose.dev.yaml` | client developers | **pulled** from the registry |
| `compose.local.yaml` | backend developers | **built** from your working tree |

They are otherwise identical. Use `compose.local.yaml` only if you are editing
the backend and want your own change running.
