# F-23: Authorization-Policy Declarations And Architecture Enforcement — Design (2026-08-06)

## Purpose And Authority

Implements ADR-0010 Required Implementation Task 10 (matrix row F-23):
the authorization-policy declaration convention and the architecture
test that fails the build on any externally reachable use case without
a declaration. Anchored entirely to accepted decisions:

- `docs/adr/ADR-0010-authorization-model.md` Dimension 4: "Every
  externally reachable application-service operation must declare
  either its required authorization policy or an explicit marker that
  it is intentionally public/authentication-only. An architecture test
  (ArchUnit ...) must fail the build when an externally reachable use
  case has no authorization declaration."
- `docs/architecture/authorization-model.md` §3: "no permission string
  may be introduced anywhere else in the codebase without a
  corresponding row" in the `permissions` catalog (V4).
- ADR-0007's adopted stack: ArchUnit is already a test dependency,
  scaffolded ahead of exactly this task.

Implementation assigned by the repository owner 2026-08-06
("ok proceed" on the recommendation naming this slice).

## Scope Honesty — What F-23 Is And Is Not

F-23 is the **declaration convention and its build-time enforcement**.
It is not the runtime permission-evaluation engine — that is F-15
(catalog/overrides migration) and F-17 (permission-matrix behavior
tests), per the matrix. ADR-0010's own Risks section records this
split: the architecture test checks *presence* of a declaration; the
paired behavior suite (F-17) checks *correctness*.

A key consequence, handled explicitly rather than silently: shipping a
`@RequiresPermission` annotation with no runtime enforcement would
create decorative security — an endpoint that *looks* gated but is
not. This design closes that hole with a build-breaking tripwire (Rule
3 below): `@RequiresPermission` may not be *used* until the runtime
enforcement component exists. Today's endpoint inventory makes this
workable — every existing endpoint is genuinely public or
authentication-only; no current endpoint needs a permission gate.

## Design

### 1. Declaration annotations (`com.workin.backend.authorization`)

Three method-level, runtime-retained annotations — exactly one must
appear on every externally reachable operation:

- `@PublicUseCase(reason = "...")` — intentionally reachable without
  authentication (registration, login, refresh, logout). The mandatory
  `reason` forces the "intentionally" part of ADR-0010's wording into
  the code.
- `@AuthenticatedUseCase(reason = "...")` — requires an authenticated
  principal but no specific catalog permission (context/identity
  lookups like `/api/tenant/me`, `/api/platform-admin/me`).
- `@RequiresPermission(PermissionKeys.X)` — requires a catalog
  permission. Value must be a `PermissionKeys` constant, never a
  string literal (§3's single-source rule). **Frozen until runtime
  enforcement exists** (Rule 3).

### 2. Typed permission constants (`PermissionKeys`)

A final constants class mirroring V4's `permissions` catalog, one
`String` constant per `permission_key` row. An integration test
(`PermissionCatalogSyncTest`) asserts an **exact bidirectional match**
between the constants and the `permissions` table, so the catalog
cannot drift from the code in either direction.

### 3. Architecture rules (`AuthorizationPolicyArchTest`, plain ArchUnit, no Spring context)

Production classes are imported with
`ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`.

- **Rule 1 (the F-23 rule)**: every controller handler method (any
  method meta-annotated with Spring's `@RequestMapping` family, in
  `com.workin.backend..`) must carry exactly one of the three policy
  annotations. Handler methods are the complete externally-reachable
  surface today (controllers delegate 1:1 to services); when the
  formal application-service layer arrives with the first business
  module, an additional service-layer rule extends this downward —
  tracked in the matrix note this slice adds, not silently assumed.
- **Rule 2 (placement)**: the three policy annotations may appear
  *only* on controller handler methods, preventing semantic drift
  (decorating arbitrary methods that nothing enforces). Revisited when
  the application-service layer takes over as the declaration point.
- **Rule 3 (tripwire)**: `@RequiresPermission` must not be used
  anywhere yet. The rule's violation message says exactly why and
  names the removal condition: delete this rule in the same PR that
  lands the runtime permission-evaluation component (F-15/F-17), which
  must itself wire `@RequiresPermission` to real enforcement.

### 4. Proven-to-fail evidence (matrix closure criterion)

F-23's closure evidence requires the test be "proven to fail on an
undeclared use case." This lives permanently in the suite, not as a
one-time manual check: test-fixture controllers (an undeclared handler,
a doubly-declared handler, a `@RequiresPermission` usage, a policy
annotation on a non-handler method) sit in a test-only fixtures
package; dedicated tests import those fixtures explicitly and assert
each rule reports the expected violation. The production rules never
see the fixtures (`DO_NOT_INCLUDE_TESTS`).

### 5. Annotating the existing surface

- `AuthController`: register, login, refresh, logout → `@PublicUseCase`
  (reasons: self-registration entry point; credential presentation;
  refresh-token possession is the credential; idempotent revocation).
- `PlatformAdminAuthController`: login, refresh, logout →
  `@PublicUseCase` (same reasoning, platform domain).
- `TenantController.me` → `@AuthenticatedUseCase` (membership-context
  establishment is the operation that *builds* authorization context;
  it has no permission of its own).
- `PlatformAdminController.me` → `@AuthenticatedUseCase`.

No behavior changes anywhere — annotations plus tests only.

## Out Of Scope (Tracked, Not Dropped)

- Runtime permission evaluation and Spring method-security wiring —
  F-15/F-17, unblocked by this slice's convention.
- The service-layer variant of Rule 1 — arrives with the first formal
  application-service layer (first business module).
- A statically-checkable "every `platform.*` business endpoint writes
  an audit event" rule (F-26's standing criterion) — needs the
  service-layer convention to be checkable without false confidence;
  deferred with it.

## Testing

1. `AuthorizationPolicyArchTest` (fast, no containers): Rules 1–3 pass
   against production classes; fixture-based tests prove each rule
   fails on its violation shape.
2. `PermissionCatalogSyncTest` (integration): `PermissionKeys`
   constants ↔ `permissions` table, exact match both directions.
3. Full existing suite unchanged and green.

## Consequences

Every future endpoint PR must declare its policy or the build fails —
`hr-legacy#8`'s per-endpoint opt-in failure mode becomes structurally
impossible to reintroduce, which is F-23's entire point. The first
business module cannot ship a permission-gated endpoint without also
building real enforcement, because Rule 3 blocks the annotation until
then.
