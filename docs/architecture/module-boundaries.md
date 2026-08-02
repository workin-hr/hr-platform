# Module Boundaries

## Initial Position

Begin with a modular monolith assumption for the backend and define internal modules only after:

- legacy behavior inventory
- API boundary analysis
- tenant isolation requirements
- attendance integration discovery

## Guardrails

- do not force technical layers to become modules
- do not preemptively split into microservices
- prefer explicit domain boundaries supported by evidence
