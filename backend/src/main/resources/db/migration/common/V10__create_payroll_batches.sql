-- Translated from hr-legacy/mysql_workin.schema.sql's payroll_batches
-- table. status is DRAFT/FINALIZED only, same as legacy -- "reopened"
-- is not a distinct state, it's FINALIZED flipped back to DRAFT
-- (docs/api/existing-endpoint-inventory.md's payroll_batches section).
--
-- Real DB-enforced fix for an already-documented legacy defect: legacy
-- has no UNIQUE constraint on (company_id, month, year) at all --
-- duplicate-batch prevention there is an app-level SELECT COUNT(*)
-- check only, a real, tracked race condition
-- (docs/legacy/business-rule-extraction.md). This schema closes it for
-- real.
CREATE TABLE payroll_batches (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    month SMALLINT NOT NULL,
    year SMALLINT NOT NULL,
    period_from DATE NOT NULL,
    period_to DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payroll_batches_status_check CHECK (status IN ('DRAFT', 'FINALIZED')),
    CONSTRAINT payroll_batches_month_check CHECK (month BETWEEN 1 AND 12),
    CONSTRAINT payroll_batches_company_month_year_unique UNIQUE (company_id, month, year)
);

CREATE INDEX payroll_batches_company_id_idx ON payroll_batches (company_id);
