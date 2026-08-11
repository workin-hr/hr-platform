# ETL Coverage Decisions Brief (2026-08-12)

## Purpose

`scripts/etl/coverage_audit.py` reports 47 gaps between the legacy schema
and what the ETL migrates. Every one is registered, so none is a surprise
— but 47 registered gaps are still 47 unanswered questions, and the
answers are not engineering's to invent.

This converts them into questions that can be answered without reading
the ETL, in the shape
[`pending-decisions-brief.md`](./pending-decisions-brief.md) used for the
readiness-gate blockers.

**This document is planning output only** (hr-platform `CLAUDE.md`).
Nothing here is decided.

Everything mechanical has already been closed: the `created_at` repair
across 15 entities, `branches.expires_at` and `qr_code`, the five
`advances.deduction_*` scheduling columns, and the four history and
scheduling tables. What remains genuinely needs a human.

## How To Use This

Each question is answerable **yes/no or with one sentence**. Answering
one lets engineering either write the migration or record a deliberate
drop in `coverage_audit.py`'s `ACCEPTED` registry — where it stops being
a gap and starts being a decision.

Priority is about cutover risk, not effort.

---

## P0 — Answer before cutover planning continues

### Q1. Do companies still log in after cutover?

**The gap.** Legacy `companies` carries `password_hash`, `email`,
`otp_verified` and `profile_completed`, and legacy companies log in with
phone + password. The ETL extracts 5 of 21 company columns; none of those
four is among them. The `identities` transform derives **only** from
`stg_employees` (`load_postgres.py`, identity block), so nothing carries
company-level credentials across at all.

**Why it is P0.** If company login is meant to survive, this is a
cutover-blocking data loss with no recovery after the legacy database is
retired. If it is not meant to survive, that is a decision nobody has
recorded, and every downstream reader of the schema is currently guessing.

**The question.** After cutover, does a *company* authenticate as itself,
or only individual users (company admins) through `identities`? ADR-0009's
role-based split suggests the latter, but no accepted decision says so.

**If the answer is "only individual users":** we record the drop and note
how existing company logins become admin identities at cutover — an
onboarding step that does not exist yet.

### Q2. Which of the 16 unmigrated tables must survive?

None has a target table, so each needs a target schema *and* a migration,
or an explicit drop. Grouped by the kind of judgement involved:

| Table | Rows | The question |
|---|---:|---|
| `notifications` | 4,014 | Is in-app notification history worth keeping, or do tenants start clean? |
| `complaints` | ~52 | Support-inbox history — is there a retention or compliance obligation? |
| `assets` | ~25 | Company assets issued to employees. Is this tracked in the new platform at all? |
| `administrative_decisions` | ~22 | Company announcements. Same question. |
| `workforce_planning` | ~15 | Headcount targets. Same question. |
| `employee_docs` | — | Metadata only; the files live under `uploads/` outside the DB. Do documents survive, and if so what happens to the files? |
| `push_tokens` | — | F-08 confirms push never worked end-to-end. Migrating dead tokens seems pointless — confirm. |
| `otp_codes` | ~588 | Short-lived codes. Presumably not worth migrating — confirm. |
| `banners`, `app_content`, `faq_categories`, `faq_items` | — | Marketing/CMS content. Does the new platform own this, or does it move elsewhere? |
| `phone_countries` | — | Reference data. Does the new platform seed its own list, or inherit legacy's? |
| `company_activities`, `company_sizes`, `company_titles` | 2–4 each | Lookup tables referenced by three `companies` FK columns. Decide together with Q3. |

**A "no" is a perfectly good answer** — it just has to be written down.

---

## P1 — Answer before the companies module is considered complete

### Q3. What happens to the other 12 `companies` columns?

Beyond Q1's four, these have no target column:

- `company_code` — do tenants keep their existing identifier, or get renumbered?
- `first_name`, `last_name` — the company *contact person*, distinct from the company. Does that person become an employee/identity, or is the contact concept dropped?
- `commercial_reg_url` — uploaded registration document. Compliance record?
- `logo_url` — tenant branding. Survives, or re-uploaded?
- `rejection_reason` — company-approval workflow state. Is rejection history retained?
- `main_branch_address` — free text on the tenant row, separate from `branches.address`. Fold into the main branch, or drop?
- `country_code` — stored separately, or folded into the phone value?
- `company_activity_id`, `company_title_id`, `company_size_id` — FKs into the three lookup tables in Q2; decide together.
- `updated_at` — see Q5.

### Q4. Do the two remaining `advances` columns matter?

The five scheduling columns with target columns are now migrated. Two are
left:

- **`deduction_type`** (`single_month` / `multiple_months`) — looks
  redundant with `deduction_mode`, but redundancy has to be confirmed by
  someone who knows the legacy semantics, not assumed by whoever reads
  the enum names.
- **`deduction_installments_json`** — real data, written by
  `advances/create.php` and `update.php`. No target column. If installment
  schedules are ever honoured, this is the only record of what was agreed.

Note `PayrollBatchService`'s Javadoc already records that scheduled
deduction is a v1 heuristic and *"V12's deduction_\* scheduling columns
remain an open product decision."* This is that decision, narrowed to two
columns.

---

## P2 — Answer opportunistically

### Q5. Does any entity need `updated_at`?

`companies`, `exception_types` and `company_settings` carry an
`updated_at` in legacy; no target table has one. Either the new platform
tracks modification time or it does not — a per-table answer would be
worse than one rule applied consistently.

### Q6. Can an exception type be deactivated?

`exception_types.is_active` exists in legacy; the target has no such
column. A migrated inactive type would silently become active and
reappear in pickers. Either add the column or confirm deactivation is not
a feature.

### Q7. Four employee columns with no target

- `token_version` — legacy JWT-invalidation counter. The rewrite revokes
  through refresh-token families (F-26), so this is probably obsolete;
  confirm rather than assume.
- `address` — employee home address. There may be a data-minimisation
  argument for *not* carrying it. That is still a decision.
- `photo_url` — survives, or re-uploaded?
- `contract_duration_months` — fixed-term contract length. Confirm no
  payroll rule depends on it before dropping.

### Q8. `configs.id`

`configs` is read only for the `is_daylight_saving` probe and has no
target table. Confirm no other config key is needed post-cutover.

---

## What Happens To An Answer

Each answer lands in one of two places:

- **Migrate it** → the column or table gets a target schema and a load
  block, with a fixture assertion, and the ledger entry disappears.
- **Drop it** → the entry moves from `PENDING` to `ACCEPTED` in
  `coverage_audit.py` with the reason recorded inline, and `--check`
  keeps it honest: if the gap later closes, the stale entry fails the
  build.

Either way the gap stops being silent, which is the whole point.

## Evidence

Generated from `scripts/etl/coverage_audit.py --report` against
`hr-legacy/mysql_workin.schema.sql`. Row counts from
[`table-volume-analysis.md`](./table-volume-analysis.md) (measured
2026-08-04, not the AUTO_INCREMENT approximations in
[`database-schema-inventory.md`](./database-schema-inventory.md)).
