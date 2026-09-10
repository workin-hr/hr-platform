#!/usr/bin/env python3
"""Fail when the committed development seed carries anything real.

`deploy/seed/dev-seed.sql` is generated from a production dump by
`scripts/build_dev_seed.sh`, and it is COMMITTED so that `docker compose up`
works from a clone. That combination is the reason this gate exists: a commit
is permanent, so the sanitisation gets exactly one chance to be right, and
"the script looked correct" is not evidence.

Three independent checks, because each catches a different way of being wrong:

1. **Value shapes.** Scan the seed for patterns that only real data produces --
   bcrypt hashes, live-looking Egyptian and Saudi mobile numbers, email
   addresses outside the generated domain, JWT-shaped strings, national ids.
   Catches a column the sanitising SQL forgot.

2. **Column coverage, from the schema.** Re-derive the identity-bearing
   columns from the vendored legacy schema and assert every one of them is
   named in the sanitising SQL. Catches a column that did not exist when the
   sanitiser was written -- the failure mode a value scan cannot see, because
   a column absent from the dump leaves no trace in it.

3. **Sentinels.** The sanitiser writes known markers (a fixed password hash, a
   fixed uploads host). Assert they are PRESENT: if the seed were ever
   replaced by a raw dump, the value scan might pass on a quiet table, but the
   markers would be gone.

Exit 0 clean, 1 on a finding, 2 when the inputs are missing.

Usage:
    python3 scripts/check_dev_seed_sanitised.py
"""
from __future__ import annotations

import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SEED = os.path.join(REPO_ROOT, "deploy", "seed", "dev-seed.sql")
SANITISER = os.path.join(REPO_ROOT, "deploy", "seed", "sanitise.sql")
SCHEMA = os.path.join(
    REPO_ROOT, "backend", "src", "test", "resources", "legacy", "mysql_workin.schema.sql"
)

# The domain and host the sanitiser generates into. Anything outside them in an
# email or URL column is a value the sanitiser did not write.
GENERATED_EMAIL_DOMAIN = "example.invalid"
GENERATED_UPLOAD_HOST = "seed.example.invalid"

# Markers the sanitiser is required to leave behind (check 3).
SENTINEL_PASSWORD_HASH = "$2y$10$devseeddevseeddevseeduYBc87okABDRtGIddmHD7eX.WbjTDHUC"

# ---------------------------------------------------------------------------
# Check 1: value shapes that only real data produces.
# ---------------------------------------------------------------------------

FORBIDDEN_SHAPES: list[tuple[str, re.Pattern[str], str]] = [
    (
        "bcrypt hash",
        # The sentinel is itself bcrypt-shaped, so it is excluded by the
        # negative lookahead rather than by filtering matches afterwards.
        re.compile(r"\$2[aby]\$\d{2}\$(?!devseed)[./A-Za-z0-9]{53}"),
        "a real password hash survived; offline cracking is the risk",
    ),
    (
        "Egyptian mobile number",
        # 010/011/012/015 + 8 digits, with or without a country prefix.
        re.compile(r"(?<!\d)(?:\+?20|0020)?01[0125]\d{8}(?!\d)"),
        "a dialable Egyptian mobile number survived",
    ),
    (
        "Saudi mobile number",
        re.compile(r"(?<!\d)(?:\+?966|00966)?05\d{8}(?!\d)"),
        "a dialable Saudi mobile number survived",
    ),
    (
        "email address",
        re.compile(r"[A-Za-z0-9._%%+-]+@(?!%s)[A-Za-z0-9.-]+\.[A-Za-z]{2,}" % re.escape(GENERATED_EMAIL_DOMAIN)),
        "an email address outside the generated domain survived",
    ),
    (
        "JWT",
        re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"),
        "a token survived",
    ),
    (
        "external upload URL",
        re.compile(r"https?://(?!%s)[A-Za-z0-9.-]+/[^\s'\"]*\.(?:jpg|jpeg|png|pdf|webp)" % re.escape(GENERATED_UPLOAD_HOST)),
        "a URL pointing at real uploaded media survived",
    ),
]

# ---------------------------------------------------------------------------
# Check 2: identity-bearing columns, derived from the schema.
# ---------------------------------------------------------------------------

CREATE_TABLE = re.compile(r"CREATE TABLE `(\w+)` \((.*?)\n\)\s*ENGINE", re.DOTALL)
COLUMN = re.compile(r"(?m)^\s+`(\w+)`\s+([a-z]+)")

# A column is identity-bearing when its NAME says so and its TYPE can hold
# text or a coordinate. The type half is what keeps `job_title_id` and
# `total_entitlements` out: an integer foreign key named "..._id" carries no
# identity of its own, it points at a row this seed also rewrites.
IDENTITY_NAME = re.compile(
    r"^(?:.*_)?(?:name|name_ar|name_en|phone|email|password_hash|token|address|"
    r"national_id|photo_url|logo_url|file_url|image_url|commercial_reg_url|"
    r"reason|rejection_reason|reply|notes|note|exception_note|body|title|"
    r"title_ar|title_en|ip_address|code|latitude|longitude)$"
)
TEXTUAL_TYPES = {"varchar", "text", "longtext", "mediumtext", "tinytext", "char", "decimal"}

# Columns whose name matches but which are structural, with the reason. A
# column here that stops matching IDENTITY_NAME fails the gate, so the list
# cannot outlive its reason.
STRUCTURAL_EXCEPTIONS: dict[str, str] = {
    "banners.title_ar": "marketing copy the platform writes, not a customer's",
    "banners.title_en": "marketing copy the platform writes, not a customer's",
    "faq_categories.name_ar": "the platform's own FAQ taxonomy",
    "faq_categories.name_en": "the platform's own FAQ taxonomy",
    "guide_videos.title_ar": "the platform's own how-to clips",
    "guide_videos.title_en": "the platform's own how-to clips",
    "phone_countries.name_ar": "a dial-code reference table, the same for every deployment",
    "phone_countries.name_en": "a dial-code reference table, the same for every deployment",
    "company_sizes.name": "a fixed lookup list (1-10, 11-50, ...)",
    "company_titles.name": "a fixed lookup list of job titles for the signup form",
    "company_activities.name": "a fixed lookup list of business activities",
    "request_types.name": "seeded request-type names, not customer-authored",
    "companies.country_code": "a dial prefix (+20, +966), not identity; rewriting it would "
                              "desynchronise the phone it pairs with",
    "employees.country_code": "a dial prefix, same reasoning as companies.country_code",
    "phone_countries.country_code": "the dial-code reference table itself",
    "exception_types.name": "seeded attendance-exception names, not customer-authored",
}


def fail(message: str, findings: list[str]) -> None:
    findings.append(message)


def read(path: str) -> str | None:
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def identity_columns(schema: str) -> list[str]:
    found: list[str] = []
    for table_match in CREATE_TABLE.finditer(schema):
        table = table_match.group(1)
        for column, column_type in COLUMN.findall(table_match.group(2)):
            if not IDENTITY_NAME.match(column):
                continue
            if column_type not in TEXTUAL_TYPES:
                continue
            found.append(f"{table}.{column}")
    return found


# The sanitiser writes phone numbers that are FORMAT-VALID -- the application
# validates against phone_countries.phone_prefixes, and a number it rejects
# would break every login flow the seed exists to exercise -- but unmistakably
# synthetic: 010 followed by five zeros. Any other Egyptian-shaped number in
# the seed is a real one the sanitiser missed.
SEED_PHONE_BLOCK = re.compile(r"^(?:\+?20|0020)?010000\d{5}$")


def check_value_shapes(seed: str, findings: list[str]) -> None:
    for label, pattern, why in FORBIDDEN_SHAPES:
        matches = [m for m in pattern.findall(seed) if not SEED_PHONE_BLOCK.match(m)]
        if matches:
            # The count and the label, never the value: this output goes into
            # CI logs, and a gate that prints the PII it found has leaked it.
            fail(
                f"{len(matches)} {label} match(es) in deploy/seed/dev-seed.sql -- {why}. "
                f"Values are deliberately not printed; reproduce locally with "
                f"scripts/check_dev_seed_sanitised.py",
                findings,
            )


# Every human-writable free-text column must be handled too. `assets.asset_text`
# is why: its name says nothing about identity, so the rule above could not ask
# for it, and it held nineteen real mobile numbers because a SIM-card asset
# records the number on it. Free text is where identity hides under a column
# name that does not mention it.
FREE_TEXT_TYPES = {"text", "longtext", "mediumtext", "tinytext"}

PLATFORM_AUTHORED_TEXT: dict[str, str] = {
    "app_content.content_value_ar": "the platform's own app copy",
    "app_content.content_value_en": "the platform's own app copy",
    "banners.description_ar": "the platform's own marketing copy",
    "banners.description_en": "the platform's own marketing copy",
    "faq_items.question_ar": "the platform's own FAQ",
    "faq_items.question_en": "the platform's own FAQ",
    "faq_items.answer_ar": "the platform's own FAQ",
    "faq_items.answer_en": "the platform's own FAQ",
    "configs.config_value": "platform configuration, not customer text",
    "phone_countries.phone_prefixes": "a JSON list of dial prefixes",
    "advances.deduction_installments_json": "generated JSON of amounts and dates, no free text",
}


def free_text_columns(schema: str) -> list[str]:
    found: list[str] = []
    for table_match in CREATE_TABLE.finditer(schema):
        table = table_match.group(1)
        for column, column_type in COLUMN.findall(table_match.group(2)):
            if column_type in FREE_TEXT_TYPES:
                found.append(f"{table}.{column}")
    return found


def check_column_coverage(schema: str, sanitiser: str, findings: list[str]) -> None:
    handled = set()
    for line in sanitiser.splitlines():
        stripped = line.strip()
        if stripped.startswith("-- covers:"):
            handled.update(part.strip() for part in stripped[len("-- covers:"):].split(","))

    for qualified in identity_columns(schema):
        if qualified in handled:
            continue
        if qualified in STRUCTURAL_EXCEPTIONS:
            continue
        fail(
            f"{qualified} is an identity-bearing column in the legacy schema and is neither "
            f"rewritten by deploy/seed/sanitise.sql (declare it with a `-- covers:` line) nor "
            f"listed in STRUCTURAL_EXCEPTIONS with a reason",
            findings,
        )

    for qualified in free_text_columns(schema):
        if qualified in handled:
            continue
        if qualified in PLATFORM_AUTHORED_TEXT:
            continue
        fail(
            f"{qualified} is a free-text column and is neither rewritten by "
            f"deploy/seed/sanitise.sql (declare it with a `-- covers:` line) nor listed in "
            f"PLATFORM_AUTHORED_TEXT with a reason. Free text is where identity hides under a "
            f"column name that does not mention it -- see assets.asset_text",
            findings,
        )


def check_exception_lists_current(schema: str, findings: list[str]) -> None:
    """Self-policing: an exemption that no longer names a live column is a line
    nobody needs, and leaving it invites the next person to add another without
    checking. Separate from coverage because it is a property of the WHOLE
    schema -- asking it about a partial one would report every entry as stale.
    """
    live_text = set(free_text_columns(schema))
    for qualified in PLATFORM_AUTHORED_TEXT:
        if qualified not in live_text:
            fail(
                f"PLATFORM_AUTHORED_TEXT lists {qualified}, which the schema no longer has as a "
                f"free-text column; delete the entry",
                findings,
            )

    live = set(identity_columns(schema))
    for qualified in STRUCTURAL_EXCEPTIONS:
        if qualified not in live:
            fail(
                f"STRUCTURAL_EXCEPTIONS lists {qualified}, which the schema no longer has as an "
                f"identity-bearing column; delete the entry",
                findings,
            )


# The compose files mount dev-seed.sql as the ONLY init script, so the seed has
# to stand alone. It did not once: the Phase 1 tables were in it AND
# phase1_extensions.sql was mounted beside it, MariaDB ran a non-idempotent
# CREATE TABLE twice, and the database never came up. Removing the second mount
# fixed that and created this requirement, so it is checked rather than assumed.
#
# The list is READ FROM Phase1SchemaCheck, not repeated here. A hardcoded copy
# is how this went stale the first time: the check listed six tables, the
# application grew to owning fourteen, and the seed satisfied the gate while
# every stack seeded from it logged `8 of 14 owned tables are MISSING` -- no
# terminal could be registered and every punch a device sent was acknowledged
# and then lost. Deriving it means a fifteenth owned table fails here the day it
# is added, rather than the day someone notices.
PHASE1_SCHEMA_CHECK = "backend/src/main/java/com/workin/backend/config/Phase1SchemaCheck.java"
OWNED_TABLE = re.compile(r'OWNED_TABLES\.put\(\s*"([^"]+)"')


def owned_tables(findings: list[str]) -> tuple[str, ...]:
    source = os.path.join(REPO_ROOT, PHASE1_SCHEMA_CHECK)
    if not os.path.isfile(source):
        fail(
            f"{PHASE1_SCHEMA_CHECK} is missing, so the tables the seed must carry cannot be "
            f"determined. If the class moved, update PHASE1_SCHEMA_CHECK here",
            findings,
        )
        return ()
    with open(source, encoding="utf-8") as handle:
        tables = tuple(OWNED_TABLE.findall(handle.read()))
    if not tables:
        fail(
            f"found no OWNED_TABLES entries in {PHASE1_SCHEMA_CHECK}. The declaration's shape "
            f"changed and this check silently stopped requiring anything",
            findings,
        )
    return tables


def check_seed_is_self_sufficient(seed: str, findings: list[str]) -> None:
    for table in owned_tables(findings):
        if f"CREATE TABLE `{table}`" not in seed:
            fail(
                f"deploy/seed/dev-seed.sql does not create `{table}`, which "
                f"Phase1SchemaCheck owns. It is mounted as the only init script, so a seed "
                f"missing a Phase 1 table leaves that feature dead on every stack seeded "
                f"from it. Rebuild it with scripts/build_dev_seed.sh, which applies "
                f"phase1_extensions.sql before dumping",
                findings,
            )


def check_sentinels(seed: str, findings: list[str]) -> None:
    if SENTINEL_PASSWORD_HASH not in seed:
        fail(
            "the sanitiser's password sentinel is absent from deploy/seed/dev-seed.sql -- the "
            "file is not the sanitiser's output, so nothing above proves anything about it",
            findings,
        )


def main() -> int:
    schema = read(SCHEMA)
    if schema is None:
        print(f"missing {SCHEMA}; the vendored schema is this gate's ground truth", file=sys.stderr)
        return 2

    sanitiser = read(SANITISER)
    if sanitiser is None:
        print(f"missing {SANITISER}", file=sys.stderr)
        return 2

    findings: list[str] = []

    # Coverage is checked even without a seed: it is a property of the
    # sanitiser and the schema, and it is the check most likely to start
    # failing on a day nobody regenerated the seed.
    check_column_coverage(schema, sanitiser, findings)
    check_exception_lists_current(schema, findings)

    seed = read(SEED)
    if seed is None:
        print(
            f"missing {SEED}. Generate it with scripts/build_dev_seed.sh; "
            "column coverage was still checked.",
            file=sys.stderr,
        )
        if findings:
            for finding in findings:
                print(f"FAIL: {finding}", file=sys.stderr)
            return 1
        return 2

    check_value_shapes(seed, findings)
    check_sentinels(seed, findings)
    check_seed_is_self_sufficient(seed, findings)

    if findings:
        for finding in findings:
            print(f"FAIL: {finding}", file=sys.stderr)
        return 1

    print(
        f"dev seed is sanitised: {len(FORBIDDEN_SHAPES)} value shapes clean, "
        f"every identity-bearing column covered, sentinels present."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
