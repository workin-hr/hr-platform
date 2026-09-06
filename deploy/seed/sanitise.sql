-- Turn a restored production database into the committed development seed.
--
-- Run by scripts/build_dev_seed.sh against a THROWAWAY container holding a
-- production dump. It rewrites in place; the container is then dumped and
-- destroyed. It is never run against anything else, and it would be a
-- catastrophe if it were -- see the guard at the top of that script.
--
-- WHAT SURVIVES: row counts, foreign-key topology, dates, enum
-- distributions, and the order of magnitude of every money column. That is
-- what makes the seed worth having over fabricated rows -- pagination,
-- payroll arithmetic and list performance behave as they do in production.
--
-- WHAT DOES NOT: every name, phone, email, address, coordinate, credential,
-- document URL and free-text field, and the exact value of every amount.
--
-- Each rewrite declares the columns it handles with a `-- covers:` line.
-- scripts/check_dev_seed_sanitised.py re-derives the identity-bearing columns
-- from the vendored schema and fails if any is neither declared here nor
-- listed as structural with a reason. A column added to the schema later
-- therefore fails the build rather than leaking.
--
-- Determinism: every generated value is a function of the row's own id, so
-- two runs over the same dump produce the same seed and the diff of a
-- regenerated seed shows only what actually changed upstream.

SET SESSION sql_mode = '';
SET SESSION group_concat_max_len = 1000000;

-- ---------------------------------------------------------------------------
-- Credentials and tokens
-- ---------------------------------------------------------------------------

-- One known hash for every account, so a developer can sign in as anybody.
-- The plaintext is `devpassword` and that is written down on purpose: a seed
-- nobody can log into teaches nothing. It is a REAL bcrypt hash, generated
-- once with a fixed salt so it is deterministic and verified to accept
-- `devpassword` and reject anything else -- the first version of this line was
-- merely bcrypt-SHAPED, which passed every check here and then failed at the
-- only thing that mattered: logging in.
--
-- It is also the gate's sentinel: its absence means the file is not this
-- script's output, so nothing the gate says about the file proves anything.
-- covers: companies.password_hash, employees.password_hash
UPDATE companies SET password_hash = '$2y$10$devseeddevseeddevseeduYBc87okABDRtGIddmHD7eX.WbjTDHUC';
UPDATE employees SET password_hash = '$2y$10$devseeddevseeddevseeduYBc87okABDRtGIddmHD7eX.WbjTDHUC';

-- Push tokens address a real device. An FCM token left in a shared seed is a
-- channel to a real person's phone.
-- covers: push_tokens.token
UPDATE push_tokens SET token = CONCAT('devseed-push-token-', id);

-- The branch QR is the attendance check-in credential: whoever holds it can
-- register attendance at that branch.
-- covers: branches.qr_code
UPDATE branches SET qr_code = CONCAT('devseed-qr-', id);

-- OTPs and the addresses that requested them. The code is a live credential
-- for its window, and the IP identifies a person's connection.
-- covers: otp_codes.code, otp_codes.phone, otp_codes.ip_address
UPDATE otp_codes SET
  code = LPAD(id % 1000000, 6, '0'),
  phone = CONCAT('010000', LPAD(id % 100000, 5, '0')),
  ip_address = CONCAT('198.51.100.', id % 256);

-- covers: otp_request_logs.phone, otp_request_logs.ip_address
UPDATE otp_request_logs SET
  phone = CONCAT('010000', LPAD(id % 100000, 5, '0')),
  ip_address = CONCAT('198.51.100.', id % 256);

-- ---------------------------------------------------------------------------
-- People and organisations
-- ---------------------------------------------------------------------------

-- Names come from a small fabricated pool indexed by id, so they read as
-- names rather than as `Employee 4821` -- a seed that looks like data gets
-- exercised like data. Arabic and English both, because the product is
-- bilingual and a Latin-only seed would never surface an RTL layout bug.
--
-- Phone numbers are FORMAT-VALID (the application validates against
-- phone_countries.phone_prefixes, and a number it rejects breaks every login
-- flow this seed exists to exercise) but unmistakably synthetic: 010 then
-- five zeros. The gate permits exactly that block and no other.
-- covers: employees.first_name, employees.last_name, employees.phone,
-- covers: employees.national_id, employees.address, employees.photo_url,
-- covers: employees.employee_code
UPDATE employees SET
  first_name = ELT(1 + (id % 10),
    'أحمد', 'فاطمة', 'محمد', 'مريم', 'يوسف', 'نور', 'عمر', 'سارة', 'خالد', 'ليلى'),
  last_name = ELT(1 + ((id DIV 10) % 8),
    'حسن', 'إبراهيم', 'عبدالله', 'منصور', 'سالم', 'رشيد', 'فؤاد', 'نجم'),
  phone = CONCAT('010000', LPAD(id % 100000, 5, '0')),
  national_id = CONCAT('2', LPAD(id % 10000000000000, 13, '0')),
  address = CONCAT(1 + (id % 200), ' Example Street, District ', 1 + (id % 12)),
  photo_url = CASE WHEN photo_url IS NULL OR photo_url = '' THEN photo_url
                   ELSE CONCAT('https://seed.example.invalid/photos/', id, '.jpg') END,
  employee_code = CONCAT('E', LPAD(id, 6, '0'));

-- covers: companies.company_name, companies.first_name, companies.last_name,
-- covers: companies.phone, companies.email, companies.company_code,
-- covers: companies.commercial_reg_url, companies.logo_url,
-- covers: companies.rejection_reason, companies.main_branch_address
UPDATE companies SET
  company_name = CONCAT(ELT(1 + (id % 8),
    'شركة النيل', 'مجموعة الأفق', 'مؤسسة البناء', 'شركة الواحة',
    'مجموعة السلام', 'شركة الفجر', 'مؤسسة الرواد', 'شركة المستقبل'),
    ' ', id),
  first_name = ELT(1 + (id % 6), 'طارق', 'هدى', 'سمير', 'أمل', 'باسم', 'رانيا'),
  last_name = ELT(1 + ((id DIV 6) % 5), 'الشريف', 'العتيبي', 'حجازي', 'قنديل', 'زيدان'),
  phone = CONCAT('010000', LPAD(90000 + (id % 9999), 5, '0')),
  email = CONCAT('company', id, '@example.invalid'),
  company_code = CONCAT('C', LPAD(id, 5, '0')),
  commercial_reg_url = CASE WHEN commercial_reg_url IS NULL OR commercial_reg_url = ''
                            THEN commercial_reg_url
                            ELSE CONCAT('https://seed.example.invalid/registrations/', id, '.pdf') END,
  logo_url = CASE WHEN logo_url IS NULL OR logo_url = '' THEN logo_url
                  ELSE CONCAT('https://seed.example.invalid/logos/', id, '.png') END,
  rejection_reason = CASE WHEN rejection_reason IS NULL OR rejection_reason = ''
                          THEN rejection_reason ELSE 'Seed: rejection reason redacted' END,
  main_branch_address = CONCAT(1 + (id % 200), ' Example Avenue, City ', 1 + (id % 9));

-- Branch coordinates are a real place of work, and the geofence radius around
-- them makes them precise. Rewritten onto a grid in the Atlantic so a map view
-- still renders and nothing points at a customer's office.
-- covers: branches.name, branches.address, branches.latitude, branches.longitude
UPDATE branches SET
  name = CONCAT(ELT(1 + (id % 5), 'الفرع الرئيسي', 'فرع الشمال', 'فرع الجنوب',
                    'فرع الشرق', 'فرع الغرب'), ' ', id),
  address = CONCAT(1 + (id % 150), ' Example Road, Zone ', 1 + (id % 7)),
  latitude = ROUND(0.0 + ((id % 400) * 0.01), 7),
  longitude = ROUND(-30.0 + ((id % 400) * 0.01), 7);

-- Attendance carries the employee's location at check-in: a movement trace,
-- and the most intrusive column in the database.
-- covers: attendance.latitude, attendance.longitude
UPDATE attendance SET
  latitude = CASE WHEN latitude IS NULL THEN NULL ELSE ROUND(0.0 + ((id % 400) * 0.01), 7) END,
  longitude = CASE WHEN longitude IS NULL THEN NULL ELSE ROUND(-30.0 + ((id % 400) * 0.01), 7) END;

-- Org structure. R-051 records that these names are competitor-visible
-- business information even without a person attached, which is why they are
-- rewritten rather than kept for realism.
-- covers: departments.name
UPDATE departments SET name = CONCAT(ELT(1 + (id % 8),
  'الموارد البشرية', 'المالية', 'المبيعات', 'التشغيل',
  'التسويق', 'الدعم الفني', 'المشتريات', 'الجودة'), ' ', id);

-- covers: job_titles.name
UPDATE job_titles SET name = CONCAT(ELT(1 + (id % 8),
  'مدير', 'مشرف', 'أخصائي', 'فني', 'محاسب', 'مندوب', 'سائق', 'إداري'), ' ', id);

-- covers: shifts.name
UPDATE shifts SET name = CONCAT('وردية ', id);

-- covers: employee_schedules.name, employee_schedules.exception_note
UPDATE employee_schedules SET
  name = CONCAT('جدول ', id),
  exception_note = CASE WHEN exception_note IS NULL OR exception_note = ''
                        THEN exception_note ELSE CONCAT('Seed note ', id) END;

-- covers: company_official_holidays.name
UPDATE company_official_holidays SET name = CONCAT('إجازة رسمية ', id);

-- ---------------------------------------------------------------------------
-- Documents
-- ---------------------------------------------------------------------------

-- Identity documents: passports, national id scans, contracts. The URL is the
-- whole exposure -- the files themselves are not in the dump, but a live URL
-- is a link to them.
-- covers: employee_docs.file_url
UPDATE employee_docs SET file_url = CONCAT('https://seed.example.invalid/docs/', id, '.pdf');

-- covers: banners.image_url
UPDATE banners SET image_url = CONCAT('https://seed.example.invalid/banners/', id, '.png');

-- ---------------------------------------------------------------------------
-- Free text
-- ---------------------------------------------------------------------------
--
-- Every one of these is written by a human about a human. A penalty reason
-- names the person and what they did; a complaint reply is a conversation.
-- None of it can be pattern-matched safely, so all of it is replaced.

-- covers: penalties.reason
UPDATE penalties SET reason = CONCAT('Seed: penalty reason ', id);

-- covers: advances.reason, advances.rejection_reason
UPDATE advances SET
  reason = CONCAT('Seed: advance reason ', id),
  rejection_reason = CASE WHEN rejection_reason IS NULL OR rejection_reason = ''
                          THEN rejection_reason ELSE CONCAT('Seed: rejection ', id) END;

-- covers: requests.notes, requests.reply
UPDATE requests SET
  notes = CASE WHEN notes IS NULL OR notes = '' THEN notes ELSE CONCAT('Seed: request note ', id) END,
  reply = CASE WHEN reply IS NULL OR reply = '' THEN reply ELSE CONCAT('Seed: reply ', id) END;

-- covers: complaints.name, complaints.email, complaints.phone, complaints.reply
UPDATE complaints SET
  name = CONCAT('Seed Complainant ', id),
  email = CONCAT('complainant', id, '@example.invalid'),
  phone = CONCAT('010000', LPAD(id % 100000, 5, '0')),
  reply = CASE WHEN reply IS NULL OR reply = '' THEN reply ELSE CONCAT('Seed: reply ', id) END;

-- Equipment issued to an employee, described by hand. A SIM-card asset records
-- the number on it, which is how nineteen real mobile numbers survived the
-- first run of this script -- the column name says nothing about identity, and
-- only the value scan saw them.
-- covers: assets.asset_text
UPDATE assets SET asset_text = CONCAT('Seed: issued asset ', id);

-- The complaint itself: written by a person, usually about a person.
-- covers: complaints.message
UPDATE complaints SET message = CONCAT('Seed: complaint body ', id);

-- covers: administrative_decisions.title, administrative_decisions.body
UPDATE administrative_decisions SET
  title = CONCAT('قرار إداري ', id),
  body = CONCAT('Seed: decision body ', id);

-- covers: notifications.title, notifications.body
UPDATE notifications SET
  title = CONCAT('إشعار ', id),
  body = CONCAT('Seed: notification body ', id);

-- ---------------------------------------------------------------------------
-- Money
-- ---------------------------------------------------------------------------
--
-- Not identity, and not required by the gate -- rewritten anyway. Salaries are
-- the customers' commercial position and their employees' private business,
-- and a seed shared with contractors should not carry either.
--
-- Scaled per row rather than flattened: the ORDER OF MAGNITUDE and the spread
-- survive, so payroll arithmetic, currency formatting and the rounding parity
-- PhpMath exists for are all still exercised on realistic figures, while no
-- stored amount is anyone's actual pay. The factor is derived from the row id,
-- so it is deterministic but not a single constant anyone could divide out.

UPDATE salary_contracts SET
  basic_salary = ROUND(basic_salary * (0.70 + ((id * 37) % 61) / 100), 2),
  daily_wage = CASE WHEN daily_wage IS NULL THEN NULL
                    ELSE ROUND(daily_wage * (0.70 + ((id * 37) % 61) / 100), 2) END,
  housing_allowance = ROUND(housing_allowance * (0.70 + ((id * 37) % 61) / 100), 2),
  transport_allowance = ROUND(transport_allowance * (0.70 + ((id * 37) % 61) / 100), 2),
  food_allowance = ROUND(food_allowance * (0.70 + ((id * 37) % 61) / 100), 2),
  risk_allowance = ROUND(risk_allowance * (0.70 + ((id * 37) % 61) / 100), 2),
  incentives = ROUND(incentives * (0.70 + ((id * 37) % 61) / 100), 2);

UPDATE payslips SET
  basic_salary = ROUND(basic_salary * (0.70 + ((id * 37) % 61) / 100), 2),
  allowances = ROUND(allowances * (0.70 + ((id * 37) % 61) / 100), 2),
  overtime_pay = ROUND(overtime_pay * (0.70 + ((id * 37) % 61) / 100), 2),
  net_salary = ROUND(net_salary * (0.70 + ((id * 37) % 61) / 100), 2),
  gross_salary = ROUND(gross_salary * (0.70 + ((id * 37) % 61) / 100), 2),
  total_entitlements = ROUND(total_entitlements * (0.70 + ((id * 37) % 61) / 100), 2),
  total_deductions = ROUND(total_deductions * (0.70 + ((id * 37) % 61) / 100), 2);

UPDATE advances SET
  amount = ROUND(amount * (0.70 + ((id * 37) % 61) / 100), 2),
  remaining = ROUND(remaining * (0.70 + ((id * 37) % 61) / 100), 2);

-- ---------------------------------------------------------------------------
-- Volatile rows nobody should inherit
-- ---------------------------------------------------------------------------
--
-- Emptied rather than rewritten: they are per-session state whose only effect
-- in a fresh environment would be confusing. A live OTP row makes a developer
-- think a code is pending; a stale push token makes the app try to deliver.
DELETE FROM otp_codes;
DELETE FROM otp_request_logs;
DELETE FROM push_tokens;

-- The platform administrators of the real deployment, with their real TOTP
-- seeds. The development stack bootstraps its own from environment variables.
DELETE FROM platform_admin_mfa;
DELETE FROM platform_admin_audit_events;
DELETE FROM platform_admins;
