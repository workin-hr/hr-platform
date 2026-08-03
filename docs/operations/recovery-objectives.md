# Recovery Objectives

Recovery Time Objective (RTO) and Recovery Point Objective (RPO) values are
**not yet discovered**. Do not fill in numbers here without a cited
business decision or measured constraint — a guessed RTO/RPO is worse than
none, because it will be treated as a real commitment.

## System Or Data Set

Record the system, workflow, or data set the objective applies to. Different
areas may need different recovery commitments.

## RTO (Not yet discovered, unless evidenced)

Recovery Time Objective is the maximum acceptable time to restore the system
or capability after disruption. Leave this as `Not yet discovered` until a
human decision or measured constraint exists.

## RPO (Not yet discovered, unless evidenced)

Recovery Point Objective is the maximum acceptable data loss window. Leave
this as `Not yet discovered` until business and technical evidence support a
real number.

## Basis For The Number (business requirement, technical constraint — required once a number is filled in)

Every RTO or RPO value must cite why it exists. Valid bases include:

- business continuity requirement
- legal or contractual obligation
- operational dependency
- measured restore capability
- measured backup frequency or replication limit

An uncited number is not acceptable.

## Owner

Record the human role accountable for approving or maintaining the objective.

## Evidence

Link the artifacts that justify the objective, such as:

- business-impact analysis
- restore-test evidence
- backup-policy evidence
- contractual or compliance requirement
- architecture or operational decision record

If no evidence exists, the objective remains undiscovered.

## Open Questions

- Which systems need distinct RTO and RPO targets rather than inheriting one
  default?
- Which objectives are driven by customer workflow impact versus internal
  operational convenience?
- What restore and backup evidence is required before a number can be
  committed?
- Which objectives must be agreed before production rollout is allowed?
