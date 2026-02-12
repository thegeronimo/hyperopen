# ADR 0001: Keep `hyperopen.core` Entrypoint-Only

## Status
Accepted

## Context
`hyperopen.core` had become a broad compatibility surface by re-exporting many action/effect aliases.
This made the entrypoint namespace look like a general service locator even though runtime dispatch is already event-driven through `:actions/*` registrations.

We want to preserve clean boundaries:
- `hyperopen.core`: app bootstrap and lifecycle entrypoint.
- `hyperopen.core.compat`: legacy alias surface.
- views/runtime callers: event dispatch through registry ids, not direct `hyperopen.core/*` action calls.

## Decision
Remove legacy alias re-exports from `hyperopen.core`.

Legacy aliases remain in `hyperopen.core.compat` as the explicit compatibility boundary.

## Consequences
- `hyperopen.core` now exposes only entrypoint/bootstrap concerns.
- Existing direct consumers of legacy aliases must require `hyperopen.core.compat` instead of `hyperopen.core`.
- Internal application code remains unchanged in behavior because it already dispatches `:actions/*` through the registry.
