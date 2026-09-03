# Admin Web Boundary

**Empty on purpose, and it is not where the admin web lives.**

This directory was reserved for a Next.js admin application under ADR-0014.
That premise was superseded on 2026-09-01 (**D-151**,
[ADR-0015](../docs/adr/ADR-0015-platform-admin-jte-authentication.md)): the
platform-admin web surface is **server-rendered JTE pages inside the existing
Spring application**, one deployment, on the application's own authentication
and session model.

So the admin web is built under `backend/` — templates in `backend/src/main/jte`,
controllers and security chain in
`backend/src/main/java/com/workin/backend/platformadmin/web`. See **D-160**.

This directory stays Phase-0-locked: `scripts/validate_phase0.py` still forbids
application files here, and **D-028** unlocked `backend/` only. Nothing should
land here unless a future decision revives a separate web deployment.
