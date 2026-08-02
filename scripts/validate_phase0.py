#!/usr/bin/env python3
from __future__ import annotations

import re
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
    ".codex/config.toml",
    ".specify/constitution.md",
    ".specify/workflow.md",
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
]

REQUIRED_DIRS = [
    ".claude/agents",
    ".codex/agents",
    ".agents/skills",
    ".specify",
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


def validate_forbidden_files(failures: list[str]) -> None:
    for path in ROOT.rglob("*"):
        rel = path.relative_to(ROOT).as_posix()
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


def validate_agent_files(failures: list[str]) -> None:
    for base in (ROOT / ".claude/agents", ROOT / ".codex/agents"):
        for path in sorted(base.glob("*.md")):
            text = path.read_text(encoding="utf-8")
            for section in AGENT_SECTIONS:
                if section not in text:
                    fail(f"Agent file missing section {section}: {path.relative_to(ROOT)}", failures)
            if "## Approval Authority\n\nMay not approve work." not in text:
                fail(f"Agent approval boundary not explicit: {path.relative_to(ROOT)}", failures)
            if "Read-only" in text and "## File Modification\n\nNo." not in text and "## File Modification\n\nNo by default." not in text:
                fail(f"Read-only agent file modification boundary unclear: {path.relative_to(ROOT)}", failures)


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
    }
    found_skills = set()
    for path in sorted((ROOT / ".agents/skills").glob("*/SKILL.md")):
        text = path.read_text(encoding="utf-8")
        found_skills.add(path.parent.name)
        if not text.startswith("---\n"):
            fail(f"Skill missing YAML frontmatter: {path.relative_to(ROOT)}", failures)
        if "name:" not in text or "description:" not in text:
            fail(f"Skill frontmatter missing name/description: {path.relative_to(ROOT)}", failures)
        for section in SKILL_SECTIONS:
            if section not in text:
                fail(f"Skill missing section {section}: {path.relative_to(ROOT)}", failures)
    missing = sorted(required_skills - found_skills)
    for name in missing:
        fail(f"Missing required skill: .agents/skills/{name}/SKILL.md", failures)


def validate_adrs(failures: list[str]) -> None:
    expected = [
        "ADR-0001-repository-strategy.md",
        "ADR-0002-modular-monolith-baseline.md",
        "ADR-0003-api-versioning-and-flutter-compatibility.md",
        "ADR-0004-mysql-to-postgresql-migration-approach.md",
        "ADR-0005-authentication-and-authorization-direction.md",
        "ADR-0006-attendance-edge-gateway-direction.md",
        "ADR-0007-testing-and-quality-gate-strategy.md",
        "ADR-0008-observability-baseline.md",
    ]
    for name in expected:
        path = ROOT / "docs/adr" / name
        if not path.is_file():
            fail(f"Missing ADR placeholder: docs/adr/{name}", failures)
            continue
        text = path.read_text(encoding="utf-8")
        if "\n## Status\n\nProposed" not in text:
            fail(f"ADR not in Proposed status: {path.relative_to(ROOT)}", failures)


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
    for rel in ("scripts/verify-bootstrap.sh", "scripts/validate_phase0.py"):
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


def main() -> int:
    failures: list[str] = []
    validate_required_paths(failures)
    validate_forbidden_files(failures)
    validate_agent_files(failures)
    validate_skill_files(failures)
    validate_adrs(failures)
    validate_issue_forms(failures)
    validate_scripts_exist(failures)
    validate_secrets(failures)
    validate_links(failures)
    if failures:
        print("Phase 0 validation failed:")
        for item in failures:
            print(f"- {item}")
        return 1
    print("Phase 0 validation passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
