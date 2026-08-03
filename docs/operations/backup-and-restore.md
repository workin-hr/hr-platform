# Backup And Restore

## System Or Data Set

Identify the system, database, file set, or integration state that requires
backup and restore planning. Record separate entries when different recovery
paths or retention expectations apply.

## Backup Method (candidate, pending Discovery and ADR)

Describe the candidate backup approach without pretending the tooling or
schedule is already final.

Capture:

- what is backed up
- how the backup is created
- whether the backup is full, incremental, snapshot-based, or another pattern
- where the backup is stored
- whether encryption, isolation, or access restrictions apply

If a reliable backup path is not yet known, say so plainly.

## Backup Frequency

State the intended backup cadence or note that it is `Not yet discovered`.

The correct frequency should be based on business tolerance for data loss and
the recovery objectives recorded in `recovery-objectives.md`, not on default
habit.

## Restore Procedure

Document the restore path in enough detail that a reviewer can see whether
recovery is plausible.

Capture:

- prerequisites for restore
- who can authorize restore
- the high-level restore sequence
- how data correctness is checked after restore
- whether customer communication is required

If restore depends on steps that are currently unknown, record those as open
questions rather than claiming the procedure is complete.

## Last Restore Test (date and result — leave blank until one actually happens)

Do not invent test history. Once restore testing exists, record the date,
scope, result, and evidence link.

## Owner

Record the human role responsible for backup policy, restore execution, and
periodic restore verification.

## Evidence

Link the artifacts that prove the backup and restore position is real, such
as:

- backup-policy decision
- restore test result
- recovery validation checklist
- access-control record
- release or incident evidence that used the restore plan

If no evidence exists yet, treat backup and restore as unresolved.

## Open Questions

- Which data sets are business-critical enough to require independent backup
  coverage?
- What retention, encryption, and isolation requirements apply?
- Which restores can be practiced safely before production exists?
- How will restored data be validated for correctness?
- Which restore paths require customer or regulatory communication?
