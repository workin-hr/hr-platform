# Existing Endpoint Inventory

## Endpoint

Record the exact endpoint, route, or RPC-style action being observed. If the
surface is not yet fully known, capture the best current identifier and mark
uncertainty explicitly.

## Consumer

Identify who depends on the endpoint, such as:

- Flutter mobile app
- Flutter desktop app
- PHP admin flow
- internal scheduled process
- third-party integration

If more than one consumer exists, list each separately so compatibility risk
is visible.

## Request Shape

Capture the request fields, headers, auth assumptions, and notable encoding or
validation expectations. If the request shape is inferred rather than observed,
mark it as such.

## Response Shape

Capture the success payload, empty-state behavior, pagination or envelope
rules, and any format quirks that clients rely on.

## Error Behavior

Record observable failure modes, including status codes, error payload shape,
retry behavior, and any known client-side tolerance for inconsistent results.

## Evidence

Link the source of truth for the entry, such as:

- source code reference
- captured request/response pair
- mobile or desktop client code
- production behavior note
- support or incident evidence

Do not mark an endpoint as understood without evidence from at least one
consumer or behavior source.
