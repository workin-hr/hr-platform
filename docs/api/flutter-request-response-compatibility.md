# Flutter Request And Response Compatibility

## Capability Or Endpoint

Record the user-visible capability or underlying endpoint the Flutter client
depends on.

## Current Flutter Expectation

Capture the exact behavior the current Flutter app appears to rely on, such as:

- required fields
- nullability assumptions
- enum or status values
- ordering guarantees
- pagination behavior
- error-message or error-code handling
- timing or polling expectations

Mark whether the expectation is directly observed in client code, inferred
from behavior, or confirmed by runtime evidence.

## Compatibility Risk

Describe what could break if the contract changes. Useful categories include:

- compile-time or deserialization failure
- silent UI corruption
- workflow failure
- degraded but recoverable behavior
- low-risk additive change

## Proposed Handling

State the safest known handling approach, such as:

- preserve exact behavior
- version the contract
- add compatibility shim
- document as open question pending evidence
- defer change until a compatibility test exists

## Evidence

Link the evidence supporting the entry, such as Flutter source, captured
traffic, endpoint inventory records, or incident/support notes.
