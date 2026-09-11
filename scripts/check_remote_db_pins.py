#!/usr/bin/env python3
"""deploy/compose.remote-db.yaml must keep the pins that make it safe to point
at production.

This is the one compose file in the repository whose own header says it may
point at the production database, and the only stack here that publishes the
same host and port the load harness defaults to. Three settings are what keep
that from being dangerous, and every one of them is a single line that reads
like a preference:

  SPRINGDOC_API_DOCS_ENABLED / SPRINGDOC_SWAGGER_UI_ENABLED
      perf/run.sh refuses a target that does not publish the API description.
      Because this stack publishes to 127.0.0.1 it satisfies the harness's
      loopback condition BY CONSTRUCTION, so this pin is the only condition
      left that refuses it. Re-enable docs for a cutover rehearsal and
      `./run.sh all` against the default BASE_URL starts generating load
      against production, with every other gate in the repository still green.

  MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE
      Anything beyond `health` exposes actuator -- heapdump, env, mappings --
      on a process holding production credentials.

  ports
      EVERY entry loopback-only. One extra `- "8081:8080"` beside the pinned
      entry puts that process on every interface, and the pinned entry is still
      sitting there looking correct.

Asserted on the PARSED document, not on the text. The first version of this
check matched `^\\s*KEY: value$` against the file's lines, which passes on any
line that merely looks right: a review moved all four pins into a dead
top-level `x-` block, left the app service with no springdoc keys and a
`0.0.0.0:8080:8080` publish, and this script still reported "keeps all 4 pins".
Reading the structure is the difference between checking the setting and
checking that somebody wrote the setting down somewhere.

Parsing also subsumes the text cases for free: a commented-out pin is an absent
key, an unquoted `false` is the boolean False rather than the string "false",
and `${SPRINGDOC_API_DOCS_ENABLED:-false}` is a string that is not "false" --
which is the point, because these are literals precisely so that `.env` cannot
override them.

ONE RULE, several instances: anything whose effect is to make the resolved
service differ from what this document says is REFUSED, not interpreted. That
covers `extends:` and top-level `include:` (both merge in definitions from
elsewhere, concatenating ports), `network_mode:` (Docker then ignores `ports`
entirely) and `!reset` (deletes the key it sits on). Each was found by a review
after the previous one was closed, which is the argument for stating the rule
rather than listing the keys: a fifth mechanism should be refused on sight.

What this reports is a property of THIS FILE, not of a running stack. The
documented production invocation is `-f compose.remote-db.yaml -f
compose.tls.yaml`, and that overlay unpublishes the app port and puts Caddy on
0.0.0.0:443. The bare one-file form is the one that puts a
production-credentialled application on 127.0.0.1:8080, where perf/run.sh
defaults -- which is the threat this guards.
"""
from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - a CI image without PyYAML
    print("FAIL: PyYAML is required (pip install pyyaml)", file=sys.stderr)
    raise SystemExit(1)


class ComposeLoader(yaml.SafeLoader):
    """SafeLoader that does not choke on compose's merge-control tags.

    `ports: !override []` is real and already in deploy/compose.tls.yaml.
    safe_load raises on it, which fails closed but reports a YAML syntax error
    for a file compose accepts -- so the reader goes looking for the wrong bug.
    """


class ResetTagFound(Exception):
    """`!reset` deletes the key it is on; this check cannot represent that."""


def _override(loader: yaml.Loader, node: yaml.Node):
    """`!override` means "this value, do not merge it" -- so the value.

    Dispatched on node type: calling construct_object here would re-enter this
    same constructor and raise "found unconstructable recursive node".
    """
    if isinstance(node, yaml.SequenceNode):
        return loader.construct_sequence(node, deep=True)
    if isinstance(node, yaml.MappingNode):
        return loader.construct_mapping(node, deep=True)
    return loader.construct_scalar(node)


def _reset(loader: yaml.Loader, node: yaml.Node):
    """`!reset` is NOT `!override`.

    It removes the key from the resolved service, so reading it as "the value
    without the tag" inverts the answer: `SPRINGDOC_API_DOCS_ENABLED: !reset
    "false"` resolves to NO such key -- springdoc back on -- while a checker
    that strips the tag sees the string "false" and reports the pin intact.
    Treating the two tags alike was a regression: before they were handled at
    all, PyYAML raised on both and this check failed closed.
    """
    raise ResetTagFound(f"line {node.start_mark.line + 1}, column {node.start_mark.column + 1}")


ComposeLoader.add_constructor("!override", _override)
ComposeLoader.add_constructor("!reset", _reset)

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = "deploy/compose.remote-db.yaml"
SERVICE = "app"

# key -> (required literal, why it matters if it changes)
REQUIRED_ENV: dict[str, tuple[str, str]] = {
    "SPRINGDOC_API_DOCS_ENABLED": (
        "false",
        "perf/run.sh's only remaining refusal for this stack; without it a load "
        "run against the default BASE_URL reaches the production database",
    ),
    "SPRINGDOC_SWAGGER_UI_ENABLED": (
        "false",
        "serves an interactive API console against production data",
    ),
    "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE": (
        "health",
        "anything wider exposes actuator on a process holding production credentials",
    ),
}

LOOPBACK = "127.0.0.1"

# Keys that make the authored document differ from the resolved one. A review
# reproduced both: `extends:` pulls in a base service whose ports compose MERGES
# with these (adding an off-loopback publish beside the pinned entry), and
# `network_mode: host` makes Docker discard `ports` entirely while the pinned
# line sits there still looking correct. Both passed a checker that read only
# this file, so both are refused instead.
UNRESOLVABLE_KEYS = {
    "extends": "pulls in another service whose ports compose merges with these",
    "network_mode": "some modes (`host`, `service:`, `container:`) make Docker ignore "
                    "`ports` entirely, and this check cannot tell which you meant",
}

# The same rule at the top level. `include:` merges another file's services into
# this one with the same list-concatenation as `extends`, so it can add an
# off-loopback publish beside the pinned entry AND add whole services the
# per-service loop below never sees.
UNRESOLVABLE_TOP_LEVEL_KEYS = {
    "include": "merges another file's services into this one",
}


def environment_of(service: dict) -> dict[str, str]:
    """Compose accepts a mapping or a `KEY=value` list; normalise both."""
    raw = service.get("environment", {})
    if isinstance(raw, dict):
        return raw
    out: dict[str, str] = {}
    for entry in raw or []:
        key, _, value = str(entry).partition("=")
        out[key] = value
    return out


def published_hosts(service: dict) -> list[tuple[str, str]]:
    """(host_ip, original) for every published port, short or long syntax."""
    out: list[tuple[str, str]] = []
    for entry in service.get("ports", []) or []:
        if isinstance(entry, dict):  # long syntax
            out.append((str(entry.get("host_ip", "")), str(entry)))
            continue
        text = str(entry)
        if text.startswith("["):  # "[::1]:8080:8080"
            host, _, _rest = text[1:].partition("]")
            out.append((host, text))
            continue
        # "127.0.0.1:8080:8080" / "127.0.0.1::8080" / "8080:8080" / "8080".
        # A host_ip is present only when there are three colon-separated parts.
        parts = text.split(":")
        out.append((parts[0] if len(parts) >= 3 else "", text))
    return out


def main() -> int:
    path = ROOT / COMPOSE
    if not path.is_file():
        print(f"FAIL: {COMPOSE} is missing", file=sys.stderr)
        return 1

    try:
        doc = yaml.load(path.read_text(encoding="utf-8"), Loader=ComposeLoader)
    except ResetTagFound as where:
        print(
            f"FAIL: {COMPOSE} uses `!reset` at {where}.\n\n"
            f"  `!reset` DELETES the key it is on, so the resolved service does not have it\n"
            f"  at all. This check reads the file, so it would see the value and report the\n"
            f"  pin intact while compose removed it.",
            file=sys.stderr,
        )
        return 1
    except yaml.composer.ComposerError:
        print(
            f"FAIL: {COMPOSE} contains more than one YAML document.\n\n"
            f"  compose reads them all; this check reads the first, so a second one can\n"
            f"  add ports or drop a pin where this cannot see it.",
            file=sys.stderr,
        )
        return 1
    except yaml.YAMLError as error:
        print(f"FAIL: {COMPOSE} is not valid YAML: {error}", file=sys.stderr)
        return 1

    problems: list[str] = []
    service = ((doc or {}).get("services") or {}).get(SERVICE)
    if not isinstance(service, dict):
        print(
            f"FAIL: {COMPOSE} has no `services.{SERVICE}` mapping, so none of its pins "
            f"can be checked. If the service was renamed, update SERVICE in "
            f"scripts/{Path(__file__).name}.",
            file=sys.stderr,
        )
        return 1

    environment = environment_of(service)
    for key, (expected, why) in REQUIRED_ENV.items():
        actual = environment.get(key, ...)
        if actual is ...:
            problems.append(f"  services.{SERVICE}.environment.{key} is absent\n    {why}")
        elif actual != expected:
            problems.append(
                f"  services.{SERVICE}.environment.{key} is {actual!r}, must be the "
                f"literal {expected!r}\n    {why}"
            )

    for key, why in UNRESOLVABLE_TOP_LEVEL_KEYS.items():
        if key in (doc or {}):
            problems.append(
                f"  top-level `{key}` is set\n    {why}, and this check reads the file "
                f"rather than the resolved stack, so it cannot see the result"
            )

    hosts: list[tuple[str, str]] = []
    for name, other in (doc.get("services") or {}).items():
        if not isinstance(other, dict):
            continue
        # EVERY service, not just `app`. A sibling with `extends` or
        # `network_mode` puts a container on every interface just as surely,
        # and the port scan below already treats siblings as in scope.
        for key, why in UNRESOLVABLE_KEYS.items():
            if key in other:
                problems.append(
                    f"  services.{name}.{key} is set\n    {why}, and this check reads "
                    f"the file rather than the resolved stack, so it cannot see the result"
                )
        hosts.extend((ip, f"{name}: {text}") for ip, text in published_hosts(other))
    if not hosts:
        problems.append(
            f"  no service publishes any port; the pinned "
            f"{LOOPBACK} publish is gone\n    without it this file no longer "
            f"describes a reachable stack"
        )
    for host_ip, original in hosts:
        if host_ip != LOOPBACK:
            problems.append(
                f"  ports entry {original!r} publishes on "
                f"{host_ip or 'every interface'}, not {LOOPBACK}\n"
                f"    exposes a process holding production credentials to the network"
            )

    if problems:
        print(f"FAIL: {COMPOSE} has lost a pin that keeps it safe.\n", file=sys.stderr)
        print("\n\n".join(problems), file=sys.stderr)
        print(
            "\nThese are literals on purpose, so that .env cannot override them.\n"
            "If one genuinely has to change, change it here too and say why.",
            file=sys.stderr,
        )
        return 1

    print(
        f"{COMPOSE} keeps all {len(REQUIRED_ENV)} environment pins and publishes "
        f"only on {LOOPBACK} ({len(hosts)} port(s))."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
