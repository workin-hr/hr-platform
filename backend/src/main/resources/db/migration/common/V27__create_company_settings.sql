-- Typed replacement for legacy's setting_definitions/
-- setting_allowed_values/company_settings/company_setting_values EAV
-- (owner-confirmed simplification; the EAV-to-typed data conversion is
-- cutover work). Exactly the five code-consumed CompanySettingEnum
-- keys. NULL means "unset -- apply the legacy fallback" (start 1,
-- end = month's last day, accrual 21.0), so a company with no row
-- behaves exactly as before this table existed.
CREATE TABLE company_settings (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    month_start_day SMALLINT,
    month_end_day SMALLINT,
    weekly_off_days VARCHAR(60),
    overtime_rate NUMERIC(5, 2),
    monthly_leave_accrual NUMERIC(5, 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT company_settings_company_unique UNIQUE (company_id)
);
