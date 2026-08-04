# Change Review — 2026-08-04

Scope reviewed:

- `hr-platform` changes from commit `26b1ffaefbe3095a16ea9a48545be5eebc54cc25..HEAD`
- current uncommitted working-tree changes in `hr-platform`
- sibling `repo-template` and `.github` checkouts had no git changes in the
  same 2026-08-03 -> 2026-08-04 window

Commands used during review included:

- `git log --since='2026-08-03 00:00' --stat --oneline`
- `git diff --stat 26b1ffa..HEAD`
- `python3 scripts/validate_phase0.py`
- `bash scripts/verify-bootstrap.sh`
- targeted reproductions against `scripts/git_guard.py` and
  `scripts/test_validate_phase0.py`

## Findings

### 1. High — secret-pattern denies still allow `Write`/`NotebookEdit`

Files:

- `.claude/settings.json:12`
- `.claude/settings.json:56`
- `scripts/validate_phase0.py:253`

Why this matters:

- The new secret-filename deny rules cover `Read(...)` and `Edit(...)`, but
  there are no matching `Write(...)` or `NotebookEdit(...)` rules.
- The same config explicitly distinguishes `Write|NotebookEdit` as separate
  tool types in `hooks.PostToolUse`.
- `validate_claude_settings()` encodes the same incomplete assumption by only
  requiring `Read` and `Edit`, so CI reports the policy as valid while
  write-path access remains open.

Impact:

- An agent can still create or overwrite secret-shaped files such as
  `*.key`, `id_rsa*`, `*secret*`, `*.p12`, `*.jks`, etc.

### 2. High — `git_guard.py` blocks unrelated commands that merely mention dangerous git text

Files:

- `scripts/git_guard.py:414`
- `scripts/git_guard.py:456`

Why this matters:

- The `_OBFUSCATION_RE` fallback runs across the raw command text after normal
  parsing and blocks any match for `git ... push|merge|rebase|...`, even when
  no git command is actually being executed.

Reproduced locally:

- `evaluate_command('echo "git push"') -> block`
- `evaluate_command('python3 -c "print('"'\"'git push'\"'"')"' ) -> block`
- `evaluate_command("printf 'git push\\n'") -> block`

Impact:

- Safe, unrelated Bash commands can be denied just for printing or mentioning
  dangerous git text.
- This contradicts the stated goal that unrelated non-git commands "must not
  be touched by this guard."

### 3. Medium — branch-aware `git commit` protection checks the hook cwd, not the repo targeted by `git -C`

Files:

- `scripts/git_guard.py:275`
- `scripts/git_guard.py:492`
- `scripts/test_git_guard.py:108`

Why this matters:

- `find_git_subcommand()` correctly understands `git -C <repo> ...`, but the
  branch-protection rule later calls `git branch --show-current` in the hook
  process's own cwd.
- There is no test coverage for `git -C <repo> commit ...`.

Reproduced locally:

- `evaluate_command('git -C /tmp/repo commit -m test', branch_getter=lambda: 'feature-x') -> allow`

Impact:

- A commit targeted at another checkout or worktree can be classified using
  the wrong branch.
- In particular, a target repo on `main` can slip through if the hook cwd is
  on a feature branch.

### 4. Medium — two "tracked-file" validators actually scan any filesystem file

Files:

- `scripts/validate_phase0.py:434`
- `scripts/validate_phase0.py:483`

Why this matters:

- `validate_codeowners_component_coverage()` says it should activate once a
  component contains a non-README tracked file, but it uses
  `component_dir.rglob("*")` over the raw filesystem.
- `validate_dependabot_ecosystem_coverage()` does the same for manifests via
  `root.rglob(manifest_name)`.

Impact:

- Untracked local scratch files can cause local validation failures that do
  not reflect the repository state under review.
- The implementation does not match the contract described in the docstrings.

### 5. Medium — `verify-bootstrap.sh` summary tests do not reach the behavior they claim to test on this machine

Files:

- `scripts/test_validate_phase0.py:57`
- `scripts/test_validate_phase0.py:79`
- `scripts/test_validate_phase0.py:118`

Why this matters:

- The helper replaces `PATH` with `SYSTEM_PATH_DIRS=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`.
- In this environment, `git` is at `/snap/codex/34/usr/bin/git`, outside that
  hardcoded list.

Reproduced locally:

- `run_verify_bootstrap_with_shims(("markdownlint-cli2", "yamllint", "shellcheck"))`
  exited `1` before the summary assertions, because nested regression checks
  hit `get_current_branch() -> None`.

Impact:

- The tests can pass or fail for PATH-layout reasons unrelated to the
  skip-summary behavior they are supposed to validate.
- On nonstandard but real installations, they do not prove the advertised
  summary behavior.

### 6. High — the new execution checklist's top-priority H1 item is impossible to complete as written

Files:

- `docs/bootstrap/execution-checklist.md:33`
- `docs/bootstrap/execution-checklist.md:51`
- `docs/bootstrap/manual-setup-checklist.md:50`
- `docs/bootstrap/decision-log.md:166`

Why this matters:

- H1 says the human owner should "Apply branch protection, rulesets" and marks
  "`main` is protected" as part of `Done when`.
- D-013 and the manual setup checklist explicitly say branch protection and
  rulesets are Deferred under the accepted GitHub Free/private-repo plan
  limitation.

Impact:

- The repo's new top-priority execution checklist reintroduces an impossible
  completion condition the canonical governance docs already removed.

### 7. Medium — the new execution checklist contradicts D-015 by reintroducing H2 as a discovery blocker

Files:

- `docs/bootstrap/execution-checklist.md:56`
- `docs/bootstrap/execution-checklist.md:168`
- `docs/bootstrap/decision-log.md:192`

Why this matters:

- H2 says open questions and ownership should be resolved before substantial
  discovery starts.
- D-015 explicitly authorizes Discovery while H2 remains unresolved.

Impact:

- Readers now have two conflicting in-repo instructions about whether open
  ownership/governance questions block discovery work.

### 8. Low — the execution checklist still describes completed operations work as a pending A2 task

Files:

- `docs/bootstrap/execution-checklist.md:127`
- `docs/operations/release-readiness.md:19`

Why this matters:

- A2 still says to replace the "stub structure" in `release-readiness.md`.
- That document is no longer a stub in the current working tree.

Impact:

- The checklist is now stale and can mislead future work into reopening a
  completed artifact.

### 9. Medium — R-008 still claims the human-approval flow never ran, despite D-014 recording the opposite

Files:

- `docs/bootstrap/risk-register.md:140`
- `docs/bootstrap/risk-register.md:151`
- `docs/bootstrap/decision-log.md:179`

Why this matters:

- R-008's description and evidence still say the workflow "has never actually
  run" and "no merge commits exist."
- D-014 records PR #1, a human merge into `main`, and post-merge validation.

Impact:

- The risk register now contradicts the canonical evidence record and its own
  updated non-blocking status text.

### 10. Medium — migration strategy overstates endpoint-level certainty

Files:

- `docs/migration/migration-strategy-and-sequencing.md:5`
- `docs/api/existing-endpoint-inventory.md:3`

Why this matters:

- The migration strategy says it synthesizes Discovery evidence across "all 199
  API endpoints."
- The endpoint inventory explicitly says this pass documented only 19 endpoints
  individually and covered the remaining 180 structurally in module inventory.

Impact:

- Downstream migration planning is given a stronger sense of endpoint-level
  coverage than the cited source supports.

### 11. Low — one dependency reference in test-layer activation is still inconsistent

Files:

- `docs/testing/test-layer-activation.md:68`
- `docs/operations/environment-and-deployment-strategy.md:1`

Why this matters:

- The DAST row's `Depends On` value is `environment-and-deployment-strategy.md`
  rather than the full repository path used elsewhere in the same document.

Impact:

- The table is easy to misread and points less precisely than adjacent rows.

## Non-findings

- `repo-template` had no git changes in the review window.
- `.github` had no git changes in the review window.
- `python3 scripts/validate_phase0.py` and `bash scripts/verify-bootstrap.sh`
  both still pass against the current repository state despite the concerns
  above.
