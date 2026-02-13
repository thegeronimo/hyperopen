# ADR 0008: API Error Normalization and Request-Only Adoption

- Status: Accepted
- Date: 2026-02-13

## Context

Application modules were still consuming `api/fetch-*` helpers that mutate store state inside the API facade. This blurred API boundary ownership and made error handling inconsistent (`(str err)` without typed category metadata).

## Decision

1. Adopt request-only API calls in additional application modules:
   - `/hyperopen/src/hyperopen/order/effects.cljs`
   - `/hyperopen/src/hyperopen/account/history/effects.cljs`
   - `/hyperopen/src/hyperopen/startup/collaborators.cljs`
2. Keep state projection ownership in application/effects by applying projection helpers after request completion.
3. Add centralized API error normalization boundary:
   - `/hyperopen/src/hyperopen/api/errors.cljs`
4. Update API projection helpers to persist both user-facing error message and typed error category.

## Consequences

- More flows now follow: request data -> apply projection in application/effects.
- Error paths carry typed categories (`:domain`, `:validation`, `:transport`, `:protocol`, `:unexpected`) in state for deterministic branching and diagnostics.
- Existing public API compatibility remains intact while migration continues.

## Invariant Ownership

- Open orders refresh-after-cancel projection ownership:
  - `/hyperopen/src/hyperopen/order/effects.cljs`
  - `refresh-open-orders-snapshot!`
- Startup bootstrap projection ownership:
  - `/hyperopen/src/hyperopen/startup/collaborators.cljs`
  - `fetch-frontend-open-orders!`, `fetch-user-fills!`, `fetch-spot-clearinghouse-state!`, `fetch-asset-contexts!`
- API error category normalization ownership:
  - `/hyperopen/src/hyperopen/api/errors.cljs`
  - `normalize-error`
