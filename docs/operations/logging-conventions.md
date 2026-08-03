# Logging Conventions

This is a proposed structured-logging field contract, written ahead of any
backend, gateway, or admin-web implementation so the first real code has a
spec to conform to instead of inventing its own shape. It is a
specification, not evidence that any logging pipeline exists yet — no
signal listed here has been implemented, and none should be assumed
observable until `docs/operations/monitoring-and-alerting.md`'s Source
System rows record a real one.

This document must not be read as looser than
`docs/security/logging-and-privacy.md`; where the two could be read to
conflict, the restrictions in that document win.

## Status

Proposed. Ownership of formal approval belongs to whoever ADR-0008
(Observability Baseline) assigns as Decider once it has real Discovery
evidence — see `docs/bootstrap/decision-log.md` D-009 for who currently
owns that domain.

## Required Fields

Every structured log line emitted by this system, once implementation
begins, should carry:

- `timestamp` — UTC, ISO 8601, with sub-second precision if the emitting
  runtime provides it
- `correlation_id` — a request or workflow identifier that lets one
  operation be traced across service and process boundaries; how it
  propagates across service boundaries and background job chains is not
  yet decided (see Open Questions)
- `severity` — one of a small, fixed set (e.g. `debug`, `info`, `warn`,
  `error`, `critical`); the exact set is not yet decided
- `service` — the emitting component or service boundary (see
  `docs/architecture/module-boundaries.md`), not a free-form string per
  call site
- `event` — a short, stable, machine-readable identifier for what
  happened (e.g. `attendance.sync.failed`), not a free-form sentence;
  human-readable detail belongs in a separate `message` field, not in
  `event` itself
- `tenant_id` — mandatory whenever the log line concerns tenant-scoped
  data or behavior. `docs/security/logging-and-privacy.md` requires tenant
  context to "remain explicit in design and testing"; a log line about
  tenant-scoped activity with no tenant context does not satisfy that
- `actor` — the human, system, or agent identity that caused the event,
  when one exists and is known

## Explicitly Excluded

Per `docs/security/logging-and-privacy.md`'s Logging Restrictions:

- secrets, credentials, tokens, or API keys, in any field, ever
- biometric raw data — attendance and device integration logging must
  record match/no-match outcomes and device/event metadata only, never a
  fingerprint template, face embedding, or other raw biometric payload
- personal data beyond what is operationally necessary; prefer a stable
  identifier (e.g. an internal user or employee id) over names, contact
  details, or other directly identifying fields unless a specific
  operational need is documented

If a future signal appears to require logging something in this list,
that is a signal the design needs to change, not that this document needs
a carve-out — escalate per `docs/security/logging-and-privacy.md`'s
Incident Escalation guidance if it already happened.

## Format

Structured (JSON or an equivalent key-value form), not free-form string
concatenation — every field above must be independently parseable so it
can feed the eventual observability baseline (ADR-0008) rather than
requiring log-scraping regexes to reconstruct it later.

## Ownership

Record the human role accountable for this contract once ADR-0008 moves
toward Accepted. Until then, treat it as `Not yet discovered`.

## Evidence

None yet. This document is the specification; evidence that it is
actually followed (a real logging library choice, a shared wrapper
module, a sample log line, a lint or CI check enforcing the fields above)
should be linked here once it exists — see Open Questions for why no such
check is added yet.

## Open Questions

- Which logging library or framework will be used, once a backend
  language and framework direction has real Discovery evidence behind it
  (see ADR-0002, ADR-0006) rather than only the placeholder boundary text
  in `backend/README.md` and `edge-gateway/README.md`?
- Should there be one shared logging wrapper module per component
  boundary, or a per-language convention documented separately for each?
  A CI check enforcing "uses the shared wrapper, not ad hoc output" is
  deliberately not added yet — it would have to guess at a specific
  language's logging API before any real backend code exists, which
  would be encoding a product-domain assumption this document is
  explicitly trying not to make.
- How does `correlation_id` propagate across an asynchronous or
  background-job boundary, not just a single synchronous request?
- What is the retention period for logs containing `tenant_id`, and does
  it differ from logs that carry no tenant-scoped data at all?
