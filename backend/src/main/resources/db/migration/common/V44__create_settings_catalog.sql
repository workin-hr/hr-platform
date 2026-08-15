-- Fix B (docs/migration/2026-08-13-etl-real-data-findings-decision-brief.md
-- "Fix B") took its option 2: the owner has confirmed the new
-- platform WILL keep an admin-editable bilingual settings catalog, so
-- these newer legacy columns migrate for real UI use rather than
-- being formally accepted as a drop.
--
-- Global, not tenant-scoped -- a setting definition's existence and
-- its labels are the same across every tenant, so no RLS applies to
-- these two tables, same precedent as
-- common/V4__create_permission_catalog.sql's permissions/
-- role_permissions.
--
-- Presentation metadata only. The typed company_settings columns
-- (V27/V40 -- month_start_day, month_end_day, weekly_off_days,
-- overtime_rate, monthly_leave_accrual, pay_overtime) remain the sole
-- authority for a company's chosen VALUE; the 6 setting_keys this
-- catalog seeds (a future, separate load step) map 1:1 onto those six
-- typed columns. This catalog supplies only labels, descriptions,
-- icons, and ordering for rendering them. This is the whole design
-- premise: do not read this pair of tables as a place to store a
-- company's actual setting value, or legacy's EAV
-- (setting_definitions/setting_allowed_values/company_settings/
-- company_setting_values, hr-legacy/mysql_workin.schema.sql) gets
-- reintroduced by accident.
--
-- Verified against live production 2026-08-15: small and fully
-- populated -- 6 definitions, 81 allowed values; all 6 definitions
-- have both labels and both descriptions, 5 of 6 have an icon; all 81
-- allowed values have both labels. Seeding is deliberately NOT done
-- here -- it is load work, not schema work -- but two notes for
-- whoever writes it: (1) only 19 of the 81 allowed values have a
-- label differing from the raw value, the other 62 are
-- month_start_day/month_end_day's numerals 1-31, better generated
-- than migrated row-by-row; (2) icon_data holds Flutter client
-- constants (e.g. FontAwesomeIcons.calendarDay,
-- FontAwesomeIcons.businessTime) -- mobile client-code identifiers
-- stored as data, a known wart carried over deliberately rather than
-- redesigned here.
--
-- updated_at is NOT carried over from either legacy table: verified
-- zero reads anywhere in the hr-legacy codebase -- dead column on
-- both setting_definitions (mysql_workin.schema.sql:905) and
-- setting_allowed_values (mysql_workin.schema.sql:881). Deferred to
-- OQ-3's uniform updated_at treatment, same as V43 -- not
-- reintroduced here as a dead DEFAULT/ON UPDATE column.
CREATE TABLE setting_definitions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    setting_key VARCHAR(100) NOT NULL UNIQUE,
    label_ar VARCHAR(150),
    label_en VARCHAR(150),
    description_ar VARCHAR(500),
    description_en VARCHAR(500),
    icon_data VARCHAR(120),
    is_multi BOOLEAN NOT NULL DEFAULT FALSE,
    is_required BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE setting_allowed_values (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    setting_definition_id BIGINT NOT NULL REFERENCES setting_definitions (id),
    value VARCHAR(120) NOT NULL,
    label_ar VARCHAR(150),
    label_en VARCHAR(150),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT setting_allowed_values_definition_value_unique UNIQUE (setting_definition_id, value)
);
