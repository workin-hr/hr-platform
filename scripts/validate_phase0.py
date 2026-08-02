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
    ".github/pull_request_template.md",
    ".github/workflows/phase0-validate.yml",
    "docs/bootstrap/approved-bootstrap-plan.md",
    "docs/bootstrap/decisions.md",
    "docs/bootstrap/open-questions.md",
    "docs/bootstrap/risks.md",
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
    "docs/migration",
    "docs/devices",
    "docs/operations",
    "specs",
    "contracts",
    "evidence",
    "backend",
    "admin-web",
    "edge-gateway",
    "infrastructure",
]

AGENT_SECTIONS = [
    "## Role",
    "## Purpose",
    "## Inputs",
    "## Outputs",
    "## Allowed Tools",
    "## Forbidden Actions",
    "## Read/Write Permissions",
    "## Escalation Rules",
    "## Completion Criteria",
]

SKILL_SECTIONS = [
    "## Trigger",
    "## Inputs",
    "## Workflow",
    "## Required Evidence",
    "## Validation Checklist",
    "## Failure And Escalation",
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


def validate_skill_files(failures: list[str]) -> None:
    for path in sorted((ROOT / ".agents/skills").glob("*/SKILL.md")):
        text = path.read_text(encoding="utf-8")
        if not text.startswith("---\n"):
            fail(f"Skill missing YAML frontmatter: {path.relative_to(ROOT)}", failures)
        if "name:" not in text or "description:" not in text:
            fail(f"Skill frontmatter missing name/description: {path.relative_to(ROOT)}", failures)
        for section in SKILL_SECTIONS:
            if section not in text:
                fail(f"Skill missing section {section}: {path.relative_to(ROOT)}", failures)


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
