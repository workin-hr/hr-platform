# Moving The Baseline

The port is measured against one `hr-legacy` commit. Moving that pin is not a
version bump: it silently changes what "correct" means for every file, test,
schema, inventory and ledger that describes the old commit.

This checklist exists because **D-185 moved the baseline to `505004f` and the
sweep that followed was driven by route and page counts.** Counts cannot see a
changed constant inside a file that already existed. The audit that eventually
ran (**R-071**) found **ten** behavioural divergences across the 72 files that
commit touched, two of them money figures, one a PII disclosure, one a drift
gate that had been reporting green while stale — plus a governance ledger that
described an exception which no longer existed.

Work the categories below in order. Each one is here because something in it
was actually missed.

## 1. Get the real change set

```bash
cd ../hr-legacy
git diff --numstat <old-baseline> <new-baseline> -- 'apis/**' 'dashboard/**' \
  | sort -rn
```

Sort by churn and read **every file**, not a sample. The largest miss in R-071
sat in a 104-line diff, well below the top of the list.

## 2. Code — read the diff, not the filenames

For each file, extract the **behavioural** changes and ignore formatting. The
five shapes that a count-based sweep cannot see, in the order they bit:

- **A changed constant.** `attendance_open_session_max_hours()` went 18 → 16.
- **A guard that became a real bound.** The same commit made the open-session
  deadline `min(next shift, check_in + 16h)` rather than a fallback.
- **A function emptied but kept.** `attendance_auto_close_stale_open_sessions()`
  became `return 0` with all its call sites intact — PHP's own comment says
  "kept so existing call sites stay valid". The port kept doing the write.
- **A dropped SQL wrapper.** `COALESCE(NULLIF(col, 0), sum)` became
  `COALESCE(col, sum)`. One removed function call; an absent employee reported
  at full gross.
- **A widened list.** Four error codes added to a `match`, two modules to an
  allow-list, one key to `sensitive_response_keys()`. Every one of these falls
  through silently rather than failing.

Judge each against **today's PHP**, never against the pre-move PHP, and quote
`path:line` on both sides. `git show <baseline>:<path>` and the working file
should agree; if they do not, the pin moved again while you were working.

## 3. Tests — assume they agree with the port

This is the counter-intuitive one, and it is why a green suite proves nothing
after a baseline move. In R-071 **every** missed defect had passing tests:

- Nine asserted a key list **by name**, so a new key was invisible to all nine.
- A `days_present` assertion used a fixture with no weekly rest and no
  holidays — passing under both the old rule and the new one.
- Batch-stats fixtures never stored a legitimate zero and never used an
  allowance column other than housing: exactly the two points where the right
  and wrong SQL agree.
- A drift test compared the Java constant against a **vendored file that was
  equally stale**, so it agreed with itself.

So: when a test's *premise* was changed by the baseline, rewrite the test and
say which — never delete the assertion, and never weaken it to green. Prefer
assertions that read the authority (`SENSITIVE_KEYS`) over assertions that
restate it (`"password_hash", "token_version"`).

## 4. Schemas and vendored inventories — check both sides

A vendored copy has two failure modes and only one is obvious.

- It can **drift from upstream** — what the gates are built to catch.
- It can be **stale in lockstep with the code that reads it**, in which case
  the gate compares two copies of the same mistake and passes. This is what
  happened to `allowed_modules.txt`: its header still said
  `vendored from hr-legacy@d113204`, the commit *before* the baseline.

Re-vendor every copy and **check each file's own provenance header names the
new baseline**. Then run the real comparisons, which need a sibling checkout
and therefore cannot run in CI:

```bash
python3 scripts/check_legacy_schema_drift.py         --legacy ../hr-legacy
python3 scripts/check_legacy_modules_drift.py        --legacy ../hr-legacy
python3 scripts/check_legacy_route_drift.py          --legacy-api ../hr-legacy/apis/api
python3 scripts/check_legacy_lang_drift.py           --legacy ../hr-legacy
python3 scripts/check_legacy_message_drift.py        --legacy ../hr-legacy
python3 scripts/check_legacy_product_defaults_drift.py --legacy ../hr-legacy
python3 scripts/check_legacy_spreadsheet_columns_drift.py --legacy ../hr-legacy
python3 scripts/check_legacy_sensitive_keys_drift.py --legacy ../hr-legacy
python3 scripts/check_legacy_excel_error_codes_drift.py --legacy ../hr-legacy
```

See **A gate's output is evidence, not a verdict** below for what to do with
what they print.

## 5. Governance artifacts and ledgers — the category that has no gate

**Added 2026-09-09 (D-220, R-071).** Every category above is code the compiler
or a script can check. This one is prose, and nothing checks it.

A baseline move can invalidate a **recorded decision, an open question, an
exclusion ledger, or a gate's denominator** — and reading Java will never find
it. The worked example: O-3 excluded `/apis/api/time/now.php` from the live
endpoint obligation *because* `time` was absent from `ApiModule::allowedList()`,
so the route 404'd. `505004f` added the module. The route has been served by
both stacks ever since, while the ledger still carried it as the one permanent
exception and G2 still read `198 live + 1 excluded`.

So, after the code work:

- [ ] Grep the new commit's changes for anything a **decision, risk or open
      question** cites as a premise — a constant, an absence, a 404, a "not
      supported", a "cannot be exposed".
- [ ] Re-read every **exclusion, exemption and deferral**: does the reason it
      was granted still hold? An exception whose cause is gone is not a
      historical note, it is a false statement about the present.
- [ ] **Re-count** every gate denominator from the new baseline. Do not adjust
      the old figure. A derived number can be checked against its source; an
      adjusted one can only be checked against whoever adjusted it.
- [ ] Leave **historical decision-log entries alone** — they were true when
      written. Supersede them with a new entry instead.

## 6. Record it

One decision entry per behavioural change, naming the PHP `path:line` and the
Java `path:line`. Update the risk register where a risk's evidence moved. If
the sweep found nothing in a file, that is worth one line too — R-071 records
its clean areas precisely so the next sweep need not re-derive them.

## A gate's output is evidence, not a verdict

**A green exit code does not make a discrepancy acceptable.** A detector that
prints a concrete total is making an accounting statement, and that statement
has to be reconciled against whatever else in the repository claims to count
the same thing.

The worked example is not hypothetical. `check_legacy_route_drift.py` prints:

```text
committed legacy routes: 202   java inventory: 202
hr-legacy present: its 202 routes match the committed inventory.
OK: every legacy route is in the Java inventory.
```

It printed **202** through the entire period in which gate G2 read `198 live +
1 excluded = 199` and the exclusion ledger named a route both stacks were
serving. The detector was right, it was right in public, and it was right on
every run. Nobody reconciled it, because the last line said `OK` and the eye
stops there.

So, whenever a gate emits a number:

- [ ] Compare it against every **prose** figure that claims to count the same
      thing — gate denominators, ledgers, decision entries, plan totals.
- [ ] When they disagree, the **derived number wins** and the prose is the
      defect. Fix the prose; do not adjust the derived number to match it.
- [ ] Treat "the gate is green" and "the accounting is correct" as two separate
      questions. The first never answers the second: a gate compares the two
      things it was pointed at, and a ledger is usually not one of them.

## The rule underneath all of this

A port's own test suite cannot detect a baseline it was never shown. Neither
can a gate whose two sides are copies of each other, nor a ledger nobody
re-reads. After a baseline move, **the only authority is the new commit** —
everything else in this repository is a claim about it, and every one of those
claims needs re-checking against it.
