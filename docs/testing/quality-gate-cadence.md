# Quality Gate Cadence

## Goal

Define which validation layers are cheap enough for continuous use and which should be scheduled or release-gated.

## Rules

- expensive load, stress, and soak tests do not run on every commit
- migration and compatibility tests expand as implementation appears
- human approval remains mandatory even when automated checks pass
- the backend suite (`.github/workflows/backend-validate.yml`) runs once per
  branch head: a push cancels the run of the head it supersedes, except on
  `main`, where every merged commit keeps its own record
- the backend `test` task is never restored from Gradle's build cache
  (`outputs.cacheIf { false }` in `backend/build.gradle`): the suite starts a
  MariaDB container that the cache cannot see, so a cached pass would be a
  pass that never started the database. Compilation is cached; the tests run
- a backend test that reads a repository file outside `backend/` (the e2e
  README and Playwright config, the legacy dashboard page manifest, the
  Phase 1 provisioning runbook) has that file declared as an input of the
  `test` task and listed in both trigger lists of `backend-validate.yml`.
  Without the first, an edit to that file alone leaves the task `UP-TO-DATE`
  locally; without the second, a pull request touching only that file never
  starts the workflow. Either way the test that guards the file does not run.
  `BackendTestRepositoryInputsTest` fails when a test reads such a path that
  either list misses
