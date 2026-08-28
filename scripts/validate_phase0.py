#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

REQUIRED_FILES = [
    "README.md",
    "AGENTS.md",
    "CLAUDE.md",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "CODEOWNERS",
    "LICENSE",
    ".github/pull_request_template.md",
    ".github/dependabot.yml",
    ".github/labels.yml",
    ".github/workflows/phase0-validate.yml",
    ".yamllint.yml",
    ".markdownlint-cli2.jsonc",
    "lychee.toml",
    ".codex/config.toml",
    ".claude/settings.json",
    ".specify/memory/constitution.md",
    ".specify/workflow.md",
    ".specify/README.md",
    ".specify/templates/constitution-template.md",
    ".specify/templates/spec-template.md",
    ".specify/templates/plan-template.md",
    ".specify/templates/tasks-template.md",
    "scripts/verify-bootstrap.sh",
    "docs/bootstrap/project-charter.md",
    "docs/bootstrap/bootstrap-plan.md",
    "docs/bootstrap/definition-of-done.md",
    "docs/bootstrap/open-questions.md",
    "docs/bootstrap/risk-register.md",
    "docs/bootstrap/decision-log.md",
    "docs/bootstrap/manual-setup-checklist.md",
    "docs/bootstrap/initial-backlog.md",
    "docs/architecture/architecture-principles.md",
    "docs/architecture/quality-attributes.md",
    "docs/architecture/system-context.md",
    "docs/architecture/container-view.md",
    "docs/architecture/module-boundaries.md",
    "docs/architecture/integration-principles.md",
    "docs/architecture/data-principles.md",
    "docs/architecture/architecture-review-checklist.md",
    "docs/agents/operating-model.md",
    "docs/agents/responsibility-matrix.md",
    "docs/agents/skill-catalog.md",
    "docs/testing/test-strategy.md",
    "docs/testing/quality-gate-cadence.md",
    "docs/tools/tool-catalog.md",
    "docs/tools/tool-decision-matrix.md",
    "docs/tools/local-bootstrap-tools.md",
    "docs/product/README.md",
    "docs/product/discovery-templates.md",
    "docs/product/existing-user-journey-inventory.md",
    "docs/product/non-functional-requirements.md",
    "docs/product/mvp-scope-prioritization.md",
    "docs/product/customer-impact-analysis.md",
]

REQUIRED_DIRS = [
    ".claude/agents",
    ".codex/agents",
    ".agents/skills",
    ".specify",
    ".specify/memory",
    ".specify/templates",
    ".specify/scripts/bash",
    "docs/bootstrap",
    "docs/product",
    "docs/architecture",
    "docs/adr",
    "docs/security",
    "docs/testing",
    "docs/legacy",
    "docs/api",
    "docs/migration",
    "docs/devices",
    "docs/operations",
    "docs/agents",
    "docs/tools",
    "specs",
    "contracts/openapi",
    "contracts/schemas",
    "contracts/examples",
    "evidence",
    "backend",
    "admin-web",
    "edge-gateway",
    "flutter-integration",
    "infrastructure",
]

AGENT_SECTIONS = [
    "## Canonical Instructions",
    "## Role",
    "## Purpose",
    "## Trigger Conditions",
    "## Required Inputs",
    "## Expected Outputs",
    "## Allowed Tools",
    "## Forbidden Tools",
    "## Read/Write Permissions",
    "## Repository Scope",
    "## File Modification",
    "## Pull Request Authority",
    "## Approval Authority",
    "## Escalation Rules",
    "## Completion Criteria",
    "## Evidence Requirements",
]

SKILL_SECTIONS = [
    "## Canonical Instructions",
    "## Description And Trigger",
    "## Inputs",
    "## Preconditions",
    "## Ordered Workflow",
    "## Required Outputs",
    "## Evidence",
    "## Validation Checklist",
    "## Failure Conditions",
    "## Escalation Conditions",
    "## Forbidden Behavior",
]

FORBIDDEN_FILE_NAMES = {
    "pom.xml",
    "package.json",
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
    "Dockerfile",
    "docker-compose.yml",
    "docker-compose.yaml",
    "application.yml",
    "application.yaml",
    "pnpm-workspace.yaml",
}

FORBIDDEN_SUFFIXES = {
    ".java",
    ".kt",
    ".kts",
    ".ts",
    ".tsx",
    ".js",
    ".jsx",
    ".cs",
    ".csproj",
    ".sql",
    ".jar",
}

FORBIDDEN_DIRS = {"src", "node_modules"}

# Deliberate, narrow exclusion — not a general loophole. spike/ is where
# docs/migration/technical-spike-plan.md's H2 experiment (a real Spring
# Boot/Java vertical slice, per that plan's Rollback/Discard Strategy
# fallback) lives while it runs, explicitly outside the governed
# COMPONENT_DIRS boundaries and torn down (not merged as "done") once the
# spike report is written. Only this one top-level directory is excluded
# from the product-code scanner below; every other path in the repository
# is still scanned exactly as before.
SPIKE_DIR_NAME = "spike"

# Deliberate, narrow, per-component exclusion — not a blanket "Phase 0 is
# over" switch. Each entry here is a top-level component directory whose
# own explicit Phase 0 -> Phase 1 transition decision has been recorded in
# docs/bootstrap/decision-log.md; only that directory's forbidden-file
# check is lifted. Every other COMPONENT_DIRS entry not listed here
# remains fully Phase-0-locked and scanned exactly as before.
#
# - "backend": docs/bootstrap/decision-log.md D-028 (2026-08-05) lifts the
#   lock for real Spring Boot/Java 25 backend implementation, following
#   every architecture ADR this depends on reaching Accepted (D-016
#   through D-026) and the Migration-Readiness Gate's minimum conditions
#   being satisfied.
PHASE1_UNLOCKED_DIRS = {"backend"}

# This is a fast, narrow, five-pattern check, not comprehensive secret
# scanning. Gitleaks (run in .github/workflows/phase0-validate.yml with its
# default ruleset) is the authoritative scanner — see
# docs/security/security-boundaries.md "Secret Scanning".
SECRET_PATTERNS = [
    re.compile(r"github_pat_[A-Za-z0-9_]+"),
    re.compile(r"ghp_[A-Za-z0-9]+"),
    re.compile(r"gho_[A-Za-z0-9]+"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
]

LINK_RE = re.compile(r"\[[^\]]+\]\(([^)]+)\)")


def fail(message: str, failures: list[str]) -> None:
    failures.append(message)


def validate_required_paths(failures: list[str]) -> None:
    for rel in REQUIRED_FILES:
        if not (ROOT / rel).is_file():
            fail(f"Missing required file: {rel}", failures)
    for rel in REQUIRED_DIRS:
        if not (ROOT / rel).is_dir():
            fail(f"Missing required directory: {rel}", failures)


def validate_instruction_source_of_truth(failures: list[str], root: Path | None = None) -> None:
    """Keep repository policy canonical in AGENTS.md and make Claude import it.

    Agent/skill section checks below ensure specialized repository-authored
    procedures explicitly inherit this contract rather than becoming silent,
    competing policy sources.
    """
    root = root if root is not None else ROOT
    agents = root / "AGENTS.md"
    claude = root / "CLAUDE.md"
    if not agents.is_file() or not claude.is_file():
        return  # validate_required_paths owns missing-file reporting

    agents_text = agents.read_text(encoding="utf-8")
    if "## Canonical Source Of Truth" not in agents_text:
        fail("AGENTS.md does not declare the Canonical Source Of Truth", failures)
    if "## Mandatory Change Propagation" not in agents_text:
        fail("AGENTS.md does not define Mandatory Change Propagation", failures)

    claude_lines = {line.strip() for line in claude.read_text(encoding="utf-8").splitlines()}
    if "@AGENTS.md" not in claude_lines:
        fail("CLAUDE.md must import the canonical repository instructions with @AGENTS.md", failures)


def load_submodule_paths(root: Path) -> list[tuple[str, ...]]:
    gitmodules = root / ".gitmodules"
    if not gitmodules.is_file():
        return []

    submodule_paths: list[tuple[str, ...]] = []
    for raw_line in gitmodules.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line.startswith("path ="):
            continue
        path_text = line.split("=", 1)[1].strip()
        if not path_text:
            continue
        submodule_paths.append(Path(path_text).parts)
    return submodule_paths


def rel_path_is_under(rel_parts: tuple[str, ...], parent_parts: tuple[str, ...]) -> bool:
    if len(rel_parts) < len(parent_parts):
        return False
    return rel_parts[: len(parent_parts)] == parent_parts


def validate_forbidden_files(failures: list[str], root: Path | None = None) -> None:
    root = root if root is not None else ROOT
    submodule_paths = load_submodule_paths(root)
    for path in root.rglob("*"):
        rel_path = path.relative_to(root)
        if rel_path.parts and rel_path.parts[0] == SPIKE_DIR_NAME:
            continue
        if rel_path.parts and rel_path.parts[0] in PHASE1_UNLOCKED_DIRS:
            continue
        if any(rel_path_is_under(rel_path.parts, submodule_path) for submodule_path in submodule_paths):
            continue
        rel = rel_path.as_posix()
        if path.is_dir() and path.name in FORBIDDEN_DIRS:
            fail(f"Forbidden directory present: {rel}", failures)
        if not path.is_file():
            continue
        if ".git" in path.parts:
            continue
        if path.name in FORBIDDEN_FILE_NAMES:
            fail(f"Forbidden file present: {rel}", failures)
        if path.suffix in FORBIDDEN_SUFFIXES:
            fail(f"Forbidden file suffix present: {rel}", failures)


def validate_agent_files(failures: list[str], root: Path | None = None) -> None:
    root = root if root is not None else ROOT
    for base in (root / ".claude/agents", root / ".codex/agents"):
        for path in sorted(base.glob("*.md")):
            text = path.read_text(encoding="utf-8")
            for section in AGENT_SECTIONS:
                if section not in text:
                    fail(f"Agent file missing section {section}: {path.relative_to(root)}", failures)
            if "## Approval Authority\n\nMay not approve work." not in text:
                fail(f"Agent approval boundary not explicit: {path.relative_to(root)}", failures)
            if "Read-only" in text and "## File Modification\n\nNo." not in text and "## File Modification\n\nNo by default." not in text:
                fail(f"Read-only agent file modification boundary unclear: {path.relative_to(root)}", failures)
            if base.name == "agents" and base.parent.name == ".claude":
                # Claude Code only treats a file under .claude/agents/ as a
                # real, technically tool-scoped subagent if it starts with
                # YAML frontmatter declaring name/description/tools. Without
                # this, the file is inert prose, not an enforced boundary.
                if not text.startswith("---\n"):
                    fail(f"Claude agent file missing YAML frontmatter (not a real subagent): {path.relative_to(root)}", failures)
                else:
                    frontmatter_end = text.find("\n---", 4)
                    frontmatter = text[:frontmatter_end] if frontmatter_end != -1 else text
                    for key in ("name:", "description:", "tools:"):
                        if key not in frontmatter:
                            fail(f"Claude agent frontmatter missing '{key}': {path.relative_to(root)}", failures)


# The independent reviewer AGENTS.md's Mandatory Workflow names (D-121). It is
# an external GitHub App, so there is no `.claude/agents` file to bind its
# permissions to the way validate_agent_matrix_consistency() binds a Claude
# agent's `tools:` frontmatter -- and that function silently skips matrix rows
# with no such file. Without the check below, the reviewer role would live only
# in prose and could be edited out of either document with nothing failing.
INDEPENDENT_REVIEWER = "chatgpt-codex-connector[bot]"

# The executable half of D-121. Named here so the workflow, the branch-protection
# checker and this validator cannot drift into three different opinions about
# which reviewer and which status context the gate uses.
REVIEW_GATE_WORKFLOW = ".github/workflows/independent-review-gate.yml"
REVIEW_GATE_CONTEXT = "independent-review"

# The single line the gate actually runs on. Bound by value, not by presence
# anywhere in the file -- the workflow's comments name the reviewer too.
REVIEW_GATE_REVIEWER_RE = re.compile(r'^\s*REVIEWER:\s*"([^"]*)"\s*$', re.MULTILINE)


# The matrix cell is written `` `chatgpt-codex-connector[bot]` (pull-request
# review) `` -- backticks plus a human annotation. Both are stripped and the
# remainder compared exactly, so a row for a *different* identity that merely
# contains the name (`impersonator-chatgpt-codex-connector[bot]`, or a renamed
# successor) cannot satisfy the check.
REVIEWER_ROW_ANNOTATION_RE = re.compile(r"\s*\([^)]*\)\s*$")

# The same exact-identity rule for prose: the name must not be glued to a
# preceding identifier character, so `impersonator-chatgpt-codex-connector[bot]`
# is a different agent rather than a match. `[bot]` makes a trailing boundary
# unnecessary -- nothing can extend the name to the right.
REVIEWER_IN_PROSE_RE = re.compile(rf"(?<![\w-]){re.escape(INDEPENDENT_REVIEWER)}")


def _names_reviewer(agent_name: str) -> bool:
    stripped = REVIEWER_ROW_ANNOTATION_RE.sub("", agent_name.strip()).strip()
    return stripped.strip("`").strip() == INDEPENDENT_REVIEWER


def _agents_section(text: str, heading: str) -> str | None:
    """The body of one `## ` section, up to the next heading of any level.

    The heading must match a whole line: a plain substring search accepts
    `### Mandatory Workflow` as `## Mandatory Workflow` (the match simply
    starts at the second `#`), which would let a demoted heading silently
    satisfy a check that claims to require the section.

    Returns None when the heading is absent, so a caller can tell "the
    section says the wrong thing" apart from "somebody deleted the section".
    """
    match = re.search(rf"^{re.escape(heading)}\s*$", text, re.MULTILINE)
    if match is None:
        return None
    body_start = match.end()
    # Any following heading ends the section -- including a deeper one, so a
    # subsection cannot be read as part of this section's own body.
    nxt = re.search(r"^#{1,6} ", text[body_start:], re.MULTILINE)
    return text[body_start:] if nxt is None else text[body_start:body_start + nxt.start()]


def validate_independent_reviewer_declaration(failures: list[str], root: Path | None = None) -> None:
    """Bind AGENTS.md's named independent reviewer to its matrix row.

    D-121 makes one named reviewer the authority that discharges the
    independent-review gate. Both halves of that must stay present and must
    stay read-only: AGENTS.md has to name the reviewer inside the workflow it
    gates, and docs/agents/responsibility-matrix.md has to carry a row for the
    same name declaring no file modification, no pull request and no approval.
    Neither can be removed or quietly widened without this failing.
    """
    root = root if root is not None else ROOT
    agents = root / "AGENTS.md"
    matrix_path = root / "docs/agents/responsibility-matrix.md"
    if not agents.is_file() or not matrix_path.is_file():
        return  # already reported by validate_required_paths

    agents_text = agents.read_text(encoding="utf-8")
    workflow = _agents_section(agents_text, "## Mandatory Workflow")
    if workflow is None:
        # Deleting the heading must not be a way to delete the gate. The
        # workflow is AGENTS.md's own mandatory contract; its absence is a
        # failure in itself, not a reason to skip the reviewer check.
        fail(
            "AGENTS.md no longer defines a '## Mandatory Workflow' section — the "
            "Issue -> ... -> Independent review -> Human merge contract every agent "
            "and human is bound by (D-121 depends on it)",
            failures,
        )
    else:
        review_at = workflow.lower().find("independent review")
        merge_at = workflow.lower().find("human merge")
        if review_at == -1:
            fail(
                "AGENTS.md's Mandatory Workflow no longer contains an independent-review "
                "step before human merge (D-121)",
                failures,
            )
        elif merge_at == -1:
            fail(
                "AGENTS.md's Mandatory Workflow no longer ends in a human-merge step, so the "
                "independent review it names gates nothing (D-121)",
                failures,
            )
        elif review_at > merge_at:
            # Presence is not the property that matters: a workflow reading
            # "Human merge -> Independent review" would satisfy a contains
            # check while describing review that cannot gate anything.
            fail(
                "AGENTS.md's Mandatory Workflow places its independent-review step after "
                "human merge; review must precede merge or it gates nothing (D-121)",
                failures,
            )
        # Scoped to the section, not the whole file (a mention elsewhere in
        # AGENTS.md does not staff this gate), and matched as a whole
        # identifier, so a look-alike such as
        # `impersonator-chatgpt-codex-connector[bot]` names a different agent
        # rather than this one.
        if not REVIEWER_IN_PROSE_RE.search(workflow):
            fail(
                f"AGENTS.md's Mandatory Workflow does not name {INDEPENDENT_REVIEWER!r} as "
                f"the reviewer that discharges its independent-review step (D-121); a mention "
                "elsewhere in the file, or of a different identity containing this name, does "
                "not count",
                failures,
            )

    matrix_text = matrix_path.read_text(encoding="utf-8")
    # Every matching row is checked, and more than one is itself a failure: a
    # read-only row followed by a permissive duplicate would otherwise leave a
    # contradictory grant in the matrix with validation green.
    rows = [
        (may_modify, may_pr, may_approve)
        for agent_name, _primary_mode, may_modify, may_pr, may_approve in MATRIX_ROW_RE.findall(matrix_text)
        if _names_reviewer(agent_name)
    ]
    if not rows:
        fail(
            f"docs/agents/responsibility-matrix.md has no row for {INDEPENDENT_REVIEWER!r}, the "
            "independent reviewer AGENTS.md's Mandatory Workflow depends on (D-121)",
            failures,
        )
        return

    if len(rows) > 1:
        fail(
            f"docs/agents/responsibility-matrix.md declares {INDEPENDENT_REVIEWER!r} in "
            f"{len(rows)} rows; exactly one is allowed, so a permissive duplicate cannot hide "
            "behind a read-only row (D-121)",
            failures,
        )

    _validate_review_gate_workflow(root, failures)

    for may_modify, may_pr, may_approve in rows:
        widened = [
            label
            for label, value in (
                ("May Modify Files", may_modify),
                ("May Open PR", may_pr),
                ("May Approve Work", may_approve),
            )
            if value.strip() != "No"
        ]
        if widened:
            fail(
                f"responsibility-matrix.md's {INDEPENDENT_REVIEWER!r} row must declare 'No' for "
                f"every permission (D-121 makes it a read-only reviewer); widened: {', '.join(widened)}",
                failures,
            )


def _validate_review_gate_workflow(root: Path, failures: list[str]) -> None:
    """The mechanical half of D-121: the workflow that publishes the gate.

    `validate_independent_reviewer_declaration()` binds AGENTS.md to the
    responsibility matrix, but the workflow carries its own copy of the
    reviewer login. Without this, changing that copy to another login -- or
    deleting the workflow -- leaves Phase 0 validation green while the
    executable gate no longer enforces the named reviewer.
    """
    workflow = root / REVIEW_GATE_WORKFLOW
    if not workflow.is_file():
        fail(
            f"{REVIEW_GATE_WORKFLOW} is missing; it publishes the '{REVIEW_GATE_CONTEXT}' status "
            f"that proves {INDEPENDENT_REVIEWER!r} covered a pull request's final head (D-121), "
            "and scripts/check-branch-protection.sh requires that context",
            failures,
        )
        return

    text = workflow.read_text(encoding="utf-8")
    # The `REVIEWER:` assignment specifically, not the file. Searching the whole
    # text is vacuous here: this workflow's own header comments name the
    # reviewer several times, so changing only the assignment -- the one line
    # the gate actually runs on -- would leave a whole-file search satisfied
    # while the executable check queried a different account.
    assignment = REVIEW_GATE_REVIEWER_RE.search(text)
    if assignment is None:
        fail(
            f"{REVIEW_GATE_WORKFLOW} has no `REVIEWER: \"...\"` assignment; the executable gate "
            f"must declare the reviewer it queries so it can be bound to {INDEPENDENT_REVIEWER!r} "
            "(D-121)",
            failures,
        )
    elif assignment.group(1) != INDEPENDENT_REVIEWER:
        fail(
            f"{REVIEW_GATE_WORKFLOW} runs its gate against {assignment.group(1)!r}, but AGENTS.md "
            f"and the responsibility matrix declare {INDEPENDENT_REVIEWER!r} (D-121); the "
            "executable gate and the declared reviewer have diverged",
            failures,
        )
    if f'context="{REVIEW_GATE_CONTEXT}"' not in text:
        fail(
            f"{REVIEW_GATE_WORKFLOW} no longer publishes the '{REVIEW_GATE_CONTEXT}' status "
            "context that scripts/check-branch-protection.sh requires (D-121)",
            failures,
        )


def validate_claude_settings(failures: list[str], root: Path | None = None) -> None:
    root = root if root is not None else ROOT
    path = root / ".claude/settings.json"
    if not path.is_file():
        return  # already reported by validate_required_paths
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        fail(f".claude/settings.json is not valid JSON: {exc}", failures)
        return

    deny = data.get("permissions", {}).get("deny", [])
    required_deny_substrings = ["--force", "merge", "reset --hard"]
    for needle in required_deny_substrings:
        if not any(needle in rule for rule in deny):
            fail(
                f".claude/settings.json permissions.deny is missing a rule covering '{needle}'",
                failures,
            )

    # HK-3: generic (not product-specific) credential/secret filename
    # patterns, ahead of discovery evidence landing under evidence/ — see
    # docs/agents/operating-model.md's Read/Write permissions section.
    # Read, Edit, Write, and NotebookEdit must each be covered; a pattern
    # present only for some tools would leave the others free to read,
    # create, or overwrite a matching file (confirmed as a real gap —
    # see docs/bootstrap/audit-remediation.md, HK-3 follow-up: Write and
    # NotebookEdit had no matching deny rules despite being distinct tool
    # types elsewhere in this same file).
    required_secret_pattern_fragments = [
        "*credentials*",
        "*secret*",
        "*.key)",
        "id_rsa*",
        "id_ed25519*",
        "*.p12",
        "*.pfx",
        "*.keystore",
        "*.jks",
    ]
    for fragment in required_secret_pattern_fragments:
        for tool in ("Read", "Edit", "Write", "NotebookEdit"):
            if not any(rule.startswith(f"{tool}(") and fragment in rule for rule in deny):
                fail(
                    f".claude/settings.json permissions.deny is missing a {tool}(...) rule "
                    f"covering '{fragment}'",
                    failures,
                )

    pretooluse = data.get("hooks", {}).get("PreToolUse", [])
    if not pretooluse:
        fail(".claude/settings.json has no hooks.PreToolUse entries", failures)
        return

    # Regression guard: the PreToolUse Bash hook must invoke the real
    # parser-based guard (scripts/git_guard.py), not a hand-rolled regex
    # string again — see docs/bootstrap/audit-remediation.md (P1-01).
    found_guard_wiring = False
    for entry in pretooluse:
        if entry.get("matcher") != "Bash":
            continue
        for hook in entry.get("hooks", []):
            if "scripts/git_guard.py" in hook.get("command", ""):
                found_guard_wiring = True
    if not found_guard_wiring:
        fail(
            ".claude/settings.json PreToolUse Bash hook does not invoke scripts/git_guard.py "
            "(the parser-based guard) — a hand-rolled regex hook is not sufficient",
            failures,
        )

    guard_script = root / "scripts/git_guard.py"
    if not guard_script.is_file():
        fail("Missing scripts/git_guard.py, required by the Claude PreToolUse hook", failures)

    # HK-2: the Edit/Write/NotebookEdit audit-trail hook (an observability
    # aid, not a permission boundary — see edit_audit_log.py's docstring).
    posttooluse = data.get("hooks", {}).get("PostToolUse", [])
    found_audit_wiring = False
    for entry in posttooluse:
        matcher = entry.get("matcher", "")
        if not {"Edit", "Write", "NotebookEdit"} & set(matcher.split("|")):
            continue
        for hook in entry.get("hooks", []):
            if "scripts/edit_audit_log.py" in hook.get("command", ""):
                found_audit_wiring = True
    if not found_audit_wiring:
        fail(
            ".claude/settings.json has no hooks.PostToolUse entry matching "
            "Edit/Write/NotebookEdit that invokes scripts/edit_audit_log.py",
            failures,
        )

    audit_script = root / "scripts/edit_audit_log.py"
    if not audit_script.is_file():
        fail("Missing scripts/edit_audit_log.py, required by the Claude PostToolUse hook", failures)


def validate_skill_files(failures: list[str]) -> None:
    required_skills = {
        "bootstrap-repository",
        "create-project-charter",
        "create-specification",
        "clarify-requirements",
        "create-adr",
        "create-agent-definition",
        "create-agent-skill",
        "create-github-backlog",
        "analyze-legacy-system",
        "analyze-api-compatibility",
        "create-test-strategy",
        "review-bootstrap",
        "validate-bootstrap",
        "prepare-pr-evidence",
        "propagate-change",
    }
    # Vendor-provided Spec Kit skills (installed by `specify init` /
    # `specify integration install`) use their own upstream schema
    # (name/description/argument-hint/metadata frontmatter, freeform `##`
    # sections) and are not authored by this repository. They are validated
    # only for basic frontmatter presence, never against SKILL_SECTIONS,
    # which is this repository's own procedure schema for its 15 authored
    # governance skills.
    found_skills = set()
    for base in (ROOT / ".agents/skills", ROOT / ".claude/skills"):
        if not base.is_dir():
            continue
        for path in sorted(base.glob("*/SKILL.md")):
            name = path.parent.name
            text = path.read_text(encoding="utf-8")
            if not text.startswith("---\n"):
                fail(f"Skill missing YAML frontmatter: {path.relative_to(ROOT)}", failures)
            if "name:" not in text or "description:" not in text:
                fail(f"Skill frontmatter missing name/description: {path.relative_to(ROOT)}", failures)
            if name.startswith("speckit-"):
                continue
            if base == ROOT / ".agents/skills":
                found_skills.add(name)
            for section in SKILL_SECTIONS:
                if section not in text:
                    fail(f"Skill missing section {section}: {path.relative_to(ROOT)}", failures)
    missing = sorted(required_skills - found_skills)
    for name in missing:
        fail(f"Missing required skill: .agents/skills/{name}/SKILL.md", failures)


MATRIX_ROW_RE = re.compile(
    r"^\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*$",
    re.MULTILINE,
)


def _agent_slug(display_name: str) -> str:
    return display_name.strip().lower().replace(" ", "-")


def validate_agent_matrix_consistency(failures: list[str], root: Path | None = None) -> None:
    """docs/agents/responsibility-matrix.md hand-declares whether each
    agent may modify files. Only Claude agents are technically tool-scoped
    (docs/agents/operating-model.md, enforcement layer 1 — Codex has no
    equivalent mechanism, per layer 2), so this binds the matrix's claim to
    the real `tools:` frontmatter for Claude agents only; Codex rows have
    nothing to check against and are silently skipped, not asserted about."""
    root = root if root is not None else ROOT
    matrix_path = root / "docs/agents/responsibility-matrix.md"
    claude_agents_dir = root / ".claude/agents"
    if not matrix_path.is_file() or not claude_agents_dir.is_dir():
        return  # already reported by validate_required_paths
    matrix_text = matrix_path.read_text(encoding="utf-8")
    for agent_name, _primary_mode, may_modify, _may_pr, _may_approve in MATRIX_ROW_RE.findall(matrix_text):
        agent_name = agent_name.strip()
        may_modify = may_modify.strip()
        if may_modify not in ("Yes", "No"):
            continue  # header row ("Agent") or separator row ("---")
        agent_file = claude_agents_dir / f"{_agent_slug(agent_name)}.md"
        if not agent_file.is_file():
            continue  # not a Claude agent (e.g. a Codex row) — nothing to bind
        agent_text = agent_file.read_text(encoding="utf-8")
        frontmatter_end = agent_text.find("\n---", 4)
        frontmatter = agent_text[:frontmatter_end] if frontmatter_end != -1 else agent_text
        tools_match = re.search(r"^tools:\s*(.+)$", frontmatter, re.MULTILINE)
        tools = tools_match.group(1).strip() if tools_match else ""
        can_modify = any(tool in tools for tool in ("Edit", "Write", "NotebookEdit"))
        if may_modify == "No" and can_modify:
            fail(
                f"responsibility-matrix.md says '{agent_name}' May Modify Files = No, but "
                f".claude/agents/{agent_file.name} tools frontmatter grants {tools!r}",
                failures,
            )
        elif may_modify == "Yes" and not can_modify:
            fail(
                f"responsibility-matrix.md says '{agent_name}' May Modify Files = Yes, but "
                f".claude/agents/{agent_file.name} tools frontmatter ({tools!r}) grants no "
                "file-modifying tool",
                failures,
            )


# Top-level component boundaries that docs/bootstrap/execution-checklist.md
# documents as intentionally empty in Phase 0 (each holds only a boundary
# README.md placeholder today). See docs/bootstrap/open-questions.md's
# "GitHub Governance" section for the team-ownership decision this check is
# waiting on.
COMPONENT_DIRS = ["backend", "admin-web", "edge-gateway", "infrastructure", "contracts", "specs"]


def _git_tracked_files(root: Path) -> set[Path] | None:
    """The set of git-tracked file paths under root, as absolute Path
    objects, or None if root is not inside a working git repository (or
    git is unavailable) — callers must treat None as "no filtering
    possible" and fall back to their prior raw-filesystem behavior rather
    than silently skipping validation. Used so "tracked file" checks (see
    docs below) don't fire on local scratch/untracked files that were
    never part of the repository content actually under review — see
    docs/bootstrap/audit-remediation.md for the finding this fixes."""
    try:
        result = subprocess.run(
            ["git", "ls-files", "-z"],
            cwd=root,
            capture_output=True,
            timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    return {
        root / entry
        for entry in result.stdout.decode("utf-8", errors="replace").split("\0")
        if entry
    }


def validate_codeowners_component_coverage(failures: list[str], root: Path | None = None) -> None:
    """CODEOWNERS today routes everything to @workin-hr/platform-owners (plus
    qa on two paths) — correct while every component directory holds only
    its boundary README.md. Dormant check: once a component directory
    contains any other tracked file, CODEOWNERS must have a path entry for
    it, or PRs touching that component get no team-specific review
    routing. Inert today; verified against the real repository below.

    Only git-tracked files count (via `_git_tracked_files`) — an untracked
    local scratch file dropped into a component directory must not trip
    this check, since it was never part of the repository content under
    review. When root is not a real git repository (e.g. a synthetic test
    fixture), tracking cannot be determined, so every file on disk is
    treated as in-scope — the same behavior this check had before the fix,
    preserved specifically so fixture-based tests keep working unchanged."""
    root = root if root is not None else ROOT
    codeowners_path = root / "CODEOWNERS"
    if not codeowners_path.is_file():
        return  # already reported by validate_required_paths
    codeowners_text = codeowners_path.read_text(encoding="utf-8")
    tracked = _git_tracked_files(root)
    for component in COMPONENT_DIRS:
        component_dir = root / component
        if not component_dir.is_dir():
            continue
        candidate_files = [
            path for path in component_dir.rglob("*") if path.is_file() and path.name != "README.md"
        ]
        if tracked is not None:
            candidate_files = [path for path in candidate_files if path in tracked]
        if not candidate_files:
            continue
        pattern = re.compile(rf"^/{re.escape(component)}/", re.MULTILINE)
        if not pattern.search(codeowners_text):
            fail(
                f"{component}/ now contains files beyond its boundary README.md, but "
                f"CODEOWNERS has no /{component}/ entry routing it to an owning team",
                failures,
            )


# Manifest filename -> a plausible ecosystem label used only in the
# failure message (dependabot.yml's actual required value is checked by
# directory match, not by this label).
MANIFEST_ECOSYSTEMS = {
    "package.json": "npm",
    "composer.json": "composer",
    "pubspec.yaml": "pub",
    "build.gradle": "gradle",
}

DEPENDABOT_DIRECTORY_RE = re.compile(r"directory:\s*[\"']?([^\"'\n]+)[\"']?")


def _normalize_dependabot_dir(raw: str) -> str:
    raw = raw.strip()
    if raw in ("", "/"):
        return "/"
    return "/" + raw.strip("/")


def validate_dependabot_ecosystem_coverage(failures: list[str], root: Path | None = None) -> None:
    """.github/dependabot.yml configures only the github-actions ecosystem
    today — correct, since no package manifest exists anywhere in this
    repository yet (package.json is even in FORBIDDEN_FILE_NAMES above).
    Dormant check: once a manifest lands, dependabot.yml must have a
    package-ecosystem entry for its directory, or that dependency surface
    silently goes unscanned. Inert today; verified below.

    Only git-tracked manifests count (see `_git_tracked_files`) — an
    untracked local scratch `package.json` must not trip this check. Falls
    back to raw-filesystem behavior when root is not a real git repository
    (synthetic test fixtures), same as `validate_codeowners_component_coverage`."""
    root = root if root is not None else ROOT
    dependabot_path = root / ".github/dependabot.yml"
    if not dependabot_path.is_file():
        return  # already reported by validate_required_paths
    dependabot_text = dependabot_path.read_text(encoding="utf-8")
    configured_dirs = {
        _normalize_dependabot_dir(m.group(1)) for m in DEPENDABOT_DIRECTORY_RE.finditer(dependabot_text)
    }
    tracked = _git_tracked_files(root)
    for manifest_name, ecosystem in MANIFEST_ECOSYSTEMS.items():
        for manifest_path in sorted(root.rglob(manifest_name)):
            if ".git" in manifest_path.parts or "node_modules" in manifest_path.parts:
                continue
            if tracked is not None and manifest_path not in tracked:
                continue
            rel_dir = manifest_path.parent.relative_to(root)
            manifest_dir = _normalize_dependabot_dir("" if str(rel_dir) == "." else str(rel_dir).replace("\\", "/"))
            if manifest_dir not in configured_dirs:
                fail(
                    f"{manifest_path.relative_to(root)} exists but .github/dependabot.yml has no "
                    f"package-ecosystem entry for directory '{manifest_dir}' (expected an ecosystem "
                    f"like '{ecosystem}')",
                    failures,
                )


# Matches a backtick-quoted path that climbs at least one directory above
# where it's written (`../foo`, `../../foo`, ...). Deliberately narrow: it
# targets exactly the bug class found once already
# (docs/bootstrap/execution-checklist.md referencing paths that escaped the
# repository root via ../../../), not general link validity — that overlap
# is validate_links()'s job for real [text](path) markdown links.
BACKTICK_DOTDOT_PATH_RE = re.compile(r"`((?:\.\./)+[^`\s]*)`")


def validate_no_repo_root_escaping_paths(failures: list[str], root: Path | None = None) -> None:
    """A backtick-quoted relative path under docs/ that climbs above the
    repository root resolves outside this Git repository for anyone who
    clones it on its own — even when the target happens to exist on one
    particular machine's local multi-repo layout. Found once in
    docs/bootstrap/execution-checklist.md; this generalizes the fix into a
    standing check instead of a one-off patch."""
    root = root if root is not None else ROOT
    docs_dir = root / "docs"
    if not docs_dir.is_dir():
        return
    resolved_root = root.resolve()
    for path in sorted(docs_dir.rglob("*.md")):
        text = path.read_text(encoding="utf-8")
        for match in BACKTICK_DOTDOT_PATH_RE.finditer(text):
            candidate = match.group(1)
            resolved = (path.parent / candidate).resolve()
            try:
                resolved.relative_to(resolved_root)
            except ValueError:
                line_no = text.count("\n", 0, match.start()) + 1
                fail(
                    f"{path.relative_to(root)}:{line_no} references `{candidate}`, which resolves "
                    f"outside the repository root ({resolved}) — anyone cloning this repository "
                    "on its own will not have that path",
                    failures,
                )


# scripts/*.py files intentionally exempt from the "every validator ships
# a test sibling" rule below, with the reason recorded here rather than
# silently allowed. Empty today — every current scripts/*.py source file
# has a real test_*.py sibling; see TS-2 /
# docs/bootstrap/execution-checklist.md.
SCRIPT_TEST_SIBLING_EXEMPTIONS: dict[str, str] = {}


def validate_script_test_siblings(failures: list[str], root: Path | None = None) -> None:
    """This repository's strongest existing pattern is a validator/guard
    script paired with its own fixture-based regression test
    (git_guard.py <-> test_git_guard.py; validate_phase0.py <->
    test_validate_phase0.py and test_adr_validation.py). Make that a rule
    instead of a habit: every non-test scripts/*.py file must have a
    sibling scripts/test_<name>.py, or an explicit, reasoned exemption in
    SCRIPT_TEST_SIBLING_EXEMPTIONS above — so a new script can never
    quietly ship with zero coverage."""
    root = root if root is not None else ROOT
    scripts_dir = root / "scripts"
    if not scripts_dir.is_dir():
        return
    for path in sorted(scripts_dir.glob("*.py")):
        name = path.name
        if name.startswith("test_"):
            continue
        if name in SCRIPT_TEST_SIBLING_EXEMPTIONS:
            continue
        sibling = scripts_dir / f"test_{name}"
        if not sibling.is_file():
            fail(
                f"scripts/{name} has no sibling scripts/test_{name} and is not in "
                "SCRIPT_TEST_SIBLING_EXEMPTIONS (validate_phase0.py) — every validator/guard "
                "script must ship with fixture-based regression tests or a recorded reason why not",
                failures,
            )


def validate_nightly_workflow_exists_if_promised(failures: list[str], root: Path | None = None) -> None:
    """docs/testing/test-strategy.md names a 'Nightly' quality-gate tier.
    Once a doc promises that tier, a real scheduled workflow must exist —
    prose intent alone is not evidence a tier actually runs. See CI-1 /
    .github/workflows/nightly.yml."""
    root = root if root is not None else ROOT
    test_strategy = root / "docs/testing/test-strategy.md"
    workflows_dir = root / ".github/workflows"
    if not test_strategy.is_file():
        return  # already reported by validate_required_paths
    text = test_strategy.read_text(encoding="utf-8")
    if not re.search(r"^## Nightly\s*$", text, re.MULTILINE):
        return
    if workflows_dir.is_dir():
        for path in sorted(workflows_dir.glob("*.yml")):
            if re.search(r"^\s*schedule:\s*$", path.read_text(encoding="utf-8"), re.MULTILINE):
                return
    fail(
        "docs/testing/test-strategy.md names a 'Nightly' tier but no .github/workflows/*.yml "
        "file has an 'on.schedule' trigger",
        failures,
    )


def validate_skill_catalog_consistency(failures: list[str], root: Path | None = None) -> None:
    """docs/agents/skill-catalog.md hand-maintains a list of skill names —
    exactly the kind of filesystem mirror that drifts silently unless
    checked (it had drifted: the 9 vendor speckit-* skills were missing
    from the catalog before this check was added). Every skill directory
    under .agents/skills/, including vendor-provided ones, must be named
    somewhere in the catalog text."""
    root = root if root is not None else ROOT
    catalog = root / "docs/agents/skill-catalog.md"
    skills_dir = root / ".agents/skills"
    if not catalog.is_file() or not skills_dir.is_dir():
        return  # already reported by validate_required_paths
    catalog_text = catalog.read_text(encoding="utf-8")
    for path in sorted(skills_dir.glob("*/SKILL.md")):
        name = path.parent.name
        if name not in catalog_text:
            fail(
                f"Skill '{name}' (.agents/skills/{name}/SKILL.md) is not listed in "
                "docs/agents/skill-catalog.md",
                failures,
            )


ADR_REQUIRED_SECTIONS = [
    "## Metadata",
    "## Context",
    "## Decision",
    "## Alternatives Considered",
    "## Consequences",
    "## Risks",
    "## Validation Evidence",
    "## Open Questions",
]

ADR_REQUIRED_FIELDS = [
    "ADR ID",
    "Title",
    "Status",
    "Date",
    "Owners",
    "Deciders",
    "Related Issues",
    "Supersedes",
    "Superseded By",
]

ADR_VALID_STATUSES = {"Proposed", "Accepted", "Rejected", "Superseded", "Deferred"}

ADR_NAME_RE = re.compile(r"^ADR-\d{4}-[a-z0-9-]+\.md$")
ADR_LOOSE_NAME_RE = re.compile(r"^ADR-.*\.md$")
ADR_TEMPLATE_NAME = "ADR-0000-template.md"

ADR_METADATA_FIELD_RE = re.compile(r"^\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*$", re.MULTILINE)

ADR_INDEX_REFERENCE_RE = re.compile(r"`(ADR-[0-9]{4}-[a-z0-9-]+\.md)`")

# Phrases this repository actually uses, verbatim, for an ADR whose
# Validation Evidence is still an open placeholder rather than real
# evidence (see e.g. ADR-0007, ADR-0008 while Status is Proposed). An
# Accepted ADR must not still read this way — see OB-1 /
# docs/bootstrap/execution-checklist.md.
ADR_EVIDENCE_PLACEHOLDER_PATTERNS = [
    re.compile(r"\bnone yet\b", re.IGNORECASE),
    re.compile(r"\bnot yet discovered\b", re.IGNORECASE),
    re.compile(r"\bpending discovery\b", re.IGNORECASE),
    re.compile(r"\bno evidence exists\b", re.IGNORECASE),
]


def _section_text(text: str, heading: str) -> str:
    """Returns the body text of a single '## Heading' section, up to the
    next '## ' heading or end of file."""
    lines = text.splitlines()
    collected: list[str] = []
    in_section = False
    for line in lines:
        if line.startswith("## "):
            if in_section:
                break
            in_section = line.strip() == heading
            continue
        if in_section:
            collected.append(line)
    return "\n".join(collected)


def _display_path(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def discover_adrs(adr_dir: Path, failures: list[str]) -> list[Path]:
    """Dynamically discover real ADR files by naming convention, so a newly
    added ADR is validated automatically without editing this file. Any
    ADR-*.md file that is not the template and does not match the strict
    ADR-NNNN-slug.md pattern is reported as an invalid name rather than
    silently skipped."""
    discovered: list[Path] = []
    if not adr_dir.is_dir():
        return discovered
    for path in sorted(adr_dir.glob("ADR-*.md")):
        if path.name == ADR_TEMPLATE_NAME:
            continue
        if not ADR_NAME_RE.match(path.name):
            fail(f"Invalid ADR file name (expected ADR-NNNN-slug.md): {_display_path(path)}", failures)
            continue
        discovered.append(path)
    return discovered


def check_duplicate_adr_numbers(adrs: list[Path], failures: list[str]) -> None:
    seen: dict[str, Path] = {}
    for path in adrs:
        number = path.name.split("-")[1]
        if number in seen:
            fail(
                f"Duplicate ADR identifier {number}: {_display_path(seen[number])} and {_display_path(path)}",
                failures,
            )
        else:
            seen[number] = path


def check_adr_index(adr_dir: Path, adrs: list[Path], failures: list[str]) -> None:
    index_path = adr_dir / "README.md"
    if not index_path.is_file():
        fail(f"Missing ADR index: {_display_path(index_path)}", failures)
        return
    index_text = index_path.read_text(encoding="utf-8")
    referenced = set(ADR_INDEX_REFERENCE_RE.findall(index_text))
    discovered_names = {p.name for p in adrs}
    for name in sorted(discovered_names - referenced):
        fail(f"ADR {name} is discovered on disk but not referenced in the ADR index ({_display_path(index_path)})", failures)
    for name in sorted(referenced - discovered_names):
        if not (adr_dir / name).is_file():
            fail(f"ADR index references a file that does not exist: {name}", failures)


def validate_adrs(failures: list[str], adr_dir: Path | None = None) -> None:
    """Discovers and validates every real ADR dynamically — there is no
    hardcoded list of expected ADR file names. A newly added, correctly
    named and structured ADR is picked up automatically; an invalid one
    fails without this file being edited."""
    adr_dir = adr_dir if adr_dir is not None else (ROOT / "docs/adr")
    template = adr_dir / ADR_TEMPLATE_NAME
    if not template.is_file():
        fail(f"Missing ADR template: {_display_path(template)}", failures)

    adrs = discover_adrs(adr_dir, failures)
    check_duplicate_adr_numbers(adrs, failures)
    check_adr_index(adr_dir, adrs, failures)
    for path in adrs:
        _validate_one_adr(path, failures)


def _validate_one_adr(path: Path, failures: list[str]) -> None:
    rel = _display_path(path)
    if not ADR_NAME_RE.match(path.name):
        fail(f"ADR file name does not match ADR-NNNN-slug.md: {rel}", failures)

    text = path.read_text(encoding="utf-8")

    for section in ADR_REQUIRED_SECTIONS:
        if section not in text:
            fail(f"ADR missing section {section}: {rel}", failures)

    fields = {m.group(1): m.group(2) for m in ADR_METADATA_FIELD_RE.finditer(text)}
    for field in ADR_REQUIRED_FIELDS:
        if field not in fields:
            fail(f"ADR missing metadata field '{field}': {rel}", failures)

    status = fields.get("Status")
    if status not in ADR_VALID_STATUSES:
        fail(f"ADR has invalid or missing Status value ({status!r}): {rel}", failures)
    elif status == "Proposed" and "Approval status: Proposed" not in text:
        fail(
            f"Proposed ADR does not visibly state its Decision is unapproved: {rel}",
            failures,
        )
    elif status == "Accepted":
        evidence_text = _section_text(text, "## Validation Evidence")
        if any(pattern.search(evidence_text) for pattern in ADR_EVIDENCE_PLACEHOLDER_PATTERNS):
            fail(
                "ADR Status is Accepted but its Validation Evidence section still reads as "
                f"an unresolved placeholder — replace it with real evidence before accepting: {rel}",
                failures,
            )

    # Non-empty content: every '## ' section must contain at least one
    # non-blank line before the next '## ' heading.
    lines = text.splitlines()
    current_section = None
    has_content = False
    for line in lines:
        if line.startswith("## "):
            if current_section is not None and not has_content:
                fail(f"ADR section '{current_section}' is empty: {rel}", failures)
            current_section = line.strip()
            has_content = False
            continue
        if current_section is not None and line.strip():
            has_content = True
    if current_section is not None and not has_content:
        fail(f"ADR section '{current_section}' is empty: {rel}", failures)


def validate_issue_forms(failures: list[str]) -> None:
    required_forms = [
        "epic.yml",
        "user-story.yml",
        "technical-task.yml",
        "spike.yml",
        "bug.yml",
        "adr.yml",
        "risk.yml",
        "security-finding.yml",
        "performance-finding.yml",
        "device-compatibility-finding.yml",
    ]
    required_fields = [
        "Problem",
        "Business value",
        "Acceptance criteria",
        "Dependencies",
        "API impact",
        "Database impact",
        "Flutter impact",
        "Security impact",
        "Performance impact",
        "Migration impact",
        "Evidence",
        "Definition of done",
    ]
    issue_dir = ROOT / ".github/ISSUE_TEMPLATE"
    for name in required_forms:
        path = issue_dir / name
        if not path.is_file():
            fail(f"Missing issue form: .github/ISSUE_TEMPLATE/{name}", failures)
            continue
        text = path.read_text(encoding="utf-8")
        for field in required_fields:
            if field not in text:
                fail(f"Issue form missing field '{field}': {path.relative_to(ROOT)}", failures)


def validate_scripts_exist(failures: list[str]) -> None:
    for rel in (
        "scripts/verify-bootstrap.sh",
        "scripts/validate_phase0.py",
        "scripts/git_guard.py",
        "scripts/test_git_guard.py",
        "scripts/test_adr_validation.py",
        "scripts/test_validate_phase0.py",
        "scripts/edit_audit_log.py",
        "scripts/test_edit_audit_log.py",
        "scripts/check-bootstrap-prerequisites.sh",
        "scripts/codex-preflight.sh",
        "scripts/check-branch-protection.sh",
    ):
        path = ROOT / rel
        if not path.is_file():
            fail(f"Missing script: {rel}", failures)
            continue
        if path.stat().st_mode & 0o111 == 0:
            fail(f"Script is not executable: {rel}", failures)


def validate_secrets(failures: list[str]) -> None:
    for path in ROOT.rglob("*"):
        if not path.is_file() or ".git" in path.parts:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for pattern in SECRET_PATTERNS:
            if pattern.search(text):
                fail(f"Potential secret detected in {path.relative_to(ROOT)}", failures)
                break


def validate_links(failures: list[str]) -> None:
    for path in ROOT.rglob("*.md"):
        text = path.read_text(encoding="utf-8")
        for match in LINK_RE.finditer(text):
            target = match.group(1).strip()
            if not target or "://" in target or target.startswith("#") or target.startswith("mailto:"):
                continue
            clean = target.split("#", 1)[0]
            if clean.startswith("/"):
                continue
            resolved = (path.parent / clean).resolve()
            if not resolved.exists():
                fail(f"Broken relative link in {path.relative_to(ROOT)}: {target}", failures)


def validate_workflow_safety(failures: list[str]) -> None:
    workflows_dir = ROOT / ".github/workflows"
    if not workflows_dir.is_dir():
        return
    for path in sorted(workflows_dir.glob("*.yml")):
        text = path.read_text(encoding="utf-8")
        if "pull_request_target" in text:
            fail(
                f"{path.relative_to(ROOT)} uses pull_request_target, which runs with "
                "privileged credentials against untrusted PR code and is forbidden here",
                failures,
            )
        if not re.search(r"^permissions:\s*$", text, re.MULTILINE):
            fail(
                f"{path.relative_to(ROOT)} does not declare an explicit top-level "
                "'permissions:' block (least-privilege requirement)",
                failures,
            )


def validate_tool_catalog_consistency(failures: list[str]) -> None:
    catalog = ROOT / "docs/tools/tool-catalog.md"
    matrix = ROOT / "docs/tools/tool-decision-matrix.md"
    if not catalog.is_file() or not matrix.is_file():
        return  # already reported by validate_required_paths

    catalog_text = catalog.read_text(encoding="utf-8")
    matrix_text = matrix.read_text(encoding="utf-8")

    # Regression guard for a real drift found during audit remediation:
    # Git worktrees is a built-in Git capability, not a separately
    # installed tool. It must not appear as its own bullet in the catalog,
    # and the matrix must classify it as needing no install.
    if re.search(r"^- Git worktrees\s*$", catalog_text, re.MULTILINE):
        fail(
            "docs/tools/tool-catalog.md lists 'Git worktrees' as a separate "
            "install item; it is part of Git (see docs/tools/tool-decision-matrix.md)",
            failures,
        )
    matrix_row = re.search(r"^\|\s*Git worktrees\s*\|.*\|\s*$", matrix_text, re.MULTILINE)
    if not matrix_row:
        fail("docs/tools/tool-decision-matrix.md is missing a 'Git worktrees' row", failures)
    elif "no install" not in matrix_row.group(0).lower():
        fail(
            "docs/tools/tool-decision-matrix.md 'Git worktrees' row must state no install is needed",
            failures,
        )


def _run_regression_script(rel_path: str, label: str, failures: list[str]) -> None:
    script = ROOT / rel_path
    if not script.is_file():
        fail(f"Missing regression test script: {rel_path}", failures)
        return
    proc = subprocess.run(
        [sys.executable, str(script)],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=120,
    )
    if proc.returncode != 0:
        tail = "\n".join(proc.stdout.strip().splitlines()[-15:])
        fail(f"{label} failed (exit {proc.returncode}); last output:\n{tail}", failures)


def validate_git_guard_tests(failures: list[str]) -> None:
    _run_regression_script("scripts/test_git_guard.py", "Git command guard regression tests", failures)


def validate_adr_dynamic_tests(failures: list[str]) -> None:
    _run_regression_script("scripts/test_adr_validation.py", "Dynamic ADR validation regression tests", failures)


def validate_governance_check_tests(failures: list[str]) -> None:
    _run_regression_script("scripts/test_validate_phase0.py", "Governance-check regression tests", failures)


def validate_edit_audit_log_tests(failures: list[str]) -> None:
    _run_regression_script("scripts/test_edit_audit_log.py", "Edit/Write audit-log hook regression tests", failures)


def _validate_single_adr_cli(target_arg: str) -> int:
    """Single-ADR CLI mode: `validate_phase0.py --validate-adr <file>`.

    This is the one authoritative ADR-structure implementation. The shell
    wrapper at .agents/skills/create-adr/scripts/validate-adr.sh delegates
    to this instead of maintaining a second, divergent implementation —
    see docs/bootstrap/audit-remediation.md (P2-02)."""
    target = Path(target_arg).resolve()
    failures: list[str] = []
    if not target.is_file():
        print(f"ADR file not found: {target}")
        return 1
    if target.name != ADR_TEMPLATE_NAME and not ADR_NAME_RE.match(target.name):
        print(f"Invalid ADR file name (expected ADR-NNNN-slug.md): {target.name}")
        return 1
    _validate_one_adr(target, failures)
    if failures:
        print(f"ADR structure invalid: {_display_path(target)}")
        for item in failures:
            print(f"- {item}")
        return 1
    print(f"ADR structure looks valid: {_display_path(target)}")
    return 0


def main() -> int:
    if len(sys.argv) >= 3 and sys.argv[1] == "--validate-adr":
        return _validate_single_adr_cli(sys.argv[2])

    failures: list[str] = []
    validate_required_paths(failures)
    validate_instruction_source_of_truth(failures)
    validate_forbidden_files(failures)
    validate_agent_files(failures)
    validate_agent_matrix_consistency(failures)
    validate_independent_reviewer_declaration(failures)
    validate_claude_settings(failures)
    validate_skill_files(failures)
    validate_nightly_workflow_exists_if_promised(failures)
    validate_skill_catalog_consistency(failures)
    validate_codeowners_component_coverage(failures)
    validate_dependabot_ecosystem_coverage(failures)
    validate_no_repo_root_escaping_paths(failures)
    validate_script_test_siblings(failures)
    validate_adrs(failures)
    validate_issue_forms(failures)
    validate_scripts_exist(failures)
    validate_secrets(failures)
    validate_links(failures)
    validate_workflow_safety(failures)
    validate_tool_catalog_consistency(failures)
    validate_git_guard_tests(failures)
    validate_adr_dynamic_tests(failures)
    validate_governance_check_tests(failures)
    validate_edit_audit_log_tests(failures)
    if failures:
        print("Phase 0 validation failed:")
        for item in failures:
            print(f"- {item}")
        return 1
    print("Phase 0 validation passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
