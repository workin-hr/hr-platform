# Main Branch Review — 2026-08-04

Scope reviewed:

- `hr-platform` `main` branch only
- commit range:
  `26b1ffaefbe3095a16ea9a48545be5eebc54cc25..main`
- time window reviewed:
  Monday, August 3, 2026 through Tuesday, August 4, 2026

Out of scope:

- current feature branches outside `main`
- current uncommitted working tree outside what is already merged to `main`
- sibling `repo-template` and `.github` repositories, which had no git changes
  in the same time window

## Summary

`main` changed heavily in this window:

- governance enforcement scripts and tests
- nightly workflow scaffold
- agent/skills governance docs
- operations template scaffolding
- discovery template scaffolding
- first real legacy/API/database discovery findings

The most important concerns on `main` are:

1. secret-pattern deny rules are incomplete and still allow `Write`
2. the git guard over-blocks unrelated commands that merely mention `git push`
3. the `git commit` branch guard can inspect the wrong repository
4. the new execution checklist contradicts the repo's accepted branch-protection
   deferral and discovery authorization decisions
5. one risk-register entry still contradicts the merge evidence already
   recorded on `main`

## Findings

### 1. High — secret-pattern deny rules still allow `Write` and `NotebookEdit`

Main snapshot references:

- `.claude/settings.json:12-36`
- `.claude/settings.json:56-58`
- `scripts/validate_phase0.py:253-276`

Why this matters:

- The deny list protects `Read(...)` and `Edit(...)` for secret-shaped files.
- The same settings file treats `Write|NotebookEdit` as separate tool types.
- The validator only enforces `Read` and `Edit`, so CI accepts the incomplete
  policy.

Impact:

- An agent can still create or overwrite files matching `*.key`, `id_rsa*`,
  `*secret*`, `*.p12`, `*.jks`, and similar sensitive patterns.

### 2. High — `git_guard.py` blocks unrelated commands that only mention dangerous git text

Main snapshot references:

- `scripts/git_guard.py:414-421`
- `scripts/git_guard.py:456-460`

Why this matters:

- The raw-text fallback regex runs after structural parsing and blocks any
  command containing text that looks like `git ... push|merge|rebase|...`,
  even if no git command will be executed.

Reproduced during review:

- `echo "git push"` -> `block`
- `python3 -c "print('git push')"` -> `block`
- `printf 'git push\n'` -> `block`

Impact:

- Safe non-git commands can be denied just for printing or mentioning dangerous
  git text.
- This breaks the repository's own stated requirement that unrelated commands
  must not be affected by the guard.

### 3. Medium — branch-aware `git commit` protection can inspect the wrong repo

Main snapshot references:

- `scripts/git_guard.py:275-303`
- `scripts/git_guard.py:395-411`

Why this matters:

- The parser correctly handles `git -C <repo> ...`.
- The branch check later uses `get_current_branch()`, which runs
  `git branch --show-current` in the hook process cwd, not the repo targeted by
  `-C`.

Impact:

- `git -C <other-repo> commit ...` can be classified using the current
  workspace branch instead of the target repository branch.
- A commit aimed at another checkout on `main` can slip through if the hook cwd
  is on a feature branch.

### 4. Medium — two validators say “tracked” but actually scan raw filesystem content

Main snapshot references:

- `scripts/validate_phase0.py:434-461`
- `scripts/validate_phase0.py:483-510`

Why this matters:

- `validate_codeowners_component_coverage()` uses `rglob("*")` over component
  directories.
- `validate_dependabot_ecosystem_coverage()` uses `rglob(manifest_name)`.
- Neither function filters to tracked git files even though the docstrings
  describe tracked-file semantics.

Impact:

- Untracked scratch files can cause local validation failures that do not
  reflect the repository state actually under review.

### 5. Medium — the `verify-bootstrap.sh` summary tests are not portable to this real environment

Main snapshot references:

- `scripts/test_validate_phase0.py:61`
- `scripts/test_validate_phase0.py:64-103`
- `scripts/test_validate_phase0.py:118-140`

Why this matters:

- The helper hard-resets `PATH` to:
  `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`
- In this environment, `git` is at `/snap/codex/34/usr/bin/git`, outside that
  list.

Impact:

- The helper can fail before reaching the summary assertions it claims to test.
- The tests do not reliably prove the “3 skipped” / “all 6 ran” summary
  behavior on nonstandard but real installations.

### 6. High — the new execution checklist’s H1 item is impossible to complete as written on `main`

Main snapshot references:

- `docs/bootstrap/execution-checklist.md:33-37`
- `docs/bootstrap/execution-checklist.md:49-54`
- `docs/bootstrap/manual-setup-checklist.md:106-127`
- `docs/bootstrap/decision-log.md:166-177`

Why this matters:

- H1 says to apply branch protection/rulesets and marks “`main` is protected”
  as part of completion.
- The canonical governance docs on `main` explicitly record branch protection as
  Deferred under the accepted GitHub Free/private-repo limitation.

Impact:

- The top-priority execution item on `main` requires a condition the same branch
  says is intentionally unavailable.

### 7. Medium — the execution checklist reintroduces H2 as a discovery blocker, contradicting D-015

Main snapshot references:

- `docs/bootstrap/execution-checklist.md:56-77`
- `docs/bootstrap/execution-checklist.md:166-175`
- `docs/bootstrap/decision-log.md:192-203`

Why this matters:

- The checklist says H2 should be resolved before substantial discovery starts.
- D-015 on `main` explicitly authorizes Discovery even though H2 remains
  unresolved.

Impact:

- `main` now contains two conflicting instructions about whether unresolved
  ownership/open-question items block discovery work.

### 8. Low — A2 in the execution checklist is already stale on `main`

Main snapshot references:

- `docs/bootstrap/execution-checklist.md:127-141`

Why this matters:

- A2 says to replace the “stub structure” in `release-readiness.md`.
- That same PR series already populated `docs/operations/release-readiness.md`
  on `main`.

Impact:

- The checklist describes completed work as pending work.

### 9. Medium — R-008 still says the human-approval flow never ran, despite D-014 on `main`

Main snapshot references:

- `docs/bootstrap/risk-register.md:136-151`
- `docs/bootstrap/decision-log.md:179-190`

Why this matters:

- R-008’s description and evidence still say the workflow “has never actually
  run” and “no merge commits exist.”
- D-014 records PR #1, a human merge into `main`, and post-merge validation.

Impact:

- The risk register contradicts the accepted evidence record on the same
  branch.

### 10. Low — one dependency reference in test-layer activation is inconsistent on `main`

Main snapshot references:

- `docs/testing/test-layer-activation.md:68`

Why this matters:

- The DAST row uses `environment-and-deployment-strategy.md` as a bare filename
  while nearby rows use explicit repository paths.

Impact:

- It is weaker and easier to misread than the surrounding dependency
  references.

## What Was Reviewed

Main changes in this window included:

- CI / governance / hook changes:
  `scripts/git_guard.py`, `scripts/validate_phase0.py`,
  `scripts/test_validate_phase0.py`, `scripts/test_git_guard.py`,
  `scripts/edit_audit_log.py`, `scripts/check-branch-protection.sh`,
  `scripts/codex-preflight.sh`, `.github/workflows/nightly.yml`,
  `.claude/settings.json`
- bootstrap/governance changes:
  `docs/bootstrap/decision-log.md`,
  `docs/bootstrap/manual-setup-checklist.md`,
  `docs/bootstrap/risk-register.md`,
  `docs/bootstrap/execution-checklist.md`,
  `docs/agents/operating-model.md`,
  `docs/agents/skill-catalog.md`
- discovery and operations documentation:
  `docs/api/`, `docs/legacy/`, `docs/migration/`, `docs/operations/`,
  `docs/product/`, `docs/testing/`

## Non-findings

- `repo-template` had no git changes in the same review window.
- `.github` had no git changes in the same review window.
