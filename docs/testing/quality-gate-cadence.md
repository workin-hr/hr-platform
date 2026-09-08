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
