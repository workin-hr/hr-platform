#!/usr/bin/env python3
"""Prove that no legacy column is silently dropped on the way to PostgreSQL.

Every gap found so far was found by accident, while looking at something
adjacent: `created_at` dropped by 14 load blocks, the `advances`
scheduling columns never extracted, `branches.qr_code` with a target
column and no export. That is not a method, and at cutover the cost of
missing one is a value nobody can get back.

This is the method. It reads the three artifacts that actually decide
what survives -- the legacy schema, `export_legacy.py`'s extraction, and
`load_postgres.py`'s staging and INSERT column lists -- and reports every
legacy column that does not reach the target, in three detection classes:

    UNEXTRACTED_TABLE   a legacy table with no SELECT at all
    UNEXTRACTED_COLUMN  a column absent from its table's SELECT
    UNLOADED_COLUMN     staged, then left out of the INSERT column list
                        -- the exact shape of the created_at defect

A fourth check, `check_export_staging_agreement`, sits beside these three
rather than inside `find_gaps`: it does not ask "does a legacy column
reach the target" (a data-loss question, answered by the ACCEPTED/
SCHEDULED/PENDING ledger below), it asks "would `\\copy` even accept this
file" (a load-mechanics question with no ledger entry to owe, because it
is never something to decide to accept -- it is only ever a bug). This is
Fix B: `export_legacy.py` used `SELECT *` on four tables while
`load_postgres.py`'s STAGING declared far fewer columns for two of them,
and `\\copy` matches a CSV to `migration.stg_<table>` positionally, so the
mismatch surfaces at load time as "extra data after last expected
column" -- a live-database-only failure until this check existed.
`check_export_staging_agreement` reads the same three artifacts as
`find_gaps` (reusing `parse_legacy_schema` to resolve what a `SELECT *`
actually expands to, since that is unknowable from EXPORT_SQL's text
alone) and fails loudly the moment EXPORT_SQL and STAGING disagree, in
length, order, or content, for any table -- not only the four Fix B
happened to hit.

The point is not the report. The point is `--check`: every gap must be
registered in exactly one of three states. A gap in none of them fails
the build. So a column can still be dropped -- deliberately, in writing
-- but it can no longer be dropped quietly.

    ACCEPTED   decided not to migrate. The value is deliberately lost,
               and the reason is the entry -- without one it is
               indistinguishable from an oversight.
    SCHEDULED  decided to migrate, not migrated yet. Names the decision
               that settled it (D-032 and the like), enforced by
               --self-test, because an entry that cannot cite a decision
               is a PENDING note wearing a different label.
    PENDING    nobody has decided. The note says what must be resolved;
               'TODO' is not a note.

SCHEDULED exists because deciding to migrate does not close a gap. The
value still is not in the target, so `--check` must keep failing on it,
while the ledger stops describing a settled question as open. Collapsing
it into either neighbour loses something real: ACCEPTED would assert the
value is deliberately gone, PENDING would keep asking a question that has
an answer.

Two failure modes beyond an unregistered gap:

- A **stale** entry -- an accepted drop since implemented, or a gap since
  closed -- is a lie in the ledger, and a ledger nobody trusts is worse
  than none.
- A **conflicting** entry, registered in more than one state, asserts two
  contradictory things at once. Because `check` unions the registries the
  contradiction otherwise registers as fine: `salary_contracts.total` sat
  in both ACCEPTED and PENDING undetected until this check was added.

Stdlib only, no database connection, same rule as the rest of
`scripts/etl/` -- it parses source, it does not query anything.

Usage:
    python3 scripts/etl/coverage_audit.py --report
    python3 scripts/etl/coverage_audit.py --check
    python3 scripts/etl/coverage_audit.py --self-test

`--report` and `--check` need the legacy schema, which lives outside this
repository; pass `--schema PATH` or keep the default sibling checkout.
`--self-test` needs nothing -- it runs every detection class against
synthetic fixtures, so CI verifies the detector itself without requiring
the legacy dump to be present.
"""

from __future__ import annotations

import os
import re
import sys

DEFAULT_SCHEMA = os.path.join("..", "hr-legacy", "mysql_workin.schema.sql")
MIGRATION_DIR = os.path.join("backend", "src", "main", "resources", "db", "migration")
EXPORT_SCRIPT = os.path.join("scripts", "etl", "export_legacy.py")
LOAD_SCRIPT = os.path.join("scripts", "etl", "load_postgres.py")

# Legacy table -> target table, for the only names that diverge. Every
# other table keeps its name; a new divergence belongs here, not in a
# special case somewhere in the load.
TABLE_MAP = {"leave_balance": "leave_balances"}

# EXPORT_SQL's FROM-clause table name -> the STAGING key that same
# statement's rows land in. Every other STAGING key equals its EXPORT_SQL
# table name; company_settings is the one exception, for the same reason
# TABLE_MAP exists on the target side: `company_settings` is legacy's EAV
# join table, staged as legacy_company_settings (STAGING key,
# load_postgres.py:147) so it is never confused with the *target*
# company_settings -- the typed table the EAV pivots into. Used only by
# check_export_staging_agreement below; parse_exported()/find_gaps() above
# predate this constant and do not need it (TRANSFORM_SOURCES already
# skips company_settings there).
EXPORT_TABLE_TO_STAGING_KEY = {"company_settings": "legacy_company_settings"}

# Legacy tables that are inputs to a transform rather than row copies.
# Their columns are consumed by the transform, not mapped one-to-one, so
# per-column coverage is meaningless for them.
TRANSFORM_SOURCES = {
    "hr_permissions",
    "company_settings",
    "company_setting_values",
    "setting_definitions",
    "setting_allowed_values",
}

# --------------------------------------------------------------------
# The ledger. Every known gap lives in exactly one of these.
# --------------------------------------------------------------------

# Decided, in writing, not to migrate. The reason is the point -- an
# entry without one is indistinguishable from an oversight.
ACCEPTED: dict[str, str] = {
    "salary_contracts.total": (
        "GENERATED ALWAYS AS STORED on both sides; PostgreSQL rejects an "
        "INSERT naming it. Exported for cross-engine comparison, never loaded."
    ),
    "employees.password_hash": (
        "Deliberately not written to employees: credentials live on identities "
        "(load_postgres.py's identity transform consumes this column). The target "
        "employees.password_hash column is vestigial and is its own question."
    ),
    "leave_balance.remaining_days": (
        "GENERATED ALWAYS AS (total_days - used_days) STORED on both sides; "
        "PostgreSQL rejects an INSERT naming it. Exported from both sides so "
        "migration_diff proves the two engines compute it identically, and "
        "asserted in EtlLoadFixtureTest -- carried by computation, not by copy."
    ),
    "employees.token_version": (
        "D-032 A7: dropped. A legacy JWT-invalidation counter, replaced by the "
        "refresh-token/session revocation model (F-26). Nothing in the target "
        "reads a per-user token generation, so carrying it would migrate a "
        "number with no consumer."
    ),
    "configs.id": (
        "D-032 A8: dropped. The generic legacy configs table is not reproduced; "
        "keys proven required by business behaviour become typed platform "
        "configuration instead, so the surrogate row id has nothing to identify."
    ),
    "employees.is_active": (
        "Found 2026-08-13 by the UNTARGETED_COLUMN detection class (see "
        "PENDING's header comment): not a drop, a rename. The value is "
        "carried -- load_postgres.py's employees INSERT computes "
        "`COALESCE(s.is_active, '1') = '1'` into the target `active` "
        "boolean column -- but the column name itself changed, so it has "
        "no target column under its legacy name. No information loss: "
        "legacy's is_active is already effectively boolean."
    ),
    "requests.approver_id": (
        "Found 2026-08-13, same detection class as employees.is_active "
        "above. Not a drop: load_postgres.py's requests INSERT resolves "
        "`s.approver_id` (a legacy employee id) through the "
        "tenant_memberships id map into the target `approver_membership_id` "
        "column -- the role-split architecture (ADR-0009/ADR-0010) "
        "represents 'who approved this' as a membership, not a bare "
        "employee id, so the rename is the point, not an oversight."
    ),
}

# Legacy tables with no extraction at all. Registered as whole tables
# because per-column entries would say the same thing 8-25 times.
ACCEPTED_TABLES: dict[str, str] = {
    "push_tokens": (
        "D-032 A2: dropped. F-08 confirms push never worked end-to-end in "
        "legacy, so these are dead FCM registrations -- migrating them would "
        "carry tokens that were never able to deliver anything."
    ),
    "otp_codes": (
        "D-032 A2: dropped. Short-lived verification codes with no value once "
        "expired; no historical or compliance use was claimed for them."
    ),
    "phone_countries": (
        "D-032 A2: legacy rows and ids deliberately not carried. The new "
        "platform seeds its own canonical reference dataset, so nothing "
        "downstream may depend on a legacy phone_countries id."
    ),
}

# Decided to migrate, not yet migrated. Distinct from ACCEPTED (which
# means the value is deliberately lost) and from PENDING (which means
# nobody has decided yet). These are settled questions owed an
# implementation, so --check must keep failing on them while no longer
# describing them as open. Every entry names the decision that settled
# it -- an entry without one is just a PENDING note in disguise.
SCHEDULED: dict[str, str] = {
    # A1 -- companies do not authenticate as principals; their logins become
    # COMPANY_ADMIN identities. Hashes are bcrypt (password_hash($x,
    # PASSWORD_BCRYPT)) and port to BCryptPasswordEncoder unchanged.
    "companies.password_hash": (
        "D-032 A1: becomes the credential of a minted COMPANY_ADMIN identity "
        "rather than a company principal. Blocked on OQ-1 -- identities.phone "
        "and companies.phone are both UNIQUE, so a company whose login phone "
        "belongs to one of its own employees must reuse that identity and be "
        "granted the role, never get a duplicate."
    ),
    "companies.email": (
        "D-032 A1: preserved to establish the COMPANY_ADMIN identity. Needs a "
        "target column; identities currently carries phone only."
    ),
    "companies.otp_verified": (
        "D-032 A1: onboarding state needed to establish the admin identity. "
        "Which subset of onboarding state the target keeps is an implementation "
        "detail of the identity-minting slice."
    ),
    "companies.profile_completed": (
        "D-032 A1: onboarding state, same slice as otp_verified."
    ),
    # A3 -- companies schema expansion. Target has 5 columns today.
    "companies.company_code": (
        "D-032 A3: preserve exactly, never renumber. It is the legacy/external "
        "tenant identifier, so a generated replacement would break every "
        "outside reference to a tenant."
    ),
    "companies.first_name": (
        "D-032 A3: preserved as the company contact and used when creating the "
        "initial COMPANY_ADMIN identity. Explicitly does NOT create an employee "
        "row for that person."
    ),
    "companies.last_name": "D-032 A3: company contact surname; same slice as first_name.",
    "companies.commercial_reg_url": (
        "D-032 A3: preserve the registration document including the underlying "
        "file. Blocked on the file-migration work stream, which does not exist "
        "-- every artifact in scripts/etl/ emits SQL and moves no bytes."
    ),
    "companies.logo_url": (
        "D-032 A3: preserve the asset itself; tenants must not re-upload "
        "branding. Blocked on the same file-migration work stream."
    ),
    "companies.rejection_reason": (
        "D-032 A3: preserved together with the associated approval/rejection "
        "state, so existing workflow history is not lost."
    ),
    "companies.main_branch_address": (
        "D-032 A3: folds into the main branch as a fallback when "
        "branches.address is missing, then company-level address data stops "
        "being maintained separately."
    ),
    "companies.country_code": (
        "D-032 A3: droppable only AFTER phones normalize to E.164 successfully. "
        "Per OQ-2 a phone that cannot be normalized keeps its original value and "
        "is marked migration-invalid, so this column cannot be dropped "
        "unconditionally and stays scheduled rather than accepted."
    ),
    "companies.company_activity_id": (
        "D-032 A3: mapped by semantic value/code into a seeded lookup, not by "
        "legacy numeric id."
    ),
    "companies.company_title_id": (
        "D-032 A3: mapped by value into a seeded lookup. company_titles' exact "
        "semantics are to be confirmed during implementation."
    ),
    "companies.company_size_id": (
        "D-032 A3: mapped by value into a seeded lookup, not by legacy id."
    ),
    # A5 -- updated_at across all mutable business entities.
    "companies.updated_at": (
        "D-032 A5: updated_at lands on all mutable business entities under one "
        "auditing model, database-enforced per OQ-3 so ETL, administrative "
        "scripts and the application share identical semantics. 3 of 40 "
        "migrations carry it today."
    ),
    "exception_types.updated_at": (
        "D-032 A5: same platform-wide auditing rollout as companies.updated_at."
    ),
    # A4 -- advances scheduling.
    "advances.deduction_type": (
        "D-032 A4: semantics preserved. If validation against real rows proves "
        "it 100% equivalent to deduction_mode, a migration assertion enforces "
        "that instead of a duplicate column; any differing value is mapped "
        "explicitly. Blocked on a data dump -- only a schema dump exists."
    ),
    "advances.deduction_installments_json": (
        "D-032 A4: normalized into advance_installment (or equivalent) schedule "
        "records. It is the only record of what repayment was actually agreed."
    ),
    # A6 -- deactivation must survive.
    "exception_types.is_active": (
        "D-032 A6: the target must carry is_active or an equivalent status, and "
        "the read paths that list exception types must filter on it -- otherwise "
        "the column lands and an inactive type still reappears in selectors."
    ),
    # A7 -- employee columns.
    "employees.address": (
        "D-032 A7: preserved as employee profile data and treated as PII. Per "
        "OQ-4 it is readable only by the employee themself, COMPANY_ADMIN, "
        "authorized HR roles and SUPER_ADMIN, enforced at the service/API layer "
        "and excluded from general employee list responses."
    ),
    "employees.photo_url": (
        "D-032 A7: preserve both the reference and the underlying image. "
        "Blocked on the file-migration work stream."
    ),
    "employees.contract_duration_months": (
        "D-032 A7: not dropped until fixed-term contract semantics exist in the "
        "target; the preference is normalizing it into explicit contract dates "
        "or equivalent terms rather than carrying a bare month count."
    ),
    # D-034 -- the three columns the 2026-08-12 brief omitted from Q7.
    "employees.is_mobile_attendance_enabled": (
        "D-034: preserve and migrate. Mobile-attendance eligibility is an "
        "employee-level business rule and must remain unchanged after cutover, "
        "so a migrated employee keeps exactly the phone check-in eligibility "
        "they had in legacy."
    ),
    "employees.can_check_in_any_branch": (
        "D-034: preserve and migrate. An employee-level attendance permission "
        "that must retain its legacy behaviour -- dropping it would silently "
        "widen or narrow where someone may check in."
    ),
    "employees.join_request_status": (
        "D-034: preserve and migrate even though the join-request workflow is "
        "not built. Legacy values map into an explicit onboarding/join status "
        "in the target so existing employee state is not lost; the future "
        "workflow builds on that state rather than reconstructing it."
    ),
    # D-035 -- Q1 of the 2026-08-13 real-data findings brief.
    "companies.status": (
        "D-035 A1: preserve all four legacy lifecycle states "
        "(active/pending/rejected/suspended) in an explicit target status "
        "column -- not the boolean `active` load_postgres.py currently "
        "computes via `COALESCE(s.status, 'active') = 'active'`, which "
        "collapses pending/rejected/suspended into a single indistinguishable "
        "state. A pending company with no name is a valid lifecycle state, "
        "not a migration-invalid one; name stays nullable at the data layer, "
        "with a non-null name required only at activation (application-layer "
        "rule, not yet implemented)."
    ),
    # D-036's six employees business fields are NOT listed here any more.
    # SCHEDULED means "decided, implementation owed"; V42 gave all six a
    # target column and the 2026-08-16 load populates them, so they are
    # no longer gaps and a SCHEDULED entry would be a stale claim that
    # work is still owed. `--check` says so itself, by design:
    # "STALE REGISTRY ENTRY ... no longer a gap; remove it".
    #
    # The decisions they recorded are not lost -- each is restated where
    # it is now enforced: load_postgres.py's employees INSERT comments
    # the mapping per field, its --self-test asserts each one, and
    # EtlLoadFixtureTest proves them against real Postgres.
    #
    # Note only employee_code ever reached "no longer a gap" on its own.
    # The other five stayed misclassified as UNTARGETED_COLUMN ("no
    # target column exists") even after V42 added them, because
    # parse_target_schema read one ADD COLUMN per ALTER TABLE; fixed in
    # the same change that removed these entries.
    #
    # D-036 -- the four updated_at columns were NOT a new decision; the
    # repository owner directed these be moved citing D-033/OQ-3 directly.
    "employees.updated_at": (
        "D-033 (OQ-3): updated_at must be database-enforced across every "
        "mutable business entity. D-036 directs this cited as an OQ-3 "
        "consequence, not a fresh decision -- employees needs this column "
        "for the same reason every other mutable entity does."
    ),
    "attendance.updated_at": (
        "D-033 (OQ-3): same as employees.updated_at above. No target "
        "column exists yet, confirmed against V21__create_attendance.sql "
        "directly."
    ),
    "advances.updated_at": (
        "D-033 (OQ-3): same as employees.updated_at above."
    ),
    "requests.updated_at": (
        "D-033 (OQ-3): same as employees.updated_at above. "
        "V25__create_requests_and_leave_balances.sql's own comment already "
        "noted 'no updated_at' as a known omission when it was written."
    ),
}

SCHEDULED_TABLES: dict[str, str] = {
    "notifications": (
        "D-032 A2: migrate all notification history. 4,014 rows. Needs a "
        "tenant-scoped target module, not an archive table."
    ),
    "complaints": (
        "D-032 A2: migrate all complaint/support history with its tenant "
        "ownership and business state intact."
    ),
    "assets": "D-032 A2: migrate all company asset records issued to employees.",
    "administrative_decisions": (
        "D-032 A2: migrate all records and history of company announcements."
    ),
    "workforce_planning": "D-032 A2: migrate all headcount-target records.",
    "employee_docs": (
        "D-032 A2: migrate the metadata AND the actual files from uploads/ into "
        "platform-controlled storage. The file half has no mechanism today."
    ),
    "banners": "D-032 A2: migrate all existing legacy content.",
    "app_content": "D-032 A2: migrate all existing legacy content.",
    "faq_categories": "D-032 A2: migrate all existing legacy content.",
    "faq_items": "D-032 A2: migrate all existing legacy content.",
    "company_activities": (
        "D-032 A2: preserve the business values and map them to seeded/"
        "normalized lookup values. Legacy numeric ids are deliberately not "
        "depended on."
    ),
    "company_sizes": (
        "D-032 A2: preserve business values, map to seeded lookups, do not "
        "depend on legacy ids."
    ),
    "company_titles": (
        "D-032 A2: preserve business values and map to seeded lookups. The exact "
        "semantics of this table are to be confirmed during implementation."
    ),
}

PENDING_TABLES: dict[str, str] = {}

# Known, undecided, and owed an answer. The note names what has to be
# resolved -- 'TODO' is not a note.
PENDING: dict[str, str] = {
    # Empty as of D-036 (2026-08-13). The UNTARGETED_COLUMN detection
    # class (find_gaps() above) landed 11 entries here the same day it
    # was added -- 7 on employees, attendance/advances/requests.updated_at,
    # and companies.status. All 11 are now answered: companies.status by
    # D-035 (Q1), the other 10 by D-036. See
    # docs/migration/2026-08-13-etl-real-data-findings-decision-brief.md
    # for the full history. Empty means nothing is owed an answer today,
    # not that nothing ever will be -- a new legacy column, or a new
    # detection class, lands here first and --check fails until somebody
    # decides.
}


# --------------------------------------------------------------------
# Parsing
# --------------------------------------------------------------------


def parse_legacy_schema(text: str) -> dict[str, list[str]]:
    """MySQL dump DDL -> {table: [column, ...]}."""
    out: dict[str, list[str]] = {}
    for m in re.finditer(r"CREATE TABLE `(\w+)` \((.*?)\n\)", text, re.S):
        out[m.group(1)] = re.findall(r"^\s*`(\w+)`", m.group(2), re.M)
    return out


def parse_target_schema(sql_by_version: list[str]) -> dict[str, list[str]]:
    """Flyway migrations, in version order -> {table: [column, ...]}.

    Reads ALTER TABLE ... ADD COLUMN as well as CREATE TABLE: a target
    column added by a later migration is still a target column, and
    reading only CREATE TABLE understates the schema (V27 added
    employees.expected_daily_hours exactly this way).

    One ALTER TABLE may add several columns, so the whole statement is
    matched and every ADD COLUMN inside it read, rather than anchoring
    each column to its own `ALTER TABLE` (only the first clause carries
    that prefix). Anchoring is what the code did until 2026-08-16, and
    it silently understated V42 by five of its six columns -- which
    understates the target schema, the exact direction that makes this
    auditor miss a gap rather than invent one.
    """
    out: dict[str, list[str]] = {}
    reserved = {"CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK"}
    for sql in sql_by_version:
        for m in re.finditer(r"CREATE TABLE (?:IF NOT EXISTS )?(\w+)\s*\((.*?)\n\);", sql, re.S):
            cols = re.findall(r"^\s+(\w+)\s+[A-Z]", m.group(2), re.M)
            out[m.group(1)] = [c for c in cols if c.upper() not in reserved]
        for m in re.finditer(r"ALTER TABLE (\w+)(.*?);", sql, re.S | re.I):
            for column in re.findall(
                r"ADD COLUMN (?:IF NOT EXISTS )?(\w+)", m.group(2), re.I
            ):
                out.setdefault(m.group(1), []).append(column)
    return out


def parse_exported(export_sql: str) -> dict[str, object]:
    """EXPORT_SQL -> {table: set(columns) or the string 'ALL'}.

    Statement-scoped: the SELECT list is only searched inside the
    statement that reads that table, so a column name mentioned in an
    unrelated query cannot mark it covered.
    """
    out: dict[str, object] = {}
    for stmt in export_sql.split(";"):
        for table in re.findall(r"\bFROM\s+(\w+)\b", stmt):
            if re.search(r"SELECT\s+\*\s+FROM\s+" + table + r"\b", stmt):
                out[table] = "ALL"
                continue
            found = out.setdefault(table, set())
            if found == "ALL":
                continue
            for word in re.findall(r"\b(\w+)\b", stmt):
                found.add(word)
    return out


def parse_permission_map(load_src: str) -> list[str]:
    """PERMISSION_MAP's keys, in the file's own definition order -- the
    same order hr-legacy's own hr_permissions columns are in
    (load_postgres.py:41-45's own comment on PERMISSION_MAP says so
    explicitly), and therefore the order STAGING's hr_permissions entry
    expands to via `+ list(PERMISSION_MAP) +` -- see parse_staging below.

    Returns [] if PERMISSION_MAP is absent (self_test's synthetic
    fixtures below have no reason to define it, and only hr_permissions'
    STAGING entry ever references it).
    """
    if "PERMISSION_MAP = {" not in load_src:
        return []
    block = load_src.split("PERMISSION_MAP = {", 1)[1].split("\n}", 1)[0]
    return re.findall(r'"(\w+)":', block)


def parse_staging(load_src: str) -> dict[str, list[str]]:
    """The STAGING dict -> {legacy table: [staged column, ...]}.

    Every entry but one is a plain list literal, handled by the original,
    simpler version of this function: `"(\\w+)":\\s*(\\[[^\\]]*\\])`. The
    exception is hr_permissions -- `["id", "employee_id"] +
    list(PERMISSION_MAP) + ["updated_at"]` (load_postgres.py:146) -- which
    that simpler regex would silently truncate to the first bracket's 2
    columns instead of the real 20. That blind spot was harmless as long
    as nothing compared STAGING's column *count* for a TRANSFORM_SOURCES
    table (find_gaps skips those entirely), but check_export_staging_
    agreement does exactly that for every STAGING table, and immediately
    reported a false "hr_permissions: STAGING declares ['id',
    'employee_id']" mismatch the first time it ran for real (2026-08-15)
    -- not a real EXPORT_SQL/STAGING drift, this parser's own blind spot.
    So this version walks each entry's full source chunk in order,
    resolving both `[...]` literals and `list(PERMISSION_MAP)` references
    positionally -- order matters here exactly as much as it does for
    \\copy itself, since hr_permissions' `updated_at` must land last.
    """
    permission_keys = parse_permission_map(load_src)
    block = load_src.split("STAGING = {", 1)[1].split("\n}", 1)[0]
    # One chunk per entry: a lookahead split on each 4-space-indented
    # `"key":` line keeps a multi-line list literal (e.g. "employees":
    # [...] spanning several lines) attached to its own key rather than
    # bleeding into the next entry.
    chunks = re.split(r'(?=^    "\w+":)', block, flags=re.M)
    out: dict[str, list[str]] = {}
    for chunk in chunks:
        key_match = re.match(r'\s*"(\w+)":', chunk)
        if not key_match:
            continue
        cols: list[str] = []
        for piece in re.finditer(r"\[[^\]]*\]|list\(PERMISSION_MAP\)", chunk):
            if piece.group(0) == "list(PERMISSION_MAP)":
                cols.extend(permission_keys)
            else:
                cols.extend(re.findall(r'"(\w+)"', piece.group(0)))
        out[key_match.group(1)] = cols
    return out


# Statements parse_export_select_columns must not treat as a table's row
# copy: export_legacy.py's own _classify_statement (export_legacy.py:406-
# 434) recognizes these same three by the same markers and routes them to
# "REPORT" instead of "TABLE". This module intentionally does not import
# export_legacy.py (see load_inputs' docstring-equivalent comment below --
# every scripts/etl/ file parses its neighbours as text, not as a module,
# same reasoning NULL_MARKER duplicates rather than imports in
# export_legacy.py itself), so the three markers are duplicated here
# rather than shared. Getting this wrong is not cosmetic: the
# created_at_quality probe is one statement with a dozen `FROM <table>`
# clauses inside a UNION ALL, and without this filter
# parse_export_select_columns would misread its first FROM table as a
# giant, garbled "column list" spanning the whole UNION.
_REPORT_MARKERS = ("HAVING COUNT(*) > 1", "CREATED_AT_QUALITY", "IS_DAYLIGHT_SAVING")


def _split_select_columns(collist: str) -> list[str]:
    """A SELECT clause's column list -> the individual column expressions.

    Splits on top-level commas only, tracking paren depth, because
    `DATE_FORMAT(check_in, '%Y-%m-%d %H:%i:%s') AS check_in`
    (export_legacy.py's attendance/branches SELECTs) contains a comma that
    is not a column separator. A plain `.split(',')` would cut that
    expression in half and misreport a mismatch against STAGING that does
    not exist.
    """
    columns: list[str] = []
    depth = 0
    current = ""
    for ch in collist:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            columns.append(current)
            current = ""
        else:
            current += ch
    columns.append(current)
    return [c for c in columns if c.strip()]


def _resolved_column_name(expr: str) -> str:
    """One SELECT-list expression -> the name it lands under in the CSV
    (and therefore the name \\copy matches, positionally, against STAGING).

    `company_name AS name` -> "name" (cursor.description / the CSV header
    uses the alias, not the source column -- export_legacy.py's own
    self-test pins this down: "companies is selected by its real column
    name, not the target's", export_legacy.py:668-671). A bare column with
    no alias resolves to itself.
    """
    expr = expr.strip()
    alias = re.search(r"\bAS\s+(\w+)\s*$", expr, re.I)
    if alias:
        return alias.group(1)
    return expr.split(".")[-1].strip()


def parse_export_select_columns(export_sql: str, legacy: dict[str, list[str]]) -> dict[str, list[str]]:
    """EXPORT_SQL -> {STAGING-key table: [column, ...]}, in SELECT order,
    exactly as \\copy will read them off the CSV.

    This is deliberately NOT parse_exported() above: that function returns
    an unordered, statement-wide word bag (deliberately tolerant, for
    find_gaps' "is this legacy column mentioned anywhere" purpose) or the
    sentinel "ALL" for `SELECT *`. Neither is precise enough to catch Fix
    B: \\copy matches migration.stg_<table> to the CSV positionally, not by
    header name (load_postgres.py:141-146's hr_permissions comment says so
    explicitly), so an exact, ORDERED column list is the only thing
    capable of proving EXPORT_SQL and STAGING agree -- a same-length,
    different-order pair is just as broken as a different-length one, and
    a word-bag comparison would call it fine.

    `SELECT *` is resolved against `legacy` (parse_legacy_schema's output)
    -- the only way to know what `*` expands to without a live database,
    and the reason this function takes the legacy schema as an argument at
    all. That resolution is also what makes this check catch a
    *regression* back to SELECT * on some future table, not just today's
    four already-fixed ones.

    Comment lines are stripped BEFORE splitting on ';', exactly as
    export_legacy.py's own _split_statements does and for the identical
    reason (export_legacy.py:386-403): several of EXPORT_SQL's comments
    read as ordinary English ("...redirect each SELECT to the matching
    file...", "...exported FROM the dump itself...") and would otherwise
    risk being mistaken for SQL keywords by the regexes below, or a
    literal ';' inside a comment splitting one statement into two.
    parse_exported() above skips this step because it only needs a
    tolerant word bag; this function needs the real statement boundary.
    """
    sql_only = "\n".join(
        line for line in export_sql.splitlines() if not line.strip().startswith("--")
    )
    out: dict[str, list[str]] = {}
    for stmt in sql_only.split(";"):
        stmt = stmt.strip()
        if not stmt:
            continue
        upper = stmt.upper()
        if upper.startswith("SET"):
            continue
        if any(marker in upper for marker in _REPORT_MARKERS):
            continue
        table_match = re.search(r"\bFROM\s+(\w+)\b", stmt, re.I)
        if not table_match:
            continue
        table = table_match.group(1)
        select_match = re.search(
            r"SELECT\s+(.*?)\s+FROM\s+" + re.escape(table) + r"\b", stmt, re.I | re.S
        )
        if not select_match:
            continue
        collist = select_match.group(1).strip()
        if collist == "*":
            resolved = list(legacy.get(table, []))
        else:
            resolved = [_resolved_column_name(c) for c in _split_select_columns(collist)]
        # attendance is exported twice (the row copy, and its own
        # DATE(check_in) bucketing for conformance test 3) -- both `FROM
        # attendance`, same as _classify_statement distinguishes them
        # (export_legacy.py:441-446). STAGING keys the bucketing query
        # under "attendance_days", not "attendance", so this must too or
        # the second statement silently overwrites the first in `out`.
        key = "attendance_days" if table == "attendance" and "legacy_day" in [c.lower() for c in resolved] else table
        key = EXPORT_TABLE_TO_STAGING_KEY.get(key, key)
        out[key] = resolved
    return out


def check_export_staging_agreement(
    exported_cols: dict[str, list[str]], staging: dict[str, list[str]]
) -> list[str]:
    """-> one message per table where EXPORT_SQL's SELECT and STAGING's
    declared columns disagree, in either length, order, or content.

    This is Fix B, generalized into a standing check: before it,
    setting_definitions selected 11 legacy columns (SELECT *) while
    STAGING declared 2, and setting_allowed_values selected 7 while
    STAGING declared 4 -- a live `\\copy` would reject both with "extra
    data after last expected column", but nothing here needs a live
    database to say the same thing. Every table STAGING declares is
    checked, not only the four transform sources Fix B happened to hit:
    the positional-match hazard this guards against applies to every
    \\copy in load_postgres.py's `_copy()`, row-copy tables included.
    """
    problems: list[str] = []
    for table in sorted(staging):
        if table not in exported_cols:
            continue
        expected = staging[table]
        actual = exported_cols[table]
        if actual != expected:
            problems.append(
                f"{table}: EXPORT_SQL selects {actual!r} but STAGING declares "
                f"{expected!r} -- \\copy matches migration.stg_{table} to the CSV "
                f"positionally, so these must be identical, in the same order"
            )
    return problems


def parse_inserted(load_src: str) -> dict[str, set[str]]:
    """Columns the load actually writes -> {target table: {column, ...}}.

    Both INSERT column lists and `UPDATE <table> SET <column>` are
    counted. The UPDATE half is not a nicety: `departments.manager_id`
    is deliberately inserted NULL and backfilled afterwards, because
    departments -> employees -> departments is a real cycle. Reading
    only INSERT lists reports that correct, deliberate design as a
    dropped column, and a detector that cries wolf on the load's most
    carefully-reasoned step is one nobody will keep running.
    """
    out: dict[str, set[str]] = {}
    for m in re.finditer(r"INSERT INTO (\w+) \(([^)]*)\)", load_src):
        if m.group(1).startswith("migration"):
            continue
        cols = {c.strip() for c in m.group(2).replace("\n", " ").split(",")}
        out.setdefault(m.group(1), set()).update(c for c in cols if c)
    for m in re.finditer(r"UPDATE (\w+)[^;]*?\bSET\s+(\w+)\s*=", load_src, re.S):
        if m.group(1).startswith("migration"):
            continue
        out.setdefault(m.group(1), set()).add(m.group(2))
    return out


# --------------------------------------------------------------------
# Detection
# --------------------------------------------------------------------


def find_gaps(legacy, target, exported, staging, inserted) -> list[tuple[str, str, str]]:
    """-> [(kind, key, detail)], sorted, one entry per gap."""
    gaps: list[tuple[str, str, str]] = []
    for table in sorted(legacy):
        if table in TRANSFORM_SOURCES:
            continue
        target_table = TABLE_MAP.get(table, table)
        if table not in exported:
            gaps.append(("UNEXTRACTED_TABLE", table, f"{len(legacy[table])} columns, no SELECT"))
            continue
        cols = exported[table]
        for column in legacy[table]:
            if cols != "ALL" and column not in cols:
                has_target = column in target.get(target_table, [])
                gaps.append((
                    "UNEXTRACTED_COLUMN",
                    f"{table}.{column}",
                    "target column exists" if has_target else "no target column",
                ))
            elif (
                column in staging.get(table, [])
                and column not in target.get(target_table, [])
            ):
                # A column that IS selected and IS staged, but has no target
                # column at all, is invisible to both neighboring branches:
                # the first only fires when the column is unextracted, and
                # the third (below) requires a target column to exist. Found
                # 2026-08-13 running the real load: employees.hire_date and
                # six siblings fell through here silently -- selected,
                # staged, never inserted, registered nowhere, and
                # `--check` still reported "0 pending" because nothing
                # detected them as a gap in the first place.
                gaps.append((
                    "UNTARGETED_COLUMN",
                    f"{table}.{column}",
                    "selected and staged, but no target column exists",
                ))
            elif (
                column in staging.get(table, [])
                and target_table in inserted
                and column in target.get(target_table, [])
                and column not in inserted[target_table]
            ):
                gaps.append((
                    "UNLOADED_COLUMN",
                    f"{table}.{column}",
                    "staged and has a target column, but absent from the INSERT list",
                ))
    return sorted(gaps)


def _conflicting(*registries: dict[str, str]) -> list[str]:
    """Keys appearing in more than one registry.

    The three states are mutually exclusive claims: dropped, owed a
    decision, or decided-and-owed-an-implementation. A key in two of them
    asserts two contradictory things, and because `check` unions the
    registries the contradiction registers as fine -- which is how
    `salary_contracts.total` sat in both ACCEPTED and PENDING unnoticed.
    """
    seen: dict[str, int] = {}
    for registry in registries:
        for key in registry:
            seen[key] = seen.get(key, 0) + 1
    return sorted(k for k, n in seen.items() if n > 1)


def check(gaps) -> tuple[list[str], list[str], list[str]]:
    """-> (unregistered, stale, conflicting). All three must be empty for
    `--check` to pass.

    Table-level gaps are matched against the table registries, column
    gaps against the column ones, so a whole unextracted table does not
    need one entry per column saying the same thing.
    """
    registered_cols = set(ACCEPTED) | set(PENDING) | set(SCHEDULED)
    registered_tables = set(ACCEPTED_TABLES) | set(PENDING_TABLES) | set(SCHEDULED_TABLES)
    seen_tables = {key for kind, key, _ in gaps if kind == "UNEXTRACTED_TABLE"}
    seen_cols = {key for kind, key, _ in gaps if kind != "UNEXTRACTED_TABLE"}
    unregistered = sorted(
        [k for k in seen_tables if k not in registered_tables]
        + [k for k in seen_cols if k not in registered_cols]
    )
    stale = sorted(
        [k for k in registered_tables if k not in seen_tables]
        + [k for k in registered_cols if k not in seen_cols]
    )
    conflicting = (
        _conflicting(ACCEPTED, PENDING, SCHEDULED)
        + _conflicting(ACCEPTED_TABLES, PENDING_TABLES, SCHEDULED_TABLES)
    )
    return unregistered, stale, sorted(conflicting)


# --------------------------------------------------------------------
# Self-test: every detection class, on synthetic input
# --------------------------------------------------------------------

_FIXTURE_SCHEMA = """
CREATE TABLE `kept` (
  `id` int(10) UNSIGNED NOT NULL,
  `carried` varchar(50) NOT NULL,
  `never_extracted` varchar(50) DEFAULT NULL,
  `staged_not_loaded` timestamp NOT NULL,
  `selected_untargeted` varchar(50) DEFAULT NULL
)
CREATE TABLE `orphan` (
  `id` int(10) UNSIGNED NOT NULL,
  `whatever` text
)
"""

_FIXTURE_EXPORT = """
SELECT id, carried, staged_not_loaded, selected_untargeted FROM kept ORDER BY id;
"""

_FIXTURE_MIGRATIONS = [
    "CREATE TABLE kept (\n    id BIGINT NOT NULL,\n    carried VARCHAR(50) NOT NULL,\n"
    "    never_extracted VARCHAR(50),\n    staged_not_loaded TIMESTAMPTZ NOT NULL\n);",
]

_FIXTURE_LOAD = '''
STAGING = {
    "kept": ["id", "carried", "staged_not_loaded", "selected_untargeted"],
}
INSERT INTO kept (id, carried)
'''


def self_test() -> int:
    failures: list[str] = []

    def check_that(name: str, condition: bool) -> None:
        print(("OK   " if condition else "FAIL ") + name)
        if not condition:
            failures.append(name)

    legacy = parse_legacy_schema(_FIXTURE_SCHEMA)
    target = parse_target_schema(_FIXTURE_MIGRATIONS)
    exported = parse_exported(_FIXTURE_EXPORT)
    staging = parse_staging(_FIXTURE_LOAD)
    inserted = parse_inserted(_FIXTURE_LOAD)

    check_that("legacy DDL parses into tables and columns",
               legacy.get("kept") == ["id", "carried", "never_extracted", "staged_not_loaded",
                                       "selected_untargeted"])
    check_that("target schema reads CREATE TABLE columns",
               "never_extracted" in target.get("kept", []))
    check_that("ALTER TABLE ADD COLUMN counts as a target column",
               "added_later" in parse_target_schema(
                   ["CREATE TABLE t (\n    id BIGINT\n);", "ALTER TABLE t ADD COLUMN added_later TEXT;"]
               ).get("t", []))
    # Regression: only the first clause of a multi-column ALTER carries
    # the `ALTER TABLE` prefix, so anchoring each column to it read one
    # of V42's six and silently understated the target schema -- which
    # makes this auditor MISS a gap, the one direction it must not fail
    # in. The single-column case above passed throughout.
    check_that("every ADD COLUMN of a multi-column ALTER TABLE counts, not just the first",
               parse_target_schema([
                   "CREATE TABLE t (\n    id BIGINT\n);",
                   "ALTER TABLE t\n    ADD COLUMN a TEXT,\n    ADD COLUMN b DATE,\n"
                   "    ADD COLUMN c VARCHAR(10)\n        CONSTRAINT t_c_check CHECK (c IN ('x')),\n"
                   "    ADD CONSTRAINT t_a_unique UNIQUE (a);",
               ]).get("t", []) == ["id", "a", "b", "c"])
    check_that("staging and INSERT column lists parse",
               staging.get("kept") == ["id", "carried", "staged_not_loaded", "selected_untargeted"]
               and inserted.get("kept") == {"id", "carried"})
    check_that("a column written by a backfill UPDATE counts as loaded",
               parse_inserted("INSERT INTO d (id, name)\n...\nUPDATE d SET manager_id = m.new_id\n")
               == {"d": {"id", "name", "manager_id"}})

    gaps = find_gaps(legacy, target, exported, staging, inserted)
    kinds = {kind: key for kind, key, _ in gaps}

    check_that("detects a legacy table with no SELECT at all",
               kinds.get("UNEXTRACTED_TABLE") == "orphan")
    check_that("detects a column absent from its table's SELECT",
               kinds.get("UNEXTRACTED_COLUMN") == "kept.never_extracted")
    check_that("detects a staged column left out of the INSERT (the created_at shape)",
               kinds.get("UNLOADED_COLUMN") == "kept.staged_not_loaded")
    check_that("detects a selected+staged column with no target column at all "
               "(the employees.hire_date shape)",
               kinds.get("UNTARGETED_COLUMN") == "kept.selected_untargeted")
    check_that("a carried column is not reported", not any("kept.carried" == k for _, k, _ in gaps))

    # A column named only in an unrelated statement must not count as covered.
    check_that(
        "coverage is statement-scoped, not a whole-file substring search",
        any(k == "kept.never_extracted" for _, k, _ in find_gaps(
            legacy, target,
            parse_exported("SELECT id, carried, staged_not_loaded FROM kept;\n"
                           "SELECT never_extracted FROM something_else;"),
            staging, inserted)),
    )

    unregistered, stale, conflicting = check([("UNEXTRACTED_COLUMN", "kept.never_extracted", "")])
    check_that("an unregistered gap is reported by --check", unregistered == ["kept.never_extracted"])
    check_that("a registry entry with no matching gap is reported as stale",
               "salary_contracts.total" in stale)
    check_that("every ACCEPTED entry carries a reason",
               all(len(v.strip()) > 20 for v in ACCEPTED.values()))
    check_that("every PENDING entry carries a note",
               all(len(v.strip()) > 20 for v in PENDING.values()))

    # The three states are mutually exclusive claims. A key in two of them
    # asserts two contradictory things at once, and the union in `check`
    # makes that register as fine -- which is exactly how
    # salary_contracts.total sat in both ACCEPTED and PENDING undetected.
    check_that("no key is registered in more than one state", conflicting == [])
    check_that("a key in two registries is reported as conflicting",
               _conflicting({"a.b": "x"}, {"a.b": "y"}) == ["a.b"])
    check_that("a key in one registry is not reported as conflicting",
               _conflicting({"a.b": "x"}, {"c.d": "y"}) == [])
    check_that("every SCHEDULED entry names the decision that settled it",
               all(re.match(r"^D-\d+", v.strip()) for v in SCHEDULED.values()))
    check_that("every SCHEDULED_TABLES entry names the decision that settled it",
               all(re.match(r"^D-\d+", v.strip()) for v in SCHEDULED_TABLES.values()))
    check_that("every SCHEDULED entry carries a note beyond the decision id",
               all(len(v.strip()) > 20 for v in
                   list(SCHEDULED.values()) + list(SCHEDULED_TABLES.values())))

    # ----------------------------------------------------------------
    # check_export_staging_agreement: the check that would have caught
    # Fix B (EXPORT_SQL's SELECT * vs STAGING's short column list)
    # without a live database. Exercised here against synthetic input, so
    # this proves the detector itself works even where the sibling
    # hr-legacy checkout (needed for a REAL --check run) is absent.
    # ----------------------------------------------------------------
    agreeing_export = parse_export_select_columns(_FIXTURE_EXPORT, legacy)
    agreeing_staging = parse_staging(_FIXTURE_LOAD)
    check_that("agreeing EXPORT_SQL and STAGING columns report no problem",
               check_export_staging_agreement(agreeing_export, agreeing_staging) == [])

    # The exact Fix B shape: EXPORT_SQL selects every legacy column
    # (SELECT *) while STAGING under-declares. Resolving `*` needs the
    # legacy schema -- this is the one assertion in this block that would
    # NOT pass if parse_export_select_columns silently treated `*` as "no
    # columns" instead of resolving it.
    star_schema = parse_legacy_schema(
        "CREATE TABLE `widgets` (\n"
        "  `id` int(10) UNSIGNED NOT NULL,\n"
        "  `name` varchar(50) NOT NULL,\n"
        "  `extra_column` varchar(50) DEFAULT NULL\n"
        ")\n"
    )
    star_export = parse_export_select_columns("SELECT * FROM widgets ORDER BY id;\n", star_schema)
    star_staging = {"widgets": ["id", "name"]}
    star_problems = check_export_staging_agreement(star_export, star_staging)
    check_that("SELECT * is resolved through the legacy schema, not skipped",
               star_export.get("widgets") == ["id", "name", "extra_column"])
    check_that("a SELECT * that outgrows STAGING is reported, by name, as a mismatch "
               "(the exact shape Fix B was)",
               len(star_problems) == 1 and "widgets" in star_problems[0])

    # An explicit column list can drift from STAGING too, same as SELECT *
    # can -- the bug is the drift, not the spelling of the SELECT clause.
    explicit_export = parse_export_select_columns(
        "SELECT id, name, extra_column FROM widgets ORDER BY id;\n", star_schema
    )
    explicit_problems = check_export_staging_agreement(explicit_export, star_staging)
    check_that("an explicit SELECT list that disagrees with STAGING is also caught, "
               "not just SELECT *",
               len(explicit_problems) == 1 and "widgets" in explicit_problems[0])

    # A same-length, different-ORDER pair must fail too: \copy is
    # positional, so a reordered column list silently swaps two columns'
    # values rather than raising "extra data" the way a length mismatch
    # does -- arguably more dangerous for being silent, and exactly what a
    # naive set-equality comparison (instead of list equality) would miss.
    reordered_export = parse_export_select_columns(
        "SELECT name, id FROM widgets ORDER BY id;\n", star_schema
    )
    reordered_problems = check_export_staging_agreement(reordered_export, {"widgets": ["id", "name"]})
    check_that("a same-length but reordered column list is still reported -- "
               "set equality alone would miss this",
               len(reordered_problems) == 1 and "widgets" in reordered_problems[0])

    # A comma inside a function call's arguments (DATE_FORMAT's format
    # string, exactly as export_legacy.py's attendance/branches SELECTs
    # use it) must not be mistaken for a column separator.
    func_export = parse_export_select_columns(
        "SELECT id, DATE_FORMAT(ts, '%Y-%m-%d %H:%i:%s') AS ts FROM widgets ORDER BY id;\n",
        star_schema,
    )
    check_that("a comma inside a function call's arguments does not split a column in two",
               func_export.get("widgets") == ["id", "ts"])

    # REPORT statements (leave_balance duplicates, the created_at
    # zero-date probe, is_daylight_saving) must never be mistaken for a
    # table's row-copy SELECT -- the created_at_quality probe in
    # particular is one statement containing a dozen `FROM <table>`
    # clauses inside a UNION ALL, and misreading it would corrupt
    # whichever table's entry it clobbers.
    report_export = parse_export_select_columns(
        "SELECT employee_id, year, COUNT(*) AS duplicate_rows FROM leave_balance "
        "GROUP BY employee_id, year HAVING COUNT(*) > 1 ORDER BY employee_id, year;\n",
        legacy,
    )
    check_that("a REPORT statement (leave_balance duplicate check) is not treated as a "
               "row-copy SELECT for leave_balance",
               "leave_balance" not in report_export)

    # company_settings (EXPORT_SQL's FROM-clause name) must resolve to the
    # legacy_company_settings STAGING key, the same divergence TABLE_MAP
    # models for the target side.
    cs_export = parse_export_select_columns(
        "SELECT id, company_id, setting_definition_id FROM company_settings ORDER BY id;\n",
        legacy,
    )
    check_that("company_settings resolves to the legacy_company_settings STAGING key",
               "legacy_company_settings" in cs_export and "company_settings" not in cs_export)

    return 1 if failures else 0


# --------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------


def load_inputs(schema_path: str):
    with open(schema_path, encoding="utf-8", errors="replace") as handle:
        legacy = parse_legacy_schema(handle.read())
    migrations = []
    for root, _, files in os.walk(MIGRATION_DIR):
        for name in files:
            if name.endswith(".sql") and name.startswith("V"):
                migrations.append((int(re.match(r"V(\d+)", name).group(1)), os.path.join(root, name)))
    sql_by_version = []
    for _, path in sorted(migrations):
        with open(path, encoding="utf-8") as handle:
            sql_by_version.append(handle.read())
    with open(EXPORT_SCRIPT, encoding="utf-8") as handle:
        export_src = handle.read()
    export_sql = export_src.split('EXPORT_SQL = r"""', 1)[1].split('"""', 1)[0]
    with open(LOAD_SCRIPT, encoding="utf-8") as handle:
        load_src = handle.read()
    return (
        legacy,
        parse_target_schema(sql_by_version),
        parse_exported(export_sql),
        parse_staging(load_src),
        parse_inserted(load_src),
        export_sql,
    )


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()

    schema_path = DEFAULT_SCHEMA
    if "--schema" in argv:
        schema_path = argv[argv.index("--schema") + 1]
    if not os.path.exists(schema_path):
        print(f"legacy schema not found at {schema_path}", file=sys.stderr)
        print("pass --schema PATH, or run --self-test which needs no schema", file=sys.stderr)
        return 2

    legacy, target, exported, staging, inserted, export_sql = load_inputs(schema_path)
    gaps = find_gaps(legacy, target, exported, staging, inserted)
    unregistered, stale, conflicting = check(gaps)

    # Fix B, as a standing check rather than a one-time fix: EXPORT_SQL's
    # SELECT list and STAGING's declared columns must agree, in order, for
    # every table \copy loads. Independent of the ACCEPTED/SCHEDULED/
    # PENDING ledger above -- it is not a "column silently dropped"
    # question (find_gaps' domain), it is "would \copy even accept this
    # file" -- so it is not unioned into `gaps`/`check()` and gets its own
    # pass/fail here.
    export_problems = check_export_staging_agreement(
        parse_export_select_columns(export_sql, legacy), staging
    )

    if "--check" in argv:
        for key in unregistered:
            print(f"UNREGISTERED GAP: {key} -- record it in ACCEPTED (with a reason), "
                  f"SCHEDULED (with the decision that settled it), or PENDING "
                  f"(with what must be decided)")
        for key in stale:
            print(f"STALE REGISTRY ENTRY: {key} -- no longer a gap; remove it")
        for key in conflicting:
            print(f"CONFLICTING REGISTRY ENTRY: {key} -- registered in more than one "
                  f"state; the three are mutually exclusive claims")
        for problem in export_problems:
            print(f"EXPORT_SQL/STAGING MISMATCH: {problem}")
        if unregistered or stale or conflicting or export_problems:
            return 1
        print(
            f"OK: {len(gaps)} gaps, all registered "
            f"({len(ACCEPTED) + len(ACCEPTED_TABLES)} accepted, "
            f"{len(SCHEDULED) + len(SCHEDULED_TABLES)} scheduled, "
            f"{len(PENDING) + len(PENDING_TABLES)} pending a decision), "
            f"0 EXPORT_SQL/STAGING mismatches"
        )
        return 0

    by_kind: dict[str, list[tuple[str, str]]] = {}
    for kind, key, detail in gaps:
        by_kind.setdefault(kind, []).append((key, detail))
    for kind in ("UNEXTRACTED_TABLE", "UNEXTRACTED_COLUMN", "UNTARGETED_COLUMN", "UNLOADED_COLUMN"):
        entries = by_kind.get(kind, [])
        print(f"\n=== {kind} ({len(entries)}) ===")
        is_table = kind == "UNEXTRACTED_TABLE"
        accepted = ACCEPTED_TABLES if is_table else ACCEPTED
        scheduled = SCHEDULED_TABLES if is_table else SCHEDULED
        pending = PENDING_TABLES if is_table else PENDING
        for key, detail in entries:
            if key in accepted:
                state = "ACCEPTED "
            elif key in scheduled:
                state = "SCHEDULED"
            elif key in pending:
                state = "PENDING  "
            else:
                state = "UNREGISTERED"
            print(f"  [{state}] {key:52} {detail}")
    print(f"\n=== EXPORT_SQL / STAGING mismatches ({len(export_problems)}) ===")
    if not export_problems:
        print("  (none)")
    for problem in export_problems:
        print(f"  {problem}")
    print(
        f"\ntotal gaps: {len(gaps)}   unregistered: {len(unregistered)}   "
        f"stale: {len(stale)}   conflicting: {len(conflicting)}   "
        f"export/staging mismatches: {len(export_problems)}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
