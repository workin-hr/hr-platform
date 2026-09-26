# ADR-0020: Canonical Phone Numbers

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0020 |
| Title | Canonical Phone Numbers |
| Status | Proposed |
| Date | 2026-09-26 |
| Owners | Solution Architect |
| Deciders | Repository owner |
| Related Issues | None |
| Supersedes | None |
| Superseded By | None |

## Context

A phone number is the login identifier of every employee and company on the
legacy surface, and until now the Java port handled it the way PHP does:
`LegacyPhoneNumbers` was a literal port of `phone_validator_helper.php` --
hand-written regexes for Egypt, Saudi Arabia and the UAE, a
`phone_countries` table of lengths and prefixes for everything else -- and its
javadoc said it was **deliberately not libphonenumber**, because "a stricter or
looser library would silently change which people can log in". Parity was the
contract.

That produced four problems, each observable:

1. **One number, several identities.** `register_company`,
   `register_employee` and `login_company` matched the `phone` column exactly;
   `join_company`, `forgot_password` and the dashboard's uniqueness check
   matched a hand-written list of Egyptian spellings; `check_status` and
   `verify_otp` matched exactly again. So `01012345678` and `1012345678` were
   one account on some routes and two on others -- which is how **16 pairs of
   companies came to share one E.164 number under two spellings**.
2. **The login throttle could be bypassed by formatting.** D-289 keyed the
   per-phone budget on the ASCII digits of the request. `01012345678`,
   `+201012345678`, `1012345678` and `0020 10 1234 5678` have different
   digits, so each was a budget of its own: eight spellings bought eight times
   the guesses against one account.
3. **The lookup bound what the client typed**, so MariaDB's
   `utf8mb4_unicode_ci` decided what matched -- and it equates circled,
   dingbat and Hangzhou digits to ASCII ones. D-289 contained that with an
   allowlist (`bindablePhone`) and a whole-BMP sweep test.
4. **Validity was hand-maintained.** "Any Saudi number starting 05" accepts
   ranges that are not Saudi mobiles; a new country needed a table row whose
   prefixes somebody had to know.

The repository owner reversed the parity stance on 2026-09-26 and asked for
Google libphonenumber, E.164 as the identity, and one normalization component
used by registration, login, lookup, uniqueness, imports, updates and login
rate limiting alike.

**The production profile** the owner authorised for this decision
(2026-09-26, read-only, libphonenumber 9.0.40): every stored phone is valid
and is bare digits -- none holds `+`, a space or punctuation.
**Employees: 2,716** phones (2,712 Egyptian mobiles, 4 Saudi), 2,696 stored
nationally with the trunk zero and 20 without it; **0 canonical collisions**.
**Companies: 466** (456 Egyptian, 9 Saudi, 1 Emirati), 375 with the zero and
91 without; **16 pairs share one E.164 number** under two spellings.

**Coexistence.** `employees.phone` and `companies.phone` are
`varchar(20) UNIQUE` in the frozen vendored schema, which the still-live PHP
application also writes, and there is no migration tool (ADR-0017; the schema
is kept identical to legacy by `scripts/check_legacy_schema_drift.py`). An
E.164 column with a unique constraint cannot be added while PHP writes rows
that would not fill it, and it cannot be made unique while 16 companies hold
one number each.

## Decision

**Approval status: Proposed — this decision has not been approved.** The
repository owner directed the change on 2026-09-26; it is accepted when the
owner merges it, following independent review, and `Status` above changes
then.

**One component decides what a phone number is:
`com.workin.legacy.phone.CanonicalPhones`**, over
`com.googlecode.libphonenumber:libphonenumber:9.0.40`. No per-country pattern
remains in the repository.

- **Parsing.** The input is read with PHP's `(string)` cast, NFKC-folded, and
  every Unicode decimal digit (Nd -- Arabic-Indic, Persian, fullwidth) becomes
  ASCII. Letters, non-decimal number characters (circled, dingbat,
  superscript, Roman numerals) and extensions are refused, because the library
  would read a vanity number or an extension as part of an identity. Anything
  the library cannot parse, or whose metadata says `isValidNumber` is false, is
  not a phone number.
- **Country.** Input written internationally (`+` or `00`) carries its own
  country and ignores any context. Otherwise the explicit context -- the
  request's or a stored row's `country_code` -- decides. Otherwise Egypt. A
  context that names no libphonenumber region validates no national number;
  nothing is guessed.
- **Identity** is the E.164 form. `01012345678`, `(010) 1234-5678`,
  `+20 10 1234 5678` and `0020 10 1234 5678` are `+201012345678`.

**Product policy stays in `LegacyPhoneNumbers`**, now a thin layer over the
component:

- `forAccount(raw, countryCode)` -- what an account may be *given*: valid,
  typed mobile by the metadata (legacy's rules never accepted a landline in
  any country, so an Egyptian landline stays refused on every write path), and
  in an **offered** country: `phone_countries`' active rows (or its fallback
  rows when the table is absent) plus Egypt, Saudi Arabia and the UAE, which
  PHP's `phone_is_valid_local_legacy()` accepted whatever the table held.
  `phone_length` and `phone_prefixes` no longer decide validity; they are
  display data for the selectors.
- `lookup(raw)` -- the rows a number typed with no country refers to.

**Lookups never bind the client's input** (`PhoneLookup`). A lookup narrows
with `phone IN (...)` over the bounded set of stored digit spellings of each
reading of the number -- national with the metadata's trunk prefix, the
significant number alone, and `cc+NSN`, `+cc+NSN`, `00cc+NSN` -- which uses
the existing unique index, then **verifies** every candidate by canonicalising
the row's own `(phone, country_code)` and keeping only rows whose E.164 is the
request's. A number typed nationally with no context is read in Egypt and in
every offered country, and a row matches only when the input, read in *that
row's* country, is that row's number: `0501234567` finds the Saudi account
stored as `0501234567` under `+966`, because the row says so.

**Every route uses it.** `login_employee`, `login_company`, `login_desktop`,
`check_status`, `register_company`, `register_employee`, `join_company`, the
four OTP routes, `request_phone_change`/`confirm_phone_change`, the employee
profile update, the API's `employees/create.php`/`update.php` and HR create,
the spreadsheet create/import/update paths and the bulk updater, and the
dashboard's company and employee forms. Each keeps its own resolution rules
(newest-first versus oldest-first, `409 multiple_accounts_same_phone`, pending
and inactive handling) and its own uniqueness scope (per company, global,
rejected join rows absent) over the verified rows.

**The login throttle keys on E.164** (`api-phone:+201012345678`). Input
that does not normalise is refused before any lookup, answered as the route
answers an unknown phone, and charged to the address only. A national number
with several readings is charged under every reading, so any account the
lookup can reach was charged under its own number. `bindablePhone`'s
allowlist is removed: nothing the client typed reaches a `WHERE`, so the
collation can no longer widen a match.

**Two companies holding one number.** Where a route answers for one company
(`login_company`, `login_desktop`'s company branch, `register_employee`'s
company-by-phone, `verify_otp`'s `otp_verified` update), one verified row is
the answer; with more than one, the row stored exactly as the request's
digits wins, and with none stored that way the route answers as for an
unknown phone rather than choose an account for the caller. This preserves
today's behaviour for the 16 pairs' users, who type the spelling they
registered. `reset_password` still updates every company holding the number,
as PHP's variant match did.

**OTPs are keyed on E.164** -- `otp_codes.phone` and
`otp_request_logs.phone` -- so every spelling of a number shares one code, one
cooldown and one hourly cap, and an OTP is delivered to the number's own
country. A national number with several readings takes the country of the
stored account it belongs to -- employees first for an `employee` request,
otherwise companies first, as `otp_resolve_country_code_for_phone()` ordered
them -- else Egypt's. A code is delivered only to a country the product
offers (the set `forAccount` admits): `resend_otp` refuses any other number
as an invalid one, and `forgot_password` answers `phone_not_found` for an
account stored in one.

**Storage keeps the legacy convention.** Every Java write stores the
number's national digits from the metadata (`01012345678`, `0501234567`) in
`phone` and its own dial code (`+20`, `+966`) in `country_code`, so PHP and
the mobile clients see the data they always have. International input stores
its own dial code, never the request's. Because a national number is read
in its row's `country_code`, a write carrying `country_code` without `phone`
(the profile PUT, `update.php`, the company's `update.php`) re-reads the
stored phone under the new code through `forAccount` and the route's
uniqueness check, and is refused as a new phone would be when it does not
hold.

**Phase 2 (not now).** After PHP is retired and the 16 company pairs are
resolved, add a stored E.164 column to `employees` and `companies`, backfill
it from `CanonicalPhones`, make it `UNIQUE`, and move lookups onto it. That
is expand-migrate-contract on a live table and needs its own decision.

## Alternatives Considered

- **Keep the hand-written rules and fix the throttle key only** -- closes the
  bypass but keeps five matching mechanisms, the pairs, and a validator
  nobody can maintain. Rejected by the owner.
- **Store E.164 in `phone` now** -- PHP reads `phone` as national digits and
  the clients display it; every PHP route would stop matching. Rejected
  while PHP writes.
- **Match on a `REPLACE()`-stripped column expression**, as PHP's
  `phone_sql_match_clause()` did -- finds punctuated stored values but
  defeats the unique index. The production profile has no punctuated value,
  so the index-friendly `IN` was chosen and the assumption is re-checked
  before cutover (Risks).
- **Resolve a national number's country from the request alone** --
  login bodies carry no country, so every Saudi user typing their number as
  stored would stop signing in. Reading it in the row's own country keeps
  them, and charging every reading keeps the throttle whole.

## Consequences

- One dependency, Apache-2.0, no transitive dependencies
  (sha1 `d0d0042d44dca1f05bd6e8298b591e80c08d6c5e`).
- A national number typed at login costs one more statement (the offered
  countries), because a Libyan or Bahraini reading needs the table;
  international input costs none.
- Behaviour changes, each listed in D-291: formatting variants now find the
  account on every route; a duplicate in another spelling is refused at
  registration; update paths that stored raw digits unvalidated
  (`update.php`, the profile PUT, `register_employee`) validate now; a number
  the metadata rejects that the regexes accepted (a Saudi `052...`) is
  refused on writes; a `country_code` written alone is validated against the
  stored phone; `resend_otp` refuses an invalid number, or one outside the
  offered countries, with `invalid_phone_number`; `join_company` and `register_employee` write
  `country_code` (R-019 closed).
- An OTP issued by PHP is not verifiable by Java and vice versa during a
  cutover or rollback window (keys differ; codes live ten minutes).

## Risks

- **A value PHP writes with punctuation is invisible to Java's lookups** --
  PHP's `register_employee.php` stores a phone as sent. The profile found
  none; re-run the count (`phone REGEXP '[^0-9]'` on both tables) before
  cutover, and normalise any row it finds.
- **Metadata updates change validity.** A new libphonenumber release can
  make a stored number valid or invalid. Pin the version and treat an upgrade
  as a behaviour change with its own test run.
- **A stored row that does not canonicalise cannot sign in** -- nothing
  in the profile. A Java write of `phone` or of `country_code` alone is
  validated, so Java cannot produce one; a PHP write still can while PHP
  runs (PHP's `update.php` stores any `country_code`).

## Validation Evidence

- `CanonicalPhonesTest`: every listed human spelling of an Egyptian mobile is
  one number; landlines are numbers but not account numbers; Saudi, Emirati
  and Libyan numbers nationally with context and internationally without;
  refused inputs (letters, circled and dingbat digits, extensions,
  wrong lengths and ranges); stored spellings derived from the metadata; the
  national-reading and row-verification rules; the duplicate-pair rule.
- `LegacyLoginThrottleTest#missesSpreadAcrossSpellingsOfOneNumberSpendOneBudget`
  fails on the pre-change throttle (see D-291).
- `LegacyLoginPhoneCollationTest`: over the whole BMP, a code point the
  collation equates to a digit is refused or read as that digit, and a lookup
  binds only ASCII; circled and dingbat spellings the collation matches
  against a stored phone reach no account.
- `LegacyCanonicalPhoneEndToEndTest` (real HTTP, real MariaDB): login in
  other spellings, a Saudi row read in its own country, registration then
  login in another format, duplicate registration in another format,
  `join_company`'s scopes, `check_status`, the OTP routes across spellings,
  and the duplicate-pair rule on `login_company` and `login_desktop`. Run
  against `main` before this change, 11 of its 12 tests fail.
- The API and sheet paths: `LegacyEmployeeCreateEndToEndTest`,
  `LegacyEmployeeUpdateEndToEndTest`, `LegacyEmployeeImportBulkEndToEndTest`
  and `LegacyEmployeeUpdateBulkEndToEndTest` assert canonical uniqueness and
  storage.

## Open Questions

- The 16 company pairs: which of each pair is the real account is an owner
  decision, and phase 2 cannot start until it is made.
