# Change Review — 2026-08-05

## Scope

- Reviewed repository: `hr-platform`
- Branch at review time: `docs/pre-migration-readiness-gap-analysis`
- Reviewed change window: from Tuesday, August 4, 2026 through Wednesday, August 5, 2026
- Reviewed git range: `c24add5f271d04356e16e11e96f60d79b36bd9eb..HEAD`
- Sibling repositories checked for the same window: `repo-template`, `.github`
- Result for sibling repositories: no git changes in that window

## What Was Reviewed

- Git history and diff for all tracked changes in the window
- Backend implementation added under `backend/`
- Validation and guard changes under `scripts/` and `.claude/`
- Documentation, ADR, migration-planning, and security-document changes under `docs/`

## Verification Performed

- `python3 scripts/validate_phase0.py`
  - Result: failed
- `python3 scripts/test_git_guard.py`
  - Result: passed (`80/80`)
- `python3 scripts/test_validate_phase0.py`
  - Result: failed (`49/51`)
- `./gradlew test` in `backend/`
  - Result: could not execute in this environment because the Gradle wrapper tried to exec `/home/afaqy/.sdkman/candidates/java/current/bin/java`, and this machine does not currently have a usable JDK configured there

## Findings

### 1. High — the new Flutter submodule strategy breaks the repository's own validation path once submodule content is initialized

The repository now records Flutter as pinned submodules in `.gitmodules`, and the security/docs narrative explicitly says this is a stronger safeguard that still keeps the repo boundary intact for local opt-in use:

- `.gitmodules:1-6`
- `docs/adr/ADR-0001-repository-strategy.md:67-85`
- `docs/security/pre-migration-flutter-credential-inventory.md:19-45`

But the forbidden-file scanner in `scripts/validate_phase0.py` still recursively walks all top-level directories except `spike/` and the newly unlocked `backend/`:

- `scripts/validate_phase0.py:174-186`
- `scripts/validate_phase0.py:216-234`

With the submodule content present locally, that scanner now flags the Flutter repositories' real Android/Gradle/Kotlin files as forbidden product code. This is not theoretical; it fails on the current tree:

- `python3 scripts/validate_phase0.py` fails on `flutter-integration/workin_desktop/android/...` and `flutter-integration/workin_mobile/android/...`
- because `verify-bootstrap.sh` starts with `validate_phase0.py`, `scripts/test_validate_phase0.py`'s verify-bootstrap summary tests also fail before reaching the summary they intend to assert

Impact:

- the repo's own bootstrap validation path is red on a locally initialized, intentionally supported submodule checkout
- the new submodule mechanism and the validator now disagree about whether this checkout shape is valid

This needs a deliberate policy choice: either initialized submodule content under `flutter-integration/` is allowed and the validator must exclude it, or the docs must stop implying that local opt-in submodule checkout is a supported state for running repository validation.

### 2. High — the backend will start with a known JWT signing key if `JWT_SECRET` is not explicitly set

The backend configuration currently falls back to a fixed literal signing key:

- `backend/src/main/resources/application.properties:8-13`

That comment labels the value as dev-only, but the application itself does not fail closed when it is left in place. There is no startup guard that rejects the placeholder secret, and no test proving such a guard exists.

Impact:

- any shared environment that forgets to inject `JWT_SECRET` will issue and accept tokens signed with a public, committed default
- once that happens, token forgery is trivial and the entire auth boundary collapses

This is a real security concern because the code path is active by default, not an inert example file.

### 3. High — JWT validation is incomplete, and a correctly signed token missing required claims can crash authenticated requests

`JwtService` issues tokens with `iss`, `aud`, `membership_id`, and `tenant_id`, but its validation path only requires the issuer:

- `backend/src/main/java/com/workin/backend/identity/JwtService.java:48-70`

`JwtAuthenticationFilter` then assumes `membership_id` and `tenant_id` are present and numeric:

- `backend/src/main/java/com/workin/backend/security/JwtAuthenticationFilter.java:39-50`

The filter calls `.longValue()` on both claims without null checks and only catches `JwtException` and `IllegalArgumentException`. A correctly signed token that omits one of those claims can therefore raise a `NullPointerException`, which is not caught here and would surface as a 500 instead of a clean auth failure.

Test coverage in the reviewed range does not exercise this malformed-but-signed-token path:

- `backend/src/test/java/com/workin/backend/identity/AuthFlowTest.java:18-79`
- `backend/src/test/java/com/workin/backend/tenancy/TenantContextIsolationTest.java:35-69`

This should fail closed at the authentication boundary, not crash inside request handling.

### 4. High — the top-level README is no longer accurate after the 2026-08-05 backend unlock

The first document a contributor sees still describes `hr-platform` as a pure Phase 0 bootstrap repo and still says Phase 0 forbids Spring Boot code, SQL migrations, and business-domain implementation:

- `README.md:3`
- `README.md:17-37`

That now conflicts directly with the accepted Phase transition:

- `docs/bootstrap/decision-log.md:369-380`
- `backend/README.md:1-10`

The repo is no longer only a Phase 0 bootstrap repository. `backend/` is now intentionally in Phase 1, with real Spring Boot, SQL migration, and business-domain code already landed. Leaving the top-level README in the old state is misleading and will cause contributors to follow the wrong boundary.

### 5. High — the migration-readiness gate and D-028 disagree on whether backend implementation was allowed to start

The gap-analysis doc still says PMR-09 is required before the first module's implementation work begins:

- `docs/migration/pre-migration-readiness-gap-analysis.md:493-500`
- `docs/migration/pre-migration-readiness-gap-analysis.md:615-620`

It also still marks PMR-09 blocked:

- `docs/migration/pre-migration-readiness-gap-analysis.md:496-500`
- `docs/migration/pre-migration-readiness-gap-analysis.md:558`

But D-028 says a readiness report confirmed every migration-readiness gate condition needed for starting core backend development, and then explicitly starts the first real backend module:

- `docs/bootstrap/decision-log.md:377-378`

Those two positions do not match. Either:

- PMR-09 was genuinely no longer a precondition for starting the first backend module and the gate document is stale, or
- the backend started before its own declared gate was satisfied

This is a governance/process inconsistency, not just cosmetic prose drift.

### 6. Medium — the first backend slice hardcodes a shared runtime database password in both source code and migration SQL

The runtime datasource is wired to a fixed username/password in application code:

- `backend/src/main/java/com/workin/backend/config/RlsDataSourceConfig.java:45-54`

The schema migration creates the role with that same literal password:

- `backend/src/main/resources/db/migration/rls/V6__create_non_superuser_app_role.sql:7-12`

This means the backend currently relies on a committed, shared database credential (`app_runtime_password`) rather than an environment-specific secret or operator-provided value. That creates two real problems:

- the credential is identical across every environment bootstrapped from this code unless humans edit the migration or runtime config out of band
- rotating it cleanly is awkward because the credential is embedded both in code and in migration history

This is materially different from the clearly labeled `JWT_SECRET` placeholder in `backend/src/main/resources/application.properties:12`, which at least goes through environment substitution. The runtime DB credential currently does not.

### 7. Medium — multi-tenant login is nondeterministic once an identity has more than one active membership

The login flow currently authenticates the identity, loads all active memberships, and then blindly chooses the first one:

- `backend/src/main/java/com/workin/backend/identity/LoginService.java:37-49`

The membership lookup query does not define an `ORDER BY`:

- `backend/src/main/java/com/workin/backend/tenancy/IdentityMembershipIndexService.java:42-46`

Today that may be masked by self-registration creating a single membership, but the architecture docs explicitly say an identity may belong to multiple tenants. As soon as that becomes true in live data, login will issue a token for whichever row the database happens to return first rather than an explicitly selected tenant.

There is no test covering the multi-membership case.

### 8. Medium — the detailed authorization architecture document still claims no implementation exists

The architecture reference still says:

- `docs/architecture/authorization-model.md:10-14`

That statement is no longer true. The repo now contains:

- real backend code under `backend/`
- real schema migrations for companies, identities, memberships, permissions, RLS, and the runtime role
- real tests referenced as completed work in `docs/migration/consolidated-task-matrix.md:137-146`

At this point the document should distinguish between:

- the architecture being authoritative
- the implementation being partial/incomplete

It should not still say no implementation exists at all.

### 9. Medium — the consolidated task matrix has drifted away from its stated source-of-truth analysis

The PMR section in the consolidated matrix now disagrees with the fuller gap-analysis document it claims to summarize.

Examples:

- PMR-02 is presented as effectively closed in `docs/migration/consolidated-task-matrix.md:107`, while the source analysis still says it is improved but "not fully closed" and `Ready`, not complete:
  - `docs/migration/pre-migration-readiness-gap-analysis.md:80-132`
- PMR-03 still reads like an inaccessible-data blocker in `docs/migration/consolidated-task-matrix.md:108`, while the source analysis says the substantial work is already done and only fresh-snapshot re-verification remains:
  - `docs/migration/pre-migration-readiness-gap-analysis.md:151-193`

The same file's summary counts are also unsupported by the table above them:

- `docs/migration/consolidated-task-matrix.md:152-168`

The table now materially differs from those reported totals, so the "informational" summary is not reliable enough to use in planning discussions.

### 10. Medium — the new `verify-bootstrap` summary assertions are already red in the current repository state

The newly added summary checks assume `verify-bootstrap.sh` will reach its final summary block:

- `scripts/test_validate_phase0.py:206-228`

But `verify-bootstrap.sh` always starts by running `validate_phase0.py`:

- `scripts/verify-bootstrap.sh:28-35`

Because repository-wide validation currently fails earlier on the initialized Flutter submodule content, `verify-bootstrap.sh` exits before its step `[3/3]` summary section. That leaves the new summary tests red right now:

- `python3 scripts/test_validate_phase0.py` currently reports `49/51`

This is a separate regression symptom from Finding 1 rather than a different root cause, but it matters because one of the repository's own always-required regression suites is already failing in the reviewed tree.

## Overall Assessment

The backend implementation and the supporting documentation show real progress, but the reviewed range has several concrete technical defects and several governance/documentation inconsistencies:

- one defect blocks the repo's own validation workflow on a supported local checkout shape
- one defect leaves a known JWT signing key active unless humans override it correctly
- one defect lets malformed but signed tokens crash requests instead of failing closed
- one defect introduces a committed shared runtime database credential
- multiple top-level planning documents still describe a pre-Phase-1 state even though Phase 1 backend work has already begun

## Recommended Next Actions

1. Decide and document the intended validation policy for initialized Flutter submodules, then make `scripts/validate_phase0.py` match it.
2. Add a startup-time guard that refuses to run with the placeholder `JWT_SECRET`, and test it.
3. Make JWT parsing require the claims the filter depends on, and turn malformed-but-signed tokens into clean auth failures rather than 500s.
4. Replace the hardcoded `app_runtime_password` pattern with environment-provided or operator-provisioned credentials.
5. Update `README.md` so the repository boundary reflects the accepted `backend/` unlock.
6. Reconcile `pre-migration-readiness-gap-analysis.md` with D-028 so the gate logic and actual transition decision match.
7. Refresh `docs/architecture/authorization-model.md` and `docs/migration/consolidated-task-matrix.md` to reflect the current implementation state accurately.
