# Spec Kit Install Plan

Status: **Executed.** `specify-cli` was already present in the Phase 0
remediation environment (v0.8.15); no `uv tool install` step was required.

## Commands actually executed

```bash
specify --version
specify init --here --integration claude --no-git --force
specify integration install codex --force
```

This deviates from the originally planned `specify init . --integration claude`
in two ways, both recorded here for traceability: `--here` was used instead of
a positional project name (equivalent behavior — initializes in the current
directory), and `--no-git --force` were added because this repository already
has Git history and a non-empty working tree that Spec Kit needed permission
to initialize into. See `docs/bootstrap/audit-remediation.md` (P1-3) for the
verification performed before running with `--force`, and `README.md` in this
directory for exactly what was installed.

Authorization: this installation was carried out as an explicit, written
remediation instruction from the repository's human owner (Phase 0 audit
remediation request), not run silently or speculatively.

## Not yet executed

`specify integration list` / `specify integration status` were run manually
during verification (see audit-remediation.md) but are not part of the
persisted install; they can be re-run at any time to inspect current state.
No `/speckit-*` workflow command has been invoked — see `README.md`.
