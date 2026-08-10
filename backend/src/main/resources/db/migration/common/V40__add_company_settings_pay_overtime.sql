-- pay_overtime did not exist as a column: V27 created month_start_day,
-- month_end_day, weekly_off_days, overtime_rate, monthly_leave_accrual
-- only. Nullable, matching V27's own convention -- NULL means "unset",
-- and payroll_company_pays_overtime's own default is true when unset
-- (hr-legacy/apis/helpers/payroll_calculation.php:140-149), so an
-- existing company with no row here keeps paying overtime exactly as
-- it does today. See docs/superpowers/specs/2026-08-08-payroll-attendance-reconciliation-design.md.
ALTER TABLE company_settings ADD COLUMN pay_overtime BOOLEAN;
