# Project Charter

## Business Objective

Modernize the current HR platform in a way that improves maintainability, reliability, delivery speed, and long-term product capability without breaking existing customers, employee workflows, or attendance operations.

## Modernization Objective

Prepare an evidence-driven transition from a shared PHP and MySQL production system toward a Java, Spring Boot, PostgreSQL, Next.js, and controlled integration architecture while preserving critical business behavior and Flutter compatibility where required.

## Current System

- Shared pure PHP backend for admin and employee applications
- PHP admin portal frontend
- Flutter mobile and desktop employee applications
- MySQL database
- Existing production customers, employees, attendance data, and operational dependencies

## Target Direction

- Java 25
- Spring Boot 4.x
- PostgreSQL latest stable release
- Next.js 16 admin portal
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
