# Decision Log

## D-001 Repository Source Of Truth

Repository files are authoritative. Chat history is not.

## D-002 Phase 0 Scope

Phase 0 is limited to bootstrap governance, documentation, agent model, skills, validation, and empty future component boundaries.

## D-003 Architecture Starting Position

The initial planning baseline is a modular monolith unless discovery and approved ADRs demonstrate a better option.

## D-004 Flutter Repository Boundary

Flutter remains outside `hr-platform` during Phase 0.

## D-005 Legacy Repository Boundary

Legacy PHP remains separate and is treated as discovery input, not as code to relocate into this repository during Phase 0.
