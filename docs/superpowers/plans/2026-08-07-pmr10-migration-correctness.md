# PMR-10 Migration-Correctness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the migration-correctness test plan and the reconciliation harness per `docs/superpowers/specs/2026-08-07-pmr10-migration-correctness-design.md`.

**Architecture:** Stdlib-only Python diffing canonical CSV exports (counts vs recorded baseline, checksums, key-based row diffs), self-tested like the repo's other tools; a plan document defining the export convention, golden-dataset format, and cutover run procedure.

## Global Constraints

- No external Python dependencies; `--self-test` must prove every detection class.
- `\N` means NULL and is distinct from the empty string everywhere.
- Non-zero exit on any mismatch; every mismatch names table, key, and column.
- No production data anywhere in this repo — synthetic samples only.

---

### Task 1: `scripts/migration_diff.py` (self-tested)

- [ ] CLI: `migration_diff.py --source <dir> --target <dir> --manifest <manifest.json>`; manifest lists per-table `{file, key: [cols], expected_count (optional)}`. Checks per table: file presence both sides; header equality; row count (and vs `expected_count` when given); duplicate-key detection; missing/extra keys; per-column cell diffs (first N named, total counted); whole-table SHA-256 over canonicalized rows. Summary block + exit 1 on any finding.
- [ ] `--self-test`: temp-dir synthetic samples — identical pass; count mismatch; changed cell (named); missing key; extra key; duplicate key; `\N` vs empty distinguished; checksum stable across two runs.
- [ ] Run both modes locally; commit.

### Task 2: CI wiring

- [ ] Add the self-test to `backend-validate.yml`'s pre-Java step (alongside the Flyway check). Commit.

### Task 3: `docs/migration/migration-correctness-test-plan.md`

- [ ] Sections: check layers (counts/checksums/row-diffs/business invariants incl. §7 authorization-migration reconciliation); canonical export convention; manifest format + example; golden-dataset format and legacy-capture procedure (payroll first, `hr-legacy#12` daily-wage case mandatory); cutover run procedure and how each run populates `migration-validation-queries.md`; explicitly-remaining items. Lint. Commit.

### Task 4: Status updates

- [ ] `migration-validation-queries.md` status: harness exists (link), template unchanged, runs still pending a migrated target.
- [ ] Matrix PMR-10/`hr-platform#16` row (Section B): mechanism exists + proven on synthetic; remaining data-bound actions listed. Lint; commit; PR via gh after human push.
