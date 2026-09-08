# OpenAPI Contracts

The Phase-1 API description is **generated and served by the application**, not
committed here.

- Swagger UI: `/swagger-ui.html`
- Client API (the 202 legacy routes): `/v3/api-docs/client-api`
- Platform administration: `/v3/api-docs/platform-admin`

Published under the `local` and `integration` profiles; **off under `prod`**, so
a live system does not hand out an unauthenticated map of every endpoint.

## Why nothing is committed in this directory

A checked-in snapshot would be a third artifact describing the same surface, and
the two that already exist are derived from the code rather than from each
other:

| Artifact | What it asserts | What keeps it true |
|---|---|---|
| `contracts/legacy-php-routes.txt` | which routes exist | `scripts/check_legacy_route_drift.py` |
| `backend/src/main/resources/legacy/route-methods.txt` | which verb each route accepts | `scripts/check_openapi_route_methods_drift.py` |

The served document is those two plus springdoc's view of the controllers. A
snapshot beside them could only drift, and drift in a *description* is the
failure mode that matters here — a client developer has no reason to doubt it.

## What the document deliberately corrects

Generated straight from the controllers it would state two false things, and
`com.workin.backend.openapi.OpenApiConfig` fixes both (`OpenApiConfigTest`
pins the behaviour):

1. **Verbs.** Every legacy route is mapped with no method restriction, so the
   handler can answer PHP's own `405 invalid_method` in the legacy envelope
   (D-111). springdoc would therefore document every verb on every route. The
   verbs are pruned to what each handler actually checks.
2. **Paths.** The mappings carry the PHP *file* path because the inventory was
   built from the PHP source tree. That is not the URL: against the system this
   replaces, `configs/get` answers 200 and `configs/get.php` answers 500, and
   neither Flutter client uses the suffix. The published paths are suffix-less.

## What it does not describe

The response envelope is `{success, message, data?, meta?}` with `data` and
`meta` typed as free-form objects, because the port returns PHP's own structures
rather than re-typing 202 endpoints. The document is authoritative about which
routes exist and which verb each accepts, and silent about the payload shape.
Payload compatibility is evidenced separately, in
[`docs/api/flutter-request-response-compatibility.md`](../../docs/api/flutter-request-response-compatibility.md).

A hand-authored or approved OpenAPI document belongs in this directory if
Phase 2 ever designs an API rather than reproducing one.
