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
    # Found 2026-08-13, running the real ETL end to end for the first time:
    # a new detection class (UNTARGETED_COLUMN, find_gaps() above) landed
    # here exactly as the note below predicted. All seven are selected in
    # export_legacy.py's employees SELECT and staged in load_postgres.py's
    # STAGING, but no target column exists for any of them and none is in
    # the actual INSERT INTO employees (...). Decision brief:
    # docs/migration/2026-08-13-etl-real-data-findings-decision-brief.md.
    # Until answered, `--check`'s "0 pending" from before this date was
    # incomplete, not a clean bill of health -- these were real gaps the
    # tool simply could not see.
    "employees.employee_code": (
        "No target column. Was already a KNOWN, deliberate exclusion -- "
        "V8__create_employees.sql's own comment calls it 'intentionally "
        "omitted... tracked follow-up' -- but was never registered here, "
        "so coverage_audit.py disagreed with the migration's own SQL "
        "comment about whether this was decided. Needs a decision: carry "
        "it as a real column, or formally accept the drop."
    ),
    "employees.country_code": (
        "No target column. Never flagged as a decision before this run. "
        "Needed to interpret employees.phone as anything but a bare "
        "digit string; dropping it silently changes what a migrated "
        "phone number means. Needs a decision: add a target column, or "
        "accept the drop and record why phone interpretation doesn't "
        "need it."
    ),
    "employees.national_id": (
        "No target column. Never flagged as a decision before this run. "
        "A legal-identity field with likely compliance weight -- not "
        "something to drop by omission. Needs a decision: add a target "
        "column, or accept the drop with an explicit reason."
    ),
    "employees.birth_date": (
        "No target column. Never flagged as a decision before this run. "
        "invalid-date-analysis.md already found 2 of 2,871 rows with a "
        "zero-date here, on the assumption the column would migrate as "
        "NULL -- that assumption has no target column to land in today. "
        "Needs a decision: add a target column (with the zero-date "
        "remediation invalid-date-analysis.md already proposed), or "
        "accept the drop."
    ),
    "employees.gender": (
        "No target column. Never flagged as a decision before this run. "
        "Needs a decision: add a target column, or accept the drop."
    ),
    "employees.hire_date": (
        "No target column. Never flagged as a decision before this run. "
        "invalid-date-analysis.md already found 22 of 2,871 rows with a "
        "zero-date here, on the same NULL-remediation assumption as "
        "birth_date above, with nowhere to load. Needs a decision: add a "
        "target column, or accept the drop."
    ),
    "employees.updated_at": (
        "No target column. Never flagged as a decision before this run. "
        "Related to OQ-3 (D-033): the decided rule is that updated_at "
        "must be database-enforced across every mutable business entity, "
        "which implies employees needs this column regardless of whether "
        "legacy's own historical values are worth carrying. Needs a "
        "decision: add the column (OQ-3 may settle this on its own), or "
        "accept the drop of the historical values specifically."
    ),
    "attendance.updated_at": (
        "No target column, confirmed by reading V21__create_attendance.sql "
        "directly -- the table has no updated_at at all. Same OQ-3 family "
        "as employees.updated_at above; found by the same UNTARGETED_COLUMN "
        "detection class on 2026-08-13, applied to every table, not just "
        "employees. Needs the same decision: add the column, or accept "
        "the drop of legacy's historical values."
    ),
    "advances.updated_at": (
        "No target column. Same OQ-3 family and same discovery as "
        "attendance.updated_at above. Needs the same decision."
    ),
    "requests.updated_at": (
        "No target column -- V25__create_requests_and_leave_balances.sql's "
        "own comment already says so explicitly ('no updated_at'), which "
        "means this was a known omission at the time V25 was written but, "
        "like employees.employee_code, was never carried into this ledger "
        "as a registered decision. Same OQ-3 family as the other three. "
        "Needs the same decision."
    ),
    "companies.status": (
        "Found 2026-08-13 by the UNTARGETED_COLUMN detection class, "
        "applied to every table. Not a clean rename like "
        "employees.is_active: load_postgres.py's companies INSERT computes "
        "`COALESCE(s.status, 'active') = 'active'` into the target `active` "
        "boolean, which collapses legacy's four-state status "
        "(`active`/`pending`/`rejected`/`suspended`) into two "
        "(active/not-active) -- a real information loss, not a rename. "
        "Directly related to finding C in the 2026-08-13 real-data decision "
        "brief: 61 of 66 `pending` companies have no name yet, and after "
        "this collapse a migrated `pending`, `rejected`, and `suspended` "
        "company are indistinguishable from each other. Needs a decision: "
        "is losing that distinction acceptable, or does the target need a "
        "real status column instead of a boolean?"
    ),
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
    """
    out: dict[str, list[str]] = {}
    reserved = {"CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK"}
    for sql in sql_by_version:
        for m in re.finditer(r"CREATE TABLE (?:IF NOT EXISTS )?(\w+)\s*\((.*?)\n\);", sql, re.S):
            cols = re.findall(r"^\s+(\w+)\s+[A-Z]", m.group(2), re.M)
            out[m.group(1)] = [c for c in cols if c.upper() not in reserved]
        for m in re.finditer(r"ALTER TABLE (\w+)\s+ADD COLUMN (?:IF NOT EXISTS )?(\w+)", sql, re.I):
            out.setdefault(m.group(1), []).append(m.group(2))
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


def parse_staging(load_src: str) -> dict[str, list[str]]:
    """The STAGING dict -> {legacy table: [staged column, ...]}."""
    block = load_src.split("STAGING = {", 1)[1].split("\n}", 1)[0]
    out: dict[str, list[str]] = {}
    for m in re.finditer(r'"(\w+)":\s*(\[[^\]]*\])', block):
        out[m.group(1)] = re.findall(r'"(\w+)"', m.group(2))
    return out


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

    gaps = find_gaps(*load_inputs(schema_path))
    unregistered, stale, conflicting = check(gaps)

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
        if unregistered or stale or conflicting:
            return 1
        print(
            f"OK: {len(gaps)} gaps, all registered "
            f"({len(ACCEPTED) + len(ACCEPTED_TABLES)} accepted, "
            f"{len(SCHEDULED) + len(SCHEDULED_TABLES)} scheduled, "
            f"{len(PENDING) + len(PENDING_TABLES)} pending a decision)"
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
    print(
        f"\ntotal gaps: {len(gaps)}   unregistered: {len(unregistered)}   "
        f"stale: {len(stale)}   conflicting: {len(conflicting)}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
