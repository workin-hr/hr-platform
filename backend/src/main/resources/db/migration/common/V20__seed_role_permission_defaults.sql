-- Default role permission bundles (ADR-0010 Dimension 3: "fixed roles
-- provide default permission bundles"). Grounded in confirmed legacy
-- behavior, not invented policy:
--   COMPANY_ADMIN -> every tenant-domain permission. Legacy
--     company_admin had unconditional full tenant access -- the
--     hr_permissions matrix only ever constrained hr-role employees
--     (dashboard/includes/hr_access.php).
--   HR -> no default rows. Legacy HR capability was entirely
--     per-employee via the hr_permissions matrix, which migrates as
--     per-membership ALLOW overrides (authorization-model.md §7), never
--     as a role default -- least privilege with exact legacy fidelity.
--   MANAGER -> no default rows. Manager capability is explicit resource
--     scopes (§1), never role-implied -- the hr-legacy#17/#18 scope
--     drift is deliberately not carried forward.
--   EMPLOYEE -> no default rows. Self-service is the own-data default
--     resource scope, not catalog permissions (§1).
INSERT INTO role_permissions (role, permission_id)
SELECT 'COMPANY_ADMIN', p.id
FROM permissions p
WHERE p.permission_key NOT LIKE 'platform.%';
