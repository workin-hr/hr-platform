# Running The Backend, For Client Developers

For a Flutter or desktop developer who needs the API running to build against.
You do not need a JDK, a database, credentials, a VPS, or any knowledge of the
backend.

## Once

Install Docker Desktop (or Docker Engine + the compose plugin).

**You need to sign in to the image registry first.** The published image is
**private**, so an unauthenticated `pull` fails with `unauthorized` — see
"Why a login is needed" below. Ask the repository owner for a personal access
token with the **`read:packages`** scope, then:

```bash
export CR_PAT=<the token you were given>
echo "$CR_PAT" | docker login ghcr.io -u <your-github-username> --password-stdin
```

That is once per machine; Docker stores it. Then:

```bash
git clone https://github.com/workin-hr/hr-platform.git
cd hr-platform/deploy
docker compose -f compose.dev.yaml up -d
```

First start takes a few minutes — it restores a 9.6 MB database. Watch it with
`docker compose -f compose.dev.yaml logs -f app`, and wait for the app to
report healthy.

The API is then on **`http://localhost:8080`**.

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
| Desktop app, or iOS simulator | `http://localhost:8080` |
| **Android emulator** | `http://10.0.2.2:8080` |
| **Physical phone**, same Wi-Fi | `http://<your machine's LAN IP>:8080` |

`localhost` inside an Android emulator is the emulator, not your machine —
`10.0.2.2` is the alias for the host. For a physical device, find your IP with
`hostname -I` (Linux) or `ipconfig getifaddr en0` (macOS); the API is published
on all interfaces so the phone can reach it, and both devices must be on the
same network.

## What is in the database

A **sanitised copy of production**: real in shape, fake in content. 386
companies, 3,783 employees, 44,756 attendance rows — production's actual
volumes, so pagination and list performance behave the way they will in
production. Every name, phone, address, coordinate, document and amount is
replaced. See `deploy/seed/README.md`.

That matters for client work: a list that is fast against five fabricated rows
tells you nothing. This tells you something.

## Signing in

The dashboard's platform administrator is `devpassword` unless you set
`ADMIN_PASSWORD`. For the mobile and desktop flows, use accounts from the seed
— they are sanitised, so any phone number in it is fake and safe to use.

## When something is wrong

**Pin a known-good backend.** Every build is also tagged by commit, so you can
go back without waiting for anyone:

```bash
BACKEND_TAG=sha-<commit> docker compose -f compose.dev.yaml up -d
```

Then say which SHA worked and which did not — that turns "the API broke" into
something immediately actionable.

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
