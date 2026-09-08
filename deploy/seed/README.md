# The development seed

`dev-seed.sql` is a **sanitised copy of production**. Real in shape, fake in
content.

## What survives, and why

Row counts, foreign-key topology, dates, enum distributions, and the order of
magnitude of every money column. 386 companies, 3,783 employees, 44,756
attendance rows, 3,741 salary contracts — production's actual volumes.

That is the whole reason this exists rather than a handful of fabricated rows.
Pagination behaves as it will in production, list queries are as slow as they
really are, payroll arithmetic runs over realistic figures, and an RTL layout
bug shows up because the names are Arabic.

## What does not survive

| Category | Replaced with |
|---|---|
| Names (people, companies, branches, departments, job titles) | a fabricated Arabic pool, indexed by row id |
| Phone numbers | `010000NNNNN` — format-valid, dialable by nobody |
| Emails | `…@example.invalid` |
| Passwords | one known bcrypt hash; the plaintext is **`devpassword`** |
| National ids | generated from the row id |
| Addresses | `N Example Street, District M` |
| Coordinates (branches, attendance check-ins) | a grid in the Atlantic |
| Document, photo, logo URLs | `https://seed.example.invalid/…` |
| Branch QR codes | `devseed-qr-N` — these are check-in credentials |
| Free text (penalty reasons, complaints, requests, notifications, assets) | `Seed: …` |
| Salaries and every other amount | scaled per row by a factor derived from its id |
| OTP codes, request logs, push tokens, platform admins | **deleted** |

The money columns are scaled rather than flattened: the spread and the order of
magnitude survive, so the rounding parity `PhpMath` exists for is still
exercised, but no stored figure is anyone's actual pay.

## Regenerating it

You need a production dump. It is not in this repository and must not be.

```sh
scripts/build_dev_seed.sh path/to/production-dump.sql
```

That stands up a throwaway MariaDB, restores the dump, applies
[`sanitise.sql`](sanitise.sql), dumps the result here, destroys the container,
and then runs the gate. **The gate decides whether the output may be
committed** — the script succeeding proves nothing on its own.

Generation is deterministic: every value is a function of its row's id, so
regenerating from the same dump produces the same file, and a diff shows only
what actually changed upstream.

## The gate

`scripts/check_dev_seed_sanitised.py`, run by `validate_phase0.py` and in CI.

This file is **committed**, so it is in git history permanently and the
sanitisation gets exactly one chance to be right. Three independent checks,
because each catches a different way of being wrong:

1. **Value shapes** — scans for bcrypt hashes, dialable numbers, real email
   domains, JWTs, external upload URLs. Catches a column the SQL forgot.
2. **Column coverage** — re-derives every identity-bearing *and* every
   free-text column from the vendored schema and requires each to be declared
   with a `-- covers:` line or listed as structural with a reason. Catches a
   column that did not exist when the sanitiser was written, which a value scan
   cannot see because a column absent from the dump leaves no trace in it.
3. **Sentinels** — requires the sanitiser's own markers. If this file were ever
   replaced by a raw dump, the value scan might pass on a quiet table, but the
   markers would be gone.

Both exemption lists are self-policing: an entry the schema no longer has
fails the gate rather than outliving its reason.

### It has caught real leaks

The first generated seed carried **nineteen real Egyptian mobile numbers** in
`assets.asset_text` — a SIM-card asset records the number on it, and the column
name says nothing about identity, so only the value scan saw them. Check 2 was
then widened from identity-*named* columns to every free-text column, so the
class cannot recur.

The first sentinel hash was bcrypt-*shaped* rather than a real hash. It passed
every check here and then failed at the only thing that mattered: logging in.
It is now a genuine hash, verified to accept `devpassword` and reject anything
else.
