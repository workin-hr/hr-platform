# Threat Model

**Status: Discovery in progress.** This file defines the template and
method the actual system threat model will use. It contains three real,
evidenced findings below (from the `hr-legacy` deep-dive, `auth` and
`advances` modules); everything else remains pending further Discovery
passes. Do not fill in specific threats, mitigations, or residual risk
ratings based on assumption — every row must cite evidence.

**The two `auth`-module rows below, read together, are the most severe
finding of the Discovery pass so far** — if `AppConfig::DEBUG` is `true`
in the live production deployment (already flagged as unconfirmed-but-real
in `docs/legacy/production-behavior-evidence.md`), both OTP-issuing
endpoints hand the real OTP code back in the API response, making every
account on the platform — any company, any employee — takeable over by
phone number alone, no WhatsApp access, no brute force, no guessing
required.

## Method

STRIDE (Spoofing, Tampering, Repudiation, Information Disclosure, Denial of
Service, Elevation of Privilege), applied per trust boundary once the
boundaries below are actually known. This is an explicit choice, not a
placeholder — if a future architecture decision picks a different method,
record that as an ADR and update this line.

## Scope (to be completed during Discovery)

- **Assets**: what data and capabilities need protecting (employee/customer
  PII, attendance/biometric data, authentication credentials, tenant data
  isolation, source code, CI/CD credentials)
- **Actors**: who interacts with the system (employees, admins, customers,
  attendance devices, vendor APIs, internal agents/automation)
- **Trust boundaries**: where control or privilege changes (client <-> API,
  API <-> database, tenant <-> tenant, edge gateway <-> device, edge gateway
  <-> cloud, agent <-> repository)
- **Data flows**: how data moves across each trust boundary above

## Threat Register (populate only with evidenced findings)

| Boundary | STRIDE Category | Threat | Likelihood | Impact | Mitigation | Residual Risk | Owner | Evidence |
| -------- | ---------------- | ------ | ---------- | ------ | ---------- | -------------- | ----- | -------- |
| Tenant ↔ tenant, within `apis/api/advances/` | Tampering, Elevation of Privilege, Information Disclosure | A `COMPANY_ADMIN`/`HR` user authenticated as *any* tenant can approve, reject, record a payment against, or delete a salary-advance record belonging to a *different* tenant, and can create an advance for an `employee_id` belonging to a different tenant, purely by supplying its numeric ID/employee_id. `approve.php`, `reject.php`, and `pay.php` run their `UPDATE`/`SELECT` with no `company_id` (or joined `employees.company_id`) filter at all. `delete.php` checks ownership only for the `EMPLOYEE` role, not for `COMPANY_ADMIN`/`HR`. `create.php` never validates that a company/HR-supplied `employee_id` belongs to the caller's own `company_id`. This is confirmed **inconsistent within the same module**: `update.php`, `one.php`, and `list.php` in this exact directory all correctly scope every query through a join on `employees.company_id`, proving the isolation pattern is known and simply omitted on these five endpoints. | Not independently assessed (would require confirming how guessable/enumerable advance IDs are in practice, and whether any WAF/rate-limit sits in front of the API — neither confirmed in this pass). Structurally, no special access is required beyond a valid Admin/HR login for *any* tenant plus a guessed or enumerated numeric ID. | High — real financial state (advance approval, remaining-balance payments, deletion) and employee PII (name, via the joined response) can be read or mutated across tenant boundaries with no tenant-isolation check on 5 of the module's 8 endpoints. | None found in code as of this Discovery pass. | **Open — unmitigated, present in the live production system.** | Unassigned — needs a human owner and a decision on whether to patch immediately (independent of migration timeline) or accept as a documented pre-migration risk. | `workin-hr/hr-legacy` commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`: `apis/api/advances/{create,approve,reject,pay,delete}.php` (missing checks), contrasted with `apis/api/advances/{update,one,list}.php` in the same commit (correct checks present). |
| Public ↔ `apis/api/auth/{forgot_password,resend_otp,register_company}.php` | Spoofing, Elevation of Privilege, Information Disclosure | All three OTP-issuing endpoints return the real OTP code in the JSON response body whenever `AppConfig::DEBUG` is truthy: `ok(..., AppConfig::DEBUG ? [Response::OTP => $otp_code] : [])` (or the equivalent `if (AppConfig::DEBUG) { $response[...] = $otp_code; }` in `register_company.php`). `docs/legacy/production-behavior-evidence.md` already documents that `AppConfig::DEBUG = true` is the value in the committed `apis/config/constants.php`, with an adjacent comment saying it should be `false` in production — whether the live deployment actually overrides this was explicitly left unconfirmed in that earlier pass. If it does not override it, any caller who knows (or guesses/enumerates) a phone number can request an OTP for that phone via `forgot_password.php` and read the real code directly back in the response — no WhatsApp access, no brute force needed — then complete `reset_password.php` and take over any company-admin or employee account on the platform. | Directly conditional on one unconfirmed fact: whether `AppConfig::DEBUG` is `true` in the live deployment. If it is, likelihood is effectively certain and requires no special access at all — only a phone number and two public, unauthenticated endpoint calls. If it is genuinely `false` in production and this pattern is dead code, likelihood is none. | Critical if the precondition holds — complete authentication bypass / full account takeover for any company or employee on the platform, not scoped to one tenant. | None in code — the `DEBUG` gate is the only control, and it is a value the live deployment could get wrong exactly as easily as right. | **Open — severity unconfirmed pending direct verification of the live `AppConfig::DEBUG` value.** This is the single highest-priority item to verify directly with whoever manages the production deployment, before anything else in this register. | Unassigned — needs a human owner with production access to check the live value, independent of migration timeline. | `workin-hr/hr-legacy` commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`: `apis/api/auth/forgot_password.php` line 76, `apis/api/auth/resend_otp.php` line 22, `apis/api/auth/register_company.php` lines 159–160; `apis/config/constants.php` (`AppConfig::DEBUG = true`, gitignored real file, sanitized `.example.php` committed); cross-referenced with the existing DEBUG finding in `docs/legacy/production-behavior-evidence.md`. |
| Public ↔ `apis/api/auth/complete_company_registration.php` | Spoofing, Tampering | This unauthenticated "step 2" registration endpoint accepts a raw, caller-supplied `company_id` with no password, token, or other possession proof — only `otp_verified=1` and `profile_completed≠1` on that row are checked. Company IDs are small sequential integers (~292 companies total per the schema inventory), trivially enumerable. Any caller can complete (and thereby corrupt/hijack) another party's pending company registration for any `company_id` in this state: setting the company name, address, title/activity/size, and uploading an arbitrary logo and commercial-registration document on their behalf. | No special access required — public endpoint, guessable small-integer ID space, no rate limiting observed. Requires the target company to be mid-registration (`otp_verified=1`, `profile_completed≠1`) at the time of the attempt, which narrows the window but does not require guessing anything about the target beyond the ID. | Medium — does not grant login access to the hijacked company (the attacker still doesn't know the real password), but does let an attacker tamper with another party's pending onboarding data and inject attacker-controlled files (logo, commercial-registration doc) into their profile. | None found in code. | **Open — unmitigated.** | Unassigned. | `workin-hr/hr-legacy` commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`: `apis/api/auth/complete_company_registration.php` lines 13–66 (no auth/token check before mutating). |
| Public/tenant ↔ session lifecycle, `apis/helpers/functions.php` + `apis/config/constants.php` | Spoofing, Elevation of Privilege | JWTs are valid for `AppConfig::JWT_EXPIRE_HOURS = 87600` hours (**10 years**) from issuance, for both auth types. For the `employee` auth type, a server-side `token_version` check gives *some* revocation — but only a **fresh login** bumps it (`employee_issue_session_token()`, called from `login_employee.php`/`login_desktop.php`'s HR path); neither `reset_password.php` nor any `profile/*` endpoint bumps it, so changing or resetting a password does **not** invalidate an already-issued employee token. For the `company` auth type (`login_company.php`, `login_desktop.php`'s admin path), there is no `token_version`-equivalent check at all — a company-admin JWT, once issued, cannot be revoked server-side by any means short of rotating the global `JWT_SECRET` (which would invalidate every tenant's sessions simultaneously). | Requires an already-leaked/stolen token (via any other vector — device compromise, log exposure, MITM, etc.), not a novel exploit path on its own — but the 10-year window means a token leaked once via *any* means remains a standing risk for years, and for company-admin tokens specifically there is no operational response available (short of the global-secret nuclear option) even after the leak is discovered. | High — a single leaked long-lived token, especially a company-admin one, grants durable, effectively unrevocable full-tenant access. | Employee tokens: partial (a fresh login by the legitimate user invalidates prior tokens, but nothing prompts that to happen after a suspected leak other than the user voluntarily logging in again). Company-admin tokens: none. | **Open — unmitigated for company-admin tokens; partially and only incidentally mitigated for employee tokens.** | Unassigned. | `workin-hr/hr-legacy` commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`: `apis/config/constants.example.php` line 20 (`JWT_EXPIRE_HOURS = 87600`), `apis/helpers/functions.php` (`jwtEncode()`, `requireEmployeeSessionValid()`, `employee_issue_session_token()`), `apis/api/auth/reset_password.php` (full file, no `token_version` reference), `apis/api/auth/login_company.php` / `login_desktop.php` (company-admin path issues a plain `jwtEncode()` with no version claim). |

| Within-tenant, `role=hr` privilege scope | Elevation of Privilege | `employees.hr_permissions` is a real 18-boolean-flag matrix (`can_employees`, `can_company_settings`, `can_payroll`, etc. — see `docs/migration/database-schema-inventory.md`), clearly designed so a company admin can grant an HR employee narrow, feature-specific access. In practice, only ~21 of the roughly 150+ endpoints reachable by `role='hr'` actually call `require_hr_permission()`/`require_company_settings_access()` to check it: `administrative_decisions/*` (5), `attendance_exception_types/{create,delete,update}.php` (3), `company_official_holidays/*` (5), `company_settings/*` (6), `company/update.php`, `employees/update.php`. Every other HR-accessible module checked in this Discovery pass — `payroll_batches`, `payslips`, `advances`, `penalties`, `salary_contracts`, `requests` (approve/reject), `attendance` (create/update/delete), `leave_balances`, `branches`, `workforce_planning`, `notifications`, `employee_docs`, `complaints`, `schedules`, `hr_employees`, and the `job_titles`/`departments`/`shifts`/`request_types`/`assets` lookup modules — authorizes purely on `role IN (COMPANY_ADMIN, HR)`, with no permission-flag check at all. | No special access required beyond being granted the generic `HR` role by a company admin — which, per the schema and the endpoints that do enforce permissions, a company admin may reasonably believe is a narrow, revocable grant. | Medium-High — an HR employee a company admin believes is restricted (e.g. to `can_employees` only) in fact has full read/write access to payroll batches, advance approval/payment, penalty management, salary contracts, and leave approval — every sensitive module this Discovery pass has covered except the handful listed above. | Partial — the permission system exists and is enforced for configuration-type modules; it is simply not wired into the operationally/financially significant ones. | **Open — unmitigated for the majority of HR-accessible functionality.** | Unassigned — needs a product decision on whether `hr_permissions` is meant to gate these modules and was never finished, or whether "HR role" was always intended as all-or-nothing and the matrix only ever covered the modules it currently covers. | `workin-hr/hr-legacy` commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`: `apis/helpers/hr_permissions.php` (`require_hr_permission()`, `require_company_settings_access()`); grep across `apis/api/**/*.php` for call sites, cross-referenced against every module documented in `docs/api/existing-endpoint-inventory.md`. |

## Specific Areas Requiring Coverage Once Discovery Exists

- Authentication and authorization (see ADR-0005, currently Proposed)
- Multi-tenant isolation (see `.specify/memory/constitution.md` Principle IV)
- PII handling (see `docs/security/logging-and-privacy.md`)
- Biometric data from attendance devices (see `docs/devices/`)
- Device-gateway and vendor-integration trust boundaries (see ADR-0006,
  currently Proposed)
- Database migration exposure window (see `docs/migration/`)
- Agent access boundaries (see `docs/agents/operating-model.md` Enforcement
  Layers — this is itself a threat surface: what happens if an agent's
  tool scope is misconfigured or a human misapplies Codex sandbox settings)
- Supply-chain risk (dependencies, CI actions, MCP servers — see
  `docs/tools/tool-catalog.md`)
- Abuse cases (e.g. attendance spoofing, tenant data leakage via
  misconfigured API scoping, credential stuffing)

## Evidence

None yet.

## Open Questions

- Who is the accountable owner for maintaining this threat model once
  Discovery starts?
- Does any compliance requirement (e.g. regional data-protection law
  applicable to the customers in scope) mandate a specific threat-modeling
  cadence or method beyond STRIDE?
