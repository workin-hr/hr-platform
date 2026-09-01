# Project Charter

## Business Objective

Modernize the current HR platform in a way that improves maintainability, reliability, delivery speed, and long-term product capability without breaking existing customers, employee workflows, or attendance operations.

## Modernization Objective

Prepare an evidence-driven transition from a shared PHP and MySQL production
system toward a Java, Spring Boot, and controlled integration architecture
while preserving critical business behavior and Flutter compatibility where
required.

**Current phase scope (D-151, 2026-09-01).** The work in flight is the
PHP-to-Java port at parity, with the admin web surface rebuilt as JTE pages
inside the same Spring application. Two items named in the original
objective are *not* in the current phase:

- **Next.js** is not used at all. The admin portal is JTE, server-side
  rendered in-process — see **ADR-0015**, which supersedes ADR-0014.
- **PostgreSQL** remains the accepted long-term target (**ADR-0004**), but
  the MySQL-to-PostgreSQL migration and its ETL are **Phase 2 and out of
  scope now**; they are not to be advanced. The port runs against the
  existing MySQL schema.

Enhancements during the port stay limited to implementation quality,
performance, reliability, and transactional correctness. Business behaviour
does not change unless a separate decision explicitly approves it.

## Current System

- Shared pure PHP backend for admin and employee applications
- PHP admin portal frontend
- Flutter mobile and desktop employee applications
- MySQL database
- Existing production customers, employees, attendance data, and operational dependencies

## Target Direction

- Java 25
- Spring Boot 4.x
- PostgreSQL latest stable release *(long-term target, ADR-0004; the
  migration is Phase 2 and out of scope now — D-151)*
- JTE admin portal, server-side rendered inside the Spring application
  *(ADR-0015, supersedes ADR-0014; Next.js is not used — D-151)*
- Existing Flutter UI retained with compatible contract evolution
- Multi-tenant HR platform
- Attendance-device integration through push, polling, vendor APIs, and a local .NET edge gateway
- Strong testing, security, observability, performance, migration validation, and operational reliability

## Initial Constraints

- No product implementation during Phase 0
- No undocumented production behavior assumptions
- No unrestricted production or organization credentials for agents
- No microservices or heavy platform additions without evidence and approved ADRs
- Existing customer impact must remain visible in planning

## Phase 0 Scope

- repository governance and structure
- documentation system
- agent operating model
- skills and templates
- backlog and GitHub governance guidance
- validation scripts and lightweight CI
- empty future component boundaries only

## Discovery Scope

- legacy PHP behavior
- API and Flutter compatibility
- database and migration constraints
- attendance-device landscape
- MVP scope and non-functional requirements

## Explicit Non-Goals

- building application code
- creating infrastructure stacks
- creating migrations or schemas
- finalizing unresolved architecture decisions
- moving Flutter into the repository during Phase 0

## Human Decision Ownership

Humans own final decisions for product scope, architecture, security, compliance, production access, risk acceptance, and merge approval.

## Agent Decision Boundaries

Agents may prepare documentation, analysis, templates, validation, and proposals. Agents must not silently finalize unresolved architecture, requirement, or production decisions.
