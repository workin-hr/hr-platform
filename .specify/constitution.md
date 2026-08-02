# Spec Kit Constitution Principles

1. Repository files are sources of truth.
2. Specifications must separate facts, assumptions, and open questions.
3. Preserve API compatibility where required by validated client behavior.
4. Multi-tenant isolation is a first-class design constraint.
5. Test obligations must be explicit and risk-based.
6. Database migration work must be evidence-driven and reversible where possible.
7. Security, least privilege, and sensitive-data protection are mandatory.
8. Performance budgets and observability expectations must be documented before implementation decisions depend on them.
9. External ingestion should be idempotent where repeat delivery is possible.
10. Attendance events should be treated as immutable facts.
11. Planning, implementation, and review roles must remain distinct.
12. No agent may approve or merge its own work.
13. No product implementation begins before approved discovery and architecture decisions justify it.
14. Sensitive production data and unrestricted credentials are never exposed to agents.
15. Human approval is mandatory before implementation and merge.
