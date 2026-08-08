-- Generated schedule rows now persist the stable token localized at
-- read time (i18n spec, owner decision 4). Rewrites the few rows the
-- pre-i18n build persisted as English display text. Idempotent; a
-- manual note that exactly matches the old label is semantically the
-- same rest-day statement, so rewriting it is correct too.
UPDATE employee_schedules SET exception_note = 'WEEKLY_REST' WHERE exception_note = 'Weekly rest';
