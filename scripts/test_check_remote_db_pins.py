#!/usr/bin/env python3
"""What the remote-db pin check catches, and what it must not fail on.

The check exists because the answer to "what test fails if these lines are
deleted?" was none. So the case that matters is each pin removed on its own --
and the near-miss forms that a checker matching loosely would let through: the
line commented out, the value flipped, the quotes dropped, the port widened.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "check_remote_db_pins.py"
CASES: list[tuple[bool, str]] = []

COMPOSE = """\
name: workin-remote-db
services:
  app:
    environment:
      APP_TRACE_SAMPLING: ${APP_TRACE_SAMPLING:-1.0}
      # Actuator stays shut: this process holds production credentials.
      MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health
      SPRINGDOC_API_DOCS_ENABLED: "false"
      SPRINGDOC_SWAGGER_UI_ENABLED: "false"
    ports:
      - "127.0.0.1:${APP_PUBLISHED_PORT:-8080}:8080"
"""


def run(compose: str | None) -> subprocess.CompletedProcess:
    root = Path(tempfile.mkdtemp(prefix="remote-db-pins-"))
    try:
        (root / "deploy").mkdir()
        (root / "scripts").mkdir()
        if compose is not None:
            (root / "deploy/compose.remote-db.yaml").write_text(compose, encoding="utf-8")
        copy = root / "scripts" / SCRIPT.name
        copy.write_text(SCRIPT.read_text(encoding="utf-8"), encoding="utf-8")
        return subprocess.run([sys.executable, str(copy)],
                              capture_output=True, text=True, timeout=30)
    finally:
        shutil.rmtree(root)


def check(ok: bool, label: str) -> None:
    CASES.append((ok, label))
    print(("  ok    " if ok else "  FAIL  ") + label)


PINS = (
    ('      SPRINGDOC_API_DOCS_ENABLED: "false"\n', "SPRINGDOC_API_DOCS_ENABLED"),
    ('      SPRINGDOC_SWAGGER_UI_ENABLED: "false"\n', "SPRINGDOC_SWAGGER_UI_ENABLED"),
    ("      MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health\n",
     "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE"),
    ('      - "127.0.0.1:${APP_PUBLISHED_PORT:-8080}:8080"\n', "127.0.0.1"),
)


def main() -> int:
    proc = run(COMPOSE)
    check(proc.returncode == 0,
          f"the pinned file passes (exit={proc.returncode}, err={proc.stderr[:120]!r})")

    # The case the check was written for: each pin deleted, one at a time. A
    # checker that passed here would be decorative.
    for line, name in PINS:
        assert line in COMPOSE, line
        proc = run(COMPOSE.replace(line, ""))
        check(proc.returncode == 1 and name in proc.stderr,
              f"deleting {name} fails and is named (exit={proc.returncode})")

    # Commented out is deleted. A checker that searched the raw text would find
    # the pin in its own explanation and pass.
    for line, name in PINS:
        proc = run(COMPOSE.replace(line, "  #" + line.lstrip()))
        check(proc.returncode == 1 and name in proc.stderr,
              f"commenting {name} out fails (exit={proc.returncode})")

    # Flipped values, not absent ones -- the form an edit actually takes.
    proc = run(COMPOSE.replace('SPRINGDOC_API_DOCS_ENABLED: "false"',
                               'SPRINGDOC_API_DOCS_ENABLED: "true"'))
    check(proc.returncode == 1 and "SPRINGDOC_API_DOCS_ENABLED" in proc.stderr,
          f"flipping api-docs to true fails (exit={proc.returncode})")

    # Unquoted `false` is YAML boolean false, which Compose rejects for an
    # environment value; pinning the quoted literal is deliberate.
    proc = run(COMPOSE.replace('SPRINGDOC_API_DOCS_ENABLED: "false"',
                               "SPRINGDOC_API_DOCS_ENABLED: false"))
    check(proc.returncode == 1,
          f"dropping the quotes fails (exit={proc.returncode})")

    # Re-enabling docs through .env instead of the literal.
    proc = run(COMPOSE.replace('SPRINGDOC_API_DOCS_ENABLED: "false"',
                               "SPRINGDOC_API_DOCS_ENABLED: ${SPRINGDOC_API_DOCS_ENABLED:-false}"))
    check(proc.returncode == 1 and "SPRINGDOC_API_DOCS_ENABLED" in proc.stderr,
          f"making the pin .env-overridable fails (exit={proc.returncode})")

    # Widening the publish to every interface.
    proc = run(COMPOSE.replace('- "127.0.0.1:${APP_PUBLISHED_PORT:-8080}:8080"',
                               '- "${APP_PUBLISHED_PORT:-8080}:8080"'))
    check(proc.returncode == 1 and "127.0.0.1" in proc.stderr,
          f"publishing on all interfaces fails (exit={proc.returncode})")

    # Widening actuator exposure.
    proc = run(COMPOSE.replace("MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health",
                               "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health,env"))
    check(proc.returncode == 1 and "MANAGEMENT_ENDPOINTS" in proc.stderr,
          f"widening actuator exposure fails (exit={proc.returncode})")

    # Comments and blank lines elsewhere are how the file explains itself.
    noisy = COMPOSE.replace("services:", "\n# why this stack exists\n\nservices:")
    proc = run(noisy)
    check(proc.returncode == 0,
          f"comments and blank lines are not drift (exit={proc.returncode})")

    # Re-ordering the environment block is not drift.
    reordered = COMPOSE.replace(
        '      SPRINGDOC_API_DOCS_ENABLED: "false"\n'
        '      SPRINGDOC_SWAGGER_UI_ENABLED: "false"\n',
        '      SPRINGDOC_SWAGGER_UI_ENABLED: "false"\n'
        '      SPRINGDOC_API_DOCS_ENABLED: "false"\n')
    proc = run(reordered)
    check(proc.returncode == 0, f"re-ordering the pins is not drift (exit={proc.returncode})")

    # STRUCTURAL BYPASSES. Both of these passed the text-matching version of
    # this check, which is why it now parses the document.

    # An extra publish beside the pinned one. The loopback entry is still there
    # and still looks right; the process is on every interface anyway.
    proc = run(COMPOSE.replace('      - "127.0.0.1:${APP_PUBLISHED_PORT:-8080}:8080"\n',
                               '      - "127.0.0.1:${APP_PUBLISHED_PORT:-8080}:8080"\n'
                               '      - "8081:8080"\n'))
    check(proc.returncode == 1 and "8081" in proc.stderr,
          f"a SECOND publish on all interfaces fails (exit={proc.returncode})")

    # The pins hoisted into a dead top-level block: every literal is still
    # present in the file, and none of them reaches the app service.
    dead = """\
name: workin-remote-db
x-dead:
  environment:
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health
    SPRINGDOC_API_DOCS_ENABLED: "false"
    SPRINGDOC_SWAGGER_UI_ENABLED: "false"
services:
  app:
    environment:
      APP_TRACE_SAMPLING: ${APP_TRACE_SAMPLING:-1.0}
    ports:
      - "0.0.0.0:8080:8080"
"""
    proc = run(dead)
    check(proc.returncode == 1 and "SPRINGDOC_API_DOCS_ENABLED" in proc.stderr,
          f"pins in a dead x- block do not count (exit={proc.returncode})")

    # The same, via a service that is not `app`.
    other = COMPOSE.replace("  app:\n", "  app:\n    image: scratch\n  notapp:\n")
    proc = run(other)
    check(proc.returncode == 1,
          f"pins under a different service do not count (exit={proc.returncode})")

    # Long-syntax ports must be checked too.
    longform = COMPOSE.replace('    ports:\n      - "127.0.0.1:${APP_PUBLISHED_PORT:-8080}:8080"\n',
                               "    ports:\n      - target: 8080\n        published: 8080\n"
                               "        host_ip: 0.0.0.0\n")
    proc = run(longform)
    check(proc.returncode == 1,
          f"long-syntax publish on all interfaces fails (exit={proc.returncode})")

    # A service with no ports at all is not silently fine.
    proc = run(COMPOSE.replace('    ports:\n      - "127.0.0.1:${APP_PUBLISHED_PORT:-8080}:8080"\n', ""))
    check(proc.returncode == 1 and "no service publishes any port" in proc.stderr,
          f"removing the publish entirely fails (exit={proc.returncode})")

    # A renamed service must fail loudly, not vacuously pass with nothing to check.
    proc = run(COMPOSE.replace("  app:\n", "  application:\n"))
    check(proc.returncode == 1 and "has no `services.app`" in proc.stderr,
          f"a renamed service fails loudly (exit={proc.returncode})")

    # RESOLUTION BYPASSES. The checker reads this file; compose resolves
    # something else. Both of these were reproduced against `docker compose
    # config` while the checker reported "publishes only on 127.0.0.1".

    # `extends` pulls in a base service whose ports compose MERGES with these.
    proc = run(COMPOSE.replace("  app:\n",
                               "  app:\n    extends:\n      file: base.yaml\n      service: wide\n"))
    check(proc.returncode == 1 and "extends" in proc.stderr,
          f"extends: is refused, not silently resolved (exit={proc.returncode})")

    # `network_mode: host` makes Docker discard `ports` and bind everything.
    proc = run(COMPOSE.replace("  app:\n", "  app:\n    network_mode: host\n"))
    check(proc.returncode == 1 and "network_mode" in proc.stderr,
          f"network_mode: host is refused (exit={proc.returncode})")

    # A SIBLING service publishing off-loopback. Latent today -- one service --
    # but "this stack is loopback-only" is a claim about the whole file.
    proc = run(COMPOSE + '  helper:\n    image: scratch\n    ports:\n      - "0.0.0.0:9999:9999"\n')
    check(proc.returncode == 1 and "9999" in proc.stderr,
          f"a sibling service publishing off-loopback fails (exit={proc.returncode})")

    # compose's own merge-control tags must not read as a YAML syntax error.
    proc = run(COMPOSE.replace('    ports:\n      - "127.0.0.1:${APP_PUBLISHED_PORT:-8080}:8080"\n',
                               "    ports: !override []\n"))
    check(proc.returncode == 1 and "not valid YAML" not in proc.stderr,
          f"!override parses, and an emptied ports list still fails (exit={proc.returncode})")

    # A bracketed IPv6 host_ip is parsed, not garbled into "[".
    proc = run(COMPOSE.replace('      - "127.0.0.1:${APP_PUBLISHED_PORT:-8080}:8080"',
                               '      - "[::1]:8080:8080"'))
    check(proc.returncode == 1 and "::1" in proc.stderr,
          f"an IPv6 host_ip is reported by name (exit={proc.returncode})")

    # `!reset` DELETES the key. Reading it as "the value without the tag" -- which
    # is what !override means -- inverts the answer, and did: compose resolved a
    # service with no springdoc key while the checker reported the pin intact.
    for key in ("SPRINGDOC_API_DOCS_ENABLED", "SPRINGDOC_SWAGGER_UI_ENABLED",
                "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE"):
        proc = run(COMPOSE.replace(f"{key}: ", f"{key}: !reset "))
        check(proc.returncode == 1 and "!reset" in proc.stderr,
              f"!reset on {key} is refused, not read as its value (exit={proc.returncode})")

    proc = run(COMPOSE.replace('    ports:\n', '    ports: !reset []\n#'))
    check(proc.returncode == 1 and "!reset" in proc.stderr,
          f"!reset on ports is refused (exit={proc.returncode})")

    # `include:` is TOP-LEVEL, so a service-level scan cannot see it. It merges
    # another file's services in with the same concatenation as extends.
    proc = run("include:\n  - base.yaml\n" + COMPOSE)
    check(proc.returncode == 1 and "include" in proc.stderr,
          f"top-level include: is refused (exit={proc.returncode})")

    # A SIBLING service with a resolution-changing key. The port scan already
    # treated siblings as in scope; this scan did not, so `extends` one service
    # to the left put a container on every interface with the checker green.
    for key, frag in (("extends", "    extends:\n      file: base.yaml\n      service: wide\n"),
                      ("network_mode", "    network_mode: host\n")):
        proc = run(COMPOSE + f"  sidecar:\n    image: scratch\n{frag}")
        check(proc.returncode == 1 and key in proc.stderr and "sidecar" in proc.stderr,
              f"{key} on a SIBLING service is refused and named (exit={proc.returncode})")

    # A second YAML document: compose reads them all, this reads the first.
    proc = run(COMPOSE + "---\nservices:\n  app:\n    ports:\n      - \"0.0.0.0:18078:8080\"\n")
    check(proc.returncode == 1 and "more than one YAML document" in proc.stderr,
          f"a second document is refused by name (exit={proc.returncode})")

    # ComposerError is not only "second document". An undefined alias is a file
    # compose also rejects; a DUPLICATE ANCHOR is one compose ACCEPTS. Neither
    # may be reported as a second document.
    proc = run(COMPOSE.replace("    environment:\n", "    environment: *nope\n#"))
    check(proc.returncode == 1 and "more than one YAML document" not in proc.stderr,
          f"an undefined alias is not called a second document (exit={proc.returncode})")

    dup = COMPOSE.replace("  app:\n", "  app: &dup\n").replace("name: workin-remote-db\n",
                                                                "name: workin-remote-db\nx-a: &dup {}\n")
    proc = run(dup)
    check("more than one YAML document" not in proc.stderr,
          f"a duplicate anchor is not called a second document (exit={proc.returncode})")

    # A missing file fails rather than skipping.
    proc = run(None)
    check(proc.returncode == 1 and "is missing" in proc.stderr,
          f"a missing compose file fails rather than skipping (exit={proc.returncode})")

    passed = sum(1 for ok, _ in CASES if ok)
    print(f"\n{passed}/{len(CASES)} remote-db pin cases passed.")
    return 0 if passed == len(CASES) else 1


if __name__ == "__main__":
    sys.exit(main())
