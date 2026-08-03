# Customer Communication

Use this document to plan external and customer-facing communication for any
change or event that could affect customer expectations, service availability,
workflow continuity, data confidence, or trust.

Communication readiness is part of release readiness. If a release, migration,
incident, or deprecation requires customer notice, that notice should exist as
an explicit artifact with an owner, intended audience, channel, timing, and
evidence of preparation or delivery.

## Scenario (planned maintenance, incident, migration cutover, deprecation)

Describe the communication scenario in concrete terms.

Typical scenarios include:

- planned maintenance
- migration cutover
- degraded service or outage
- delayed data processing or synchronization
- deprecation of an endpoint, workflow, or device path
- customer-visible bug with workaround guidance
- rollback after a customer-affecting release

If a scenario does not require customer communication, record why that is a
safe decision rather than assuming silence is acceptable.

## Audience

Identify exactly who needs the message.

Possible audiences include:

- all customers
- a subset of affected tenants
- customer administrators
- employees using a specific workflow
- internal support, sales, or account-management teams
- implementation partners or device vendors

If the audience depends on Discovery that has not happened yet, mark it `Not
yet discovered`.

## Channel

Record the communication path intended for the scenario.

Possible channels include:

- status page
- email
- in-product notice
- support ticket or account-manager outreach
- admin-only announcement
- internal operations channel for customer-support coordination

Do not invent a live communication channel that has not been established. If
the channel is unknown, say so explicitly.

## Notice Period

State how much lead time or response time is required.

Examples:

- advance notice for planned maintenance
- immediate notification for active incident impact
- follow-up notification after rollback
- staged reminder sequence for deprecation

If the required timing is not yet decided, mark it `Not yet discovered` and
record the risk of not knowing.

## Ownership (who drafts and sends it)

Record the human roles responsible for:

- drafting the message
- approving the message
- sending or publishing the message
- answering follow-up questions from customers or internal stakeholders

Agents may help draft communication plans, but humans own final publication
and customer interaction.

## Evidence

Link the artifacts proving the communication was prepared or delivered. Useful
evidence includes:

- drafted message text
- approval record
- status-page entry
- sent email or notification record
- support-team briefing note
- customer list or tenant-impact mapping used to target the message
- follow-up confirmation after the event completes

If communication is required but no evidence exists yet, treat that as an open
gap rather than as implicit readiness.

## Open Questions

- Which customer communication channels actually exist in the live operating
  model?
- Which scenarios require advance notice versus immediate or post-event
  notice?
- Which human role owns final customer communication approval?
- How are affected customers or tenants identified for targeted messages?
- Which releases are low risk enough that internal-only communication is
  acceptable?
