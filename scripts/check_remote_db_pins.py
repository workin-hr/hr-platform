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
service differ from what this document says is REFUSED, not interpreted. Six so
far, each found by a review after the previous one was closed:

  extends:            merges a base service in, concatenating ports
  include:            the same, top-level, and it can add whole services
  network_mode:       some modes make Docker ignore `ports` entirely
  !reset              deletes the key it sits on
  a second document   compose reads them all; this reads the first
  <<                  in the overlay, pulls keys in from an anchor that the
                      node graph shows only as a key named `<<`

and the scan runs over EVERY service, not just `app`, because a sibling
publishes just as widely. That history is the argument for stating the rule
rather than listing the keys: a seventh mechanism should be refused on sight.

The same goes for a pinned property set under another environment name.
Spring's relaxed binding ignores case and separators when it maps an
environment name to a property, so `SPRINGDOC_APIDOCS_ENABLED` names
`springdoc.api-docs.enabled` as surely as the pinned spelling does. Which of the
two the binder prefers is its business; this check refuses the alias rather
than depend on it.

The overlay is held to an allowlist rather than to that list. Three review
rounds in a row found another way to switch a pin back through it -- a restated
pin; a merge key and a second spelling; then SPRING_APPLICATION_JSON,
JAVA_TOOL_OPTIONS, env_file, command and a relay service -- and a list of
refusals only ever catches the ways already found. The overlay is small enough
to say what it may contain instead: services `app` and `proxy` only; `app`
carries `ports: !override []` and SERVER_FORWARD_HEADERS_STRATEGY and nothing
else; `proxy` keeps a Caddy image and exactly ports 80, 443 and 443/udp.

The base file is too large for that, so it refuses the channels Spring Boot
documents as outranking an OS environment variable: command-line arguments
(`command`, and `entrypoint`, which replaces them), SPRING_APPLICATION_JSON, and
JVM system properties (JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS). It
also refuses `env_file`, which adds environment variables this check does not
read. Configuration files rank below environment variables, so a mounted
application.yml cannot switch a pin back and is not refused. What this check
still cannot see is configuration baked into the image, or anything outside
these two files.

What this reports is a property of THIS FILE, not of a running stack. The
documented production invocation is `-f compose.remote-db.yaml -f
compose.tls.yaml`, and that overlay unpublishes the app port and puts Caddy on
0.0.0.0:443. The bare one-file form is the one that puts a
production-credentialled application on 127.0.0.1:8080, where perf/run.sh
defaults -- which is the threat this guards.

It also checks that overlay, because the documented invocation is only safe
while deploy/compose.tls.yaml carries both halves of R-049: the application's
published port removed with `ports: !override []`, and
SERVER_FORWARD_HEADERS_STRATEGY fixed at `native`. Each is one line whose
removal passed every other gate. Without the first, a caller on the host
reaches the application directly and a forged X-Forwarded-For is believed.
Without the second, this file's profiles run behind Caddy with no strategy, so
every client shares one login-throttle bucket and eight misses lock out every
administrator. The tag is checked and not only the value: ComposeLoader reads
`!override []` and a plain `[]` alike, and compose merges the plain one with
the base file's publish.
"""
from __future__ import annotations

import re
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

OVERLAY = "deploy/compose.tls.yaml"
OVERLAY_STRATEGY = ("SERVER_FORWARD_HEADERS_STRATEGY", "native")
OVERLAY_STRATEGY_WHY = (
    "behind Caddy the remote-db profiles would run with no strategy, so every client "
    "shares one login-throttle bucket and eight misses lock out every administrator; "
    "a variable here would let .env do the same"
)
PLAIN_STRING = "tag:yaml.org,2002:str"
MERGE_TAG = "tag:yaml.org,2002:merge"
ALIAS_WHY = (
    "Spring's relaxed binding ignores case and separators in an environment name, so the "
    "application may read this instead of the pinned spelling; the check refuses the alias "
    "rather than depend on which one the binder prefers"
)

OVERLAY_TOP_LEVEL = {"services", "volumes"}
OVERLAY_SERVICES = {"app", "proxy"}
OVERLAY_APP_KEYS = {"ports", "environment"}
OVERLAY_PROXY_KEYS = {"image", "restart", "depends_on", "environment", "volumes", "ports",
                      "healthcheck", "deploy"}
OVERLAY_PROXY_PORTS = ["80:80", "443:443", "443:443/udp"]
OVERLAY_ALLOWLIST_WHY = (
    "the overlay is held to what it needs -- Caddy in front, the application's port removed, "
    "the strategy fixed -- because anything more is a way to switch a pin back that this "
    "check would otherwise have to be taught one review round at a time"
)

# Keys and environment names that outrank an OS environment variable, or feed
# environment variables this check does not read.
OVERRIDING_SERVICE_KEYS = {
    "env_file": "loads environment variables from a file this check does not read",
    "command": "the image's entrypoint is exec-form `java -jar`, so these become application "
               "arguments, which outrank environment variables",
    "entrypoint": "replaces how the application starts, arguments included",
}
JVM_OPTION_VARIABLES = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")


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


def value_node(mapping: yaml.Node | None, key: str) -> yaml.Node | None:
    """The node under `key` in a mapping node -- the last one, as a loader keeps."""
    if not isinstance(mapping, yaml.MappingNode):
        return None
    found = None
    for key_node, node in mapping.value:
        if isinstance(key_node, yaml.ScalarNode) and key_node.value == key:
            found = node
    return found


def describe(node: yaml.Node | None) -> str:
    if node is None:
        return "absent"
    if isinstance(node, yaml.ScalarNode):
        shown = repr(node.value)
    elif isinstance(node, yaml.SequenceNode):
        shown = f"a list of {len(node.value)}"
    else:
        shown = "a mapping"
    return shown if node.tag.startswith("tag:yaml.org,2002:") else f"{node.tag} {shown}"


def spring_name(name: str) -> str:
    """An environment name as Spring's relaxed binding compares it."""
    return re.sub(r"[_.\-]", "", name).upper()


def aliases(names, pins) -> list[tuple[str, str]]:
    """(name, pin) for every name that is not the pin but binds to the same property."""
    return [(name, pin) for name in dict.fromkeys(names) for pin in pins
            if name != pin and spring_name(name) == spring_name(pin)]


def merge_keys(node, path: str, seen: set[int] | None = None) -> list[str]:
    """Every place under `node` that uses a YAML merge key (`<<`)."""
    seen = set() if seen is None else seen
    if id(node) in seen:
        return []
    seen.add(id(node))
    found: list[str] = []
    if isinstance(node, yaml.MappingNode):
        for key, value in node.value:
            name = key.value if isinstance(key, yaml.ScalarNode) else "?"
            if key.tag == MERGE_TAG or name == "<<":
                found.append(path)
            found += merge_keys(value, f"{path}.{name}" if path else name, seen)
    elif isinstance(node, yaml.SequenceNode):
        for i, value in enumerate(node.value):
            found += merge_keys(value, f"{path}[{i}]", seen)
    return found


def keys_of(node) -> list[str]:
    """The key names of a mapping node, leaving merge keys to merge_keys()."""
    if not isinstance(node, yaml.MappingNode):
        return []
    return [k.value for k, _ in node.value
            if isinstance(k, yaml.ScalarNode) and k.tag != MERGE_TAG and k.value != "<<"]


def overlay_problems() -> list[str]:
    """Both halves of R-049 must stay in deploy/compose.tls.yaml.

    Composed to nodes rather than loaded to values, because the tag is the
    point: a loaded `!override []` and a plain `[]` are the same empty list.
    """
    path = ROOT / OVERLAY
    if not path.is_file():
        return [f"  {OVERLAY} is missing\n    the documented invocation layers it, and it "
                f"is what removes the application's published port"]
    try:
        root = yaml.compose(path.read_text(encoding="utf-8"), Loader=ComposeLoader)
    except yaml.composer.ComposerError as error:
        if error.context == "expected a single document in the stream":
            return [f"  {OVERLAY} contains more than one YAML document\n    compose reads "
                    f"them all; this check reads the first"]
        return [f"  {OVERLAY} could not be composed: {error}"]
    except yaml.YAMLError as error:
        return [f"  {OVERLAY} is not valid YAML: {error}"]

    app = value_node(value_node(root, "services"), SERVICE)
    if not isinstance(app, yaml.MappingNode):
        return [f"  {OVERLAY} has no `services.{SERVICE}` mapping, so neither half of "
                f"R-049 can be checked"]

    problems: list[str] = []
    services = value_node(root, "services")
    for name in keys_of(root):
        if name not in OVERLAY_TOP_LEVEL and name not in UNRESOLVABLE_TOP_LEVEL_KEYS:
            problems.append(f"  {OVERLAY}: top-level `{name}` is not allowed\n    {OVERLAY_ALLOWLIST_WHY}")
    for name in keys_of(services):
        if name not in OVERLAY_SERVICES:
            problems.append(
                f"  {OVERLAY}: services.{name} is not allowed\n    a second service can publish "
                f"another route to the application, where a forged X-Forwarded-For is believed"
            )
    for name in keys_of(app):
        if name not in OVERLAY_APP_KEYS and name not in UNRESOLVABLE_KEYS:
            problems.append(f"  {OVERLAY}: services.{SERVICE}.{name} is not allowed\n    {OVERLAY_ALLOWLIST_WHY}")
    proxy = value_node(services, "proxy")
    if proxy is not None:
        for name in keys_of(proxy):
            if name not in OVERLAY_PROXY_KEYS:
                problems.append(f"  {OVERLAY}: services.proxy.{name} is not allowed\n    {OVERLAY_ALLOWLIST_WHY}")
        proxy_ports = value_node(proxy, "ports")
        listed = ([n.value for n in proxy_ports.value] if isinstance(proxy_ports, yaml.SequenceNode)
                  and proxy_ports.tag == "tag:yaml.org,2002:seq"
                  and all(isinstance(n, yaml.ScalarNode) for n in proxy_ports.value) else None)
        if listed != OVERLAY_PROXY_PORTS:
            problems.append(
                f"  {OVERLAY}: services.proxy.ports is {describe(proxy_ports)}, must be exactly "
                f"{OVERLAY_PROXY_PORTS}\n    another publish on the proxy is another route in"
            )
        image = value_node(proxy, "image")
        if not (isinstance(image, yaml.ScalarNode) and image.tag == PLAIN_STRING
                and image.value.startswith("caddy:")):
            problems.append(
                f"  {OVERLAY}: services.proxy.image is {describe(image)}, must be a `caddy:` image\n"
                f"    the proxy on 80 and 443 is what makes the application's forwarded headers true"
            )
    ports = value_node(app, "ports")
    if not (isinstance(ports, yaml.SequenceNode) and ports.tag == "!override" and not ports.value):
        problems.append(
            f"  {OVERLAY}: services.{SERVICE}.ports is {describe(ports)}, must be "
            f"`!override []`\n    compose merges lists, so anything else leaves the base "
            f"file's published port open while this file has the application believe "
            f"forwarded headers"
        )

    key, expected = OVERLAY_STRATEGY
    environment = value_node(app, "environment")
    if isinstance(environment, yaml.SequenceNode):
        entries = [entry for entry in environment.value
                   if isinstance(entry, yaml.ScalarNode) and entry.value.partition("=")[0] == key]
        node = entries[-1] if entries else None
        actual = node.value.partition("=")[2] if node is not None else None
    else:
        node = value_node(environment, key)
        actual = node.value if isinstance(node, yaml.ScalarNode) else None
    if node is None:
        problems.append(f"  {OVERLAY}: services.{SERVICE}.environment.{key} is absent\n"
                        f"    {OVERLAY_STRATEGY_WHY}")
    elif node.tag != PLAIN_STRING or actual != expected:
        problems.append(
            f"  {OVERLAY}: services.{SERVICE}.environment.{key} is {describe(node)}, must be "
            f"the literal {expected!r}\n    {OVERLAY_STRATEGY_WHY}"
        )

    # The overlay is layered AFTER the base file, so a pin it restates wins, and
    # the checks above read those pins from the base file alone.
    if environment is not None and environment.tag not in (
            "tag:yaml.org,2002:map", "tag:yaml.org,2002:seq"):
        problems.append(
            f"  {OVERLAY}: services.{SERVICE}.environment is tagged {environment.tag}\n"
            f"    `!override` replaces the base file's whole environment, pins included, "
            f"and `!reset` removes it, so this check cannot see what either leaves behind"
        )
    if isinstance(environment, yaml.MappingNode):
        names = [k.value for k, _ in environment.value if isinstance(k, yaml.ScalarNode)]
    elif isinstance(environment, yaml.SequenceNode):
        names = [e.value.partition("=")[0] for e in environment.value if isinstance(e, yaml.ScalarNode)]
    else:
        names = []
    pinned = {spring_name(pin) for pin in REQUIRED_ENV}
    for name, canonical in aliases(names, [key]):
        problems.append(
            f"  {OVERLAY}: services.{SERVICE}.environment.{name} names the same property as "
            f"{canonical}\n    {ALIAS_WHY}"
        )
    for name in dict.fromkeys(n for n in names if spring_name(n) in pinned):
        problems.append(
            f"  {OVERLAY}: services.{SERVICE}.environment.{name} is set\n"
            f"    the overlay is layered after {COMPOSE}, so its value wins over that file's "
            f"pin; the pin belongs in {COMPOSE} alone"
        )
    for name in dict.fromkeys(names):
        if (name != key and name != "<<" and spring_name(name) not in pinned
                and spring_name(name) != spring_name(key)):
            problems.append(
                f"  {OVERLAY}: services.{SERVICE}.environment.{name} is not allowed\n    the overlay's "
                f"app sets {key} and nothing else, so it cannot switch a pin back through a channel "
                f"such as SPRING_APPLICATION_JSON or JAVA_TOOL_OPTIONS"
            )

    for name, why in UNRESOLVABLE_KEYS.items():
        if value_node(app, name) is not None:
            problems.append(f"  {OVERLAY}: services.{SERVICE}.{name} is set\n    {why}, and "
                            f"this check reads the file rather than the resolved stack")
    for name, why in UNRESOLVABLE_TOP_LEVEL_KEYS.items():
        if value_node(root, name) is not None:
            problems.append(f"  {OVERLAY}: top-level `{name}` is set\n    {why}, and this "
                            f"check reads the file rather than the resolved stack")
    for where in merge_keys(root, ""):
        problems.append(
            f"  {OVERLAY}: {where or 'the top level'} uses a YAML merge key (`<<`)\n    it pulls keys in from an "
            f"anchor, which this check sees only as a key named `<<`, so the resolved service can "
            f"differ from what is checked; write the keys out"
        )
    return problems


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
    except yaml.composer.ComposerError as error:
        # ComposerError covers three conditions, and only one of them is a
        # second document -- "found undefined alias" and "found duplicate
        # anchor" raise it too, and compose ACCEPTS the duplicate-anchor file.
        # Naming all three "more than one document" would be a false reason in
        # a gate whose whole point is not to send the reader after the wrong bug.
        # error.context, not str(error): the latter embeds the offending source
        # line, so a file containing this phrase in a comment would be reported
        # as a second document. The discriminator must not be file content.
        if error.context == "expected a single document in the stream":
            print(
                f"FAIL: {COMPOSE} contains more than one YAML document.\n\n"
                f"  compose reads them all; this check reads the first, so a second one can\n"
                f"  add ports or drop a pin where this cannot see it.",
                file=sys.stderr,
            )
        else:
            print(f"FAIL: {COMPOSE} could not be composed: {error}", file=sys.stderr)
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
    for name, canonical in aliases(environment, list(REQUIRED_ENV) + [OVERLAY_STRATEGY[0]]):
        problems.append(
            f"  services.{SERVICE}.environment.{name} names the same property as {canonical}\n"
            f"    {ALIAS_WHY}"
        )
    for key, why in OVERRIDING_SERVICE_KEYS.items():
        if key in service:
            problems.append(
                f"  services.{SERVICE}.{key} is set\n    {why}; a pin could be switched back there "
                f"while its line still reads correctly"
            )
    for name in environment:
        if name in JVM_OPTION_VARIABLES:
            why = "JVM system properties outrank environment variables"
        elif spring_name(name) == spring_name("SPRING_APPLICATION_JSON"):
            why = "SPRING_APPLICATION_JSON properties outrank environment variables"
        else:
            continue
        problems.append(
            f"  services.{SERVICE}.environment.{name} is set\n    {why}, so it could switch a pin "
            f"back while the pinned line still reads correctly"
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

    problems.extend(overlay_problems())

    if problems:
        print("FAIL: the remote-db stack has lost a pin that keeps it safe.\n", file=sys.stderr)
        print("\n\n".join(problems), file=sys.stderr)
        print(
            "\nThese are literals on purpose, so that .env cannot override them.\n"
            "If one genuinely has to change, change it here too and say why.",
            file=sys.stderr,
        )
        return 1

    print(
        f"{COMPOSE} keeps all {len(REQUIRED_ENV)} environment pins and publishes "
        f"only on {LOOPBACK} ({len(hosts)} port(s)); {OVERLAY} keeps "
        f"`ports: !override []` and {OVERLAY_STRATEGY[0]}={OVERLAY_STRATEGY[1]}."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
